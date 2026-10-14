package com.readcomic.curl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Process
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
    private val poseMatrix = FloatArray(16)
    private val posed = FloatArray(16)

    /** View pixels back to layout coordinates: for the edges of the stacks, and for the comic's shadow. */
    private val bookInverse = FloatArray(9)
    private val shadowInverse = FloatArray(9)
    private var shadowSoft = 1f
    private var shadowAlpha = 1f
    private var stackAlpha = 1f
    private var hidden = false

    /** Background colour of the "table" (linear 0..1 RGB). */
    @Volatile
    var backgroundColor = floatArrayOf(0.12f, 0.115f, 0.11f)

    /**
     * The display refresh this frame is drawn for (System.nanoTime clock); 0 when unknown.
     * Animations advance by whole refreshes, so motion stays even when frames start a little early or late.
     */
    var frameClock: () -> Long = { 0L }

    /** Called at the start of every frame with its time, to apply the fingers' current positions. */
    var beforeFrame: ((frameNanos: Long) -> Unit)? = null

    /** Whether a finger is on the page: frames keep coming even if nothing animates by itself. */
    @Volatile
    var touching = false

    /** Set at the end of every frame: whether the next one is needed. */
    @Volatile
    var wantsFrames = false
        private set

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Drawing comes before decoding pages in the background: frames keep their pace.
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
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
        val now = frameClock().takeIf { it > 0L } ?: System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 1f / 20f)
        beforeFrame?.invoke(now)
        controller.coverReady = textures[0] != null
        var animating = controller.step(dt)

        // A few milliseconds per frame for GPU uploads: pages appear without stalling a turn or a pinch.
        val busy = animating || touching
        if (textures.uploadPending(budgetMillis = if (busy) 3L else 10L)) animating = true
        textures.update(
            sharp = controller.wantedImages(),
            previews = controller.previewImages(),
            zoomed = if (controller.scale > 1.3f) controller.visibleImageSet() else emptySet(),
            box = controller::displayBox,
        )
        lastFrameNanos = if (animating || touching) now else 0L
        wantsFrames = animating

        updateMatrices()
        val scene = controller.scene()
        val turning = scene.turning
        val shadowOn = turning?.curl != null
        if (turning != null && turning.curl != null) renderShadow(turning, turning.curl)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        drawBackground(scene, shadowOn)

        GLES30.glBindVertexArray(gridVao)
        pageProgram.use()
        setCommonPageUniforms(scene)
        if (!hidden) for (sheet in scene.flats) drawSheet(sheet, shadowOn, depth = -1f)
        if (turning != null) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            drawSheet(turning, shadowOn, depth = 0f)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        }
        GLES30.glBindVertexArray(0)

        if (animating) requestRender()
    }

    private fun updateMatrices() {
        val w = width.toFloat()
        val h = height.toFloat()
        val zRange = 4f * max(w, h)
        val eye = EYE_DISTANCE * max(w, h)
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
        val ox = controller.offsetX
        val oy = controller.offsetY
        view.fill(0f)
        view[0] = s
        view[5] = s
        view[10] = s
        view[12] = ox
        view[13] = oy
        view[15] = 1f

        val pose = controller.pose()
        if (pose == null) {
            Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
            inverseAffine(bookInverse, s, ox, oy, 0f, 0f, 0f, 0f, 0f, 1f)
            shadowInverse.indices.forEach { shadowInverse[it] = bookInverse[it] }
            shadowSoft = 1f
            shadowAlpha = 1f
            stackAlpha = 1f
            hidden = false
            return
        }

        // The closed comic above the table: turned and tilted around its middle, lifted towards
        // the eye (the perspective makes it larger), moved over as it opens.
        Matrix.setIdentityM(poseMatrix, 0)
        Matrix.translateM(poseMatrix, 0, pose.pivotX + pose.shiftX, pose.pivotY + pose.shiftY, 0f)
        Matrix.rotateM(poseMatrix, 0, pose.angle, 0f, 0f, 1f)
        Matrix.rotateM(poseMatrix, 0, pose.tilt, 1f, 0f, 0f)
        Matrix.translateM(poseMatrix, 0, 0f, 0f, pose.lift * LIFT * eye)
        Matrix.scaleM(poseMatrix, 0, pose.scale, pose.scale, 1f)
        Matrix.translateM(poseMatrix, 0, -pose.pivotX, -pose.pivotY, 0f)
        Matrix.multiplyMM(posed, 0, view, 0, poseMatrix, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, posed, 0)

        // On the table its shadow falls away from the light the higher it is, larger and softer.
        val a = Math.toRadians(pose.angle.toDouble()).toFloat()
        inverseAffine(bookInverse, s, ox, oy, a, pose.pivotX, pose.pivotY, pose.shiftX, pose.shiftY, pose.scale)
        val reach = pose.lift * min(w, h)
        inverseAffine(
            shadowInverse, s, ox, oy, a, pose.pivotX, pose.pivotY,
            pose.shiftX + reach * 0.07f, pose.shiftY + reach * 0.11f,
            pose.scale * (1f + 0.06f * pose.lift),
        )
        shadowSoft = 1f + 7f * pose.lift
        shadowAlpha = if (pose.hidden) 0f else 1f - 0.5f * pose.lift
        stackAlpha = if (pose.hidden) 0f else pose.stacks
        hidden = pose.hidden
    }

    /**
     * Column-major 3x3 matrix taking view pixels back to layout coordinates, for a layout drawn
     * zoomed by [s] and moved by ([ox], [oy]), with the comic turned by [angle] (radians) around
     * ([px], [py]), moved by ([dx], [dy]) and scaled by [k].
     */
    private fun inverseAffine(
        out: FloatArray, s: Float, ox: Float, oy: Float,
        angle: Float, px: Float, py: Float, dx: Float, dy: Float, k: Float,
    ) {
        val c = kotlin.math.cos(angle) / (k * s)
        val n = kotlin.math.sin(angle) / (k * s)
        // p = R(-angle) (frag - o - s (pivot + d)) / (k s) + pivot
        val tx = -ox - s * (px + dx)
        val ty = -oy - s * (py + dy)
        out[0] = c
        out[1] = -n
        out[2] = 0f
        out[3] = n
        out[4] = c
        out[5] = 0f
        out[6] = c * tx + n * ty + px
        out[7] = -n * tx + c * ty + py
        out[8] = 1f
    }

    private fun setSheetUniforms(program: GlProgram, sheet: Sheet) {
        val rect = sheet.rect
        GLES30.glUniform4f(program["uPageRect"], rect.left, rect.top, rect.width, rect.height)
        GLES30.glUniform1f(program["uMirror"], if (sheet.mirror) 1f else 0f)
        val curl = sheet.curl
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

    private fun renderShadow(sheet: Sheet, curl: CurlGeometry) {
        GLES30.glViewport(0, 0, shadowWidth, shadowHeight)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFramebuffers[0])
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        shadowProgram.use()
        GLES30.glBindVertexArray(gridVao)
        GLES30.glUniformMatrix4fv(shadowProgram["uMVP"], 1, false, mvp, 0)
        GLES30.glUniform3f(shadowProgram["uLight"], LIGHT[0], LIGHT[1], LIGHT[2])
        GLES30.glUniform1f(shadowProgram["uStrength"], SHADOW_STRENGTH)
        GLES30.glUniform1f(shadowProgram["uShadowScale"], sheet.rect.width * 0.3f)
        setSheetUniforms(shadowProgram, sheet)
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
        val radius = max(curl.radius, sheet.rect.width * 0.04f)
        val spread = 0.5f * radius * controller.scale / SHADOW_DOWNSCALE / 3.2f
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

    private fun drawBackground(scene: Scene, shadowOn: Boolean) {
        val p = backgroundProgram
        p.use()
        GLES30.glBindVertexArray(emptyVao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTextures[0])
        GLES30.glUniform1i(p["uShadowTex"], 1)
        GLES30.glUniform1f(p["uShadowOn"], if (shadowOn) 1f else 0f)
        GLES30.glUniform2f(p["uViewport"], width.toFloat(), height.toFloat())
        GLES30.glUniformMatrix3fv(p["uBookInverse"], 1, false, bookInverse, 0)
        GLES30.glUniformMatrix3fv(p["uShadowInverse"], 1, false, shadowInverse, 0)
        GLES30.glUniform1f(p["uShadowSoft"], shadowSoft)
        GLES30.glUniform1f(p["uShadowAlpha"], shadowAlpha)
        GLES30.glUniform1f(p["uStackAlpha"], stackAlpha)
        val drops = scene.drops.take(2)
        val dropRects = FloatArray(8)
        val dropAlphas = FloatArray(2)
        drops.forEachIndexed { i, d ->
            dropRects[i * 4] = d.rect.left
            dropRects[i * 4 + 1] = d.rect.top
            dropRects[i * 4 + 2] = d.rect.width
            dropRects[i * 4 + 3] = d.rect.height
            dropAlphas[i] = d.alpha
        }
        GLES30.glUniform4fv(p["uDropRect[0]"], 2, dropRects, 0)
        GLES30.glUniform1fv(p["uDropAlpha[0]"], 2, dropAlphas, 0)
        GLES30.glUniform1i(p["uDropCount"], drops.size)

        val stacks = scene.stacks.take(2)
        val rects = FloatArray(8)
        val shifts = FloatArray(4)
        val lines = FloatArray(2)
        stacks.forEachIndexed { i, s ->
            rects[i * 4] = s.rect.left
            rects[i * 4 + 1] = s.rect.top
            rects[i * 4 + 2] = s.rect.width
            rects[i * 4 + 3] = s.rect.height
            shifts[i * 2] = s.dx
            shifts[i * 2 + 1] = s.dy
            lines[i] = s.lines
        }
        GLES30.glUniform4fv(p["uStackRect[0]"], 2, rects, 0)
        GLES30.glUniform2fv(p["uStackShift[0]"], 2, shifts, 0)
        GLES30.glUniform1fv(p["uStackLines[0]"], 2, lines, 0)
        GLES30.glUniform1i(p["uStackCount"], stacks.size)

        val bg = backgroundColor
        GLES30.glUniform3f(p["uBgColor"], bg[0], bg[1], bg[2])
        GLES30.glUniform3f(p["uPaper"], PAPER[0], PAPER[1], PAPER[2])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun setCommonPageUniforms(scene: Scene) {
        val p = pageProgram
        GLES30.glUniformMatrix4fv(p["uMVP"], 1, false, mvp, 0)
        GLES30.glUniform1i(p["uFrontTex"], 0)
        GLES30.glUniform1i(p["uShadowTex"], 1)
        GLES30.glUniform1i(p["uBackTex"], 2)
        GLES30.glUniform2f(p["uViewport"], width.toFloat(), height.toFloat())
        GLES30.glUniform3f(p["uLight"], LIGHT[0], LIGHT[1], LIGHT[2])
        GLES30.glUniform3f(p["uPaper"], PAPER[0], PAPER[1], PAPER[2])
        GLES30.glUniform1f(p["uGloss"], 0.22f)
        GLES30.glUniform1f(p["uGutter"], scene.gutter)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTextures[0])
    }

    /** Binds the texture of a face to [unit] and sets its uniforms ("uFront..." / "uBack..."). */
    private fun setFace(prefix: String, face: Face?, unit: Int) {
        val p = pageProgram
        val texture = face?.takeIf { !it.isBlank }?.let { textures[it.image] }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture?.id ?: 0)
        GLES30.glUniform1f(p["${prefix}HasTex"], if (texture != null) 1f else 0f)
        val rect = face?.rect ?: Face.FULL
        val sub = face?.sub ?: Face.FULL
        GLES30.glUniform4f(p["${prefix}Rect"], rect[0], rect[1], rect[2], rect[3])
        GLES30.glUniform4f(p["${prefix}Sub"], sub[0], sub[1], sub[2], sub[3])
        GLES30.glUniform1f(p["${prefix}Flip"], if (face?.flip == true) 1f else 0f)
        GLES30.glUniform1f(p["${prefix}Glossy"], if (face?.glossy == true && texture != null) 1f else 0f)
    }

    private fun drawSheet(sheet: Sheet, shadowOn: Boolean, depth: Float) {
        val p = pageProgram
        setFace("uFront", sheet.front, 0)
        setFace("uBack", sheet.back, 2)
        GLES30.glUniform1f(p["uBackPrinted"], if (sheet.back != null) 1f else 0f)
        GLES30.glUniform1f(p["uShadowOn"], if (shadowOn) 1f else 0f)
        GLES30.glUniform1f(p["uDepth"], depth)
        // Reflections are seen from above the middle of the sheet (where it lies when flat), so a
        // glossy cover shows its sheen at the same place in any layout and it slides as the sheet bends.
        val r = sheet.rect
        val centerX = r.left + r.width / 2f
        GLES30.glUniform3f(
            p["uEye"],
            if (sheet.mirror) 2f * r.left - centerX else centerX,
            r.top + r.height / 2f,
            EYE_DISTANCE * r.height,
        )
        setSheetUniforms(p, sheet)
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

        /** Distance from the eye to the screen, in screen sizes: sets the perspective and reflections. */
        const val EYE_DISTANCE = 2.2f

        /** How high a comic in the hand is above the table, as a part of the distance to the eye. */
        const val LIFT = 0.16f

        /** Direction towards the light: from the top left, mostly from the front. */
        val LIGHT = floatArrayOf(-0.30f, -0.45f, 0.84f).let { v ->
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }
        val PAPER = floatArrayOf(0.95f, 0.93f, 0.88f)
    }
}
