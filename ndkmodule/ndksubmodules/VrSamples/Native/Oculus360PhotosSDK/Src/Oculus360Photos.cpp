/************************************************************************************

Filename    :   Oculus360Photos.cpp
Content     :   
Created     :   
Authors     :   

Copyright   :   Copyright 2014 Oculus VR, LLC. All Rights reserved.

This source code is licensed under the BSD-style license found in the
LICENSE file in the Oculus360Photos/ directory. An additional grant 
of patent rights can be found in the PATENTS file in the same directory.

*************************************************************************************/

#include "Oculus360Photos.h"
#include "Input.h"
#include "GuiSys.h"
#include "PanoBrowser.h"
#include "PanoMenu.h"
#include "FileLoader.h"
#include "ImageData.h"
#include "PackageFiles.h"
#include "PhotosMetaData.h"
#include "OVR_Locale.h"

#if defined( OVR_OS_ANDROID )
#include "unistd.h"
#endif

static const char * DEFAULT_PANO = "assets/placeholderBackground.jpg";

extern "C" {

long Java_com_oculus_oculus360photossdk_MainActivity_nativeSetAppInterface( JNIEnv *jni, jclass clazz, jobject activity,
	jstring fromPackageName, jstring commandString, jstring uriString )
{
	// This is called by the java UI thread.
	LOG( "nativeSetAppInterface" );
	return (new OVR::Oculus360Photos())->SetActivity( jni, clazz, activity, fromPackageName, commandString, uriString );
}

} // extern "C"

namespace OVR
{

//==============================================================
// ovrGuiSoundEffectPlayer
class ovrGuiSoundEffectPlayer : public OvrGuiSys::SoundEffectPlayer
{
public:
	ovrGuiSoundEffectPlayer( ovrSoundEffectContext & context )
		: SoundEffectContext( context )
	{
	}

