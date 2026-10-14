package com.readcomic.shelf

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Small 3D math for the shelf (pure Kotlin, so it also runs in unit tests). Y is up. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized() = this * (1f / length())

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)

        fun lerp(a: Vec3, b: Vec3, t: Float) = a + (b - a) * t
    }
}

data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(q: Quat) = Quat(
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
        w * q.w - x * q.x - y * q.y - z * q.z,
    )

    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = u.cross(v) * 2f
        return v + t * w + u.cross(t)
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        fun axisAngle(axis: Vec3, angle: Float): Quat {
            val a = axis.normalized()
            val s = sin(angle / 2f)
            return Quat(a.x * s, a.y * s, a.z * s, cos(angle / 2f))
        }

        fun slerp(a: Quat, b0: Quat, t: Float): Quat {
            var b = b0
            var d = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
            if (d < 0f) {
                b = Quat(-b.x, -b.y, -b.z, -b.w)
                d = -d
            }
            if (d > 0.9995f) {
                val q = Quat(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t)
                val l = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
                return Quat(q.x / l, q.y / l, q.z / l, q.w / l)
            }
            val theta = acos(d)
            val sa = sin((1f - t) * theta) / sin(theta)
            val sb = sin(t * theta) / sin(theta)
            return Quat(a.x * sa + b.x * sb, a.y * sa + b.y * sb, a.z * sa + b.z * sb, a.w * sa + b.w * sb)
        }
    }
}

/** Column-major 4x4 matrices in FloatArrays, laid out like OpenGL expects them. */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var s = 0f
                for (k in 0..3) s += a[k * 4 + row] * b[col * 4 + k]
                r[col * 4 + row] = s
            }
        }
        return r
    }

    fun perspective(fovY: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(fovY / 2f)
        return FloatArray(16).also {
            it[0] = f / aspect
            it[5] = f
            it[10] = (far + near) / (near - far)
            it[11] = -1f
            it[14] = 2f * far * near / (near - far)
        }
    }

    fun orthographic(left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float): FloatArray =
        FloatArray(16).also {
            it[0] = 2f / (right - left)
            it[5] = 2f / (top - bottom)
            it[10] = -2f / (far - near)
            it[12] = -(right + left) / (right - left)
            it[13] = -(top + bottom) / (top - bottom)
            it[14] = -(far + near) / (far - near)
            it[15] = 1f
        }

    fun lookAt(eye: Vec3, target: Vec3, up: Vec3): FloatArray {
        val f = (target - eye).normalized()
        val s = f.cross(up).normalized()
        val u = s.cross(f)
        return floatArrayOf(
            s.x, u.x, -f.x, 0f,
            s.y, u.y, -f.y, 0f,
            s.z, u.z, -f.z, 0f,
            -s.dot(eye), -u.dot(eye), f.dot(eye), 1f,
        )
    }

    /** Translation * rotation * scale. */
    fun compose(position: Vec3, rotation: Quat, scale: Vec3): FloatArray {
        val (x, y, z, w) = rotation
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        return floatArrayOf(
            (1f - 2f * (yy + zz)) * scale.x, (2f * (xy + wz)) * scale.x, (2f * (xz - wy)) * scale.x, 0f,
            (2f * (xy - wz)) * scale.y, (1f - 2f * (xx + zz)) * scale.y, (2f * (yz + wx)) * scale.y, 0f,
            (2f * (xz + wy)) * scale.z, (2f * (yz - wx)) * scale.z, (1f - 2f * (xx + yy)) * scale.z, 0f,
            position.x, position.y, position.z, 1f,
        )
    }

    fun transformPoint(m: FloatArray, p: Vec3): Vec3 {
        val x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
        val y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
        val z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
        val w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
        return Vec3(x / w, y / w, z / w)
    }

    fun invert(m: FloatArray): FloatArray? {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (det == 0f) return null
        for (i in 0..15) inv[i] /= det
        return inv
    }
}
