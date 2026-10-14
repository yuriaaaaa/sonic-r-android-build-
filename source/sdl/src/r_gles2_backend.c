/**
 * r_gles2_backend.c — OpenGL ES 2.0 implementation of the immediate-mode
 * render API (Android).
 *
 * Same contract as r_gl_backend.c (r_state.h, r_draw.h, r_texture.h), but
 * ES 2.0 has no fixed-function pipeline, so:
 *   - triangle fans are expanded into a client-side triangle list and drawn
 *     in batches that share one render state;
 *   - texenv MODULATE / ADD_SIGNED and the alpha test run in the fragment
 *     shader;
 *   - render_gl.c's glOrtho + glTranslatef + glScalef arrive as one matrix
 *     through R_GLES2_SetProjection.
 *
 * Built instead of r_gl_backend.c when SONICR_GLES2 is defined.
 */
#if defined(SONICR_GLES2) && !defined(SONICR_SOFT_RENDER)

#define R_GLES2_BACKEND_IMPL
#include "r_gles2.h"

#include "r_types.h"
#include "r_state.h"
#include "r_draw.h"
#include "r_texture.h"
#include "sonicr_types.h"
#include "sonicr_globals.h"
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

#ifdef __ANDROID__
#include <android/log.h>
#define GLES2_LOG(...) __android_log_print(ANDROID_LOG_INFO, "SonicR", __VA_ARGS__)
#else
#define GLES2_LOG(...) fprintf(stderr, __VA_ARGS__)
#endif

extern void BeginFrame(void);
extern void EndFrame(void);
extern void FlipD3D(void);
extern void ProcessTpageStates(void);

extern void GL_UploadTpage(int tpage);
extern void GL_UploadTpageRGBA(int tpage, unsigned char *rgba, int w, int h);
extern void GL_UploadTpageSubRect(int tpage, int destX, int destY, int width, int height);
extern void GL_MarkTpageDirty(int tpage);
extern void GL_ClearTpageDirty(int tpage);
extern void GL_FreezeTpage(int tpage);
extern void GL_SetPendingRGBA(int tpage, unsigned char *rgba, int w, int h);
extern void GL_SetNoColorKey(int tpage);
extern void GL_ClearNoColorKey(int tpage);
extern void GL_SetTpageGreen6(int tpage, int on);
extern void GL_InitTextures(void);
extern void GL_SetTpageRGBA8(int tpage, unsigned char *rgba);

extern GLuint s_glTextures[];       /* render_gl.c */

/* =====================================================================
 * State
 * ===================================================================== */

typedef struct {
    int          textureId;    /* tpage index, or -1 for untextured */
    R_BlendMode  blendMode;
    int          depthTest;
    R_DepthFunc  depthFunc;
    int          depthWrite;
    R_TexEnvMode texEnv;
    R_FilterMode filter;
    R_CullMode   cullMode;
    int          alphaTest;
    float        alphaRef;
    int          scissorEnabled;
    int          scissorX, scissorY, scissorW, scissorH;
} R_StateSnapshot;

static R_StateSnapshot s_desired;
static R_StateSnapshot s_current;    /* state the queued vertices use */

#define R_STATE_STACK_DEPTH 4
static R_StateSnapshot s_stateStack[R_STATE_STACK_DEPTH];
static int s_stackDepth = 0;

static const R_StateSnapshot s_defaults = {
    .textureId      = -1,
    .blendMode      = R_BLEND_ALPHA,
    .depthTest      = 1,
    .depthFunc      = R_DEPTH_LEQUAL,
    .depthWrite     = 1,
    .texEnv         = R_TEXENV_MODULATE,
    .filter         = R_FILTER_NEAREST,
    .cullMode       = R_CULL_NONE,
    .alphaTest      = 1,
    .alphaRef       = 0.01f,
    .scissorEnabled = 0,
    .scissorX       = 0,
    .scissorY       = 0,
    .scissorW       = 640,
    .scissorH       = 480,
};

/* Per-tpage filter pin and the filter resident on each texture object;
 * 0 = unset, otherwise mode + 1 (see r_gl_backend.c). */
