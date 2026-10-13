/**
 * r_gles2.h — OpenGL ES 2.0 backend glue (Android).
 *
 * r_gles2_backend.c batches geometry, so any raw GL call made outside the
 * backend (render_gl.c's texture uploads and clears) must first draw what is
 * queued. Files other than the backend get wrapper macros that do that.
 */
#ifndef R_GLES2_H
#define R_GLES2_H

#include <GLES2/gl2.h>

void R_GLES2_InitShaders(void);
void R_GLES2_FlushVerts(void);
void R_GLES2_InvalidateBinding(void);
void R_GLES2_SetProjection(float left, float right, float bottom, float top,
                           float tx, float ty, float sx, float sy);

#ifndef R_GLES2_BACKEND_IMPL
/* A function-like macro does not expand recursively, so (glX)(...) is the
 * real entry point. */
#define glClear(m)            (R_GLES2_FlushVerts(), (glClear)(m))
#define glViewport(x, y, w, h) (R_GLES2_FlushVerts(), (glViewport)(x, y, w, h))
#define glBindTexture(t, n)   (R_GLES2_FlushVerts(), R_GLES2_InvalidateBinding(), \
                               (glBindTexture)(t, n))
#define glTexImage2D(...)     (R_GLES2_FlushVerts(), (glTexImage2D)(__VA_ARGS__))
#define glTexSubImage2D(...)  (R_GLES2_FlushVerts(), (glTexSubImage2D)(__VA_ARGS__))
#define glTexParameteri(...)  (R_GLES2_FlushVerts(), (glTexParameteri)(__VA_ARGS__))
#endif

#endif /* R_GLES2_H */
