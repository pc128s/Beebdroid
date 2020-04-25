/*
	main.c - some JNI entry points for Beebdroid

	Written by Reuben Scratton, based on code by Tom Walker
*/

#include "main.h"
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <sys/time.h>

extern BITMAP* b;
extern uint8_t crtc[];
#define PAL_SIZE 256
extern int pal[256];
int* current_palette;
unsigned short *current_palette16;

int samples;
int framecount=0;
int autoboot=0;
int joybutton[2];
int ideenable=0;
int resetting=0;
int numSndbufSamples;
short* sndbuf;


extern uint8_t keys[16][16];


jobject  g_obj;
jclass cls;
static JavaVM *gJavaVM = 0;
JNIEnv* env = 0;
jmethodID midAudioCallback;
jmethodID midVideoCallback;

jint JNI_OnLoad(JavaVM *jvm, void *reserved) {
	LOGI("JNI_OnLoad");
	gJavaVM = jvm;
	int status = (*gJavaVM)->GetEnv(gJavaVM, (void **) &env, JNI_VERSION_1_4);
	cls = (*env)->FindClass(env, "com/littlefluffytoys/beebdroid/Beebdroid");
	midAudioCallback = (*env)->GetMethodID(env, cls, "audioCallback", "(II)V");
	midVideoCallback = (*env)->GetMethodID(env, cls, "videoCallback", "()V");

	//importGLInit();

	return JNI_VERSION_1_4;

}
void reset_all() {
	disc_reset();
	ssd_reset();
	reset6502();
	resetsysvia();
	resetuservia();
	reset8271();
}

JNIEXPORT void JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcBreak(JNIEnv * env, jobject  obj, jint flags) {
	LOGI("bbcBreak");

	autoboot = 0;
	resetting = 1;
	reset_all();

	the_cpu->pc_triggers[0]=0;
	the_cpu->pc_triggers[1]=0;
	the_cpu->pc_triggers[2]=0;
	the_cpu->pc_triggers[3]=0;

}


JNIEXPORT void JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcLoadDisc(JNIEnv * env, jobject  obj, jobject directBuffer, jint do_autoboot)
{
	LOGI("bbcLoadDisc");

	unsigned int size = (*env)->GetDirectBufferCapacity(env, directBuffer);
	unsigned char* disc = (*env)->GetDirectBufferAddress(env, directBuffer);

	loaddisc(0, 0, disc, size);

	if (do_autoboot) {
		autoboot = 150;
		resetting = 1;
		resetsysvia();
	}
}

JNIEXPORT void JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcSetTriggers(JNIEnv * env, jobject  obj, jshortArray pc_triggers)
{
	LOGI("bbcSetTriggers");

	unsigned int c = (*env)->GetArrayLength(env, pc_triggers);
	unsigned short* tmp = (unsigned short*)(*env)->GetShortArrayElements(env, pc_triggers, 0);
	the_cpu->pc_triggers[0]=0;
	the_cpu->pc_triggers[1]=0;
	the_cpu->pc_triggers[2]=0;
	the_cpu->pc_triggers[3]=0;
	if (c>0) the_cpu->pc_triggers[0]=tmp[0];
	if (c>1) the_cpu->pc_triggers[1]=tmp[1];
	if (c>2) the_cpu->pc_triggers[2]=tmp[2];
	if (c>3) the_cpu->pc_triggers[3]=tmp[3];
}



JNIEXPORT void JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcExit(JNIEnv * env, jobject  obj) {
	//moncleanup();
}



JNIEXPORT void JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcInit(JNIEnv * env, jobject  obj,
		jobject amem, jobject aroms, jobject audiobuff, jint firstTime) {

	LOGI("bbcInit");
	autoboot = 0;

	// Profiling
	//monstartup("bbcmicro.so");

	// Get reference to the Java Beebdroid object (needed for callbacks)
	g_obj = (*env)->NewGlobalRef(env, obj);

	// Get pointers to memory and ROMs arrays, allocated on the Java side.
	the_cpu->mem = (*env)->GetDirectBufferAddress(env, amem);
	roms = (*env)->GetDirectBufferAddress(env, aroms);

	// Get sound buffer details
	numSndbufSamples = (*env)->GetDirectBufferCapacity(env, audiobuff);
	sndbuf = (short*) (*env)->GetDirectBufferAddress(env, audiobuff);


	// First-time init
	if (firstTime) {
		LOGI("calling initvideo()");
		initvideo();
		makemode7chars();

		reset_all();

		initsound();
		initadc();
	}

	LOGI("exiting initbbc()");
}