static unsigned char s_tpageFilter[52];
static unsigned char s_glTextureFilter[52];

/* GL texture name currently bound to unit 0, or 0 when unknown. */
static GLuint s_boundTex = 0;

/* =====================================================================
 * Shaders
 * ===================================================================== */

static const char *s_vsSrc =
    "attribute vec4 a_pos;\n"
    "attribute vec4 a_col;\n"
    "attribute vec2 a_uv;\n"
    "uniform mat4 u_mvp;\n"
    "varying vec4 v_col;\n"
    "varying vec2 v_uv;\n"
    "void main() {\n"
    "    gl_Position = u_mvp * a_pos;\n"
    "    v_col = a_col;\n"
    "    v_uv = a_uv;\n"
    "}\n";

static const char *s_fsSrc =
    "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
    "precision highp float;\n"
    "#else\n"
    "precision mediump float;\n"
    "#endif\n"
    "uniform sampler2D u_tex;\n"
    "uniform float u_textured;\n"
    "uniform float u_addSigned;\n"
    "uniform float u_alphaTest;\n"
    "uniform float u_alphaRef;\n"
    "varying vec4 v_col;\n"
    "varying vec2 v_uv;\n"
    "void main() {\n"
    "    vec4 c = v_col;\n"
    "    if (u_textured > 0.5) {\n"
    "        vec4 t = texture2D(u_tex, v_uv);\n"
    "        if (u_addSigned > 0.5)\n"
    "            c = vec4(clamp(t.rgb + c.rgb - 0.5, 0.0, 1.0), t.a * c.a);\n"
    "        else\n"
    "            c = t * c;\n"
    "    }\n"
    "    if (u_alphaTest > 0.5 && c.a <= u_alphaRef) discard;\n"
    "    gl_FragColor = c;\n"
    "}\n";

static GLuint s_program;
static GLint  s_uMvp, s_uTex, s_uTextured, s_uAddSigned, s_uAlphaTest, s_uAlphaRef;
static float  s_mvp[16];

static GLuint CompileShader(GLenum type, const char *src)
{
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, NULL);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(sh, sizeof(log), NULL, log);
        GLES2_LOG("shader compile failed: %s\n", log);
    }
    return sh;
}

void R_GLES2_InitShaders(void)
{
    if (s_program) {
        return;
    }
    GLuint vs = CompileShader(GL_VERTEX_SHADER, s_vsSrc);
    GLuint fs = CompileShader(GL_FRAGMENT_SHADER, s_fsSrc);
    s_program = glCreateProgram();
    glAttachShader(s_program, vs);
    glAttachShader(s_program, fs);
    glBindAttribLocation(s_program, 0, "a_pos");
    glBindAttribLocation(s_program, 1, "a_col");
    glBindAttribLocation(s_program, 2, "a_uv");
    glLinkProgram(s_program);
    GLint ok = 0;
    glGetProgramiv(s_program, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(s_program, sizeof(log), NULL, log);
        GLES2_LOG("program link failed: %s\n", log);
    }
    glDeleteShader(vs);
    glDeleteShader(fs);

    s_uMvp       = glGetUniformLocation(s_program, "u_mvp");
    s_uTex       = glGetUniformLocation(s_program, "u_tex");
    s_uTextured  = glGetUniformLocation(s_program, "u_textured");
    s_uAddSigned = glGetUniformLocation(s_program, "u_addSigned");
    s_uAlphaTest = glGetUniformLocation(s_program, "u_alphaTest");
    s_uAlphaRef  = glGetUniformLocation(s_program, "u_alphaRef");

    glUseProgram(s_program);
    glUniform1i(s_uTex, 0);
    glActiveTexture(GL_TEXTURE0);
    glDisable(GL_CULL_FACE);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glEnableVertexAttribArray(2);
}

/* glOrtho(l, r, b, t, 0, -1) * glTranslatef(tx, ty, 0) * glScalef(sx, sy, 1),
 * column-major. */
