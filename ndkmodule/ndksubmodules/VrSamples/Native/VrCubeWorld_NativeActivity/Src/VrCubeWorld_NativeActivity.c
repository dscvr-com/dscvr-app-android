/************************************************************************************

Filename	:	VrCubeWorld_NativeActivity.c
Content		:	This sample uses the Android NativeActivity class. This sample does
				not use the application framework and also does not use LibOVRKernel.
				This sample only uses the VrApi and SystemUtils.
Created		:	March, 2015
Authors		:	J.M.P. van Waveren

Copyright	:	Copyright 2015 Oculus VR, LLC. All Rights reserved.

*************************************************************************************/

#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>
#include <android/native_window_jni.h>	// for native window JNI
#include <android_native_app_glue.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>

#if !defined( EGL_OPENGL_ES3_BIT_KHR )
#define EGL_OPENGL_ES3_BIT_KHR		0x0040
#endif

#if !defined( GL_EXT_multisampled_render_to_texture )
typedef void (GL_APIENTRY* PFNGLRENDERBUFFERSTORAGEMULTISAMPLEEXTPROC) (GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height);
typedef void (GL_APIENTRY* PFNGLFRAMEBUFFERTEXTURE2DMULTISAMPLEEXTPROC) (GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level, GLsizei samples);
#endif

#include "VrApi.h"
#include "VrApi_Helpers.h"

#include "SystemActivities.h"

#define DEBUG 1
#define LOG_TAG "VrCubeWorld"

#define ALOGE(...) __android_log_print( ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__ )
#if DEBUG
#define ALOGV(...) __android_log_print( ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__ )
#else
#define ALOGV(...)
#endif

static const int CPU_LEVEL			= 2;
static const int GPU_LEVEL			= 3;

#define MULTI_THREADED			0
#define REDUCED_LATENCY			0
#define EXPLICIT_GL_OBJECTS		0

/*
================================================================================

OpenGL-ES Utility Functions

================================================================================
*/

static const char * EglErrorString( const EGLint error )
{
	switch ( error )
	{
		case EGL_SUCCESS:				return "EGL_SUCCESS";
		case EGL_NOT_INITIALIZED:		return "EGL_NOT_INITIALIZED";
		case EGL_BAD_ACCESS:			return "EGL_BAD_ACCESS";
		case EGL_BAD_ALLOC:				return "EGL_BAD_ALLOC";
		case EGL_BAD_ATTRIBUTE:			return "EGL_BAD_ATTRIBUTE";
		case EGL_BAD_CONTEXT:			return "EGL_BAD_CONTEXT";
		case EGL_BAD_CONFIG:			return "EGL_BAD_CONFIG";
		case EGL_BAD_CURRENT_SURFACE:	return "EGL_BAD_CURRENT_SURFACE";
		case EGL_BAD_DISPLAY:			return "EGL_BAD_DISPLAY";
		case EGL_BAD_SURFACE:			return "EGL_BAD_SURFACE";
		case EGL_BAD_MATCH:				return "EGL_BAD_MATCH";
		case EGL_BAD_PARAMETER:			return "EGL_BAD_PARAMETER";
		case EGL_BAD_NATIVE_PIXMAP:		return "EGL_BAD_NATIVE_PIXMAP";
		case EGL_BAD_NATIVE_WINDOW:		return "EGL_BAD_NATIVE_WINDOW";
		case EGL_CONTEXT_LOST:			return "EGL_CONTEXT_LOST";
		default:						return "unknown";
	}
}

static const char * GlFrameBufferStatusString( GLenum status )
{
	switch ( status )
	{
		case GL_FRAMEBUFFER_UNDEFINED:						return "GL_FRAMEBUFFER_UNDEFINED";
		case GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT:			return "GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT";
		case GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT:	return "GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT";
		case GL_FRAMEBUFFER_UNSUPPORTED:					return "GL_FRAMEBUFFER_UNSUPPORTED";
		case GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE:			return "GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE";
		default:											return "unknown";
	}
}

#ifdef CHECK_GL_ERRORS

static const char * GlErrorString( GLenum error )
{
	switch ( error )
	{
		case GL_NO_ERROR:						return "GL_NO_ERROR";
		case GL_INVALID_ENUM:					return "GL_INVALID_ENUM";
		case GL_INVALID_VALUE:					return "GL_INVALID_VALUE";
		case GL_INVALID_OPERATION:				return "GL_INVALID_OPERATION";
		case GL_INVALID_FRAMEBUFFER_OPERATION:	return "GL_INVALID_FRAMEBUFFER_OPERATION";
		case GL_OUT_OF_MEMORY:					return "GL_OUT_OF_MEMORY";
		default: return "unknown";
	}
}

static void GLCheckErrors()
{
	for ( int i = 0; i < 10; i++ )
	{
		const GLenum error = glGetError();
		if ( error == GL_NO_ERROR )
		{
			break;
		}
		ALOGE( "GL error: %s", GlErrorString( error ) );
	}
}

#define GL( func )		func; GLCheckErrors();

#else // CHECK_GL_ERRORS

#define GL( func )		func;

#endif // CHECK_GL_ERRORS

/*
================================================================================

ovrEgl

================================================================================
*/

typedef struct
{
	EGLint		MajorVersion;
	EGLint		MinorVersion;
	EGLDisplay	Display;
	EGLConfig	Config;
	EGLSurface	TinySurface;
	EGLSurface	MainSurface;
	EGLContext	Context;
} ovrEgl;

static void ovrEgl_Clear( ovrEgl * egl )
{
	egl->MajorVersion = 0;
	egl->MinorVersion = 0;
	egl->Display = 0;
	egl->Config = 0;
	egl->TinySurface = EGL_NO_SURFACE;
	egl->MainSurface = EGL_NO_SURFACE;
	egl->Context = EGL_NO_CONTEXT;
}

static void ovrEgl_CreateContext( ovrEgl * egl, const ovrEgl * shareEgl )
{
	if ( egl->Display != 0 )
	{
		return;
	}

	egl->Display = eglGetDisplay( EGL_DEFAULT_DISPLAY );
	ALOGV( "        eglInitialize( Display, &MajorVersion, &MinorVersion )" );
	eglInitialize( egl->Display, &egl->MajorVersion, &egl->MinorVersion );
	// Do NOT use eglChooseConfig, because the Android EGL code pushes in multisample
	// flags in eglChooseConfig if the user has selected the "force 4x MSAA" option in
	// settings, and that is completely wasted for our warp target.
	const int MAX_CONFIGS = 1024;
	EGLConfig configs[MAX_CONFIGS];
	EGLint numConfigs = 0;
	if ( eglGetConfigs( egl->Display, configs, MAX_CONFIGS, &numConfigs ) == EGL_FALSE )
	{
		ALOGE( "        eglGetConfigs() failed: %s", EglErrorString( eglGetError() ) );
		return;
	}
	const EGLint configAttribs[] =
	{
		EGL_ALPHA_SIZE, 8, // need alpha for the multi-pass timewarp compositor
		EGL_BLUE_SIZE,  8,
		EGL_GREEN_SIZE, 8,
		EGL_RED_SIZE,   8,
		EGL_DEPTH_SIZE, 0,
		EGL_SAMPLES,	0,
		EGL_NONE
	};
	egl->Config = 0;
	for ( int i = 0; i < numConfigs; i++ )
	{
		EGLint value = 0;

		eglGetConfigAttrib( egl->Display, configs[i], EGL_RENDERABLE_TYPE, &value );
		if ( ( value & EGL_OPENGL_ES3_BIT_KHR ) != EGL_OPENGL_ES3_BIT_KHR )
		{
			continue;
		}

		// The pbuffer config also needs to be compatible with normal window rendering
		// so it can share textures with the window context.
		eglGetConfigAttrib( egl->Display, configs[i], EGL_SURFACE_TYPE, &value );
		if ( ( value & ( EGL_WINDOW_BIT | EGL_PBUFFER_BIT ) ) != ( EGL_WINDOW_BIT | EGL_PBUFFER_BIT ) )
		{
			continue;
		}

		int	j = 0;
		for ( ; configAttribs[j] != EGL_NONE; j += 2 )
		{
			eglGetConfigAttrib( egl->Display, configs[i], configAttribs[j], &value );
			if ( value != configAttribs[j + 1] )
			{
				break;
			}
		}
		if ( configAttribs[j] == EGL_NONE )
		{
			egl->Config = configs[i];
			break;
		}
	}
	if ( egl->Config == 0 )
	{
		ALOGE( "        eglChooseConfig() failed: %s", EglErrorString( eglGetError() ) );
		return;
	}
	EGLint contextAttribs[] =
	{
		EGL_CONTEXT_CLIENT_VERSION, 3,
		EGL_NONE
	};
	ALOGV( "        Context = eglCreateContext( Display, Config, EGL_NO_CONTEXT, contextAttribs )" );
	egl->Context = eglCreateContext( egl->Display, egl->Config, ( shareEgl != NULL ) ? shareEgl->Context : EGL_NO_CONTEXT, contextAttribs );
	if ( egl->Context == EGL_NO_CONTEXT )
	{
		ALOGE( "        eglCreateContext() failed: %s", EglErrorString( eglGetError() ) );
		return;
	}
	const EGLint surfaceAttribs[] =
	{
		EGL_WIDTH, 16,
		EGL_HEIGHT, 16,
		EGL_NONE
	};
	ALOGV( "        TinySurface = eglCreatePbufferSurface( Display, Config, surfaceAttribs )" );
	egl->TinySurface = eglCreatePbufferSurface( egl->Display, egl->Config, surfaceAttribs );
	if ( egl->TinySurface == EGL_NO_SURFACE )
	{
		ALOGE( "        eglCreatePbufferSurface() failed: %s", EglErrorString( eglGetError() ) );
		eglDestroyContext( egl->Display, egl->Context );
		egl->Context = EGL_NO_CONTEXT;
		return;
	}
	ALOGV( "        eglMakeCurrent( Display, TinySurface, TinySurface, Context )" );
	if ( eglMakeCurrent( egl->Display, egl->TinySurface, egl->TinySurface, egl->Context ) == EGL_FALSE )
	{
		ALOGE( "        eglMakeCurrent() failed: %s", EglErrorString( eglGetError() ) );
		eglDestroySurface( egl->Display, egl->TinySurface );
		eglDestroyContext( egl->Display, egl->Context );
		egl->Context = EGL_NO_CONTEXT;
		return;
	}
}

