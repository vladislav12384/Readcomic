package com.readcomic.curl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws the "table", the page lying on it and the page being turned. The turning page is a
 * dense grid deformed in the vertex shader; its shadow is rendered into a small offscreen
 * texture, blurred and multiplied onto everything that lies flat underneath.
 */
class CurlRenderer(
    private val assets: AssetManager,
    private val controller: PageTurnController,
    private val textures: PageTextureCache,
    private val density: Float,
    private val requestRender: () -> Unit,
) : GLSurfaceView.Renderer {

    private lateinit var pageProgram: GlProgram
    private lateinit var shadowProgram: GlProgram
    private lateinit var blurProgram: GlProgram
    private lateinit var backgroundProgram: GlProgram

    private var gridVao = 0
    private var emptyVao = 0
    private var gridIndexCount = 0

    private val shadowTextures = IntArray(2)
    private val shadowFramebuffers = IntArray(2)
    private var shadowWidth = 1
    private var shadowHeight = 1

    private var width = 1
    private var height = 1
    private var lastFrameNanos = 0L

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    /** Background colour of the "table" (linear 0..1 RGB). */
    @Volatile
    var backgroundColor = floatArrayOf(0.12f, 0.115f, 0.11f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        pageProgram = GlProgram.fromAssets(assets, "page.vert", "page.frag")
        shadowProgram = GlProgram.fromAssets(assets, "shadow.vert", "shadow.frag")
        blurProgram = GlProgram.fromAssets(assets, "fullscreen.vert", "blur.frag")
        backgroundProgram = GlProgram.fromAssets(assets, "fullscreen.vert", "background.frag")
        createGrid()
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        emptyVao = vaos[0]
        GLES30.glGenTextures(2, shadowTextures, 0)
        GLES30.glGenFramebuffers(2, shadowFramebuffers, 0)
        textures.onContextCreated()
        lastFrameNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = max(width, 1)
        this.height = max(height, 1)
        GLES30.glViewport(0, 0, this.width, this.height)
        controller.setViewSize(this.width, this.height)

        shadowWidth = max(this.width / SHADOW_DOWNSCALE, 1)
        shadowHeight = max(this.height / SHADOW_DOWNSCALE, 1)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTextures[i])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, shadowWidth, shadowHeight, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null,
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFramebuffers[i])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, shadowTextures[i], 0,
            )
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 1f / 20f)
        val animating = controller.step(dt)
        lastFrameNanos = if (animating) now else 0L

        val current = controller.currentIndex
        textures.update(
            controller.wantedPages(),
            if (controller.scale > 1.3f) current else null,
            controller.viewWidth,
            controller.viewHeight,
        )

        updateMatrices()

        val turning = controller.turningIndex
        val curl = if (turning != null) controller.currentCurl() else null
        val shadowOn = curl != null && turning != null
        if (curl != null && turning != null) renderShadow(controller.pageRect(turning), curl)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val under = controller.underIndex
        // The stack below the comic is the page that stays put while another one is turned.
        val stackIndex = under ?: turning ?: current
        drawBackground(controller.pageRect(stackIndex), shadowOn, stackSheets(stackIndex), under != null || turning != null)

        GLES30.glBindVertexArray(gridVao)
        pageProgram.use()
        setCommonPageUniforms()
        if (under != null) {
            drawPage(under, controller.pageRect(under), null, shadowOn)
        }
        if (turning != null) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            drawPage(turning, controller.pageRect(turning), curl, shadowOn)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        }
        GLES30.glBindVertexArray(0)

        if (animating) requestRender()
    }

    private fun stackSheets(index: Int): Int = max(controller.pageCount - 1 - index, 0)

    private fun updateMatrices() {
        val w = width.toFloat()
        val h = height.toFloat()
        val zRange = 4f * max(w, h)
        val eye = 2.2f * max(w, h)
        // Orthographic mapping of the z = 0 plane to view pixels, with a mild perspective
        // (w = 1 - z / eye) so that lifted parts of a sheet come towards the reader.
        projection.fill(0f)
        projection[0] = 2f / w
        projection[5] = -2f / h
        projection[10] = -1f / zRange
        projection[11] = -1f / eye
        projection[12] = -1f
        projection[13] = 1f
        projection[15] = 1f

        val s = controller.scale
        view.fill(0f)
        view[0] = s
        view[5] = s
        view[10] = s
        view[12] = controller.offsetX
        view[13] = controller.offsetY
        view[15] = 1f
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
    }

    private fun setCurlUniforms(program: GlProgram, rect: PageRect, curl: CurlGeometry?) {
        GLES30.glUniform4f(program["uPageRect"], rect.left, rect.top, rect.width, rect.height)
        if (curl != null) {
            GLES30.glUniform2f(program["uAxisPoint"], curl.axisX, curl.axisY)
            GLES30.glUniform2f(program["uAxisNormal"], curl.nx, curl.ny)
            GLES30.glUniform1f(program["uRadius"], curl.radius)
        } else {
            GLES30.glUniform2f(program["uAxisPoint"], 0f, 0f)
            GLES30.glUniform2f(program["uAxisNormal"], 1f, 0f)
            GLES30.glUniform1f(program["uRadius"], 0f)
        }
    }

    private fun renderShadow(rect: PageRect, curl: CurlGeometry) {
        GLES30.glViewport(0, 0, shadowWidth, shadowHeight)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFramebuffers[0])
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        shadowProgram.use()
        GLES30.glBindVertexArray(gridVao)
        GLES30.glUniformMatrix4fv(shadowProgram["uMVP"], 1, false, mvp, 0)
        GLES30.glUniform3f(shadowProgram["uLight"], LIGHT[0], LIGHT[1], LIGHT[2])
        GLES30.glUniform1f(shadowProgram["uStrength"], SHADOW_STRENGTH)
        setCurlUniforms(shadowProgram, rect, curl)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_MAX)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, gridIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)

        // Two separable blur passes give a soft penumbra that scales with the curl radius.
        blurProgram.use()
        GLES30.glBindVertexArray(emptyVao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(blurProgram["uTex"], 0)
        val spread = 0.5f * curl.radius * controller.scale / SHADOW_DOWNSCALE / 3.2f
        repeat(2) {
            blurPass(shadowFramebuffers[1], shadowTextures[0], spread / shadowWidth, 0f)
            blurPass(shadowFramebuffers[0], shadowTextures[1], 0f, spread / shadowHeight)
        }
    }

    private fun blurPass(target: Int, source: Int, stepX: Float, stepY: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source)
        GLES30.glUniform2f(blurProgram["uStep"], stepX, stepY)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun drawBackground(rect: PageRect, shadowOn: Boolean, sheets: Int, hasPage: Boolean) {
        backgroundProgram.use()
        GLES30.glBindVertexArray(emptyVao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTextures[0])
        GLES30.glUniform1i(backgroundProgram["uShadowTex"], 1)
        GLES30.glUniform1f(backgroundProgram["uShadowOn"], if (shadowOn) 1f else 0f)
        GLES30.glUniform2f(backgroundProgram["uViewport"], width.toFloat(), height.toFloat())
        GLES30.glUniform3f(backgroundProgram["uTransform"], controller.scale, controller.offsetX, controller.offsetY)
        GLES30.glUniform4f(backgroundProgram["uPageRect"], rect.left, rect.top, rect.width, rect.height)
        // A visible edge of the remaining sheets: thicker for longer comics, a few dp at most.
        val thickness = min(sqrt(sheets.toFloat()) * 0.9f, 4.5f) * density
        GLES30.glUniform2f(backgroundProgram["uStack"], thickness * 0.55f, thickness)
        GLES30.glUniform1f(backgroundProgram["uSheets"], max(1f, min(sheets.toFloat(), thickness / (1.5f * density))))
        GLES30.glUniform1f(backgroundProgram["uHasPage"], if (hasPage) 1f else 0f)
        val bg = backgroundColor
        GLES30.glUniform3f(backgroundProgram["uBgColor"], bg[0], bg[1], bg[2])
        GLES30.glUniform3f(backgroundProgram["uPaper"], PAPER[0], PAPER[1], PAPER[2])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun setCommonPageUniforms() {
        GLES30.glUniformMatrix4fv(pageProgram["uMVP"], 1, false, mvp, 0)
        GLES30.glUniform1i(pageProgram["uTex"], 0)
        GLES30.glUniform1i(pageProgram["uShadowTex"], 1)
        GLES30.glUniform2f(pageProgram["uViewport"], width.toFloat(), height.toFloat())
        GLES30.glUniform3f(pageProgram["uLight"], LIGHT[0], LIGHT[1], LIGHT[2])
        GLES30.glUniform3f(pageProgram["uPaper"], PAPER[0], PAPER[1], PAPER[2])
        GLES30.glUniform1f(pageProgram["uGloss"], 0.22f)
        GLES30.glUniform1f(pageProgram["uGutter"], 0.10f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTextures[0])
    }

    private fun drawPage(index: Int, rect: PageRect, curl: CurlGeometry?, shadowOn: Boolean) {
        val texture = textures[index]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture?.id ?: 0)
        GLES30.glUniform1f(pageProgram["uHasTex"], if (texture != null) 1f else 0f)
        GLES30.glUniform1f(pageProgram["uShadowOn"], if (shadowOn) 1f else 0f)
        GLES30.glUniform1f(pageProgram["uDepth"], if (curl == null) -1f else 0f)
        setCurlUniforms(pageProgram, rect, curl)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, gridIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    private fun createGrid() {
        val vertices = ByteBuffer.allocateDirect((GRID_X + 1) * (GRID_Y + 1) * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (j in 0..GRID_Y) {
            for (i in 0..GRID_X) {
                vertices.put(i.toFloat() / GRID_X)
                vertices.put(j.toFloat() / GRID_Y)
            }
        }
        vertices.position(0)

        gridIndexCount = GRID_X * GRID_Y * 6
        val indices = ByteBuffer.allocateDirect(gridIndexCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (j in 0 until GRID_Y) {
            for (i in 0 until GRID_X) {
                val a = j * (GRID_X + 1) + i
                val b = a + 1
                val c = a + GRID_X + 1
                val d = c + 1
                indices.put(a.toShort()).put(c.toShort()).put(b.toShort())
                indices.put(b.toShort()).put(c.toShort()).put(d.toShort())
            }
        }
        indices.position(0)

        val vao = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        gridVao = vao[0]
        GLES30.glBindVertexArray(gridVao)
        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.capacity() * 4, vertices, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, gridIndexCount * 2, indices, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
    }

    private companion object {
        const val GRID_X = 64
        const val GRID_Y = 96
        const val SHADOW_DOWNSCALE = 4
        const val SHADOW_STRENGTH = 0.72f

        /** Direction towards the light: from the top left, mostly from the front. */
        val LIGHT = floatArrayOf(-0.30f, -0.45f, 0.84f).let { v ->
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }
        val PAPER = floatArrayOf(0.95f, 0.93f, 0.88f)
    }
}