void R_GLES2_SetProjection(float l, float r, float b, float t,
                           float tx, float ty, float sx, float sy)
{
    R_GLES2_FlushVerts();

    float p[16] = {0};
    p[0]  = 2.0f / (r - l);
    p[5]  = 2.0f / (t - b);
    p[10] = 2.0f;                 /* -2 / (far - near), near = 0, far = -1 */
    p[12] = -(r + l) / (r - l);
    p[13] = -(t + b) / (t - b);
    p[14] = -1.0f;                /* -(far + near) / (far - near) */
    p[15] = 1.0f;

    float m[16] = {0};
    m[0]  = sx;
    m[5]  = sy;
    m[10] = 1.0f;
    m[12] = tx;
    m[13] = ty;
    m[15] = 1.0f;

    for (int c = 0; c < 4; c++) {
        for (int rr = 0; rr < 4; rr++) {
            float s = 0.0f;
            for (int k = 0; k < 4; k++) {
                s += p[k * 4 + rr] * m[c * 4 + k];
            }
            s_mvp[c * 4 + rr] = s;
        }
    }
}

/* =====================================================================
 * Vertex batch
 * ===================================================================== */

typedef struct {
    float         x, y, z, w;
    unsigned char r, g, b, a;
    float         u, v;
} GLES2Vertex;

#define BATCH_MAX_VERTS 24576     /* multiple of 3 */
static GLES2Vertex s_batch[BATCH_MAX_VERTS];
static int s_batchCount = 0;

static int EffectiveTexture(int tp)
{
    if (tp >= 0 && tp < 52 && g_tpagePixelBuf[tp] != NULL) {
        return tp;
    }
    return -1;
}

void R_GLES2_InvalidateBinding(void)
{
    s_boundTex = 0;
    /* Every upload path hard-sets GL_NEAREST on whatever it binds. */
    memset(s_glTextureFilter, 0, sizeof(s_glTextureFilter));
}

static void BindForBatch(int tp)
{
    /* --smooth can be switched while playing; re-apply every page's filter. */
    extern int g_smoothTextures;
    static int s_lastSmooth = -1;
    if (g_smoothTextures != s_lastSmooth) {
        s_lastSmooth = g_smoothTextures;
        memset(s_glTextureFilter, 0, sizeof(s_glTextureFilter));
    }
    GLuint name = s_glTextures[tp];
    if (s_boundTex != name) {
        glBindTexture(GL_TEXTURE_2D, name);
        s_boundTex = name;
    }
    unsigned char want = s_tpageFilter[tp];
    if (want == 0) {
        want = (unsigned char)(s_current.filter + 1);
    }
    if (s_glTextureFilter[tp] != want) {
        GLenum f = (g_smoothTextures || want == (unsigned char)(R_FILTER_LINEAR + 1))
                       ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, f);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, f);
        s_glTextureFilter[tp] = want;
    }
}

void R_GLES2_FlushVerts(void)
{
    if (s_batchCount == 0 || !s_program) {
        s_batchCount = 0;
        return;
    }

    glUseProgram(s_program);
    glUniformMatrix4fv(s_uMvp, 1, GL_FALSE, s_mvp);

    int tp = EffectiveTexture(s_current.textureId);
    if (tp >= 0) {
        BindForBatch(tp);
        glUniform1f(s_uTextured, 1.0f);
    }
    else {
        glUniform1f(s_uTextured, 0.0f);
    }
    glUniform1f(s_uAddSigned, s_current.texEnv == R_TEXENV_ADD_SIGNED ? 1.0f : 0.0f);
    glUniform1f(s_uAlphaTest, s_current.alphaTest ? 1.0f : 0.0f);
    glUniform1f(s_uAlphaRef, s_current.alphaRef);

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, sizeof(GLES2Vertex), &s_batch[0].x);
    glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, GL_TRUE, sizeof(GLES2Vertex), &s_batch[0].r);
    glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, sizeof(GLES2Vertex), &s_batch[0].u);
    glDrawArrays(GL_TRIANGLES, 0, s_batchCount);
    s_batchCount = 0;
}

/* =====================================================================
 * R_FlushState — make the desired state current
 *
 * GL-side state (depth, blend, scissor) is applied right away because
 * render_gl.c issues glClear straight after R_FlushState and relies on the
 * scissor and depth mask being in effect. Queued vertices that were built
 * under a different state are drawn first.
 * ===================================================================== */