// Without the .S file, PIC is achieved
// void exec6502(M6502* f) {}
struct timeval tval_before, tval_after, tval_result;

JNIEXPORT jint JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcRun(JNIEnv * env, jobject  obj)
{

	if (autoboot)
		autoboot--;

    if (framecount++ == 0) {
    // Position independent code, hopefully!
    the_cpu->c_fns = fns; // +40
    
    	// All the following are bodges for x86 because I don't understand the loader.
    the_cpu->fns_asm = &fns_asm; // +44 for fns_asm table
    the_cpu->do_poll_C = &do_poll_C ; // +48 for do_poll_C
    the_cpu->readmem_ex = &readmem_ex; // +52 for readmem_ex
    the_cpu->readword_ex = &readword_ex; // +56 for readword_ex
    the_cpu->writemem_ex = &writemem_ex; // +60 for writemem_ex
    the_cpu->adc_bcd_C = &adc_bcd_C; // +64 for adc_bcd_C
    the_cpu->sbc_bcd_C = &sbc_bcd_C; // +68 for sbc_bcd_C
    the_cpu->log_undef_opcode_C = &log_undef_opcode_C_x86; // +72 for log_undef_opcode_C
    the_cpu->log_cpu_C = &log_cpu_C; // +76 for log_cpu_C
    the_cpu->log_asm_C = &log_asm; // +80 for log_cpu_C

    LOGI("the_cpu->fns_asm     = %p", &fns_asm); // +44 for fns_asm table
    LOGI("the_cpu->do_poll_C   = %p", &do_poll_C ); // +48 for do_poll_C
    LOGI("the_cpu->readmem_ex  = %p", &readmem_ex); // +52 for readmem_ex
    LOGI("the_cpu->readword_ex = %p", &readword_ex); // +56 for readword_ex
    LOGI("the_cpu->writemem_ex = %p", &writemem_ex); // +60 for writemem_ex
    LOGI("the_cpu->adc_bcd_C   = %p", &adc_bcd_C); // +64 for adc_bcd_C
    LOGI("the_cpu->sbc_bcd_C   = %p", &sbc_bcd_C); // +68 for sbc_bcd_C
    LOGI("the_cpu->log_un_op_C = %p", &log_undef_opcode_C_x86); // +72 for log_undef_opcode_C
    LOGI("the_cpu->log_cpu_C   = %p", &log_cpu_C); // +76 for log_cpu_C
    }

//    LOGI("%i exec6502=%X &acpu=%X the_cpu=%X  c_fns=%X", framecount, exec6502, &(*the_cpu), the_cpu, the_cpu->c_fns, the_cpu->cycles);

gettimeofday(&tval_before, NULL);
    void* ret = exec6502(the_cpu);
gettimeofday(&tval_after, NULL);

timersub(&tval_after, &tval_before, &tval_result);

//LOGI("Time elapsed: %ld.%06ld\n", (long int)tval_result.tv_sec, (long int)tval_result.tv_usec);

    //LOGI("exec6502() <= %X", ret);

	checkkeys();

	// Break!
	if (resetting) {
		LOGI("resetting... ");
		reset6502();
		reset8271();
		resetting = 0;
	}


	return the_cpu->pc_trigger_hit;
}




extern void dump_pages();

JNIEXPORT void JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcKeyEvent(JNIEnv * env, jclass _obj, jint vkey, jint flags, jint down) {
	//if (vkey == 0xaa) return; // BBCKEY_BREAK ?
	int raw = vkey&0x800; // BBCKEY_RAW_MOD
	int ctrl = vkey&0x400; // BBCKEY_CTRL_MOD
	if (vkey&0x100) { // BBCKEY_SHIFT_MOD
		flags = 1;
	}
	if (vkey&0x200) { // BBCKEY_ANTISHIFT_MOD
		flags = 0;
	}
	vkey &= 0xff;
	int col=vkey&15;
	int row=(vkey>>4)&15;

	if (down && vkey==0x37) { // BeebKeys.P
		dump_pages();
	}

	if (raw == 0) {
		// Press / unpress SHIFT
		keys[0][0] = flags ? down : 0x00;
		// Press / unpress CTRL
		keys[1][0] = ctrl ? down : 0x00;
	}
	// Press / unpress the key
	keys[col][row] = down ? 1 : 0;
	//LOGI("Key event %d,%d = %d", col, row, down);
}