static void ovrEgl_DestroyContext( ovrEgl * egl )
{
	if ( egl->Display != 0 )
	{
		ALOGE( "        eglMakeCurrent( Display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT )" );
		if ( eglMakeCurrent( egl->Display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT ) == EGL_FALSE )
		{
			ALOGE( "        eglMakeCurrent() failed: %s", EglErrorString( eglGetError() ) );
		}
	}
	if ( egl->Context != EGL_NO_CONTEXT )
	{
		ALOGE( "        eglDestroyContext( Display, Context )" );
		if ( eglDestroyContext( egl->Display, egl->Context ) == EGL_FALSE )
		{
			ALOGE( "        eglDestroyContext() failed: %s", EglErrorString( eglGetError() ) );
		}
		egl->Context = EGL_NO_CONTEXT;
	}
	if ( egl->TinySurface != EGL_NO_SURFACE )
	{
		ALOGE( "        eglDestroySurface( Display, TinySurface )" );
		if ( eglDestroySurface( egl->Display, egl->TinySurface ) == EGL_FALSE )
		{
			ALOGE( "        eglDestroySurface() failed: %s", EglErrorString( eglGetError() ) );
		}
		egl->TinySurface = EGL_NO_SURFACE;
	}
	if ( egl->Display != 0 )
	{
		ALOGE( "        eglTerminate( Display )" );
		if ( eglTerminate( egl->Display ) == EGL_FALSE )
		{
			ALOGE( "        eglTerminate() failed: %s", EglErrorString( eglGetError() ) );
		}
		egl->Display = 0;
	}
}

static void ovrEgl_CreateSurface( ovrEgl * egl, ANativeWindow * nativeWindow )
{
	if ( egl->MainSurface != EGL_NO_SURFACE )
	{
		return;
	}
	ALOGV( "        MainSurface = eglCreateWindowSurface( Display, Config, nativeWindow, attribs )" );
	const EGLint surfaceAttribs[] = { EGL_NONE };
	egl->MainSurface = eglCreateWindowSurface( egl->Display, egl->Config, nativeWindow, surfaceAttribs );
	if ( egl->MainSurface == EGL_NO_SURFACE )
	{
		ALOGE( "        eglCreateWindowSurface() failed: %s", EglErrorString( eglGetError() ) );
		return;
	}
#if EXPLICIT_GL_OBJECTS == 0
	ALOGV( "        eglMakeCurrent( display, MainSurface, MainSurface, Context )" );
	if ( eglMakeCurrent( egl->Display, egl->MainSurface, egl->MainSurface, egl->Context ) == EGL_FALSE )
	{
		ALOGE( "        eglMakeCurrent() failed: %s", EglErrorString( eglGetError() ) );
		return;
	}
#endif
}

static void ovrEgl_DestroySurface( ovrEgl * egl )
{
#if EXPLICIT_GL_OBJECTS == 0
	if ( egl->Context != EGL_NO_CONTEXT )
	{
		ALOGV( "        eglMakeCurrent( display, TinySurface, TinySurface, Context )" );
		if ( eglMakeCurrent( egl->Display, egl->TinySurface, egl->TinySurface, egl->Context ) == EGL_FALSE )
		{
			ALOGE( "        eglMakeCurrent() failed: %s", EglErrorString( eglGetError() ) );
		}
	}
#endif
	if ( egl->MainSurface != EGL_NO_SURFACE )
	{
		ALOGV( "        eglDestroySurface( Display, MainSurface )" );
		if ( eglDestroySurface( egl->Display, egl->MainSurface ) == EGL_FALSE )
		{
			ALOGE( "        eglDestroySurface() failed: %s", EglErrorString( eglGetError() ) );
		}
		egl->MainSurface = EGL_NO_SURFACE;
	}
}

/*
================================================================================

ovrGeometry

================================================================================
*/

typedef struct
{
	GLuint			Index;
 	GLint			Size;
 	GLenum			Type;
 	GLboolean		Normalized;
 	GLsizei			Stride;
 	const GLvoid *	Pointer;
} ovrVertexAttribPointer;

#define MAX_VERTEX_ATTRIB_POINTERS		3

typedef struct
{
	GLuint					VertexBuffer;
	GLuint					IndexBuffer;
	GLuint					VertexArrayObject;
	int						VertexCount;
	int 					IndexCount;
	ovrVertexAttribPointer	VertexAttribs[MAX_VERTEX_ATTRIB_POINTERS];
} ovrGeometry;

typedef struct
{
	enum
	{
		VERTEX_ATTRIBUTE_LOCATION_POSITION,
		VERTEX_ATTRIBUTE_LOCATION_COLOR,
		VERTEX_ATTRIBUTE_LOCATION_UV,
		VERTEX_ATTRIBUTE_LOCATION_TRANSFORM
	}				location;
	const char *	name;
} ovrVertexAttribute;

static ovrVertexAttribute ProgramVertexAttributes[] =
{
	{ VERTEX_ATTRIBUTE_LOCATION_POSITION,	"vertexPosition" },
	{ VERTEX_ATTRIBUTE_LOCATION_COLOR,		"vertexColor" },
	{ VERTEX_ATTRIBUTE_LOCATION_UV,			"vertexUv" },
	{ VERTEX_ATTRIBUTE_LOCATION_TRANSFORM,	"vertexTransform" }
};

static void ovrGeometry_Clear( ovrGeometry * geometry )
{
	geometry->VertexBuffer = 0;
	geometry->IndexBuffer = 0;
	geometry->VertexArrayObject = 0;
	geometry->VertexCount = 0;
	geometry->IndexCount = 0;
	for ( int i = 0; i < MAX_VERTEX_ATTRIB_POINTERS; i++ )
	{
		memset( &geometry->VertexAttribs[i], 0, sizeof( geometry->VertexAttribs[i] ) );
		geometry->VertexAttribs[i].Index = -1;
	}
}

static void ovrGeometry_CreateCube( ovrGeometry * geometry )
{
	typedef struct
	{
		char positions[8][4];
		unsigned char colors[8][4];
	} ovrCubeVertices;

	static const ovrCubeVertices cubeVertices =
	{
		// positions
		{
			{ -127, +127, -127, +127 }, { +127, +127, -127, +127 }, { +127, +127, +127, +127 }, { -127, +127, +127, +127 },	// top
			{ -127, -127, -127, +127 }, { -127, -127, +127, +127 }, { +127, -127, +127, +127 }, { +127, -127, -127, +127 }	// bottom
		},
		// colors
		{
			{ 255,   0, 255, 255 }, {   0, 255,   0, 255 }, {   0,   0, 255, 255 }, { 255,   0,   0, 255 },
			{   0,   0, 255, 255 }, {   0, 255,   0, 255 }, { 255,   0, 255, 255 }, { 255,   0,   0, 255 }
		},
	};

	static const unsigned short cubeIndices[36] =
	{
		0, 1, 2, 2, 3, 0,	// top
		4, 5, 6, 6, 7, 4,	// bottom
		2, 6, 7, 7, 1, 2,	// right
		0, 4, 5, 5, 3, 0,	// left
		3, 5, 6, 6, 2, 3,	// front
		0, 1, 7, 7, 4, 0	// back
	};

	geometry->VertexCount = 8;
	geometry->IndexCount = 36;

	geometry->VertexAttribs[0].Index = VERTEX_ATTRIBUTE_LOCATION_POSITION;
 	geometry->VertexAttribs[0].Size = 4;
 	geometry->VertexAttribs[0].Type = GL_BYTE;
 	geometry->VertexAttribs[0].Normalized = true;
 	geometry->VertexAttribs[0].Stride = sizeof( cubeVertices.positions[0] );
 	geometry->VertexAttribs[0].Pointer = (const GLvoid *)offsetof( ovrCubeVertices, positions );

	geometry->VertexAttribs[1].Index = VERTEX_ATTRIBUTE_LOCATION_COLOR;
 	geometry->VertexAttribs[1].Size = 4;
 	geometry->VertexAttribs[1].Type = GL_UNSIGNED_BYTE;
 	geometry->VertexAttribs[1].Normalized = true;
 	geometry->VertexAttribs[1].Stride = sizeof( cubeVertices.colors[0] );
 	geometry->VertexAttribs[1].Pointer = (const GLvoid *)offsetof( ovrCubeVertices, colors );

	GL( glGenBuffers( 1, &geometry->VertexBuffer ) );
	GL( glBindBuffer( GL_ARRAY_BUFFER, geometry->VertexBuffer ) );
	GL( glBufferData( GL_ARRAY_BUFFER, sizeof( cubeVertices ), &cubeVertices, GL_STATIC_DRAW ) );
	GL( glBindBuffer( GL_ARRAY_BUFFER, 0 ) );

	GL( glGenBuffers( 1, &geometry->IndexBuffer ) );
	GL( glBindBuffer( GL_ELEMENT_ARRAY_BUFFER, geometry->IndexBuffer ) );
	GL( glBufferData( GL_ELEMENT_ARRAY_BUFFER, sizeof( cubeIndices ), cubeIndices, GL_STATIC_DRAW ) );
	GL( glBindBuffer( GL_ELEMENT_ARRAY_BUFFER, 0 ) );
}

static void ovrGeometry_Destroy( ovrGeometry * geometry )
{
	GL( glDeleteBuffers( 1, &geometry->IndexBuffer ) );
	GL( glDeleteBuffers( 1, &geometry->VertexBuffer ) );

	ovrGeometry_Clear( geometry );
}

