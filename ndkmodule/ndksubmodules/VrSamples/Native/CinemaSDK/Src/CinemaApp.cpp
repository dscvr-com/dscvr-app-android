/************************************************************************************

Filename    :   CinemaApp.cpp
Content     :   
Created     :	6/17/2014
Authors     :   Jim Dosé

Copyright   :   Copyright 2014 Oculus VR, LLC. All Rights reserved.

This source code is licensed under the BSD-style license found in the
LICENSE file in the Cinema/ directory. An additional grant 
of patent rights can be found in the PATENTS file in the same directory.

*************************************************************************************/

#include "Kernel/OVR_String_Utils.h"
#include "CinemaApp.h"
#include "Native.h"
#include "CinemaStrings.h"
#include "OVR_Locale.h"

//=======================================================================================

namespace OculusCinema {

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
	ovrSoundEffectContext & SoundEffectContext;
};

CinemaApp::CinemaApp() :
	GuiSys( OvrGuiSys::Create() ),
	Locale( NULL ),
	CinemaStrings( NULL ),
	StartTime( 0 ),
	SceneMgr( *this ),
	ShaderMgr( *this ),
	ModelMgr( *this ),
	MovieMgr( *this ),
	InLobby( true ),
	AllowDebugControls( false ),
	SoundEffectContext( NULL ),
	SoundEffectPlayer( NULL ),
	ViewMgr(),
	MoviePlayer( *this ),
	MovieSelectionMenu( *this ),
	TheaterSelectionMenu( *this ),
	ResumeMovieMenu( *this ),
	MessageQueue( 100 ),
	vrFrame(),
	FrameCount( 0 ),
	CurrentMovie( NULL ),
	PlayList(),
	ShouldResumeMovie( false ),
	MovieFinishedPlaying( false ),
	MountState( true ),			// We assume that the device is mounted at start since we can only detect changes in mount state
	LastMountState( true )

{
}

CinemaApp::~CinemaApp()
{
	OvrGuiSys::Destroy( GuiSys );
}

void CinemaApp::Configure( ovrSettings & settings )
{
	// We need very little CPU for movie playing, but a fair amount of GPU.
	// The CPU clock should ramp up above the minimum when necessary.
	settings.PerformanceParms.CpuLevel = 1;
	settings.PerformanceParms.GpuLevel = 2;

	// when the app is throttled, go to the platform UI and display a
	// dismissable warning. On return to the app, force 30Hz timewarp.
	settings.ModeParms.AllowPowerSave = true;

	// Default to 2x MSAA.
	settings.EyeBufferParms.colorFormat = COLOR_8888;
	//settings.EyeBufferParms.depthFormat = DEPTH_16;
	settings.EyeBufferParms.multisamples = 2;
}

void CinemaApp::OneTimeInit( const char * fromPackage, const char * launchIntentJSON, const char * launchIntentURI )
{
	LOG( "--------------- CinemaApp OneTimeInit ---------------");

	const ovrJava * java = app->GetJava();
	SoundEffectContext = new ovrSoundEffectContext( *java->Env, java->ActivityObject );
	SoundEffectContext->Initialize();
	SoundEffectPlayer = new ovrGuiSoundEffectPlayer( *SoundEffectContext );

	Locale = ovrLocale::Create( *app, "default" );

	String fontName;
	GetLocale().GetString( "@string/font_name", "efigs.fnt", fontName );
	GuiSys->Init( this->app, *SoundEffectPlayer, fontName.ToCStr(), &app->GetDebugLines() );

	GuiSys->GetGazeCursor().ShowCursor();
	
	StartTime = vrapi_GetTimeInSeconds();

	Native::OneTimeInit( app, ActivityClass );

	CinemaStrings = ovrCinemaStrings::Create( *this );

	ShaderMgr.OneTimeInit( launchIntentURI );
	ModelMgr.OneTimeInit( launchIntentURI );
	SceneMgr.OneTimeInit( launchIntentURI );
	MovieMgr.OneTimeInit( launchIntentURI );
	MoviePlayer.OneTimeInit( launchIntentURI );
	MovieSelectionMenu.OneTimeInit( launchIntentURI );
	TheaterSelectionMenu.OneTimeInit( launchIntentURI );
	ResumeMovieMenu.OneTimeInit( launchIntentURI );

	ViewMgr.AddView( &MoviePlayer );
	ViewMgr.AddView( &MovieSelectionMenu );
	ViewMgr.AddView( &TheaterSelectionMenu );

	MovieSelection( true );

	LOG( "CinemaApp::OneTimeInit: %3.1f seconds", vrapi_GetTimeInSeconds() - StartTime );
}