extern uint8_t* load_cpu(uint8_t* p);
extern uint8_t* save_cpu(uint8_t* p);
extern uint8_t* load_video(uint8_t* p);
extern uint8_t* save_video(uint8_t* p);
extern uint8_t* load_sysvia(uint8_t* p);
extern uint8_t* save_sysvia(uint8_t* p);

JNIEXPORT int JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcSerialize(JNIEnv * env, jobject  obj, jbyteArray abuff) {

	LOGI("bbcSerialize");
	uint8_t* buff = (uint8_t*) (*env)->GetByteArrayElements(env, abuff, 0);
	uint8_t* buff_orig = buff;

	// RAM
	memcpy(buff, the_cpu->mem, 65536);
	buff += 65536;

	// Save state
	buff = save_cpu(buff);
	buff = save_video(buff);
	buff = save_sysvia(buff);


	return (buff - buff_orig);
}

JNIEXPORT void JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcDeserialize(JNIEnv * env, jobject  obj, jbyteArray abuff) {

	LOGI("bbcDeserialize");
	uint8_t* buff = (uint8_t*) (*env)->GetByteArrayElements(env, abuff, 0);

	// RAM
	memcpy(the_cpu->mem, buff, 65536);
	buff += 65536;

	// Video controller registers
	buff = load_cpu(buff);
	buff = load_video(buff);
	buff = load_sysvia(buff);



}

void givealbuffer(int16_t *buf, int pos, int cb) {
	//numSndbufSamples = (*env)->GetArrayLength(env, aaudiobuff);
	// (*env)->ReleaseByteArrayElements(env, aaudiobuff, 0);

	//LOGI("givealbuffer! %02X %02X %02X %02X %02X %02X %02X %02X", buf[0], buf[1], buf[2], buf[3], buf[4], buf[5], buf[6], buf[7]);
	(*env)->CallVoidMethod(env, g_obj, midAudioCallback, pos, cb);

}




/*
 * Clears the bitmap to the specified color
 */
void clear_to_color(BITMAP *bitmap, int color) {
	LOGI("clear_to_color");
	int x,y;
	unsigned char* pixels = bitmap->pixels;
	for (y=0 ; y<bitmap->height ; y++) {
		for (x=0 ; x<bitmap->width ; x++) {
			pixels[x] = color;
		}
		pixels += bitmap->stride;
	}
}

/*
 * Sets the entire palette of 256 colors. You should provide an array of 256 RGB structures.
 * Unlike set_color(), there is no need to call vsync() before this function
 */

unsigned short RGB_TO_565(unsigned int rgb) {
	return (RED(rgb)>>3)<<11 | ((GREEN(rgb)>>2)<<5) | ((BLUE(rgb)>>3));
}

void set_palette(int* p) {
	LOGI("set_palette");
	int i;
	current_palette16 = (unsigned short*)malloc(2*256);
	for (i=0 ; i<256 ; i++) {
		current_palette16[i] = RGB_TO_565(p[i]);
	}
}



extern int s_firstx, s_lastx, s_firsty, s_lasty;


JNIEXPORT jint JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcGetLocks(JNIEnv * env, jobject  obj)
{
    return IC32;
}

JNIEXPORT jint JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcPushAdc(JNIEnv * env, jobject  obj, jint x1, jint y1, jint x2, jint y2)
{
    joy1x = x1;
    joy1y = y1;
    joy2x = x2;
    joy2y = y2;
    return IC32;
}