static void ovrGeometry_CreateVAO( ovrGeometry * geometry )
{
	GL( glGenVertexArrays( 1, &geometry->VertexArrayObject ) );
	GL( glBindVertexArray( geometry->VertexArrayObject ) );

	GL( glBindBuffer( GL_ARRAY_BUFFER, geometry->VertexBuffer ) );

	for ( int i = 0; i < MAX_VERTEX_ATTRIB_POINTERS; i++ )
	{
		if ( geometry->VertexAttribs[i].Index != -1 )
		{
			GL( glEnableVertexAttribArray( geometry->VertexAttribs[i].Index ) );
			GL( glVertexAttribPointer( geometry->VertexAttribs[i].Index, geometry->VertexAttribs[i].Size,
					geometry->VertexAttribs[i].Type, geometry->VertexAttribs[i].Normalized,
					geometry->VertexAttribs[i].Stride, geometry->VertexAttribs[i].Pointer ) );
		}
	}

	GL( glBindBuffer( GL_ELEMENT_ARRAY_BUFFER, geometry->IndexBuffer ) );

	GL( glBindVertexArray( 0 ) );
}

static void ovrGeometry_DestroyVAO( ovrGeometry * geometry )
{
	GL( glDeleteVertexArrays( 1, &geometry->VertexArrayObject ) );
}

/*
================================================================================

ovrProgram

================================================================================
*/

#define MAX_PROGRAM_UNIFORMS	8
#define MAX_PROGRAM_TEXTURES	8

typedef struct
{
	GLuint	Program;
	GLuint	VertexShader;
	GLuint	FragmentShader;
	// These will be -1 if not used by the program.
	GLint	Uniforms[MAX_PROGRAM_UNIFORMS];		// ProgramUniforms[].name
	GLint	Textures[MAX_PROGRAM_TEXTURES];		// Texture%i
} ovrProgram;

typedef struct
{
	enum
	{
		UNIFORM_MODEL_MATRIX,
		UNIFORM_VIEW_MATRIX,
		UNIFORM_PROJECTION_MATRIX
	}				index;
	enum
	{
		UNIFORM_TYPE_VECTOR4,
		UNIFORM_TYPE_MATRIX4X4
	}				type;
	const char *	name;
} ovrUniform;

static ovrUniform ProgramUniforms[] =
{
	{ UNIFORM_MODEL_MATRIX,			UNIFORM_TYPE_MATRIX4X4,	"ModelMatrix" },
	{ UNIFORM_VIEW_MATRIX,			UNIFORM_TYPE_MATRIX4X4,	"ViewMatrix" },
	{ UNIFORM_PROJECTION_MATRIX,	UNIFORM_TYPE_MATRIX4X4,	"ProjectionMatrix" }
};

static void ovrProgram_Clear( ovrProgram * program )
{
	program->Program = 0;
	program->VertexShader = 0;
	program->FragmentShader = 0;
	memset( program->Uniforms, 0, sizeof( program->Uniforms ) );
	memset( program->Textures, 0, sizeof( program->Textures ) );
}

static bool ovrProgram_Create( ovrProgram * program, const char * vertexSource, const char * fragmentSource )
{
	GLint r;

	GL( program->VertexShader = glCreateShader( GL_VERTEX_SHADER ) );
	GL( glShaderSource( program->VertexShader, 1, &vertexSource, 0 ) );
	GL( glCompileShader( program->VertexShader ) );
	GL( glGetShaderiv( program->VertexShader, GL_COMPILE_STATUS, &r ) );
	if ( r == GL_FALSE )
	{
		GLchar msg[4096];
		GL( glGetShaderInfoLog( program->VertexShader, sizeof( msg ), 0, msg ) );
		ALOGE( "%s\n%s\n", vertexSource, msg );
		return false;
	}

	GL( program->FragmentShader = glCreateShader( GL_FRAGMENT_SHADER ) );
	GL( glShaderSource( program->FragmentShader, 1, &fragmentSource, 0 ) );
	GL( glCompileShader( program->FragmentShader ) );
	GL( glGetShaderiv( program->FragmentShader, GL_COMPILE_STATUS, &r ) );
	if ( r == GL_FALSE )
	{
		GLchar msg[4096];
		GL( glGetShaderInfoLog( program->FragmentShader, sizeof( msg ), 0, msg ) );
		ALOGE( "%s\n%s\n", fragmentSource, msg );
		return false;
	}

	GL( program->Program = glCreateProgram() );
	GL( glAttachShader( program->Program, program->VertexShader ) );
	GL( glAttachShader( program->Program, program->FragmentShader ) );

	// Bind the vertex attribute locations.
	for ( int i = 0; i < sizeof( ProgramVertexAttributes ) / sizeof( ProgramVertexAttributes[0] ); i++ )
	{
		GL( glBindAttribLocation( program->Program, ProgramVertexAttributes[i].location, ProgramVertexAttributes[i].name ) );
	}

	GL( glLinkProgram( program->Program ) );
	GL( glGetProgramiv( program->Program, GL_LINK_STATUS, &r ) );
	if ( r == GL_FALSE )
	{
		GLchar msg[4096];
		GL( glGetProgramInfoLog( program->Program, sizeof( msg ), 0, msg ) );
		ALOGE( "Linking program failed: %s\n", msg );
		return false;
	}

	// Get the uniform locations.
	memset( program->Uniforms, -1, sizeof( program->Uniforms ) );
	for ( int i = 0; i < sizeof( ProgramUniforms ) / sizeof( ProgramUniforms[0] ); i++ )
	{
		program->Uniforms[ProgramUniforms[i].index] = glGetUniformLocation( program->Program, ProgramUniforms[i].name );
	}

	GL( glUseProgram( program->Program ) );

	// Get the texture locations.
	for ( int i = 0; i < MAX_PROGRAM_TEXTURES; i++ )
	{
		char name[32];
		sprintf( name, "Texture%i", i );
		program->Textures[i] = glGetUniformLocation( program->Program, name );
		if ( program->Textures[i] != -1 )
		{
			GL( glUniform1i( program->Textures[i], i ) );
		}
	}

	GL( glUseProgram( 0 ) );

	return true;
}

static void ovrProgram_Destroy( ovrProgram * program )
{
	if ( program->Program != 0 )
	{
		GL( glDeleteProgram( program->Program ) );
		program->Program = 0;
	}
	if ( program->VertexShader != 0 )
	{
		GL( glDeleteShader( program->VertexShader ) );
		program->VertexShader = 0;
	}
	if ( program->FragmentShader != 0 )
	{
		GL( glDeleteShader( program->FragmentShader ) );
		program->FragmentShader = 0;
	}
}

/*
================================================================================

ovrFramebuffer

================================================================================
*/

typedef struct
{
	int						Width;
	int						Height;
	int						Multisamples;
	int						TextureSwapChainLength;
	int						TextureSwapChainIndex;
	ovrTextureSwapChain *	ColorTextureSwapChain;
	GLuint *				DepthBuffers;
	GLuint *				FrameBuffers;
} ovrFramebuffer;

static void ovrFramebuffer_Clear( ovrFramebuffer * frameBuffer )
{
	frameBuffer->Width = 0;
	frameBuffer->Height = 0;
	frameBuffer->Multisamples = 0;
	frameBuffer->TextureSwapChainLength = 0;
	frameBuffer->TextureSwapChainIndex = 0;
	frameBuffer->ColorTextureSwapChain = NULL;
	frameBuffer->DepthBuffers = NULL;
	frameBuffer->FrameBuffers = NULL;
}