static int StateDiffers(const R_StateSnapshot *a, const R_StateSnapshot *b)
{
    if (EffectiveTexture(a->textureId) != EffectiveTexture(b->textureId)) return 1;
    if (a->blendMode != b->blendMode) return 1;
    if (a->depthTest != b->depthTest) return 1;
    if (a->depthFunc != b->depthFunc) return 1;
    if (a->depthWrite != b->depthWrite) return 1;
    if (a->texEnv != b->texEnv) return 1;
    if (a->filter != b->filter) return 1;
    if (a->alphaTest != b->alphaTest) return 1;
    if (a->alphaRef != b->alphaRef) return 1;
    if (a->scissorEnabled != b->scissorEnabled) return 1;
    if (a->scissorEnabled &&
        (a->scissorX != b->scissorX || a->scissorY != b->scissorY ||
         a->scissorW != b->scissorW || a->scissorH != b->scissorH)) return 1;
    return 0;
}

void R_FlushState(void)
{
    int tp = s_desired.textureId;
    if (tp >= 0 && tp < 52 && g_tpagePixelBuf[tp] != NULL && s_glTextureDirty[tp]) {
        R_GLES2_FlushVerts();
        GL_UploadTpage(tp);
    }

    if (s_batchCount > 0 && StateDiffers(&s_desired, &s_current)) {
        R_GLES2_FlushVerts();
    }

    if (s_desired.depthTest != s_current.depthTest) {
        if (s_desired.depthTest) glEnable(GL_DEPTH_TEST);
        else                     glDisable(GL_DEPTH_TEST);
    }
    if (s_desired.depthFunc != s_current.depthFunc) {
        switch (s_desired.depthFunc) {
            case R_DEPTH_LEQUAL: glDepthFunc(GL_LEQUAL); break;
            case R_DEPTH_LESS:   glDepthFunc(GL_LESS);   break;
            case R_DEPTH_ALWAYS: glDepthFunc(GL_ALWAYS); break;
        }
    }
    if (s_desired.depthWrite != s_current.depthWrite) {
        glDepthMask(s_desired.depthWrite ? GL_TRUE : GL_FALSE);
    }
    if (s_desired.blendMode != s_current.blendMode) {
        switch (s_desired.blendMode) {
            case R_BLEND_NONE:
                glDisable(GL_BLEND);
                break;
            case R_BLEND_ALPHA:
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                break;
            case R_BLEND_ADDITIVE:
                glEnable(GL_BLEND);
                glBlendFunc(GL_ONE, GL_ONE);
                break;
        }
    }
    if (s_desired.scissorEnabled != s_current.scissorEnabled) {
        if (s_desired.scissorEnabled) glEnable(GL_SCISSOR_TEST);
        else                          glDisable(GL_SCISSOR_TEST);
    }
    if (s_desired.scissorEnabled &&
        (s_desired.scissorX != s_current.scissorX ||
         s_desired.scissorY != s_current.scissorY ||
         s_desired.scissorW != s_current.scissorW ||
         s_desired.scissorH != s_current.scissorH ||
         !s_current.scissorEnabled))
    {
        glScissor(s_desired.scissorX, s_desired.scissorY,
                  s_desired.scissorW, s_desired.scissorH);
    }

    s_current = s_desired;
}

/* =====================================================================
 * Render state API
 * ===================================================================== */

void R_SetTexture(int tpageIndex)          { s_desired.textureId = tpageIndex; }
void R_SetBlendMode(R_BlendMode mode)      { s_desired.blendMode = mode; }
void R_SetDepthTest(int enable)            { s_desired.depthTest = enable; }
void R_SetDepthFunc(R_DepthFunc func)      { s_desired.depthFunc = func; }
void R_SetDepthWrite(int enable)           { s_desired.depthWrite = enable; }
void R_SetTexEnv(R_TexEnvMode mode)        { s_desired.texEnv = mode; }
void R_SetFilter(R_FilterMode mode)        { s_desired.filter = mode; }
void R_SetCullMode(R_CullMode mode)        { s_desired.cullMode = mode; }
void R_SetAlphaTest(int enable)            { s_desired.alphaTest = enable; }
void R_SetAlphaRef(float ref)              { s_desired.alphaRef = ref; }