JNIEXPORT jint JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcGetThumbnail(JNIEnv * env, jobject  obj, jobject jbitmap)
{
	AndroidBitmapInfo info;
	void* pixels;
	int ret;

    if ((ret = AndroidBitmap_getInfo(env, jbitmap, &info)) < 0) {
        LOGE("AndroidBitmap_getInfo() failed ! error=%d", ret);
        return 0; /* does LOGE exit?  Should LOGE below return? */
    }


    if ((ret = AndroidBitmap_lockPixels(env, jbitmap, &pixels)) < 0) {
        LOGE("AndroidBitmap_lockPixels() failed ! error=%d", ret);
    }

	uint8_t* pixelsSrc = (uint8_t*)(b->pixels + s_firsty * b->stride);
	uint8_t* pixelsDst = (uint8_t*)(pixels   + 0 * info.stride);
	int cx = s_lastx - s_firstx;
	int cy = s_lasty - s_firsty;
	int y;
	for (y=0 ; y<cy ; y++) {
		memcpy(pixelsDst, pixelsSrc + s_firstx*2, cx*2);
		pixelsSrc += b->stride;
		pixelsDst += info.stride;
	}
    AndroidBitmap_unlockPixels(env, jbitmap);
    return 0;
}

/*

JNIEXPORT jint JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcRender(JNIEnv * env, jobject  obj, jobject jbitmap)
{
	AndroidBitmapInfo info;
    void*              pixels;
    int                ret;

    if (b == NULL) {
    	return;
    }

    if ((ret = AndroidBitmap_getInfo(env, jbitmap, &info)) < 0) {
        LOGE("AndroidBitmap_getInfo() failed ! error=%d", ret);
        return;
    }


    if ((ret = AndroidBitmap_lockPixels(env, jbitmap, &pixels)) < 0) {
        LOGE("AndroidBitmap_lockPixels() failed ! error=%d", ret);
    }


	if (screenblitinfo.source_y<0) {
		screenblitinfo.height += screenblitinfo.source_y;
		screenblitinfo.dest_y -= screenblitinfo.source_y;
		screenblitinfo.source_y=0;
	}
	if (screenblitinfo.source_y<64000) {
		screenblitinfo.pixels = pixels;
		screenblitinfo.stride = info.stride;
		screenblitinfo.source_y >>= 1;
		register unsigned char*  pixelsSrc = (unsigned char*)(b->pixels + screenblitinfo.source_y * b->stride);
		register unsigned short* pixelsDst = (unsigned short*)(pixels   + screenblitinfo.dest_y   * info.stride);
		pixelsDst += screenblitinfo.dest_x;
		register int x,y;
		register unsigned short* pal = current_palette16;
		for (y=0 ; y<screenblitinfo.height>>1 ; y++) {
			for (x=0 ; x<screenblitinfo.width; x+=4) {
				// Read 4 pixel values at once (source bitmap is 8-bit)
				register uint32_t pixval = *(uint32_t*)(pixelsSrc+xoff+x);
				// Write 2 output pixel values at a time
				*(unsigned int*)(pixelsDst + x) = (pal[(pixval&0xff00u)>>8]<<16) | pal[pixval&0xff];
				*(unsigned int*)(pixelsDst + x + 2) = (pal[pixval>>24]<<16) | pal[(pixval&0xff0000u)>>16];
			}
			pixelsSrc += b->stride;
			pixelsDst += info.stride/2;
		}
	}


    AndroidBitmap_unlockPixels(env, jbitmap);
    return (((crtc[9]+1)*crtc[4]) <<16) | (crtc[1]*8);
}*/

BITMAP *create_bitmap(int width, int height) {
//	LOGI("create_bitmap %d x %d on 0x%X", width, height, g_obj);
	BITMAP* bmp = (BITMAP*)malloc(sizeof(BITMAP));
	bmp->width=width;
	bmp->height=height;
	bmp->bpp = 16;
	bmp->stride = width * (16/8);
	bmp->pixels = (unsigned char*) malloc(bmp->stride*height);
	return bmp;
}

typedef struct _VERTEX {
    float x;
    float y;
    float z;
    float s;
    float t;
} VERTEX;

VERTEX vertexes[4] = {
        {-1,-1,-1,  0,0.5f},
        { 1,-1,-1,  1,0.5f},
        {-1, 1,-1,  0,0},
        { 1, 1,-1,  1,0}
};

short indexes[4] = {0,1,2,3};


int tex;
int beebview_width;
int beebview_height;