static bool ovrFramebuffer_Create( ovrFramebuffer * frameBuffer, const ovrTextureFormat colorFormat, const int width, const int height, const int multisamples )
{
	frameBuffer->Width = width;
	frameBuffer->Height = height;
	frameBuffer->Multisamples = multisamples;

	frameBuffer->ColorTextureSwapChain = vrapi_CreateTextureSwapChain( VRAPI_TEXTURE_TYPE_2D, colorFormat, width, height, 1, true );
	frameBuffer->TextureSwapChainLength = vrapi_GetTextureSwapChainLength( frameBuffer->ColorTextureSwapChain );
	frameBuffer->DepthBuffers = (GLuint *)malloc( frameBuffer->TextureSwapChainLength * sizeof( GLuint ) ); 
	frameBuffer->FrameBuffers = (GLuint *)malloc( frameBuffer->TextureSwapChainLength * sizeof( GLuint ) );

	PFNGLRENDERBUFFERSTORAGEMULTISAMPLEEXTPROC glRenderbufferStorageMultisampleEXT =
		(PFNGLRENDERBUFFERSTORAGEMULTISAMPLEEXTPROC)eglGetProcAddress( "glRenderbufferStorageMultisampleEXT" );
	PFNGLFRAMEBUFFERTEXTURE2DMULTISAMPLEEXTPROC glFramebufferTexture2DMultisampleEXT =
		(PFNGLFRAMEBUFFERTEXTURE2DMULTISAMPLEEXTPROC)eglGetProcAddress( "glFramebufferTexture2DMultisampleEXT" );

	for ( int i = 0; i < frameBuffer->TextureSwapChainLength; i++ )
	{
		// Create the color buffer texture.
		const GLuint colorTexture = vrapi_GetTextureSwapChainHandle( frameBuffer->ColorTextureSwapChain, i );
		GL( glBindTexture( GL_TEXTURE_2D, colorTexture ) );
		GL( glTexParameteri( GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE ) );
		GL( glTexParameteri( GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE ) );
		GL( glTexParameteri( GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR ) );
		GL( glTexParameteri( GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR ) );
		GL( glBindTexture( GL_TEXTURE_2D, 0 ) );

		if ( multisamples > 1 && glRenderbufferStorageMultisampleEXT != NULL && glFramebufferTexture2DMultisampleEXT != NULL )
		{
			// Create multisampled depth buffer.
			GL( glGenRenderbuffers( 1, &frameBuffer->DepthBuffers[i] ) );
			GL( glBindRenderbuffer( GL_RENDERBUFFER, frameBuffer->DepthBuffers[i] ) );
			GL( glRenderbufferStorageMultisampleEXT( GL_RENDERBUFFER, multisamples, GL_DEPTH_COMPONENT24, width, height ) );
			GL( glBindRenderbuffer( GL_RENDERBUFFER, 0 ) );

			// Create the frame buffer.
			GL( glGenFramebuffers( 1, &frameBuffer->FrameBuffers[i] ) );
			GL( glBindFramebuffer( GL_FRAMEBUFFER, frameBuffer->FrameBuffers[i] ) );
			GL( glFramebufferTexture2DMultisampleEXT( GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0, multisamples ) );
			GL( glFramebufferRenderbuffer( GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, frameBuffer->DepthBuffers[i] ) );
			GL( GLenum renderFramebufferStatus = glCheckFramebufferStatus( GL_FRAMEBUFFER ) );
			GL( glBindFramebuffer( GL_FRAMEBUFFER, 0 ) );
			if ( renderFramebufferStatus != GL_FRAMEBUFFER_COMPLETE )
			{
				ALOGE( "Incomplete frame buffer object: %s", GlFrameBufferStatusString( renderFramebufferStatus ) );
				return false;
			}
		}
		else
		{
			// Create depth buffer.
			GL( glGenRenderbuffers( 1, &frameBuffer->DepthBuffers[i] ) );
			GL( glBindRenderbuffer( GL_RENDERBUFFER, frameBuffer->DepthBuffers[i] ) );
			GL( glRenderbufferStorage( GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height ) );
			GL( glBindRenderbuffer( GL_RENDERBUFFER, 0 ) );

			// Create the frame buffer.
			GL( glGenFramebuffers( 1, &frameBuffer->FrameBuffers[i] ) );
			GL( glBindFramebuffer( GL_FRAMEBUFFER, frameBuffer->FrameBuffers[i] ) );
			GL( glFramebufferRenderbuffer( GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, frameBuffer->DepthBuffers[i] ) );
			GL( glFramebufferTexture2D( GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0 ) );
			GL( GLenum renderFramebufferStatus = glCheckFramebufferStatus( GL_FRAMEBUFFER ) );
			GL( glBindFramebuffer( GL_FRAMEBUFFER, 0 ) );
			if ( renderFramebufferStatus != GL_FRAMEBUFFER_COMPLETE )
			{
				ALOGE( "Incomplete frame buffer object: %s", GlFrameBufferStatusString( renderFramebufferStatus ) );
				return false;
			}
		}
	}

	return true;
}

static void ovrFramebuffer_Destroy( ovrFramebuffer * frameBuffer )
{
	GL( glDeleteFramebuffers( frameBuffer->TextureSwapChainLength, frameBuffer->FrameBuffers ) );
	GL( glDeleteRenderbuffers( frameBuffer->TextureSwapChainLength, frameBuffer->DepthBuffers ) );
	vrapi_DestroyTextureSwapChain( frameBuffer->ColorTextureSwapChain );

	free( frameBuffer->DepthBuffers );
	free( frameBuffer->FrameBuffers );

	ovrFramebuffer_Clear( frameBuffer );
}

static void ovrFramebuffer_SetCurrent( ovrFramebuffer * frameBuffer )
{
	GL( glBindFramebuffer( GL_FRAMEBUFFER, frameBuffer->FrameBuffers[frameBuffer->TextureSwapChainIndex] ) );
}

static void ovrFramebuffer_SetNone()
{
	GL( glBindFramebuffer( GL_FRAMEBUFFER, 0 ) );
}

static void ovrFramebuffer_Resolve( ovrFramebuffer * frameBuffer )
{
	// Discard the depth buffer, so the tiler won't need to write it back out to memory.
	const GLenum depthAttachment[1] = { GL_DEPTH_ATTACHMENT };
	glInvalidateFramebuffer( GL_FRAMEBUFFER, 1, depthAttachment );

	// Flush this frame worth of commands.
	glFlush();
}

static void ovrFramebuffer_Advance( ovrFramebuffer * frameBuffer )
{
	// Advance to the next texture from the set.
	frameBuffer->TextureSwapChainIndex = ( frameBuffer->TextureSwapChainIndex + 1 ) % frameBuffer->TextureSwapChainLength;
}

/*
================================================================================

ovrScene

================================================================================
*/

#define NUM_INSTANCES		1500

typedef struct
{
	bool				CreatedScene;
	bool				CreatedVAOs;
	unsigned int		Random;
	ovrProgram			Program;
	ovrGeometry			Cube;
	GLuint				InstanceTransformBuffer;
	ovrVector3f			CubePositions[NUM_INSTANCES];
	ovrVector3f			CubeRotations[NUM_INSTANCES];
} ovrScene;

static const char VERTEX_SHADER[] =
	"#version 300 es\n"
	"in vec3 vertexPosition;\n"
	"in vec4 vertexColor;\n"
	"in mat4 vertexTransform;\n"
	"uniform mat4 ViewMatrix;\n"
	"uniform mat4 ProjectionMatrix;\n"
	"out vec4 fragmentColor;\n"
	"void main()\n"
	"{\n"
	"	gl_Position = ProjectionMatrix * ( ViewMatrix * ( vertexTransform * vec4( vertexPosition, 1.0 ) ) );\n"
	"	fragmentColor = vertexColor;\n"
	"}\n";

static const char FRAGMENT_SHADER[] =
	"#version 300 es\n"
	"in lowp vec4 fragmentColor;\n"
	"out lowp vec4 outColor;\n"
	"void main()\n"
	"{\n"
	"	outColor = fragmentColor;\n"
	"}\n";

static void ovrScene_Clear( ovrScene * scene )
{
	scene->CreatedScene = false;
	scene->CreatedVAOs = false;
	scene->Random = 2;
	scene->InstanceTransformBuffer = 0;

	ovrProgram_Clear( &scene->Program );
	ovrGeometry_Clear( &scene->Cube );
}

static bool ovrScene_IsCreated( ovrScene * scene )
{
	return scene->CreatedScene;
}

static void ovrScene_CreateVAOs( ovrScene * scene )
{
	if ( !scene->CreatedVAOs )
	{
		ovrGeometry_CreateVAO( &scene->Cube );

		// Modify the VAO to use the instance transform attributes.
		GL( glBindVertexArray( scene->Cube.VertexArrayObject ) );
		GL( glBindBuffer( GL_ARRAY_BUFFER, scene->InstanceTransformBuffer ) );
		for ( int i = 0; i < 4; i++ )
		{
			GL( glEnableVertexAttribArray( VERTEX_ATTRIBUTE_LOCATION_TRANSFORM + i ) );
			GL( glVertexAttribPointer( VERTEX_ATTRIBUTE_LOCATION_TRANSFORM + i, 4, GL_FLOAT,
										false, 4 * 4 * sizeof( float ), (void *)( i * 4 * sizeof( float ) ) ) );
			GL( glVertexAttribDivisor( VERTEX_ATTRIBUTE_LOCATION_TRANSFORM + i, 1 ) );
		}
		GL( glBindVertexArray( 0 ) );

		scene->CreatedVAOs = true;
	}
}

static void ovrScene_DestroyVAOs( ovrScene * scene )
{
	if ( scene->CreatedVAOs )
	{
		ovrGeometry_DestroyVAO( &scene->Cube );

		scene->CreatedVAOs = false;
	}
}

static float ovrScene_RandomFloat( ovrScene * scene )
{
	scene->Random = 1664525L * scene->Random + 1013904223L;
	unsigned int rf = 0x3F800000 | ( scene->Random & 0x007FFFFF );
	return (*(float *)&rf) - 1.0f;
}

static void ovrScene_Create( ovrScene * scene )
{
	ovrProgram_Create( &scene->Program, VERTEX_SHADER, FRAGMENT_SHADER );
	ovrGeometry_CreateCube( &scene->Cube );

	// Create the instance transform attribute buffer.
	GL( glGenBuffers( 1, &scene->InstanceTransformBuffer ) );
	GL( glBindBuffer( GL_ARRAY_BUFFER, scene->InstanceTransformBuffer ) );
	GL( glBufferData( GL_ARRAY_BUFFER, NUM_INSTANCES * 4 * 4 * sizeof( float ), NULL, GL_DYNAMIC_DRAW ) );
	GL( glBindBuffer( GL_ARRAY_BUFFER, 0 ) );

	// Setup random cube positions and rotations.
	for ( int i = 0; i < NUM_INSTANCES; i++ )
	{
		// Using volatile keeps the compiler from optimizing away multiple calls to drand48().
		volatile float rx, ry, rz;
		for ( ; ; )
		{
			rx = ( ovrScene_RandomFloat( scene ) - 0.5f ) * ( 50.0f + sqrt( NUM_INSTANCES ) );
			ry = ( ovrScene_RandomFloat( scene ) - 0.5f ) * ( 50.0f + sqrt( NUM_INSTANCES ) );
			rz = ( ovrScene_RandomFloat( scene ) - 0.5f ) * ( 50.0f + sqrt( NUM_INSTANCES ) );
			// If too close to 0,0,0
			if ( fabsf( rx ) < 4.0f && fabsf( ry ) < 4.0f && fabsf( rz ) < 4.0f )
			{
				continue;
			}
			// Test for overlap with any of the existing cubes.
			bool overlap = false;
			for ( int j = 0; j < i; j++ )
			{
				if (	fabsf( rx - scene->CubePositions[j].x ) < 4.0f &&
						fabsf( ry - scene->CubePositions[j].y ) < 4.0f &&
						fabsf( rz - scene->CubePositions[j].z ) < 4.0f )
				{
					overlap = true;
					break;
				}
			}
			if ( !overlap )
			{
				break;
			}
		}

		// Insert into list sorted based on distance.
		int insert = 0;
		const float distSqr = rx * rx + ry * ry + rz * rz;
		for ( int j = i; j > 0; j-- )
		{
			const ovrVector3f * otherPos = &scene->CubePositions[j - 1];
			const float otherDistSqr = otherPos->x * otherPos->x + otherPos->y * otherPos->y + otherPos->z * otherPos->z;
			if ( distSqr > otherDistSqr )
			{
				insert = j;
				break;
			}
			scene->CubePositions[j] = scene->CubePositions[j - 1];
			scene->CubeRotations[j] = scene->CubeRotations[j - 1];
		}

		scene->CubePositions[insert].x = rx;
		scene->CubePositions[insert].y = ry;
		scene->CubePositions[insert].z = rz;

		scene->CubeRotations[insert].x = ovrScene_RandomFloat( scene );
		scene->CubeRotations[insert].y = ovrScene_RandomFloat( scene );
		scene->CubeRotations[insert].z = ovrScene_RandomFloat( scene );
	}

	scene->CreatedScene = true;

#if !MULTI_THREADED
	ovrScene_CreateVAOs( scene );
#endif
}

