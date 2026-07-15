package de.lobianco.saftssh.rustdesk

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Draws a synthetic mouse-pointer arrow onto a Surface canvas at a given position — ported
 * byte-for-byte from remote-desktop-plugin's VncClient/RdpClient equivalent (see that project's
 * own copy for the full doc) so the user always sees where the pointer is: RustDesk's own peer
 * bakes no cursor into the framebuffer for us (we bypass its own cursor-shape channel entirely
 * since we never wired session_get_platform's cursor callbacks in this headless setup), and a
 * locally-drawn arrow at the last position we sent via sendMouse is always visible and always
 * tracks the finger — essential for the trackpad-style CURSOR input mode, where the tap position
 * is NOT where the finger physically is.
 */
object SyntheticCursor {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // Classic arrow outline with its tip at (0,0), in a roughly 12x19 unit box, scaled up for
    // visibility on high-DPI phone screens.
    private const val S = 2.4f

    /** Draws the arrow with its tip (hotspot) at surface pixel ([tipX], [tipY]). */
    fun draw(canvas: Canvas, tipX: Float, tipY: Float) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 16f * S)
            lineTo(4f * S, 12.5f * S)
            lineTo(7f * S, 19f * S)
            lineTo(9.5f * S, 18f * S)
            lineTo(6.5f * S, 11.5f * S)
            lineTo(11f * S, 11.5f * S)
            close()
            offset(tipX, tipY)
        }
        canvas.drawPath(path, stroke)
        canvas.drawPath(path, fill)
    }
}