void R_SetTpageFilter(int tpage, R_FilterMode mode)
{
    if (tpage >= 0 && tpage < 52) {
        R_GLES2_FlushVerts();
        s_tpageFilter[tpage] = (unsigned char)(mode + 1);
    }
}

void R_ClearTpageFilter(int tpage)
{
    if (tpage >= 0 && tpage < 52) {
        R_GLES2_FlushVerts();
        s_tpageFilter[tpage] = 0;
    }
}

void R_SetTpageSatBoost(int tpage, int k256)
{
    (void)tpage;
    (void)k256;
}

void R_SetScissor(int x, int y, int w, int h)
{
    s_desired.scissorEnabled = 1;
    s_desired.scissorX = x;
    s_desired.scissorY = y;
    s_desired.scissorW = w;
    s_desired.scissorH = h;
}

void R_DisableScissor(void)
{
    s_desired.scissorEnabled = 0;
}

void R_PushState(void)
{
    if (s_stackDepth < R_STATE_STACK_DEPTH) {
        s_stateStack[s_stackDepth++] = s_desired;
    }
}

void R_PopState(void)
{
    if (s_stackDepth > 0) {
        s_desired = s_stateStack[--s_stackDepth];
    }
}

void R_DebugGetState(int *depthTest, int *depthWrite, int *depthFunc,
                     int *blendMode, int *alphaTest, float *alphaRef)
{
    if (depthTest)  *depthTest  = s_desired.depthTest;
    if (depthWrite) *depthWrite = s_desired.depthWrite;
    if (depthFunc)  *depthFunc  = (int)s_desired.depthFunc;
    if (blendMode)  *blendMode  = (int)s_desired.blendMode;
    if (alphaTest)  *alphaTest  = s_desired.alphaTest;
    if (alphaRef)   *alphaRef   = s_desired.alphaRef;
}

void R_ResetState(void)
{
    R_GLES2_FlushVerts();
    s_desired = s_defaults;
    /* Force every GL state to be re-applied on the next R_FlushState. */
    memset(&s_current, 0xFF, sizeof(s_current));
    s_current.textureId = -99;
    s_current.alphaRef = -1.0f;
    R_GLES2_InvalidateBinding();
    s_stackDepth = 0;
}

/* =====================================================================
 * Geometry submission
 * ===================================================================== */

static inline void PutVertex(GLES2Vertex *o, const RenderVertex *v)
{
    uint32_t argb = v->color;
    o->a = (unsigned char)(argb >> 24);
    o->r = (unsigned char)(argb >> 16);
    o->g = (unsigned char)(argb >> 8);
    o->b = (unsigned char)(argb);
    o->u = v->u;
    o->v = v->v;
    /* W = 1/RHW for perspective-correct interpolation, as glVertex4f did. */
    float w = (v->rhw > 0.0f) ? (1.0f / v->rhw) : 1.0f;
    o->x = v->sx * w;
    o->y = v->sy * w;
    o->z = v->sz * w;
    o->w = w;
}

void R_DrawTriFan(const RenderVertex *v, int count)
{
    if (count < 3) {
        return;
    }

    R_FlushState();

    int tris = count - 2;
    if (s_batchCount + tris * 3 > BATCH_MAX_VERTS) {
        R_GLES2_FlushVerts();
    }
    for (int i = 1; i <= tris; i++) {
        if (s_batchCount + 3 > BATCH_MAX_VERTS) {
            R_GLES2_FlushVerts();
        }
        PutVertex(&s_batch[s_batchCount++], &v[0]);
        PutVertex(&s_batch[s_batchCount++], &v[i]);
        PutVertex(&s_batch[s_batchCount++], &v[i + 1]);
    }
}

void R_DrawTri(const RenderVertex v[3])
{
    R_DrawTriFan(v, 3);
}

void R_DrawQuad(const RenderVertex v[4])
{
    R_DrawTriFan(v, 4);
}