static void ovrScene_Destroy( ovrScene * scene )
{
#if !MULTI_THREADED
	ovrScene_DestroyVAOs( scene );
#endif

	ovrProgram_Destroy( &scene->Program );
	ovrGeometry_Destroy( &scene->Cube );
	GL( glDeleteBuffers( 1, &scene->InstanceTransformBuffer ) );
	scene->CreatedScene = false;
}

/*
================================================================================

ovrSimulation

================================================================================
*/

typedef struct
{
	ovrVector3f			CurrentRotation;
} ovrSimulation;

static void ovrSimulation_Clear( ovrSimulation * simulation )
{
	simulation->CurrentRotation.x = 0.0f;
	simulation->CurrentRotation.y = 0.0f;
	simulation->CurrentRotation.z = 0.0f;
}

static void ovrSimulation_Advance( ovrSimulation * simulation, double predictedDisplayTime )
{
	// Update rotation.
	simulation->CurrentRotation.x = (float)( predictedDisplayTime );
	simulation->CurrentRotation.y = (float)( predictedDisplayTime );
	simulation->CurrentRotation.z = (float)( predictedDisplayTime );
}

/*
================================================================================

ovrRenderer

================================================================================
*/

#define NUM_MULTI_SAMPLES	4

typedef struct
{
	ovrFramebuffer	FrameBuffer[VRAPI_FRAME_LAYER_EYE_MAX];
	ovrMatrix4f		ProjectionMatrix;
	ovrMatrix4f		TexCoordsTanAnglesMatrix;
} ovrRenderer;

static void ovrRenderer_Clear( ovrRenderer * renderer )
{
	for ( int eye = 0; eye < VRAPI_FRAME_LAYER_EYE_MAX; eye++ )
	{
		ovrFramebuffer_Clear( &renderer->FrameBuffer[eye] );
	}
	renderer->ProjectionMatrix = ovrMatrix4f_CreateIdentity();
	renderer->TexCoordsTanAnglesMatrix = ovrMatrix4f_CreateIdentity();
}

static void ovrRenderer_Create( ovrRenderer * renderer, const ovrJava * java )
{
	// Create the frame buffers.
	for ( int eye = 0; eye < VRAPI_FRAME_LAYER_EYE_MAX; eye++ )
	{
		ovrFramebuffer_Create( &renderer->FrameBuffer[eye],
								VRAPI_TEXTURE_FORMAT_8888,
								vrapi_GetSystemPropertyInt( java, VRAPI_SYS_PROP_SUGGESTED_EYE_TEXTURE_WIDTH ),
								vrapi_GetSystemPropertyInt( java, VRAPI_SYS_PROP_SUGGESTED_EYE_TEXTURE_HEIGHT ),
								NUM_MULTI_SAMPLES );
	}

	// Setup the projection matrix.
	renderer->ProjectionMatrix = ovrMatrix4f_CreateProjectionFov(
										vrapi_GetSystemPropertyFloat( java, VRAPI_SYS_PROP_SUGGESTED_EYE_FOV_DEGREES_X ),
										vrapi_GetSystemPropertyFloat( java, VRAPI_SYS_PROP_SUGGESTED_EYE_FOV_DEGREES_Y ),
										0.0f, 0.0f, 1.0f, 0.0f );
	renderer->TexCoordsTanAnglesMatrix = ovrMatrix4f_TanAngleMatrixFromProjection( &renderer->ProjectionMatrix );
}

static void ovrRenderer_Destroy( ovrRenderer * renderer )
{
	for ( int eye = 0; eye < VRAPI_FRAME_LAYER_EYE_MAX; eye++ )
	{
		ovrFramebuffer_Destroy( &renderer->FrameBuffer[eye] );
	}
	renderer->ProjectionMatrix = ovrMatrix4f_CreateIdentity();
	renderer->TexCoordsTanAnglesMatrix = ovrMatrix4f_CreateIdentity();
}

static ovrFrameParms ovrRenderer_RenderFrame( ovrRenderer * renderer, const ovrJava * java,
											long long frameIndex, int minimumVsyncs, const ovrPerformanceParms * perfParms,
											const ovrScene * scene, const ovrSimulation * simulation, 
											const ovrTracking * tracking, ovrMobile * ovr )
{
	ovrFrameParms parms = vrapi_DefaultFrameParms( java, VRAPI_FRAME_INIT_DEFAULT, vrapi_GetTimeInSeconds(), NULL );
	parms.FrameIndex = frameIndex;
	parms.MinimumVsyncs = minimumVsyncs;
	parms.PerformanceParms = *perfParms;
	parms.Layers[VRAPI_FRAME_LAYER_TYPE_WORLD].Flags |= VRAPI_FRAME_LAYER_FLAG_CHROMATIC_ABERRATION_CORRECTION;


	// Update the instance transform attributes.
	GL( glBindBuffer( GL_ARRAY_BUFFER, scene->InstanceTransformBuffer ) );
	GL( ovrMatrix4f * cubeTransforms = (ovrMatrix4f *) glMapBufferRange( GL_ARRAY_BUFFER, 0,
				NUM_INSTANCES * sizeof( ovrMatrix4f ), GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT ) );
	for ( int i = 0; i < NUM_INSTANCES; i++ )
	{
		const ovrMatrix4f rotation = ovrMatrix4f_CreateRotation(
										scene->CubeRotations[i].x * simulation->CurrentRotation.x,
										scene->CubeRotations[i].y * simulation->CurrentRotation.y,
										scene->CubeRotations[i].z * simulation->CurrentRotation.z );
		const ovrMatrix4f translation = ovrMatrix4f_CreateTranslation(
											scene->CubePositions[i].x,
											scene->CubePositions[i].y,
											scene->CubePositions[i].z );
		const ovrMatrix4f transform = ovrMatrix4f_Multiply( &translation, &rotation );
		cubeTransforms[i] = ovrMatrix4f_Transpose( &transform );
	}
	GL( glUnmapBuffer( GL_ARRAY_BUFFER ) );
	GL( glBindBuffer( GL_ARRAY_BUFFER, 0 ) );

	// Calculate the center view matrix.
	const ovrHeadModelParms headModelParms = vrapi_DefaultHeadModelParms();

	// Render the eye images.
	for ( int eye = 0; eye < VRAPI_FRAME_LAYER_EYE_MAX; eye++ )
	{
		// updated sensor prediction for each eye (updates orientation, not position)
#if REDUCED_LATENCY
		ovrTracking updatedTracking = vrapi_GetPredictedTracking( ovr, tracking->HeadPose.TimeInSeconds );
		updatedTracking.HeadPose.Pose.Position = tracking->HeadPose.Pose.Position;
#else
		ovrTracking updatedTracking = *tracking;
#endif

		// Calculate the center view matrix.
		const ovrMatrix4f centerEyeViewMatrix = vrapi_GetCenterEyeViewMatrix( &headModelParms, &updatedTracking, NULL );
		const ovrMatrix4f eyeViewMatrix = vrapi_GetEyeViewMatrix( &headModelParms, &centerEyeViewMatrix, eye );

		ovrFramebuffer * frameBuffer = &renderer->FrameBuffer[eye];
		ovrFramebuffer_SetCurrent( frameBuffer );

		GL( glEnable( GL_SCISSOR_TEST ) );
		GL( glDepthMask( GL_TRUE ) );
		GL( glEnable( GL_DEPTH_TEST ) );
		GL( glDepthFunc( GL_LEQUAL ) );
		GL( glViewport( 0, 0, frameBuffer->Width, frameBuffer->Height ) );
		GL( glScissor( 0, 0, frameBuffer->Width, frameBuffer->Height ) );
		GL( glClearColor( 0.125f, 0.0f, 0.125f, 1.0f ) );
		GL( glClear( GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT ) );
		GL( glUseProgram( scene->Program.Program ) );
		GL( glUniformMatrix4fv( scene->Program.Uniforms[UNIFORM_VIEW_MATRIX], 1, GL_TRUE, (const GLfloat *)eyeViewMatrix.M[0] ) );
		GL( glUniformMatrix4fv( scene->Program.Uniforms[UNIFORM_PROJECTION_MATRIX], 1, GL_TRUE, (const GLfloat *)renderer->ProjectionMatrix.M[0] ) );
		GL( glBindVertexArray( scene->Cube.VertexArrayObject ) );
		GL( glDrawElementsInstanced( GL_TRIANGLES, scene->Cube.IndexCount, GL_UNSIGNED_SHORT, NULL, NUM_INSTANCES ) );
		GL( glBindVertexArray( 0 ) );
		GL( glUseProgram( 0 ) );

		// Explicitly clear the border texels to black because OpenGL-ES does not support GL_CLAMP_TO_BORDER.
		{
			// Clear to fully opaque black.
			GL( glClearColor( 0.0f, 0.0f, 0.0f, 1.0f ) );
			// bottom
			GL( glScissor( 0, 0, frameBuffer->Width, 1 ) );
			GL( glClear( GL_COLOR_BUFFER_BIT ) );
			// top
			GL( glScissor( 0, frameBuffer->Height - 1, frameBuffer->Width, 1 ) );
			GL( glClear( GL_COLOR_BUFFER_BIT ) );
			// left
			GL( glScissor( 0, 0, 1, frameBuffer->Height ) );
			GL( glClear( GL_COLOR_BUFFER_BIT ) );
			// right
			GL( glScissor( frameBuffer->Width - 1, 0, 1, frameBuffer->Height ) );
			GL( glClear( GL_COLOR_BUFFER_BIT ) );
		}

		ovrFramebuffer_Resolve( frameBuffer );

		parms.Layers[VRAPI_FRAME_LAYER_TYPE_WORLD].Textures[eye].ColorTextureSwapChain = frameBuffer->ColorTextureSwapChain;
		parms.Layers[VRAPI_FRAME_LAYER_TYPE_WORLD].Textures[eye].TextureSwapChainIndex = frameBuffer->TextureSwapChainIndex;
		parms.Layers[VRAPI_FRAME_LAYER_TYPE_WORLD].Textures[eye].TexCoordsFromTanAngles = renderer->TexCoordsTanAnglesMatrix;
		parms.Layers[VRAPI_FRAME_LAYER_TYPE_WORLD].Textures[eye].HeadPose = updatedTracking.HeadPose;

		ovrFramebuffer_Advance( frameBuffer );
	}

	ovrFramebuffer_SetNone();

	return parms;
}