	virtual bool Has( const char * name ) const OVR_OVERRIDE { return SoundEffectContext.GetMapping().HasSound( name ); }
	virtual void Play( const char * name ) OVR_OVERRIDE { SoundEffectContext.Play( name ); }

private:
	ovrGuiSoundEffectPlayer &	operator = ( ovrGuiSoundEffectPlayer & );
private:
	ovrSoundEffectContext & SoundEffectContext;
};


Oculus360Photos::DoubleBufferedTextureData::DoubleBufferedTextureData() :
	TextureSwapChain(),
	Width(),
	Height(),
	CurrentIndex( 0 )
{
}

Oculus360Photos::DoubleBufferedTextureData::~DoubleBufferedTextureData()
{
	DestroyTextureSwapChain( TextureSwapChain[ 0 ] );
	DestroyTextureSwapChain( TextureSwapChain[ 1 ] );
}

ovrTextureSwapChain * Oculus360Photos::DoubleBufferedTextureData::GetRenderTextureSwapChain() const
{
	return TextureSwapChain[ CurrentIndex ^ 1 ];
}

ovrTextureSwapChain * Oculus360Photos::DoubleBufferedTextureData::GetLoadTextureSwapChain() const
{
	return TextureSwapChain[ CurrentIndex ];
}

void Oculus360Photos::DoubleBufferedTextureData::SetLoadTextureSwapChain( ovrTextureSwapChain * chain )
{
	TextureSwapChain[ CurrentIndex ] = chain;
}

void Oculus360Photos::DoubleBufferedTextureData::Swap()
{
	CurrentIndex ^= 1;
}

void Oculus360Photos::DoubleBufferedTextureData::SetSize( const int width, const int height )
{
	Width[ CurrentIndex ] = width;
	Height[ CurrentIndex ] = height;
}

bool Oculus360Photos::DoubleBufferedTextureData::SameSize( const int width, const int height ) const
{
	return ( Width[ CurrentIndex ] == width && Height[ CurrentIndex ] == height );
}

Oculus360Photos::Oculus360Photos()
	: SoundEffectContext( NULL )
	, SoundEffectPlayer( NULL )
	, GuiSys( OvrGuiSys::Create() )
	, Locale( NULL )
	, Fader( 0.0f )	
	, MetaData( NULL )
	, PanoMenu( NULL )
	, Browser( NULL )
	, ActivePano( NULL )
	, BackgroundPanoTexData()
	, BackgroundCubeTexData()
	, CurrentPanoIsCubeMap( false )
	, MessageQueue( 100 )
	, MenuState( MENU_NONE )
	, FadeOutRate( 1.0f / 0.45f )
	, FadeInRate( 1.0f / 0.5f )
	, PanoMenuVisibleTime( 7.0f )
	, CurrentFadeRate( FadeOutRate )
	, CurrentFadeLevel( 0.0f )
	, PanoMenuTimeLeft( -1.0f )
	, BrowserOpenTime( 0.0f )
	, UseOverlay( true )
	, UseSrgb( true )
	, BackgroundCommands( 100 )	
	, EglClientVersion( 0 )
	, EglDisplay( 0 )
	, EglConfig( 0 )
	, EglPbufferSurface( 0 )
	, EglShareContext( 0 )
{
	ShutdownRequest.SetState( false );
}

Oculus360Photos::~Oculus360Photos()
{
	OvrGuiSys::Destroy( GuiSys );
}

void Oculus360Photos::Configure( ovrSettings & settings )
{
	settings.UseSrgbFramebuffer = true;

	// We need very little CPU for pano browsing, but a fair amount of GPU.
	// The CPU clock should ramp up above the minimum when necessary.
	settings.PerformanceParms.CpuLevel = 1;	// jpeg loading is slow, but otherwise we need little
	settings.PerformanceParms.GpuLevel = 3;	// we need a fair amount for cube map overlay support

	// When the app is throttled, go to the platform UI and display a
	// dismissable warning. On return to the app, force 30Hz timewarp.
	settings.ModeParms.AllowPowerSave = true;

	// No hard edged geometry, so no need for MSAA
	settings.EyeBufferParms.multisamples = 1;
	settings.EyeBufferParms.colorFormat = UseSrgb ? COLOR_8888_sRGB : COLOR_8888;
	settings.EyeBufferParms.depthFormat = DEPTH_16;
}

Thread loadingThread;

void Oculus360Photos::OneTimeInit( const char * fromPackage, const char * launchIntentJSON, const char * launchIntentURI )
{
	// This is called by the VR thread, not the java UI thread.
	LOG( "--------------- Oculus360Photos OneTimeInit ---------------" );

	const ovrJava * java = app->GetJava();
	SoundEffectContext = new ovrSoundEffectContext( *java->Env, java->ActivityObject );
	SoundEffectContext->Initialize();
	SoundEffectPlayer = new ovrGuiSoundEffectPlayer( *SoundEffectContext );

	Locale = ovrLocale::Create( *app, "default" );

	String fontName;
	GetLocale().GetString( "@string/font_name", "efigs.fnt", fontName );
	GuiSys->Init( this->app, *SoundEffectPlayer, fontName.ToCStr(), &app->GetDebugLines() );

	GuiSys->GetGazeCursor().ShowCursor();
	
	//-------------------------------------------------------------------------
	TexturedMvpProgram = BuildProgram(
		"uniform mat4 Mvpm;\n"
		"attribute vec4 Position;\n"
		"attribute vec4 VertexColor;\n"
		"attribute vec2 TexCoord;\n"
		"uniform mediump vec4 UniformColor;\n"
		"varying  lowp vec4 oColor;\n"
		"varying highp vec2 oTexCoord;\n"
		"void main()\n"
		"{\n"
		"   gl_Position = Mvpm * Position;\n"
		"	oTexCoord = TexCoord;\n"
		"   oColor = /* VertexColor * */ UniformColor;\n"
		"}\n"
		,
		"uniform sampler2D Texture0;\n"
		"varying highp vec2 oTexCoord;\n"
		"varying lowp vec4	oColor;\n"
		"void main()\n"
		"{\n"
		"	gl_FragColor = oColor * texture2D( Texture0, oTexCoord );\n"
		"}\n"
		);

	CubeMapPanoProgram = BuildProgram(
		"uniform mat4 Mvpm;\n"
		"attribute vec4 Position;\n"
		"uniform mediump vec4 UniformColor;\n"
		"varying  lowp vec4 oColor;\n"
		"varying highp vec3 oTexCoord;\n"
		"void main()\n"
		"{\n"
		"   gl_Position = Mvpm * Position;\n"
		"	oTexCoord = Position.xyz;\n"
		"   oColor = UniformColor;\n"
		"}\n"
		,
		"uniform samplerCube Texture0;\n"
		"varying highp vec3 oTexCoord;\n"
		"varying lowp vec4	oColor;\n"
		"void main()\n"
		"{\n"
		"	gl_FragColor = oColor * textureCube( Texture0, oTexCoord );\n"
		"}\n"
		);

	PanoramaProgram = BuildProgram(
		"uniform highp mat4 Mvpm;\n"
		"uniform highp mat4 Texm;\n"
		"attribute vec4 Position;\n"
		"attribute vec2 TexCoord;\n"
		"varying  highp vec2 oTexCoord;\n"
		"void main()\n"
		"{\n"
		"   gl_Position = Mvpm * Position;\n"
		"   oTexCoord = vec2( Texm * vec4( TexCoord, 0, 1 ) );\n"
		"}\n"
		,
		"#extension GL_OES_EGL_image_external : require\n"
		"uniform samplerExternalOES Texture0;\n"
		"uniform lowp vec4 UniformColor;\n"
		"uniform lowp vec4 ColorBias;\n"
		"varying highp vec2 oTexCoord;\n"
		"void main()\n"
		"{\n"
		"	gl_FragColor = ColorBias + UniformColor * texture2D( Texture0, oTexCoord );\n"
		"}\n"
		);

	// launch cube pano -should always exist!
	StartupPano = DEFAULT_PANO;

	LOG( "Creating Globe" );
	Globe = BuildGlobe();

	// Stay exactly at the origin, so the panorama globe is equidistant
	// Don't clear the head model neck length, or swipe view panels feel wrong.
	ovrHeadModelParms headModelParms = app->GetHeadModelParms();
	headModelParms.EyeHeight = 0.0f;
	app->SetHeadModelParms( headModelParms );

	InitFileQueue( app, this );
	
	// meta file used by OvrMetaData 
	const char * relativePath = "Oculus/360Photos/";
	const char * metaFile = "meta.json";

	// Get package name
	const char * packageName = NULL;
	JNIEnv * jni = app->GetJava()->Env;
	jstring result;
	jmethodID getPackageNameId = jni->GetMethodID( app->GetVrActivityClass(), "getPackageName", "()Ljava/lang/String;" );
	if ( getPackageNameId != NULL )
	{
		result = ( jstring )jni->CallObjectMethod( app->GetJava()->ActivityObject, getPackageNameId );
		if ( !jni->ExceptionOccurred() )
		{
			jboolean isCopy;
			packageName = app->GetJava()->Env->GetStringUTFChars( result, &isCopy );
		}
	}
	else
	{
		FAIL( "Oculus360Photos::OneTimeInit getPackageName failed" );
	}
	OVR_ASSERT( packageName );

	MetaData = new OvrPhotosMetaData();
	if ( MetaData == NULL )
	{
		FAIL( "Oculus360Photos::OneTimeInit failed to create MetaData" );
	}

	OvrMetaDataFileExtensions fileExtensions;

	fileExtensions.GoodExtensions.PushBack( ".jpg" );

	fileExtensions.BadExtensions.PushBack( ".jpg.x" );
	fileExtensions.BadExtensions.PushBack( "_px.jpg" );
	fileExtensions.BadExtensions.PushBack( "_py.jpg" );
	fileExtensions.BadExtensions.PushBack( "_pz.jpg" );
	fileExtensions.BadExtensions.PushBack( "_nx.jpg" );
	fileExtensions.BadExtensions.PushBack( "_ny.jpg" );

	const OvrStoragePaths & storagePaths = app->GetStoragePaths();
	storagePaths.PushBackSearchPathIfValid( EST_SECONDARY_EXTERNAL_STORAGE, EFT_ROOT, "RetailMedia/", SearchPaths );
	storagePaths.PushBackSearchPathIfValid( EST_SECONDARY_EXTERNAL_STORAGE, EFT_ROOT, "", SearchPaths );
	storagePaths.PushBackSearchPathIfValid( EST_PRIMARY_EXTERNAL_STORAGE, EFT_ROOT, "RetailMedia/", SearchPaths );
	storagePaths.PushBackSearchPathIfValid( EST_PRIMARY_EXTERNAL_STORAGE, EFT_ROOT, "", SearchPaths );

	LOG( "360 PHOTOS using %d searchPaths", SearchPaths.GetSizeI() );

	const double startTime = vrapi_GetTimeInSeconds();

	String AppFileStoragePath;
	LOG( "360 PHOTOS using %d searchPaths", SearchPaths.GetSizeI() );

	if ( !storagePaths.GetPathIfValidPermission( EST_PRIMARY_EXTERNAL_STORAGE, EFT_CACHE, "", permissionFlags_t( PERMISSION_WRITE ), AppFileStoragePath ) )
	{
		FAIL( "Oculus360Photos::OneTimeInit - failed to access app cache storage" );
	}
	LOG( "Oculus360Photos::OneTimeInit found AppCacheStoragePath: %s", AppFileStoragePath.ToCStr() );
	
	//MetaData->InitFromDirectoryMergeMeta( relativePath, SearchPaths, fileExtensions, metaFile, packageName );
	MetaData->InitFromDirectory( relativePath, SearchPaths, fileExtensions );
	MetaData->InsertCategoryAt( 0, "Favorites" );
	JSON * storedMetaData = MetaData->CreateOrGetStoredMetaFile( AppFileStoragePath.ToCStr(), metaFile );
	MetaData->ProcessMetaData( storedMetaData, SearchPaths, metaFile );


	LOG( "META DATA INIT TIME: %f", vrapi_GetTimeInSeconds() - startTime );

	jni->ReleaseStringUTFChars( result, packageName );

	// Start building the PanoMenu
	PanoMenu = ( OvrPanoMenu * )GuiSys->GetMenu( OvrPanoMenu::MENU_NAME );
	if ( PanoMenu == NULL )
	{
		PanoMenu = OvrPanoMenu::Create( *GuiSys, *MetaData, 2.0f, 2.0f );
		OVR_ASSERT( PanoMenu );
		GuiSys->AddMenu( PanoMenu );
	}

	PanoMenu->SetFlags( VRMenuFlags_t( VRMENU_FLAG_PLACE_ON_HORIZON ) | VRMENU_FLAG_SHORT_PRESS_HANDLED_BY_APP );

	// Start building the FolderView
	Browser = ( PanoBrowser * )GuiSys->GetMenu( OvrFolderBrowser::MENU_NAME );
	if ( Browser == NULL )
	{
		Browser = PanoBrowser::Create(
			*this,
			*GuiSys,
			*MetaData,
			256, 20.0f,
			160, 220.0f,
			7,
			5.3f );
		OVR_ASSERT( Browser );
		GuiSys->AddMenu( Browser );
	}

	Browser->SetFlags( VRMenuFlags_t( VRMENU_FLAG_PLACE_ON_HORIZON ) | VRMENU_FLAG_BACK_KEY_EXITS_APP );
	Browser->SetFolderTitleSpacingScale( 0.35f );
	Browser->SetScrollBarSpacingScale( 0.82f );
	Browser->SetScrollBarRadiusScale( 0.97f );
	Browser->SetPanelTextSpacingScale( 0.28f );
	Browser->OneTimeInit( *GuiSys );
	Browser->BuildDirtyMenu( *GuiSys, *MetaData );
	Browser->ReloadFavoritesBuffer( *GuiSys );

	//---------------------------------------------------------
	// OpenGL initialization for shared context for 
	// background loading thread done on the main thread
	//---------------------------------------------------------

	// Get values for the current OpenGL context
	EglDisplay = eglGetCurrentDisplay();
	if ( EglDisplay == EGL_NO_DISPLAY )
	{
		FAIL( "EGL_NO_DISPLAY" );
	}

	EglShareContext = eglGetCurrentContext();
	if ( EglShareContext == EGL_NO_CONTEXT )
	{
		FAIL( "EGL_NO_CONTEXT" );
	}

	EGLint attribList[] =
	{
			EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
			EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
			EGL_NONE
	};

	EGLint numConfigs;
	if ( !eglChooseConfig( EglDisplay, attribList, &EglConfig, 1, &numConfigs ) )
	{
		FAIL( "eglChooseConfig failed" );
	}

	if ( EglConfig == NULL )
	{
		FAIL( "EglConfig NULL" );
	}
	if ( !eglQueryContext( EglDisplay, EglShareContext, EGL_CONTEXT_CLIENT_VERSION, ( EGLint * )&EglClientVersion ) )
	{
		FAIL( "eglQueryContext EGL_CONTEXT_CLIENT_VERSION failed" );
	}
	LOG( "Current EGL_CONTEXT_CLIENT_VERSION:%i", EglClientVersion );

	EGLint SurfaceAttribs [ ] =
	{
		EGL_WIDTH, 1,
		EGL_HEIGHT, 1,
		EGL_NONE
	};

	EglPbufferSurface = eglCreatePbufferSurface( EglDisplay, EglConfig, SurfaceAttribs );
	if ( EglPbufferSurface == EGL_NO_SURFACE ) {
		FAIL( "eglCreatePbufferSurface failed: %s", GL_GetErrorString() );
	}

	EGLint bufferWidth, bufferHeight;
	if ( !eglQuerySurface( EglDisplay, EglPbufferSurface, EGL_WIDTH, &bufferWidth ) ||
		!eglQuerySurface( EglDisplay, EglPbufferSurface, EGL_HEIGHT, &bufferHeight ) )
	{
		FAIL( "eglQuerySurface failed:  %s", GL_GetErrorString() );
	}

	loadingThread = Thread( Thread::CreateParams( &BackgroundGLLoadThread, this, 128 * 1024, -1, Thread::NotRunning, Thread::NormalPriority) );
	loadingThread.Start();

	// We might want to save the view state and position for perfect recall
}

void Oculus360Photos::EnteredVrMode()
{
	LOG( "Oculus360Photos::EnteredVrMode" );
	Browser->SetMenuPose( Posef() );
	PanoMenu->SetMenuPose( Posef() );
}

void Oculus360Photos::OneTimeShutdown()
{
	// This is called by the VR thread, not the java UI thread.
	LOG( "--------------- Oculus360Photos OneTimeShutdown ---------------" );

	delete SoundEffectPlayer;
	SoundEffectPlayer = NULL;

	delete SoundEffectContext;
	SoundEffectContext = NULL;

	// Shut down background loader
	ShutdownRequest.SetState( true );

	Globe.Free();

	if ( MetaData )
	{
		delete MetaData;
	}

	DeleteProgram( TexturedMvpProgram );
	DeleteProgram( CubeMapPanoProgram );
	DeleteProgram( PanoramaProgram );
	
	if ( eglDestroySurface( EglDisplay, EglPbufferSurface ) == EGL_FALSE )
	{
		FAIL( "eglDestroySurface: shutdown failed" );
	}
}

void * Oculus360Photos::BackgroundGLLoadThread( Thread * thread, void * v )
{
	thread->SetThreadName( "BackgrndGLLoad" );

	Oculus360Photos * photos = ( Oculus360Photos * )v;

	// Create a new GL context on this thread, sharing it with the main thread context
	// so the loaded background texture can be passed.
	EGLint loaderContextAttribs [ ] =
	{
		EGL_CONTEXT_CLIENT_VERSION, photos->EglClientVersion,
		EGL_NONE, EGL_NONE,
		EGL_NONE
	};

	EGLContext EglBGLoaderContext = eglCreateContext( photos->EglDisplay, photos->EglConfig, photos->EglShareContext, loaderContextAttribs );
	if ( EglBGLoaderContext == EGL_NO_CONTEXT )
	{
		FAIL( "eglCreateContext failed: %s", GL_GetErrorString() );
	}

	// Make the context current on the window, so no more makeCurrent calls will be needed
	if ( eglMakeCurrent( photos->EglDisplay, photos->EglPbufferSurface, photos->EglPbufferSurface, EglBGLoaderContext ) == EGL_FALSE )
	{
		FAIL( "BackgroundGLLoadThread eglMakeCurrent failed: %s", GL_GetErrorString() );
	}

	// run until Shutdown requested
	for ( ; ; )
	{
		if ( photos->ShutdownRequest.GetState() )
		{
			LOG( "BackgroundGLLoadThread ShutdownRequest received" );
			break;
		}

		photos->BackgroundCommands.SleepUntilMessage();
		const char * msg = photos->BackgroundCommands.GetNextMessage();
		LOG( "BackgroundGLLoadThread Commands: %s", msg );
		if ( MatchesHead( "pano ", msg ) )
		{
			unsigned char * data;
			int		width, height;
			sscanf( msg, "pano %p %i %i", &data, &width, &height );

			const double start = vrapi_GetTimeInSeconds( );

			// Resample oversize images so gl can load them.
			// We could consider resampling to GL_MAX_TEXTURE_SIZE exactly for better quality.
			GLint maxTextureSize = 0;
			glGetIntegerv( GL_MAX_TEXTURE_SIZE, &maxTextureSize );

			while ( width > maxTextureSize || width > maxTextureSize )
			{
				LOG( "Quartering oversize %ix%i image", width, height );
				unsigned char * newBuf = QuarterImageSize( data, width, height, false );
				free( data );
				data = newBuf;
				width >>= 1;
				height >>= 1;
			}

			photos->LoadRgbaTexture( data, width, height, true );
			free( data );

			// Wait for the upload to complete.
			glFinish();

			photos->GetMessageQueue().PostPrintf( "%s", "loaded pano" );

			const double end = vrapi_GetTimeInSeconds();
			LOG( "%4.2fs to load %ix%i res pano map", end - start, width, height );
		}
		else if ( MatchesHead( "cube ", msg ) )
		{
			unsigned char * data[ 6 ];
			int		size;
			sscanf( msg, "cube %i %p %p %p %p %p %p", &size, &data[ 0 ], &data[ 1 ], &data[ 2 ], &data[ 3 ], &data[ 4 ], &data[ 5 ] );

			const double start = vrapi_GetTimeInSeconds( );

			photos->LoadRgbaCubeMap( size, data, true );
			for ( int i = 0; i < 6; i++ )
			{
				free( data[ i ] );
			}

			// Wait for the upload to complete.
			glFinish();

			photos->GetMessageQueue().PostPrintf( "%s", "loaded cube" );
			
			const double end = vrapi_GetTimeInSeconds();
			LOG( "%4.2fs to load %i res cube map", end - start, size );
		}
	}

	// release the window so it can be made current by another thread
	if ( eglMakeCurrent( photos->EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT ) == EGL_FALSE )
	{
		FAIL( "BackgroundGLLoadThread eglMakeCurrent: shutdown failed" );
	}

	if ( eglDestroyContext( photos->EglDisplay, EglBGLoaderContext ) == EGL_FALSE )
	{
		FAIL( "BackgroundGLLoadThread eglDestroyContext: shutdown failed" );
	}
	return NULL;
}

void Oculus360Photos::Command( const char * msg )
{
	if ( MatchesHead( "loaded pano", msg ) )
	{
		BackgroundPanoTexData.Swap();
		CurrentPanoIsCubeMap = false;
		SetMenuState( MENU_PANO_FADEIN );
		GuiSys->GetGazeCursor().ClearGhosts();
		return;
	}

	if ( MatchesHead( "loaded cube", msg ) )
	{
		BackgroundCubeTexData.Swap();
		CurrentPanoIsCubeMap = true;
		SetMenuState( MENU_PANO_FADEIN );
		GuiSys->GetGazeCursor().ClearGhosts( );
		return;
	}
}

bool Oculus360Photos::GetUseOverlay() const
{
	// Don't enable the overlay when in throttled state
	return ( UseOverlay && !FrameInput.DeviceStatus.PowerLevelStateThrottled );
}

bool Oculus360Photos::OnKeyEvent( const int keyCode, const int repeatCount, const KeyEventType eventType )
{
	if ( GuiSys->OnKeyEvent( keyCode, repeatCount, eventType ) )
	{
		return true;
	}

	if ( ( ( keyCode == OVR_KEY_BACK ) && ( eventType == KEY_EVENT_SHORT_PRESS ) ) ||
		( ( keyCode == OVR_KEY_BUTTON_B ) && ( eventType == KEY_EVENT_UP ) ) )
	{
		SetMenuState( MENU_BROWSER );
		return true;
	}

	return false;
}

void Oculus360Photos::LoadRgbaCubeMap( const int resolution, const unsigned char * const rgba[ 6 ], const bool useSrgbFormat )
{
	GL_CheckErrors( "enter LoadRgbaCubeMap" );

	// Create texture storage once
	ovrTextureSwapChain * chain = BackgroundCubeTexData.GetLoadTextureSwapChain();
	if ( chain == NULL || !BackgroundCubeTexData.SameSize( resolution, resolution ) )
	{
		DestroyTextureSwapChain( chain );
		const ovrTextureFormat textureFormat = useSrgbFormat ?
												VRAPI_TEXTURE_FORMAT_8888_sRGB :
												VRAPI_TEXTURE_FORMAT_8888;
		chain = CreateTextureSwapChain( VRAPI_TEXTURE_TYPE_CUBE, textureFormat, resolution, resolution,
											VRAPI_TEXTURE_SWAPCHAIN_FULL_MIP_CHAIN, false );

		BackgroundCubeTexData.SetLoadTextureSwapChain( chain );

		BackgroundCubeTexData.SetSize( resolution, resolution );
	}
	glBindTexture( GL_TEXTURE_CUBE_MAP, GetTextureSwapChainHandle( chain, 0 ) );
	for ( int side = 0; side < 6; side++ )
	{
		glTexSubImage2D( GL_TEXTURE_CUBE_MAP_POSITIVE_X + side, 0, 0, 0, resolution, resolution, GL_RGBA, GL_UNSIGNED_BYTE, rgba[ side ] );
	}
	glGenerateMipmap( GL_TEXTURE_CUBE_MAP );
	glBindTexture( GL_TEXTURE_CUBE_MAP, 0 );

	GL_CheckErrors( "leave LoadRgbaCubeMap" );
}

void Oculus360Photos::LoadRgbaTexture( const unsigned char * data, int width, int height, const bool useSrgbFormat )
{
	GL_CheckErrors( "enter LoadRgbaTexture" );

	// Create texture storage once
	ovrTextureSwapChain * chain = BackgroundPanoTexData.GetLoadTextureSwapChain();
	if ( chain == NULL || !BackgroundPanoTexData.SameSize( width, height ) )
	{
		DestroyTextureSwapChain( chain );
		const ovrTextureFormat textureFormat = useSrgbFormat ?
												VRAPI_TEXTURE_FORMAT_8888_sRGB :
												VRAPI_TEXTURE_FORMAT_8888;
		chain = CreateTextureSwapChain( VRAPI_TEXTURE_TYPE_2D, textureFormat, width, height,
											VRAPI_TEXTURE_SWAPCHAIN_FULL_MIP_CHAIN, false );

		BackgroundPanoTexData.SetLoadTextureSwapChain( chain );
		BackgroundPanoTexData.SetSize( width, height );
	}

	glBindTexture( GL_TEXTURE_2D, GetTextureSwapChainHandle( chain, 0 ) );
	glTexSubImage2D( GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, data );
	glGenerateMipmap( GL_TEXTURE_2D );
	// Because equirect panos pinch at the poles so much,
	// they would pull in mip maps so deep you would see colors
	// from the opposite half of the pano.  Clamping the level avoids this.
	// A well filtered pano shouldn't have any high frequency texels
	// that alias near the poles.
	glTexParameteri( GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 2 );
	glBindTexture( GL_TEXTURE_2D, 0 );

	GL_CheckErrors( "leave LoadRgbaTexture" );
}

Matrix4f CubeMatrixForViewMatrix( const Matrix4f & viewMatrix )
{
	Matrix4f m = viewMatrix;
	// clear translation
	for ( int i = 0; i < 3; i++ )
	{
		m.M[ i ][ 3 ] = 0.0f;
	}
	return m.Inverted();
}

Matrix4f Oculus360Photos::DrawEyeView( const int eye, const float fovDegreesX, const float fovDegreesY, ovrFrameParms & frameParms )
{
	// Don't draw the scene at all if it is faded out
	const bool drawScene = true;

	const Matrix4f viewMatrix = Scene.GetEyeViewMatrix( eye );
	const Matrix4f projectionMatrix = Scene.GetEyeProjectionMatrix( eye, fovDegreesX, fovDegreesY );
	const Matrix4f eyeViewProjection = drawScene ?
		Scene.DrawEyeView( eye, fovDegreesX, fovDegreesY ) :
		Scene.GetEyeViewProjectionMatrix( eye, fovDegreesX, fovDegreesY );

	const float color = CurrentFadeLevel;
	// Dim pano when browser open
	float fadeColor = color;
	if ( Browser->IsOpenOrOpening() || MenuState == MENU_PANO_LOADING )
	{
		fadeColor *= 0.09f;
	}

	frameParms.Layers[VRAPI_FRAME_LAYER_TYPE_WORLD].Flags |= VRAPI_FRAME_LAYER_FLAG_CHROMATIC_ABERRATION_CORRECTION;

	if ( GetUseOverlay() && CurrentPanoIsCubeMap )
	{
		// Clear the eye buffers to 0 alpha so the overlay plane shows through.
		glClearColor( 0, 0, 0, 0 );
		glClear( GL_COLOR_BUFFER_BIT );

		frameParms.Flags = ( UseSrgb ? 0 : VRAPI_FRAME_FLAG_INHIBIT_SRGB_FRAMEBUFFER );
		frameParms.LayerCount = 2;
		{
			ovrFrameLayer & layer = frameParms.Layers[VRAPI_FRAME_LAYER_TYPE_WORLD];
			layer.SrcBlend = VRAPI_FRAME_LAYER_BLEND_ONE;
			layer.DstBlend = VRAPI_FRAME_LAYER_BLEND_ZERO;	
			layer.Flags |= VRAPI_FRAME_LAYER_FLAG_WRITE_ALPHA;
			layer.Flags &= ~VRAPI_FRAME_LAYER_FLAG_CHROMATIC_ABERRATION_CORRECTION;
		}
		{
			ovrFrameLayer & layer = frameParms.Layers[VRAPI_FRAME_LAYER_TYPE_OVERLAY];
			layer.Flags |= VRAPI_FRAME_LAYER_FLAG_CHROMATIC_ABERRATION_CORRECTION;
			layer.SrcBlend = VRAPI_FRAME_LAYER_BLEND_ONE_MINUS_DST_ALPHA;
			layer.DstBlend = VRAPI_FRAME_LAYER_BLEND_DST_ALPHA;	
			layer.Textures[eye].ColorTextureSwapChain = BackgroundCubeTexData.GetRenderTextureSwapChain();
			layer.Textures[eye].TextureSwapChainIndex = 0;
			layer.Textures[eye].TexCoordsFromTanAngles = CubeMatrixForViewMatrix( Scene.GetCenterEyeViewMatrix() );
			layer.Textures[eye].HeadPose = FrameInput.Tracking.HeadPose;
			layer.ProgramParms[ 2 ] = fadeColor;
		}
	}
	else
	{
		glActiveTexture( GL_TEXTURE0 );
		GLenum target = CurrentPanoIsCubeMap ? GL_TEXTURE_CUBE_MAP : GL_TEXTURE_2D;
		DoubleBufferedTextureData & db = CurrentPanoIsCubeMap ? BackgroundCubeTexData : BackgroundPanoTexData;
		ovrTextureSwapChain * chain = db.GetRenderTextureSwapChain();

		glBindTexture( target, GetTextureSwapChainHandle( chain, 0 ) );
		if ( HasEXT_sRGB_texture_decode )
		{
			glTexParameteri( target, GL_TEXTURE_SRGB_DECODE_EXT,
				UseSrgb ? GL_DECODE_EXT : GL_SKIP_DECODE_EXT );
		}

		GlProgram & prog = CurrentPanoIsCubeMap ? CubeMapPanoProgram : TexturedMvpProgram;

		glUseProgram( prog.program );

		glUniform4f( prog.uColor, fadeColor, fadeColor, fadeColor, fadeColor );
		glUniformMatrix4fv( prog.uMvp, 1, GL_TRUE, eyeViewProjection.M[ 0 ] );

		Globe.Draw();

		glBindTexture( target, 0 );

		frameParms.Flags = UseSrgb ? 0 : VRAPI_FRAME_FLAG_INHIBIT_SRGB_FRAMEBUFFER;
		frameParms.LayerCount = 1;
		frameParms.Layers[VRAPI_FRAME_LAYER_TYPE_WORLD].SrcBlend = VRAPI_FRAME_LAYER_BLEND_ONE;
		frameParms.Layers[VRAPI_FRAME_LAYER_TYPE_WORLD].DstBlend = VRAPI_FRAME_LAYER_BLEND_ZERO;
		frameParms.Layers[VRAPI_FRAME_LAYER_TYPE_WORLD].Flags &= ~VRAPI_FRAME_LAYER_FLAG_WRITE_ALPHA;
		frameParms.Layers[VRAPI_FRAME_LAYER_TYPE_OVERLAY].Textures[eye].ColorTextureSwapChain = NULL;
	}

	frameParms.ExternalVelocity = Scene.GetExternalVelocity();


	GuiSys->RenderEyeView( Scene.GetCenterEyeViewMatrix(), viewMatrix, projectionMatrix );

	GL_CheckErrors( "draw" );

	return eyeViewProjection;
}

void Oculus360Photos::StartBackgroundPanoLoad( const char * filename )
{
	LOG( "StartBackgroundPanoLoad( %s )", filename );

	// Queue1 will determine if this is a cube map and then post a message for each
	// cube face to the other queues.

	bool isCubeMap = strstr( filename, "_nz.jpg" );
	char const * command = isCubeMap ? "cube" : "pano";

	// Dump any load that hasn't started
	Queue1.ClearMessages();

	// Start a background load of the current pano image
	Queue1.PostPrintf( "%s %s", command, filename );
}

void Oculus360Photos::SetMenuState( const OvrMenuState state )
{
	OvrMenuState lastState = MenuState;
	MenuState = state;
	LOG( "%s to %s", MenuStateString( lastState ), MenuStateString( MenuState ) );
	switch ( MenuState )
	{
	case MENU_NONE:
		break;
	case MENU_BROWSER:
		GuiSys->CloseMenu( PanoMenu, false );
		GuiSys->OpenMenu( OvrFolderBrowser::MENU_NAME );
		BrowserOpenTime = 0.0f;
		GuiSys->GetGazeCursor().ShowCursor();
		break;
	case MENU_PANO_LOADING:
		GuiSys->CloseMenu( Browser, false );
		GuiSys->OpenMenu( OvrPanoMenu::MENU_NAME );
		CurrentFadeRate = FadeOutRate;
		Fader.StartFadeOut();
		StartBackgroundPanoLoad( ActivePano->Url.ToCStr() );

		PanoMenu->UpdateButtonsState( *GuiSys, ActivePano );
		break;
	// pano menu now to fully open
	case MENU_PANO_FADEIN:
	case MENU_PANO_REOPEN_FADEIN:
		if ( lastState != MENU_BROWSER )
		{
			GuiSys->OpenMenu( OvrPanoMenu::MENU_NAME );
			GuiSys->CloseMenu( Browser, false );
		}
		else
		{
			GuiSys->OpenMenu( OvrFolderBrowser::MENU_NAME );
			GuiSys->CloseMenu( PanoMenu, false );
		}		
		GuiSys->GetGazeCursor().ShowCursor();
		Fader.Reset( );
		CurrentFadeRate = FadeInRate;
		Fader.StartFadeIn( );
		break;
	case MENU_PANO_FULLY_VISIBLE:
		PanoMenuTimeLeft = PanoMenuVisibleTime;
		break;
	case MENU_PANO_FADEOUT:
		PanoMenu->StartFadeOut();
		break;
	default:
		OVR_ASSERT( false );
		break;
	}
}

void Oculus360Photos::OnPanoActivated( const OvrMetaDatum * panoData )
{
	ActivePano = static_cast< const OvrPhotosMetaDatum * >( panoData );
	Browser->ReloadFavoritesBuffer( *GuiSys );
	SetMenuState( MENU_PANO_LOADING );
}

Matrix4f Oculus360Photos::Frame( const VrFrame & vrFrame )
{
	// Process incoming messages until the queue is empty.
	for ( ; ; )
	{
		const char * msg = MessageQueue.GetNextMessage();
		if ( msg == NULL )
		{
			break;
		}
		Command( msg );
		free( (void *)msg );
	}

	FrameInput = vrFrame;

	// if just starting up, begin loading a background image
	if ( !StartupPano.IsEmpty() )
	{
		StartBackgroundPanoLoad( StartupPano.ToCStr() );
		SetMenuState( MENU_BROWSER );
		StartupPano.Clear();
	}

	// disallow player movement
	VrFrame vrFrameWithoutMove = vrFrame;
	vrFrameWithoutMove.Input.sticks[ 0 ][ 0 ] = 0.0f;
	vrFrameWithoutMove.Input.sticks[ 0 ][ 1 ] = 0.0f;

	Scene.Frame( vrFrameWithoutMove, app->GetHeadModelParms() );

	// reopen PanoMenu when in pano
	if ( ActivePano && Browser->IsClosedOrClosing( ) && ( MenuState != MENU_PANO_LOADING ) )
	{
		// single touch 
		if ( MenuState > MENU_PANO_FULLY_VISIBLE && vrFrame.Input.buttonPressed & ( BUTTON_TOUCH_SINGLE | BUTTON_A ) )
		{
			SetMenuState( MENU_PANO_REOPEN_FADEIN );
		}

		// PanoMenu input - needs to swipe even when PanoMenu is closed and in pano
		const OvrPhotosMetaDatum * nextPano = NULL;
		
		if ( vrFrame.Input.buttonPressed & ( BUTTON_SWIPE_BACK | BUTTON_DPAD_LEFT | BUTTON_LSTICK_LEFT ) )
		{
			nextPano = static_cast< const OvrPhotosMetaDatum * >( Browser->NextFileInDirectory( *GuiSys, -1 ) );
		}
		else if ( vrFrame.Input.buttonPressed & ( BUTTON_SWIPE_FORWARD | BUTTON_DPAD_RIGHT | BUTTON_LSTICK_RIGHT ) )
		{
			nextPano = static_cast< const OvrPhotosMetaDatum * >( Browser->NextFileInDirectory( *GuiSys, 1 ) );
		}

		if ( nextPano && ( ActivePano != nextPano ) )
		{
			PanoMenu->RepositionMenu( app->GetLastViewMatrix() );
			SoundEffectContext->Play( "sv_release_active" );
			SetActivePano( nextPano );
			SetMenuState( MENU_PANO_LOADING );
		}
	}

	// State transitions
	if ( Fader.GetFadeState() != Fader::FADE_NONE )
	{
		Fader.Update( CurrentFadeRate, vrFrame.DeltaSeconds );
		if ( MenuState != MENU_PANO_REOPEN_FADEIN )
		{
			CurrentFadeLevel = Fader.GetFinalAlpha();
		}
	}
	else if ( (MenuState == MENU_PANO_FADEIN || MenuState == MENU_PANO_REOPEN_FADEIN ) && 
		Fader.GetFadeAlpha() == 1.0 )
	{
		SetMenuState( MENU_PANO_FULLY_VISIBLE );
	}

	if ( MenuState == MENU_PANO_FULLY_VISIBLE )
	{
		if ( !PanoMenu->Interacting() )
		{
			if ( PanoMenuTimeLeft > 0.0f )
			{
				PanoMenuTimeLeft -= vrFrame.DeltaSeconds;
			}
			else
			{
				PanoMenuTimeLeft = 0.0f;
				SetMenuState( MENU_PANO_FADEOUT );
			}
		}
		else // Reset PanoMenuTimeLeft
		{
			PanoMenuTimeLeft = PanoMenuVisibleTime;
		}
	}

	// update gui systems after the app frame, but before rendering anything
	GuiSys->Frame( vrFrame, Scene.GetCenterEyeViewMatrix() );

	return Scene.GetCenterEyeViewMatrix();
}

const char * menuStateNames[] = 
{
	"MENU_NONE",
	"MENU_BROWSER",
	"MENU_PANO_LOADING",
	"MENU_PANO_FADEIN",
	"MENU_PANO_REOPEN_FADEIN",
	"MENU_PANO_FULLY_VISIBLE",
	"MENU_PANO_FADEOUT",
	"NUM_MENU_STATES"
};

const char* Oculus360Photos::MenuStateString( const OvrMenuState state )
{
	OVR_ASSERT( state >= 0 && state < NUM_MENU_STATES );
	return menuStateNames[ state ];
}

int Oculus360Photos::ToggleCurrentAsFavorite()
{
	// Save MetaData - 
	TagAction result = MetaData->ToggleTag( const_cast< OvrPhotosMetaDatum * >( ActivePano ), "Favorites" );

	switch ( result )
	{
	case TAG_ADDED:
		Browser->AddToFavorites( *GuiSys, ActivePano );
		break;
	case TAG_REMOVED:
		Browser->RemoveFromFavorites( *GuiSys, ActivePano );
		break;
	case TAG_ERROR:
	default:
		OVR_ASSERT( false );
		break;
	}

	return result;
}

int Oculus360Photos::GetNumPanosInActiveCategory( OvrGuiSys & guiSys ) const
{
	return Browser->GetNumPanosInActive( guiSys );
}

bool Oculus360Photos::AllowPanoInput() const
{
	return Browser->IsClosed() && MenuState == MENU_PANO_FULLY_VISIBLE;
}

} // namespace OVR