void R_DrawQuad2D(float x0, float y0, float x1, float y1,
                  float u0, float v0, float u1, float v1,
                  float z, uint32_t color)
{
    float rhw = (z > 0.0f) ? (1.0f / z) : 1.0f;
    float farSafe = (g_farClipFloat > 0.0f) ? g_farClipFloat : 1.0f;
    float normZ = z / farSafe;

    RenderVertex v[4];
    v[0] = (RenderVertex){ x0, y0, normZ, rhw, color, 0, u0, v0 };
    v[1] = (RenderVertex){ x1, y0, normZ, rhw, color, 0, u1, v0 };
    v[2] = (RenderVertex){ x1, y1, normZ, rhw, color, 0, u1, v1 };
    v[3] = (RenderVertex){ x0, y1, normZ, rhw, color, 0, u0, v1 };
    R_DrawQuad(v);
}

void R_DrawQuad2DSolid(float x0, float y0, float x1, float y1,
                       float z, uint32_t color)
{
    int savedTex = s_desired.textureId;
    s_desired.textureId = -1;

    float rhw = (z > 0.0f) ? (1.0f / z) : 1.0f;
    float farSafe = (g_farClipFloat > 0.0f) ? g_farClipFloat : 1.0f;
    float normZ = z / farSafe;

    RenderVertex v[4];
    v[0] = (RenderVertex){ x0, y0, normZ, rhw, color, 0, 0.0f, 0.0f };
    v[1] = (RenderVertex){ x1, y0, normZ, rhw, color, 0, 0.0f, 0.0f };
    v[2] = (RenderVertex){ x1, y1, normZ, rhw, color, 0, 0.0f, 0.0f };
    v[3] = (RenderVertex){ x0, y1, normZ, rhw, color, 0, 0.0f, 0.0f };
    R_DrawQuad(v);

    s_desired.textureId = savedTex;
}

/* =====================================================================
 * Texture API (forward to render_gl.c; its GL calls flush the batch)
 * ===================================================================== */

void R_InitTextures(void)                  { GL_InitTextures(); }
void R_UploadTexture(int tpage)            { GL_UploadTpage(tpage); }

void R_MarkTextureDirty(int tpage)
{
    R_GLES2_FlushVerts();
    if (tpage >= 0 && tpage < 52) {
        s_glTextureFilter[tpage] = 0;
    }
    GL_MarkTpageDirty(tpage);
}

void R_ClearTextureDirty(int tpage)        { GL_ClearTpageDirty(tpage); }
void R_FreezeTexture(int tpage)            { GL_FreezeTpage(tpage); }
void R_ThawTexture(int tpage)              { (void)tpage; }
void R_SetNoColorKey(int tpage)            { GL_SetNoColorKey(tpage); }
void R_ClearNoColorKey(int tpage)          { GL_ClearNoColorKey(tpage); }
void R_SetTpageGreen6(int tpage, int on)   { GL_SetTpageGreen6(tpage, on); }
void R_SetTpageRGBA8(int tpage, unsigned char *rgba) { GL_SetTpageRGBA8(tpage, rgba); }

void R_UploadTextureRGBA(int tpage, unsigned char *rgba, int w, int h)
{
    GL_UploadTpageRGBA(tpage, rgba, w, h);
}

void R_UploadTextureSubRect(int tpage, int x, int y, int w, int h)
{
    GL_UploadTpageSubRect(tpage, x, y, w, h);
}

void R_SetPendingRGBA(int tpage, unsigned char *rgba, int w, int h)
{
    R_GLES2_FlushVerts();
    GL_SetPendingRGBA(tpage, rgba, w, h);
}

void R_EndFrame(void)
{
    EndFrame();
}

void R_Flip(void)
{
    R_GLES2_FlushVerts();
    FlipD3D();
}

void R_ClearAndReset(void)
{
    ProcessTpageStates();
}

void R_ClearDepth(void)
{
    R_FlushState();
    R_GLES2_FlushVerts();
    glClear(GL_DEPTH_BUFFER_BIT);
}

#endif /* SONICR_GLES2 && !SONICR_SOFT_RENDER */