static void checkGlError(const char* op) {
    GLint error;
    for (error = glGetError(); error; error = glGetError()) {
        LOGI("after %s() glError (0x%x)\n", op, error);
    }
}
GLuint loadShader(GLenum shaderType, const char* pSource) {
    GLuint shader = glCreateShader(shaderType);
    if (shader) {
        glShaderSource(shader, 1, &pSource, NULL);
        glCompileShader(shader);
        GLint compiled = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            GLint infoLen = 0;
            glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
            if (infoLen) {
                char* buf = (char*) malloc(infoLen);
                if (buf) {
                    glGetShaderInfoLog(shader, infoLen, NULL, buf);
                    LOGE("Could not compile shader %d:\n%s\n",
                         shaderType, buf);
                    free(buf);
                }
                glDeleteShader(shader);
                shader = 0;
            }
        }
    }
    return shader;
}

GLuint vertexBufferId;
GLuint indexBufferId;
GLuint gProgram;
GLuint gvPositionHandle;
GLuint gvTexCoord;

#define ATTRIB_VERTEX 1
#define ATTRIB_TEXCOORD 2

static const char gVertexShader[] =
        "attribute vec4 vPosition;\n"
        "attribute vec2 texcoord;\n"
        "varying mediump vec2 v_texcoord;\n"
                "void main() {\n"
                "  gl_Position = vPosition;\n"
                "  v_texcoord = texcoord;\n"
                "}\n";


static const char* gFragmentShader = "\
    varying mediump vec2 v_texcoord;\n\
    uniform sampler2D texture;\n\
    \n\
    void main()\n\
    {\n\
    gl_FragColor = texture2D(texture, v_texcoord);\n\
    }\n\
    ";

//
// bbcInitGl
//
JNIEXPORT jint JNICALL Java_com_littlefluffytoys_beebdroid_Beebdroid_bbcInitGl(JNIEnv * env, jobject  obj, jint width, jint height)
{
	beebview_width = width;
	beebview_height = height;

    GLuint vertexShader = loadShader(GL_VERTEX_SHADER, gVertexShader);
    if (!vertexShader) {
        return 0;
    }

    GLuint pixelShader = loadShader(GL_FRAGMENT_SHADER, gFragmentShader);
    if (!pixelShader) {
        return 0;
    }

    gProgram = glCreateProgram();
    glAttachShader(gProgram, vertexShader);
    checkGlError("glAttachShader");
    glAttachShader(gProgram, pixelShader);
    checkGlError("glAttachShader");

    glBindAttribLocation(gProgram, ATTRIB_VERTEX, "vPosition");
    glBindAttribLocation(gProgram, ATTRIB_TEXCOORD, "texcoord");

    glLinkProgram(gProgram);
    GLint linkStatus = GL_FALSE;
    glGetProgramiv(gProgram, GL_LINK_STATUS, &linkStatus);
    if (linkStatus != GL_TRUE) {
        GLint bufLength = 0;
        glGetProgramiv(gProgram, GL_INFO_LOG_LENGTH, &bufLength);
        if (bufLength) {
            char* buf = (char*) malloc(bufLength);
            if (buf) {
                glGetProgramInfoLog(gProgram, bufLength, NULL, buf);
                LOGE("Could not link program:\n%s\n", buf);
                free(buf);
            }
        }
        glDeleteProgram(gProgram);
        gProgram = 0;
    }

    if (!gProgram) {
        LOGE("Could not create program.");
        return 0;
    }
    gvPositionHandle = glGetAttribLocation(gProgram, "vPosition");
    checkGlError("glGetAttribLocation");
    gvTexCoord = glGetAttribLocation(gProgram, "texcoord");
    checkGlError("glGetAttribLocation");

 	glEnable(GL_TEXTURE_2D);
    glGenBuffers(1, &vertexBufferId);
    glGenBuffers(1, &indexBufferId);

 	// Get a texture ID
	GLuint textures[] = {1};
	glGenTextures(1, textures);
	tex = textures[0];

	// Set up the texture
	glBindTexture(GL_TEXTURE_2D, tex);
	glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

	// Assign the bitmap pixels to the texture
	glTexImage2D(GL_TEXTURE_2D,
		0,
		GL_RGB,
		1024,
		512,
		0,
		GL_RGB,
		GL_UNSIGNED_SHORT_5_6_5,
		b->pixels);

	return 1;
}