/*
================================================================================

ovrRenderThread

================================================================================
*/

#if MULTI_THREADED

typedef enum
{
	RENDER_FRAME,
	RENDER_LOADING_ICON,
	RENDER_BLACK_FLUSH,
	RENDER_BLACK_FINAL
} ovrRenderType;

typedef struct
{
	JavaVM *			JavaVm;
	jobject				ActivityObject;
	const ovrEgl *		ShareEgl;
	pthread_t			Thread;
	int					Tid;
	// Synchronization
	bool				Exit;
	bool				WorkAvailableFlag;
	bool				WorkDoneFlag;
	pthread_cond_t		WorkAvailableCondition;
	pthread_cond_t		WorkDoneCondition;
	pthread_mutex_t		Mutex;
	// Latched data for rendering.
	ovrMobile *			Ovr;
	ovrRenderType		RenderType;
	long long			FrameIndex;
	int					MinimumVsyncs;
	ovrPerformanceParms	PerformanceParms;
	ovrScene *			Scene;
	ovrSimulation		Simulation;
	ovrTracking			Tracking;
} ovrRenderThread;

void * RenderThreadFunction( void * parm )
{
	ovrRenderThread * renderThread = (ovrRenderThread *)parm;
	renderThread->Tid = gettid();

	ovrJava java;
	java.Vm = renderThread->JavaVm;
	(*java.Vm)->AttachCurrentThread( java.Vm, &java.Env, NULL );
	java.ActivityObject = renderThread->ActivityObject;

	ovrEgl egl;
	ovrEgl_CreateContext( &egl, renderThread->ShareEgl );

	ovrRenderer renderer;
	ovrRenderer_Create( &renderer, &java );

	ovrScene * lastScene = NULL;

	for( ; ; )
	{
		// Signal work completed.
		pthread_mutex_lock( &renderThread->Mutex );
		renderThread->WorkDoneFlag = true;
		pthread_cond_signal( &renderThread->WorkDoneCondition );
		pthread_mutex_unlock( &renderThread->Mutex );

		// Wait for work.
		pthread_mutex_lock( &renderThread->Mutex );
		while ( !renderThread->WorkAvailableFlag )
		{
			pthread_cond_wait( &renderThread->WorkAvailableCondition, &renderThread->Mutex );
		}
		renderThread->WorkAvailableFlag = false;
		pthread_mutex_unlock( &renderThread->Mutex );

		// Check for exit.
		if ( renderThread->Exit )
		{
			break;
		}

		// Make sure the scene has VAOs created for this context.
		if ( renderThread->Scene != NULL && renderThread->Scene != lastScene )
		{
			if ( lastScene != NULL )
			{
				ovrScene_DestroyVAOs( lastScene );
			}
			ovrScene_CreateVAOs( renderThread->Scene );
			lastScene = renderThread->Scene;
		}

		// Render.
		ovrFrameParms frameParms;
		if ( renderThread->RenderType == RENDER_FRAME )
		{
			frameParms = ovrRenderer_RenderFrame( &renderer, &java,
					renderThread->FrameIndex, renderThread->MinimumVsyncs, &renderThread->PerformanceParms,
					renderThread->Scene, &renderThread->Simulation,
					&renderThread->Tracking, renderThread->Ovr );
		}
		else if ( renderThread->RenderType == RENDER_LOADING_ICON )
		{
			frameParms = vrapi_DefaultFrameParms( &java, VRAPI_FRAME_INIT_LOADING_ICON_FLUSH, vrapi_GetTimeInSeconds(), NULL );
			frameParms.FrameIndex = renderThread->FrameIndex;
			frameParms.MinimumVsyncs = renderThread->MinimumVsyncs;
			frameParms.PerformanceParms = renderThread->PerformanceParms;
		}
		else if ( renderThread->RenderType == RENDER_BLACK_FLUSH )
		{
			frameParms = vrapi_DefaultFrameParms( &java, VRAPI_FRAME_INIT_BLACK_FLUSH, vrapi_GetTimeInSeconds(), NULL );
			frameParms.FrameIndex = renderThread->FrameIndex;
			frameParms.MinimumVsyncs = renderThread->MinimumVsyncs;
			frameParms.PerformanceParms = renderThread->PerformanceParms;
		}
		else if ( renderThread->RenderType == RENDER_BLACK_FINAL )
		{
			frameParms = vrapi_DefaultFrameParms( &java, VRAPI_FRAME_INIT_BLACK_FINAL, vrapi_GetTimeInSeconds(), NULL );
			frameParms.FrameIndex = renderThread->FrameIndex;
			frameParms.MinimumVsyncs = renderThread->MinimumVsyncs;
			frameParms.PerformanceParms = renderThread->PerformanceParms;
		}

		// Hand over the eye images to the time warp.
		vrapi_SubmitFrame( renderThread->Ovr, &frameParms );
	}

	if ( lastScene != NULL )
	{
		ovrScene_DestroyVAOs( lastScene );
	}

	ovrRenderer_Destroy( &renderer );
	ovrEgl_DestroyContext( &egl );

	(*java.Vm)->DetachCurrentThread( java.Vm );
}

static void ovrRenderThread_Clear( ovrRenderThread * renderThread )
{
	renderThread->JavaVm = NULL;
	renderThread->ActivityObject = NULL;
	renderThread->ShareEgl = NULL;
	renderThread->Thread = 0;
	renderThread->Tid = 0;
	renderThread->Exit = false;
	renderThread->WorkAvailableFlag = false;
	renderThread->WorkDoneFlag = false;
	renderThread->Ovr = NULL;
	renderThread->RenderType = RENDER_FRAME;
	renderThread->FrameIndex = 1;
	renderThread->MinimumVsyncs = 1;
	renderThread->PerformanceParms = vrapi_DefaultPerformanceParms();
	renderThread->Scene = NULL;
	ovrSimulation_Clear( &renderThread->Simulation );
}

static void ovrRenderThread_Create( ovrRenderThread * renderThread, const ovrJava * java, const ovrEgl * shareEgl )
{
	renderThread->JavaVm = java->Vm;
	renderThread->ActivityObject = java->ActivityObject;
	renderThread->ShareEgl = shareEgl;
	renderThread->Thread = 0;
	renderThread->Tid = 0;
	renderThread->Exit = false;
	renderThread->WorkAvailableFlag = false;
	renderThread->WorkDoneFlag = false;
	pthread_cond_init( &renderThread->WorkAvailableCondition, NULL );
	pthread_cond_init( &renderThread->WorkDoneCondition, NULL );
	pthread_mutex_init( &renderThread->Mutex, NULL );

	const int createErr = pthread_create( &renderThread->Thread, NULL, RenderThreadFunction, renderThread );
	if ( createErr != 0 )
	{
		ALOGE( "pthread_create returned %i", createErr );
	}
}

static void ovrRenderThread_Destroy( ovrRenderThread * renderThread )
{
	pthread_mutex_lock( &renderThread->Mutex );
	renderThread->Exit = true;
	renderThread->WorkAvailableFlag = true;
	pthread_cond_signal( &renderThread->WorkAvailableCondition );
	pthread_mutex_unlock( &renderThread->Mutex );

	pthread_join( renderThread->Thread, NULL );
	pthread_cond_destroy( &renderThread->WorkAvailableCondition );
	pthread_cond_destroy( &renderThread->WorkDoneCondition );
	pthread_mutex_destroy( &renderThread->Mutex );
}

static void ovrRenderThread_Submit( ovrRenderThread * renderThread, ovrMobile * ovr,
		ovrRenderType type, long long frameIndex, int minimumVsyncs, const ovrPerformanceParms * perfParms,
		ovrScene * scene, const ovrSimulation * simulation, const ovrTracking * tracking )
{
	// Wait for the renderer thread to finish the last frame.
	pthread_mutex_lock( &renderThread->Mutex );
	while ( !renderThread->WorkDoneFlag )
	{
		pthread_cond_wait( &renderThread->WorkDoneCondition, &renderThread->Mutex );
	}
	renderThread->WorkDoneFlag = false;
	// Latch the render data.
	renderThread->Ovr = ovr;
	renderThread->RenderType = type;
	renderThread->FrameIndex = frameIndex;
	renderThread->MinimumVsyncs = minimumVsyncs;
	renderThread->PerformanceParms = *perfParms;
	renderThread->Scene = scene;
	if ( simulation != NULL )
	{
		renderThread->Simulation = *simulation;
	}
	if ( tracking != NULL )
	{
		renderThread->Tracking = *tracking;
	}
	// Signal work is available.
	renderThread->WorkAvailableFlag = true;
	pthread_cond_signal( &renderThread->WorkAvailableCondition );
	pthread_mutex_unlock( &renderThread->Mutex );
}

