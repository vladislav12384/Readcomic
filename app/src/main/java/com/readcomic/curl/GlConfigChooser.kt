package com.readcomic.curl

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/** Prefers ES 3 with 4x MSAA for smooth edges and a 24-bit depth buffer, falls back gracefully. */
class GlConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val attempts = listOf(
            attributes(EGL_OPENGL_ES3_BIT, 4, 24),
            attributes(EGL_OPENGL_ES3_BIT, 4, 16),
            attributes(EGL_OPENGL_ES3_BIT, 0, 24),
            attributes(EGL_OPENGL_ES3_BIT, 0, 16),
            attributes(EGL_OPENGL_ES2_BIT, 4, 16),
            attributes(EGL_OPENGL_ES2_BIT, 0, 16),
        )
        for (attributes in attempts) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (egl.eglChooseConfig(display, attributes, configs, 1, count) && count[0] > 0) {
                configs[0]?.let { return it }
            }
        }
        throw IllegalStateException("No suitable EGL config")
    }

    private fun attributes(renderable: Int, samples: Int, depth: Int): IntArray {
        val list = mutableListOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, depth,
            EGL10.EGL_RENDERABLE_TYPE, renderable,
        )
        if (samples > 0) {
            list += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
        }
        list += EGL10.EGL_NONE
        return list.toIntArray()
    }

    private companion object {
        const val EGL_OPENGL_ES2_BIT = 0x0004
        const val EGL_OPENGL_ES3_BIT = 0x0040
    }
}