void CinemaApp::OneTimeShutdown()
{
	LOG( "--------------- CinemaApp OneTimeShutdown ---------------");

	delete SoundEffectPlayer;
	SoundEffectPlayer = NULL;

	delete SoundEffectContext;
	SoundEffectContext = NULL;

	Native::OneTimeShutdown();
	ShaderMgr.OneTimeShutdown();
	ModelMgr.OneTimeShutdown();
	SceneMgr.OneTimeShutdown();
	MovieMgr.OneTimeShutdown();
	MoviePlayer.OneTimeShutdown();
	MovieSelectionMenu.OneTimeShutdown();
	TheaterSelectionMenu.OneTimeShutdown();
	ResumeMovieMenu.OneTimeShutdown();
	ovrCinemaStrings::Destroy( *this, CinemaStrings );
}

void CinemaApp::EnteredVrMode()
{
	LOG( "CinemaApp::EnteredVrMode" );
	// Clear cursor trails.
	GetGuiSys().GetGazeCursor().HideCursorForFrames( 10 );	
	ViewMgr.EnteredVrMode();
}

void CinemaApp::LeavingVrMode()
{
	LOG( "CinemaApp::LeavingVrMode" );
	ViewMgr.LeavingVrMode();
}

const char * CinemaApp::RetailDir( const char *dir ) const
{
	static char subDir[ 256 ];
	StringUtils::SPrintf( subDir, "%s/%s", SDCardDir( "RetailMedia" ), dir );
	return subDir;
}

const char * CinemaApp::ExternalRetailDir( const char *dir ) const
{
	static char subDir[ 256 ];
	StringUtils::SPrintf( subDir, "%s/%s", ExternalSDCardDir( "RetailMedia" ), dir );
	return subDir;
}

const char * CinemaApp::SDCardDir( const char *dir ) const
{
	static char subDir[ 256 ];
	StringUtils::SPrintf( subDir, "/sdcard/%s", dir );
	return subDir;
}

const char * CinemaApp::ExternalSDCardDir( const char *dir ) const
{
	static char subDir[ 256 ];
	StringUtils::SPrintf( subDir, "/storage/extSdCard/%s", dir );
	return subDir;
}

const char * CinemaApp::ExternalCacheDir( const char *dir ) const
{
	static char subDir[ 256 ];
	StringUtils::SPrintf( subDir, "%s/%s", Native::GetExternalCacheDirectory( app ).ToCStr(), dir );
	return subDir;
}

bool CinemaApp::IsExternalSDCardDir( const char *dir ) const
{
	const char * sdcardDir = ExternalSDCardDir( "" );
	const int l = strlen( sdcardDir );
	return ( 0 == strncmp( sdcardDir, dir, l ) );
}

bool CinemaApp::FileExists( const char *filename ) const
{
	FILE *f = fopen( filename, "r" );
	if ( !f )
	{
		return false;
	}
	else
	{
		fclose( f );
		return true;
	}
}

void CinemaApp::SetPlaylist( const Array<const MovieDef *> &playList, const int nextMovie )
{
	PlayList = playList;

	OVR_ASSERT( nextMovie < PlayList.GetSizeI() );
	SetMovie( PlayList[ nextMovie ] );
}

void CinemaApp::SetMovie( const MovieDef *movie )
{
	LOG( "SetMovie( %s )", movie->Filename.ToCStr() );
	CurrentMovie = movie;
	MovieFinishedPlaying = false;
}

void CinemaApp::MovieLoaded( const int width, const int height, const int duration )
{
	MoviePlayer.MovieLoaded( width, height, duration );
}

const MovieDef * CinemaApp::GetNextMovie() const
{
	const MovieDef *next = NULL;
	if ( PlayList.GetSizeI() != 0 )
	{
		for( int i = 0; i < PlayList.GetSizeI() - 1; i++ )
		{
			if ( PlayList[ i ] == CurrentMovie )
			{
				next = PlayList[ i + 1 ];
				break;
			}
		}

		if ( next == NULL )
		{
			next = PlayList[ 0 ];
		}
	}

	return next;
}

const MovieDef * CinemaApp::GetPreviousMovie() const
{
	const MovieDef *previous = NULL;
	if ( PlayList.GetSizeI() != 0 )
	{
		for( int i = 0; i < PlayList.GetSizeI(); i++ )
		{
			if ( PlayList[ i ] == CurrentMovie )
			{
				break;
			}
			previous = PlayList[ i ];
		}

		if ( previous == NULL )
		{
			previous = PlayList[ PlayList.GetSizeI() - 1 ];
		}
	}

	return previous;
}