static void ovrRenderThread_Wait( ovrRenderThread * renderThread )
{
	// Wait for the renderer thread to finish the last frame.
	pthread_mutex_lock( &renderThread->Mutex );
	while ( !renderThread->WorkDoneFlag )
	{
		pthread_cond_wait( &renderThread->WorkDoneCondition, &renderThread->Mutex );
	}
	pthread_mutex_unlock( &renderThread->Mutex );
}

static int ovrRenderThread_GetTid( ovrRenderThread * renderThread )
{
	ovrRenderThread_Wait( renderThread );
	return renderThread->Tid;
}

#endif // MULTI_THREADED

/*
================================================================================

ovrApp

================================================================================
*/

typedef enum
{
	BACK_BUTTON_STATE_NONE,
	BACK_BUTTON_STATE_PENDING_DOUBLE_TAP,
	BACK_BUTTON_STATE_PENDING_SHORT_PRESS,
	BACK_BUTTON_STATE_SKIP_UP
} ovrBackButtonState;

typedef struct
{
	ovrJava				Java;
	ovrEgl				Egl;
	ANativeWindow *		NativeWindow;
	bool				Resumed;
	ovrMobile *			Ovr;
	ovrScene			Scene;
	ovrSimulation		Simulation;
	long long			FrameIndex;
	int					MinimumVsyncs;
	ovrBackButtonState	BackButtonState;
	bool				BackButtonDown;
	double				BackButtonDownStartTime;
#if MULTI_THREADED
	ovrRenderThread		RenderThread;
#else
	ovrRenderer			Renderer;
#endif
	bool				WasMounted;
} ovrApp;

static void ovrApp_Clear( ovrApp * app )
{
	app->Java.Vm = NULL;
	app->Java.Env = NULL;
	app->Java.ActivityObject = NULL;
	app->NativeWindow = NULL;
	app->Resumed = false;
	app->Ovr = NULL;
	app->FrameIndex = 1;
	app->MinimumVsyncs = 1;
	app->BackButtonState = BACK_BUTTON_STATE_NONE;
	app->BackButtonDown = false;
	app->BackButtonDownStartTime = 0.0;
	app->WasMounted = false;

	ovrEgl_Clear( &app->Egl );
	ovrScene_Clear( &app->Scene );
	ovrSimulation_Clear( &app->Simulation );
#if MULTI_THREADED
	ovrRenderThread_Clear( &app->RenderThread );
#else
	ovrRenderer_Clear( &app->Renderer );
#endif
}

static void ovrApp_PushBlackFinal( ovrApp * app, const ovrPerformanceParms * perfParms )
{
#if MULTI_THREADED
	ovrRenderThread_Submit( &app->RenderThread, app->Ovr,
			RENDER_BLACK_FINAL, app->FrameIndex, app->MinimumVsyncs, perfParms,
			NULL, NULL, NULL );
#else
	ovrFrameParms frameParms = vrapi_DefaultFrameParms( &app->Java, VRAPI_FRAME_INIT_BLACK_FINAL, vrapi_GetTimeInSeconds(), NULL );
	frameParms.FrameIndex = app->FrameIndex;
	frameParms.PerformanceParms = *perfParms;
	vrapi_SubmitFrame( app->Ovr, &frameParms );
#endif
}

static void ovrApp_HandleVrModeChanges( ovrApp * app )
{
	if ( app->NativeWindow != NULL && app->Egl.MainSurface == EGL_NO_SURFACE )
	{
		ovrEgl_CreateSurface( &app->Egl, app->NativeWindow );
	}

	if ( app->Resumed != false && app->NativeWindow != NULL )
	{
		if ( app->Ovr == NULL )
		{
			ovrModeParms parms = vrapi_DefaultModeParms( &app->Java );
			parms.ResetWindowFullscreen = false;	// No need to reset the FLAG_FULLSCREEN window flag when using a View

#if EXPLICIT_GL_OBJECTS == 1
			parms.Display = (size_t)app->Egl.Display;
			parms.WindowSurface = (size_t)app->Egl.MainSurface;
			parms.ShareContext = (size_t)app->Egl.Context;
#else
			ALOGV( "        eglGetCurrentSurface( EGL_DRAW ) = %p", eglGetCurrentSurface( EGL_DRAW ) );
#endif

			app->Ovr = vrapi_EnterVrMode( &parms );

			ALOGV( "        vrapi_EnterVrMode()" );

#if EXPLICIT_GL_OBJECTS == 0
			ALOGV( "        eglGetCurrentSurface( EGL_DRAW ) = %p", eglGetCurrentSurface( EGL_DRAW ) );
#endif
		}
	}
	else
	{
		if ( app->Ovr != NULL )
		{
#if MULTI_THREADED
			// Make sure the renderer thread is no longer using the ovrMobile.
			ovrRenderThread_Wait( &app->RenderThread );
#endif
			ALOGV( "        eglGetCurrentSurface( EGL_DRAW ) = %p", eglGetCurrentSurface( EGL_DRAW ) );

			vrapi_LeaveVrMode( app->Ovr );
			app->Ovr = NULL;

			ALOGV( "        vrapi_LeaveVrMode()" );
			ALOGV( "        eglGetCurrentSurface( EGL_DRAW ) = %p", eglGetCurrentSurface( EGL_DRAW ) );
		}
	}

	if ( app->NativeWindow == NULL && app->Egl.MainSurface != EGL_NO_SURFACE )
	{
		ovrEgl_DestroySurface( &app->Egl );
	}
}

static void ovrApp_BackButtonAction( ovrApp * app, const ovrPerformanceParms * perfParms )
{
	if ( app->BackButtonState == BACK_BUTTON_STATE_PENDING_DOUBLE_TAP )
	{
		ALOGV( "back button double tap" );
		app->BackButtonState = BACK_BUTTON_STATE_SKIP_UP;
	}
	else if ( app->BackButtonState == BACK_BUTTON_STATE_PENDING_SHORT_PRESS && !app->BackButtonDown )
	{
		if ( ( vrapi_GetTimeInSeconds() - app->BackButtonDownStartTime ) > BACK_BUTTON_DOUBLE_TAP_TIME_IN_SECONDS )
		{
			ALOGV( "back button short press" );
			ALOGV( "        ovrApp_PushBlackFinal()" );
			ovrApp_PushBlackFinal( app, perfParms );
			ALOGV( "        SystemActivities_StartSystemActivity( %s )", PUI_CONFIRM_QUIT );
			SystemActivities_StartSystemActivity( &app->Java, PUI_CONFIRM_QUIT, NULL );
			app->BackButtonState = BACK_BUTTON_STATE_NONE;
		}
	}
	else if ( app->BackButtonState == BACK_BUTTON_STATE_NONE && app->BackButtonDown )
	{
		if ( ( vrapi_GetTimeInSeconds() - app->BackButtonDownStartTime ) > BACK_BUTTON_LONG_PRESS_TIME_IN_SECONDS )
		{
			ALOGV( "back button long press" );
			ALOGV( "        ovrApp_PushBlackFinal()" );
			ovrApp_PushBlackFinal( app, perfParms );
			ALOGV( "        SystemActivities_StartSystemActivity( %s )", PUI_GLOBAL_MENU );
			SystemActivities_StartSystemActivity( &app->Java, PUI_GLOBAL_MENU, NULL );
			app->BackButtonState = BACK_BUTTON_STATE_SKIP_UP;
		}
	}
}

static int ovrApp_HandleKeyEvent( ovrApp * app, const int keyCode, const int action )
{
	// Handle GearVR back button.
	if ( keyCode == AKEYCODE_BACK )
	{
		if ( action == AKEY_EVENT_ACTION_DOWN )
		{
			if ( !app->BackButtonDown )
			{
				if ( ( vrapi_GetTimeInSeconds() - app->BackButtonDownStartTime ) < BACK_BUTTON_DOUBLE_TAP_TIME_IN_SECONDS )
				{
					app->BackButtonState = BACK_BUTTON_STATE_PENDING_DOUBLE_TAP;
				}
				app->BackButtonDownStartTime = vrapi_GetTimeInSeconds();
			}
			app->BackButtonDown = true;
		}
		else if ( action == AKEY_EVENT_ACTION_UP )
		{
			if ( app->BackButtonState == BACK_BUTTON_STATE_NONE )
			{
				if ( ( vrapi_GetTimeInSeconds() - app->BackButtonDownStartTime ) < BACK_BUTTON_SHORT_PRESS_TIME_IN_SECONDS )
				{
					app->BackButtonState = BACK_BUTTON_STATE_PENDING_SHORT_PRESS;
				}
			}
			else if ( app->BackButtonState == BACK_BUTTON_STATE_SKIP_UP )
			{
				app->BackButtonState = BACK_BUTTON_STATE_NONE;
			}
			app->BackButtonDown = false;
		}
		return 1;
	}
	return 0;
}

static int ovrApp_HandleTouchEvent( ovrApp * app, const int action, const float x, const float y )
{
	// Handle GearVR touch pad.
	if ( app->Ovr != NULL && action == AMOTION_EVENT_ACTION_UP )
	{
#if 0
		// Cycle through 60Hz, 30Hz, 20Hz and 15Hz synthesis.
		app->MinimumVsyncs++;
		if ( app->MinimumVsyncs > 4 )
		{
			app->MinimumVsyncs = 1;
		}
		ALOGV( "        MinimumVsyncs = %d", app->MinimumVsyncs );
#endif
	}
	return 1;
}

