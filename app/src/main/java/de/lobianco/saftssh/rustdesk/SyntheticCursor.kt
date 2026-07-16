package de.lobianco.saftssh.rustdesk

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Draws a synthetic mouse-pointer arrow onto a Surface canvas at a given position — ported
 * byte-for-byte from remote-desktop-plugin's VncClient/RdpClient equivalent (see that project's
 * own copy for the full doc) so the user always sees where the pointer is: RustDesk's own peer
 * bakes no cursor into the framebuffer for us, and a locally-drawn arrow at the last position we
 * sent via sendMouse is always visible and always tracks the finger — essential for the
 * trackpad-style CURSOR input mode, where the tap position is NOT where the finger physically is.
 *
 * This is now only one of the options: the peer's REAL cursor bitmap can be shown instead (or as
 * well), which is the only way to see an I-beam over text or a resize arrow over a window edge —
 * see IRustDeskSession.setCursorOptions and RustDeskSessionService's host-cursor rendering. This
 * synthetic arrow stays the fallback whenever no host cursor has arrived (yet).
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

    // Classic arrow outline with its tip at (0,0), in a roughly 12x19 unit box. BASE is the
    // long-standing default scale-up for visibility on high-DPI phone screens; the caller's
    // sizeScale multiplies it, so sizeScale = 1.0 keeps exactly the size this always drew at.
    private const val BASE = 2.4f

    /** Draws the arrow with its tip (hotspot) at surface pixel ([tipX], [tipY]), at [sizeScale]
     *  times the default size (1.0 = default). */
    fun draw(canvas: Canvas, tipX: Float, tipY: Float, sizeScale: Float = 1f) {
        val s = BASE * sizeScale
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 16f * s)
            lineTo(4f * s, 12.5f * s)
            lineTo(7f * s, 19f * s)
            lineTo(9.5f * s, 18f * s)
            lineTo(6.5f * s, 11.5f * s)
            lineTo(11f * s, 11.5f * s)
            close()
            offset(tipX, tipY)
        }
        canvas.drawPath(path, stroke)
        canvas.drawPath(path, fill)
    }
}