void CinemaApp::StartMoviePlayback()
{
	if ( CurrentMovie != NULL )
	{
		MovieFinishedPlaying = false;
		Native::StartMovie( app, CurrentMovie->Filename.ToCStr(), ShouldResumeMovie, CurrentMovie->IsEncrypted, false );
		ShouldResumeMovie = false;
	}
}

void CinemaApp::ResumeMovieFromSavedLocation()
{
	LOG( "ResumeMovie");
	InLobby = false;
	ShouldResumeMovie = true;
	ViewMgr.OpenView( MoviePlayer );
}

void CinemaApp::PlayMovieFromBeginning()
{
	LOG( "PlayMovieFromBeginning");
	InLobby = false;
	ShouldResumeMovie = false;
	ViewMgr.OpenView( MoviePlayer );
}

void CinemaApp::ResumeOrRestartMovie()
{
	LOG( "StartMovie");
	if ( Native::CheckForMovieResume( app, CurrentMovie->Filename.ToCStr() ) )
	{
		LOG( "Open ResumeMovieMenu");
		ViewMgr.OpenView( ResumeMovieMenu );
	}
	else
	{
		PlayMovieFromBeginning();
	}
}

void CinemaApp::MovieFinished()
{
	InLobby = false;
	MovieFinishedPlaying = true;
	MovieSelectionMenu.SetMovieList( PlayList, GetNextMovie() );
	ViewMgr.OpenView( MovieSelectionMenu );
}

void CinemaApp::UnableToPlayMovie()
{
	InLobby = false;
	MovieSelectionMenu.SetError( GetCinemaStrings().Error_UnableToPlayMovie.ToCStr(), false, true );
	ViewMgr.OpenView( MovieSelectionMenu );
}

void CinemaApp::TheaterSelection()
{
	ViewMgr.OpenView( TheaterSelectionMenu );
}

void CinemaApp::MovieSelection( bool inLobby )
{
	InLobby = inLobby;
	ViewMgr.OpenView( MovieSelectionMenu );
}

bool CinemaApp::AllowTheaterSelection() const
{
	if ( CurrentMovie != NULL )
	{
		return CurrentMovie->AllowTheaterSelection;
	}

	return true;
}

bool CinemaApp::IsMovieFinished() const
{
	return MovieFinishedPlaying;
}


const SceneDef & CinemaApp::GetCurrentTheater() const
{
	return ModelMgr.GetTheater( TheaterSelectionMenu.GetSelectedTheater() );
}

bool CinemaApp::OnKeyEvent( const int keyCode, const int repeatCount, const KeyEventType eventType )
{
	if ( GuiSys->OnKeyEvent( keyCode, repeatCount, eventType ) )
	{
		return true;
	}

	return ViewMgr.OnKeyEvent( keyCode, repeatCount, eventType );
}

void CinemaApp::Command( const char * msg )
{
	if ( SceneMgr.Command( msg ) )
	{
		return;
	}
}

Matrix4f CinemaApp::Frame( const VrFrame & vrFrame )
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

	FrameCount++;
	this->vrFrame = vrFrame;

	// update mount state
	LastMountState = MountState;
	MountState = vrFrame.DeviceStatus.HeadsetIsMounted;
	if ( HeadsetWasMounted() )
	{
		LOG( "Headset mounted" );
	}
	else if ( HeadsetWasUnmounted() )
	{
		LOG( "Headset unmounted" );
	}

	CenterViewMatrix = ViewMgr.Frame( vrFrame );

	// update gui systems after the app frame, but before rendering anything
	GuiSys->Frame( vrFrame, CenterViewMatrix );

	return CenterViewMatrix;
}

Matrix4f CinemaApp::DrawEyeView( const int eye, const float fovDegreesX, const float fovDegreesY, ovrFrameParms & frameParms )
{
	const Matrix4f viewMatrix = ViewMgr.GetEyeViewMatrix( eye );
	const Matrix4f projectionMatrix = ViewMgr.GetEyeProjectionMatrix( eye, fovDegreesX, fovDegreesY );
	const Matrix4f eyeViewProjection = ViewMgr.DrawEyeView( eye, fovDegreesX, fovDegreesY, frameParms );

	GuiSys->RenderEyeView( CenterViewMatrix, viewMatrix, projectionMatrix );

	return eyeViewProjection;
}

ovrCinemaStrings & CinemaApp::GetCinemaStrings() const
{
	return *CinemaStrings;
}

} // namespace OculusCinema