static void ovrApp_HandleSystemEvents( ovrApp * app )
{
	SystemActivitiesAppEventList_t appEvents;
	SystemActivities_Update( app->Ovr, &app->Java, &appEvents );

	// if you want to reorient on mount, check mount state here
	const bool isMounted = ( vrapi_GetSystemStatusInt( &app->Java, VRAPI_SYS_STATUS_MOUNTED ) != VRAPI_FALSE );
	if ( isMounted && !app->WasMounted )
	{
		// We just mounted so push a reorient event to be handled at the app level (if desired)
		char reorientMessage[1024];
		SystemActivities_CreateSystemActivitiesCommand( "", SYSTEM_ACTIVITY_EVENT_REORIENT, "", "", reorientMessage, sizeof( reorientMessage ) );
		SystemActivities_AppendAppEvent( &appEvents, reorientMessage );
	}
	app->WasMounted = isMounted;

	// TODO: any app specific events should be handled right here by looping over appEvents list

	SystemActivities_PostUpdate( app->Ovr, &app->Java, &appEvents );
}

/*
================================================================================

Native Activity

================================================================================
*/

/**
 * Process the next main command.
 */
static void app_handle_cmd( struct android_app * app, int32_t cmd )
{
	ovrApp * appState = (ovrApp *)app->userData;

	switch ( cmd )
	{
		// There is no APP_CMD_CREATE. The ANativeActivity creates the
		// application thread from onCreate(). The application thread
		// then calls android_main().
		case APP_CMD_START:
		{
			ALOGV( "onStart()" );
			ALOGV( "    APP_CMD_START" );
			break;
		}
		case APP_CMD_RESUME:
		{
			ALOGV( "onResume()" );
			ALOGV( "    APP_CMD_RESUME" );
			appState->Resumed = true;
			break;
		}
		case APP_CMD_PAUSE:
		{
			ALOGV( "onPause()" );
			ALOGV( "    APP_CMD_PAUSE" );
			appState->Resumed = false;
			break;
		}
		case APP_CMD_STOP:
		{
			ALOGV( "onStop()" );
			ALOGV( "    APP_CMD_STOP" );
			break;
		}
		case APP_CMD_DESTROY:
		{
			ALOGV( "onDestroy()" );
			ALOGV( "    APP_CMD_DESTROY" );
			appState->NativeWindow = NULL;
			break;
		}
		case APP_CMD_INIT_WINDOW:
		{
			ALOGV( "surfaceCreated()" );
			ALOGV( "    APP_CMD_INIT_WINDOW" );
			appState->NativeWindow = app->window;
			break;
		}
		case APP_CMD_TERM_WINDOW:
		{
			ALOGV( "surfaceDestroyed()" );
			ALOGV( "    APP_CMD_TERM_WINDOW" );
			appState->NativeWindow = NULL;
			break;
		}
    }
}

/**
 * Process the next input event.
 */
static int32_t app_handle_input( struct android_app * app, AInputEvent * event )
{
	ovrApp * appState = (ovrApp *)app->userData;
	const int type = AInputEvent_getType( event );
	if ( type == AINPUT_EVENT_TYPE_KEY )
	{
		const int keyCode = AKeyEvent_getKeyCode( event );
		const int action = AKeyEvent_getAction( event );
		return ovrApp_HandleKeyEvent( appState, keyCode, action );
	}
	else if ( type == AINPUT_EVENT_TYPE_MOTION )
	{
		const int source = AInputEvent_getSource( event );
		// Events with source == AINPUT_SOURCE_TOUCHSCREEN come from the phone's builtin touch screen.
		// Events with source == AINPUT_SOURCE_MOUSE come from the trackpad on the right side of the GearVR.
		if ( source == AINPUT_SOURCE_TOUCHSCREEN || source == AINPUT_SOURCE_MOUSE )
		{
			const int action = AKeyEvent_getAction( event ) & AMOTION_EVENT_ACTION_MASK;
			const float x = AMotionEvent_getRawX( event, 0 );
			const float y = AMotionEvent_getRawY( event, 0 );
			return ovrApp_HandleTouchEvent( appState, action, x, y );
		}
	}
	return 0;
}

/**
 * This is the main entry point of a native application that is using
 * android_native_app_glue.  It runs in its own thread, with its own
 * event loop for receiving input events and doing other things.
 */
void android_main( struct android_app * app )
{
	// Make sure the native app glue is not stripped.
	app_dummy();

	ALOGV( "----------------------------------------------------------------" );
	ALOGV( "android_app_entry()" );
	ALOGV( "    android_main()" );

	ovrJava java;
	java.Vm = app->activity->vm;
	(*java.Vm)->AttachCurrentThread( java.Vm, &java.Env, NULL );
	java.ActivityObject = app->activity->clazz;

	SystemActivities_Init( &java );

	const ovrInitParms initParms = vrapi_DefaultInitParms( &java );
	int32_t initResult = vrapi_Initialize( &initParms );
	if ( initResult != VRAPI_INITIALIZE_SUCCESS )
	{
		char const * msg = initResult == VRAPI_INITIALIZE_PERMISSIONS_ERROR ? 
										"Thread priority security exception. Make sure the APK is signed." :
										"VrApi initialization error.";
		SystemActivities_DisplayError( &java, SYSTEM_ACTIVITIES_FATAL_ERROR_OSIG, __FILE__, msg );
	}

	ovrApp appState;
	ovrApp_Clear( &appState );
	appState.Java = java;

	ovrEgl_CreateContext( &appState.Egl, NULL );

	ovrPerformanceParms perfParms = vrapi_DefaultPerformanceParms();
	perfParms.CpuLevel = CPU_LEVEL;
	perfParms.GpuLevel = GPU_LEVEL;
	perfParms.MainThreadTid = gettid();

#if MULTI_THREADED
	ovrRenderThread_Create( &appState.RenderThread, &appState.Java, &appState.Egl );
	// Also set the renderer thread to SCHED_FIFO.
	perfParms.RenderThreadTid = ovrRenderThread_GetTid( &appState.RenderThread );
#else
	ovrRenderer_Create( &appState.Renderer, &java );
#endif

	app->userData = &appState;
	app->onAppCmd = app_handle_cmd;
	app->onInputEvent = app_handle_input;

	while ( app->destroyRequested == 0 )
	{
		// Read all pending events.
		for ( ; ; )
		{
			int events;
			struct android_poll_source * source;
			const int timeoutMilliseconds = ( appState.Ovr == NULL && app->destroyRequested == 0 ) ? -1 : 0;
			if ( ALooper_pollAll( timeoutMilliseconds, NULL, &events, (void **)&source ) < 0 )
			{
				break;
			}

			// Process this event.
			if ( source != NULL )
			{
				source->process( app, source );
			}

			ovrApp_HandleVrModeChanges( &appState );
		}

		ovrApp_BackButtonAction( &appState, &perfParms );
		ovrApp_HandleSystemEvents( &appState );

		if ( appState.Ovr == NULL )
		{
			continue;
		}

		// Create the scene if not yet created.
		// The scene is created here to be able to show a loading icon.
		if ( !ovrScene_IsCreated( &appState.Scene ) )
		{
#if MULTI_THREADED
			// Show a loading icon.
			ovrRenderThread_Submit( &appState.RenderThread, appState.Ovr,
					RENDER_LOADING_ICON, appState.FrameIndex, appState.MinimumVsyncs, &perfParms,
					NULL, NULL, NULL );
#else
			// Show a loading icon.
			ovrFrameParms frameParms = vrapi_DefaultFrameParms( &appState.Java, VRAPI_FRAME_INIT_LOADING_ICON_FLUSH, vrapi_GetTimeInSeconds(), NULL );
			frameParms.FrameIndex = appState.FrameIndex;
			frameParms.PerformanceParms = perfParms;
			vrapi_SubmitFrame( appState.Ovr, &frameParms );
#endif

			// Create the scene.
			ovrScene_Create( &appState.Scene );
		}

		// This is the only place the frame index is incremented, right before
		// calling vrapi_GetPredictedDisplayTime().
		appState.FrameIndex++;

		// Get the HMD pose, predicted for the middle of the time period during which
		// the new eye images will be displayed. The number of frames predicted ahead
		// depends on the pipeline depth of the engine and the synthesis rate.
		// The better the prediction, the less black will be pulled in at the edges.
		const double predictedDisplayTime = vrapi_GetPredictedDisplayTime( appState.Ovr, appState.FrameIndex );
		const ovrTracking baseTracking = vrapi_GetPredictedTracking( appState.Ovr, predictedDisplayTime );

		// Apply the head-on-a-stick model if there is no positional tracking.
		const ovrHeadModelParms headModelParms = vrapi_DefaultHeadModelParms();
		const ovrTracking tracking = vrapi_ApplyHeadModel( &headModelParms, &baseTracking );

		// Advance the simulation based on the predicted display time.
		ovrSimulation_Advance( &appState.Simulation, predictedDisplayTime );

#if MULTI_THREADED
		// Render the eye images on a separate thread.
		ovrRenderThread_Submit( &appState.RenderThread, appState.Ovr,
				RENDER_FRAME, appState.FrameIndex, appState.MinimumVsyncs, &perfParms,
				&appState.Scene, &appState.Simulation, &tracking );
#else
		// Render eye images and setup ovrFrameParms using ovrTracking.
		const ovrFrameParms frameParms = ovrRenderer_RenderFrame( &appState.Renderer, &appState.Java,
				appState.FrameIndex, appState.MinimumVsyncs, &perfParms,
				&appState.Scene, &appState.Simulation, &tracking,
				appState.Ovr );

		// Hand over the eye images to the time warp.
		vrapi_SubmitFrame( appState.Ovr, &frameParms );
#endif
	}

#if MULTI_THREADED
	ovrRenderThread_Destroy( &appState.RenderThread );
#else
	ovrRenderer_Destroy( &appState.Renderer );
#endif

	ovrScene_Destroy( &appState.Scene );
	ovrEgl_DestroyContext( &appState.Egl );

	vrapi_Shutdown();

	SystemActivities_Shutdown( &java );

	(*java.Vm)->DetachCurrentThread( java.Vm );
}