static char b4096[4096];

void blit_to_screen(int source_x, int source_y, int width, int height)
{
#if LOGGING
    LOGF("blit_to_screen(%i, %i, %i, %i)\n", source_x, source_y, width, height);
    char *p=b4096;
    for (int i=0; i <32; ++i) {
        int c = sprintf(p, "%i, ", crtc[i]);
        if ( c > 0 ) p += c; else break;
    }
    LOGF("CRTC %s", b4096);
	log_asm(0xb1140000);
#endif
	glViewport(0, 0, beebview_width, beebview_height);

    glBindTexture(GL_TEXTURE_2D, tex);

    //LOGI("blit_to_screen source_y=%d, height=%d", source_y, height);
    vertexes[0].s = vertexes[2].s = (float)source_x / (float)1024.0f;
    vertexes[1].s = vertexes[3].s = (float)(source_x+width) / (float)1024.0f;
    vertexes[2].t = vertexes[3].t = (float)source_y / (float)512.0f;
    vertexes[0].t = vertexes[1].t = (float)(source_y+height) / (float)512.0f;

    glBindBuffer(GL_ARRAY_BUFFER, vertexBufferId);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertexes), vertexes, GL_STATIC_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBufferId);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(indexes), indexes, GL_STATIC_DRAW);


    // Update the texture with the updated bitmap pixels
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0,0,
            	             1024,320, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, b->pixels);


    glUseProgram(gProgram);

    glBindBuffer(GL_ARRAY_BUFFER, vertexBufferId);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBufferId);

    glVertexAttribPointer(gvPositionHandle, 3, GL_FLOAT, GL_FALSE, sizeof(VERTEX), 0);
    glEnableVertexAttribArray(gvPositionHandle);
    glVertexAttribPointer(gvTexCoord, 2, GL_FLOAT, GL_FALSE, sizeof(VERTEX), (void*)(3*sizeof(float)));
    glEnableVertexAttribArray(gvTexCoord);
    glDrawElements(GL_TRIANGLE_STRIP, 4, GL_UNSIGNED_SHORT, 0);

	// Swap buffers on the java side (also updates FPS display)
	(*env)->CallVoidMethod(env, g_obj, midVideoCallback);

}




/* generate_332_palette:
 *  Used when loading a truecolor image into an 8 bit bitmap, to generate
 *  a 3.3.2 RGB palette.
 */
#define MAKE_RGB(r,g,b) ((b<<16)|(g<<8)|r)

void generate_332_palette(int* pal)
{
   int c;

   for (c=0; c<PAL_SIZE; c++) {
	   unsigned char r = ((c>>5)&7) * 255/7;
	   unsigned char g = ((c>>2)&7) * 255/7;
	   unsigned char b = (c&3) * 255/3;
	   pal[c] = MAKE_RGB(r,g,b);
   }
}


/*void updatewindowsize(int x, int y)
{
	x=(x+3)&~3; y=(y+3)&~3;
	if (x<128) x=128;
	if (y<64)  y=64;
	if (windx!=x || windy!=y) {
		windx=winsizex=x; windy=winsizey=y;
		set_palette(pal);
	}
}*/




int page_hits[256];
int inpage_hits[256];
int inpage = 0x32;

void chk_triggers(M6502* cpu, uint16_t pc) {
	page_hits[pc>>8]++;

	if (pc>>8 == inpage) {
		inpage_hits[pc&255]++;
	}
}

void dump_pages() {
	int i;
	LOGI("Page dump:");
	for (i=0 ; i<128 ; i++) {
		if (page_hits[i] > 0) {
			LOGI("Page 0x%04X hit %d times", i*256, page_hits[i]);
			page_hits[i] = 0;
		}
	}
	LOGI("In-page dump:");
	for (i=0 ; i<256 ; i++) {
		if (inpage_hits[i] > 0) {
			LOGI("0x%02X%02X hit %d times", inpage, i, inpage_hits[i]);
			inpage_hits[i] = 0;
		}
	}
}

void closebbc()
{
        closedisc(0);
        closedisc(1);

}

