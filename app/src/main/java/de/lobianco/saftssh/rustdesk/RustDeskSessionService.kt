package de.lobianco.saftssh.rustdesk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "RustDeskSessionService"
private const val NOTIF_CHANNEL_ID = "rustdesk_session"
private const val NOTIF_ID = 3001
// blitToSurface() rate-limit when the Surface can't be locked (e.g. display off) — see that
// function's doc for why this can't be detected proactively.
private const val BLIT_RETRY_INTERVAL_MS = 2000L

/** IMPORTANT: this check must live in the AIDL Stub's method bodies, NOT in Service.onBind() —
 *  onBind() is a local lifecycle callback dispatched by this process's own ActivityThread, not a
 *  live incoming Binder transaction, so Binder.getCallingUid() there just returns THIS process's
 *  own uid. AIDL Stub methods, in contrast, genuinely execute inside the calling transaction,
 *  where getCallingUid() is meaningful. Same pattern as the Linux Plugin / Remote Desktop Plugin. */
private val ALLOWED_CALLER_PACKAGES = setOf("de.lobianco.saftssh")

/**
 * Bound AIDL service wrapping [NativeBridge] (the JNI entry point into the vendored RustDesk core,
 * see rust/android-shim). Milestone 6 (plans/soft-frolicking-thimble.md): full video + input,
 * mirroring RemoteDesktopSessionService's (VNC/RDP/Proxmox VE plugin) session-lifecycle shape.
 */
class RustDeskSessionService : Service() {

    // A plain bound service has no priority protection: once BOTH the main app and this plugin are
    // backgrounded (or the screen turns off, which backgrounds the main app's Activity the same
    // way), the OS kills this process under memory pressure — confirmed as the cause of a reported
    // "connection drops when backgrounded/screen off", since nothing kept the process's priority up
    // while the native client socket/video-pump thread it owns needed to keep running. Same pattern
    // as RemoteDesktopSessionService/LinuxSessionService's promoteToForeground — the manifest's
    // foregroundServiceType="specialUse" + FOREGROUND_SERVICE(_SPECIAL_USE) permissions were already
    // in place for this, just never wired up on the Kotlin side.
    private val openSessions = CopyOnWriteArrayList<RustDeskSessionImpl>()

    override fun onCreate() {
        super.onCreate()
        NativeBridge.initialize(filesDir.absolutePath)
    }

    override fun onBind(intent: Intent?): IBinder = serviceStub

    override fun onDestroy() {
        openSessions.forEach { runCatching { it.destroyInternal() } }
        openSessions.clear()
        super.onDestroy()
    }

    private fun ensureNotifChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL_ID, "RustDesk session", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Keeps the RustDesk session running in the background" }
            )
        }
    }

    private fun promoteToForeground() {
        ensureNotifChannel()
        val notification = Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("RustDesk session running")
            .setContentText("Tap to return to LobiShell")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setOngoing(true)
            .apply {
                packageManager.getLaunchIntentForPackage("de.lobianco.saftssh")?.let { launch ->
                    setContentIntent(
                        PendingIntent.getActivity(
                            this@RustDeskSessionService, 0, launch,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
            }
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun demoteFromForegroundIfIdle() {
        if (openSessions.isEmpty()) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** True if the app on the other end of the CURRENT incoming Binder transaction is an
     *  authorized caller. Must only be called from inside an AIDL Stub method body. */
    private fun isCallerAuthorized(): Boolean {
        val callingUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callingUid) ?: arrayOf()
        val authorized = callerPackages.any { it in ALLOWED_CALLER_PACKAGES }
        if (!authorized) {
            Log.w(TAG, "Rejected call from unauthorized caller uid=$callingUid packages=${callerPackages.joinToString()}")
        }
        return authorized
    }

    private val serviceStub = object : IRustDeskSessionService.Stub() {
        override fun createSession(
            id: String,
            password: String,
            idServer: String,
            relayServer: String,
            apiServer: String,
            key: String,
            surface: Surface?,
            callback: IRustDeskSessionCallback?
        ): IRustDeskSession? {
            if (!isCallerAuthorized()) return null
            NativeBridge.setServerConfig(idServer, relayServer, apiServer, key)
            val result = NativeBridge.connect(id, password)
            if (result.startsWith("ERR:")) {
                val reason = result.removePrefix("ERR:")
                Log.e(TAG, "connect($id) failed: $reason")
                runCatching { callback?.onDisconnected(reason) }
                return null
            }
            val session = RustDeskSessionImpl(result, surface, callback)
            openSessions.add(session)
            promoteToForeground()
            return session
        }

        override fun checkOnline(
            id: String,
            idServer: String,
            relayServer: String,
            apiServer: String,
            key: String,
        ): Int {
            if (!isCallerAuthorized()) return -1
            NativeBridge.setServerConfig(idServer, relayServer, apiServer, key)
            return NativeBridge.checkOnline(id)
        }
    }

    private inner class RustDeskSessionImpl(
        private val sessionId: String,
        @Volatile private var surface: Surface?,
        private val callback: IRustDeskSessionCallback?
    ) : IRustDeskSession.Stub() {

        @Volatile private var running = true
        private var announcedConnected = false
        private var loggedFirstBlit = false
        private var blitFailLogCount = 0

        // Pinch-zoom transform (see setZoom / IRustDeskSession.setZoom). scale is relative to the
        // base letterbox fit; panX/panY are Surface-local pixel offsets. RustDeskScreen owns these
        // and mirrors the same transform in its touch inverse-map.
        @Volatile private var zoomScale = 1f
        @Volatile private var panX = 0f
        @Volatile private var panY = 0f
        // Last decoded frame, kept so setZoom() can redraw immediately without waiting for the next
        // server frame (a pinch while the remote screen is static would otherwise not reflect).
        @Volatile private var currentBitmap: Bitmap? = null

        // Last position sent via sendMouse, in REMOTE framebuffer pixels — drawn as a synthetic
        // cursor in blitToSurface (see SyntheticCursor's doc for why: RustDesk's own peer bakes no
        // cursor into the framebuffer for us, and we never wired its separate cursor-shape channel
        // in this headless setup, so without this the pointer was completely invisible, both
        // "remote" and "local" — confirmed reported on-device). -1 = not yet known (no cursor drawn
        // until the first real position arrives).
        @Volatile private var pointerFbX = -1
        @Volatile private var pointerFbY = -1
        // Throttles cursor-move AND zoom/pan-change redraws to a shared ~60fps budget — same
        // reasoning as VncClient's lastCursorBlitMs: touch delivers move events much faster than
        // that, and every blitToSurface call takes the same Surface lock the video-pump thread
        // uses for real frames, so redrawing on every single move event could starve real frame
        // updates. Originally only sendMouse used this; setZoom redrew UNTHROTTLED on every call,
        // which is exactly what edge-panning (RustDeskScreen's handleMove) triggers on every touch
        // move sample while zoomed — sendMouse's throttled redraw AND setZoom's unthrottled one
        // firing back-to-back on every move event was the reported "cursor lags, especially when
        // panning at the edge": far more than 60 blits/sec, contending with the video-pump
        // thread's own redraws for the same Surface lock. Sharing one timestamp between both call
        // sites caps their COMBINED rate instead of each getting its own independent 60fps budget.
        @Volatile private var lastExtraBlitMs = 0L
        // Same fix as VncClient's identical problem (task #43): turning the DISPLAY off (not
        // backgrounding the app) does NOT invalidate/reallocate the SurfaceView's Surface — so
        // `surface.isValid` stays true and lockCanvas() itself is what starts failing natively
        // ("dequeueBuffer failed (No such device)"), hundreds of times per second since nothing
        // here was rate-limiting retries. That tight failure loop was pure wasted CPU/battery
        // (and plausibly starved the video-pump thread enough to contribute to the connection
        // getting aborted, as seen on-device). Once a lockCanvas failure is observed, back off to
        // one retry attempt every BLIT_RETRY_INTERVAL_MS instead of one per incoming frame;
        // resets automatically once a real attempt succeeds again (screen back on).
        @Volatile private var blitFailing = false
        @Volatile private var lastBlitAttemptMs = 0L
        // Multi-monitor: which display index pumpVideo currently polls/renders. switchDisplay()
        // bumps this AND clears currentBitmap + announcedConnected so pumpVideo re-runs its
        // "discover size, then announce onConnected" sequence exactly like a fresh connect —
        // the new display can have a different resolution, and RustDeskViewModel needs the new
        // width/height for its own touch inverse-map.
        @Volatile private var currentDisplay = 0
        // blitToSurface() is called from three different threads (the video-pump thread, plus
        // Binder threadpool threads for sendMouse's/setZoom's own redraw) — Surface.lockCanvas()
        // isn't designed for concurrent calls from multiple threads at once, and letting them race
        // was plausibly adding to the reported cursor lag on top of the throttling fix above.
        private val renderLock = Any()
        private val videoThread = Thread({ pumpVideo() }, "RustDesk-video-$sessionId").apply {
            isDaemon = true
            start()
        }

        /** Same poll-and-blit loop as InfoActivity's test-harness startVideo/blitToSurface, moved
         *  here so a real caller (the main app, via updateSurface) gets the same video path the
         *  test panel already validated on-device. NOTE: there is no push-based "peer disconnected"
         *  signal available (see session_start_headless's doc) — onDisconnected is only fired from
         *  createSession's own connect-failure path and destroy() is not itself announced back, so
         *  a mid-session drop (peer closes, network dies) is currently only observable via isAlive()
         *  going stale, not a callback. Revisit once/if a sync connection-state getter is added. */
        private fun pumpVideo() {
            try {
                while (running) {
                    // Captured once per iteration (not re-read below) so a switchDisplay() call
                    // mid-iteration can't mismatch the size this bitmap was allocated for against
                    // the display getFrame reads from.
                    val display = currentDisplay
                    val bmp = currentBitmap ?: run {
                        val size = NativeBridge.getDisplaySize(sessionId, display)
                        if (size == null || size.size != 2 || size[0] <= 0 || size[1] <= 0) {
                            Thread.sleep(200)
                            return@run null
                        }
                        val fresh = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
                        currentBitmap = fresh
                        if (!announcedConnected) {
                            announcedConnected = true
                            runCatching { callback?.onConnected(size[0], size[1]) }
                        }
                        fresh
                    } ?: continue
                    val frame = NativeBridge.getFrame(sessionId, display)
                    if (frame == null) {
                        Thread.sleep(16)
                        continue
                    }
                    if (frame.size == bmp.width * bmp.height * 4) {
                        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(frame))
                        blitToSurface(bmp)
                    }
                }
            } catch (_: InterruptedException) {
                // destroy() calls videoThread.interrupt() to stop this loop promptly while it's
                // parked in a Thread.sleep — a clean shutdown, not an error. Without catching it,
                // the uncaught InterruptedException propagates to Android's default handler and
                // SIGKILLs the whole plugin process (confirmed on-device: FATAL EXCEPTION on the
                // RustDesk-video thread, process ended, right after a disconnect).
                Log.i(TAG, "pumpVideo($sessionId) interrupted — stopping cleanly")
            }
        }

        private fun blitToSurface(bitmap: Bitmap) {
            val s = surface ?: run {
                Log.w(TAG, "blitToSurface($sessionId): no surface set yet — nothing to draw onto")
                return
            }
            if (!s.isValid) {
                Log.w(TAG, "blitToSurface($sessionId): surface is not valid (torn down?), skipping frame")
                return
            }
            val now = System.currentTimeMillis()
            if (blitFailing && now - lastBlitAttemptMs < BLIT_RETRY_INTERVAL_MS) return
            lastBlitAttemptMs = now
            try {
                // Serializes against the OTHER threads that can call this concurrently (video-pump
                // thread vs. Binder threadpool threads via sendMouse/setZoom) — Surface.lockCanvas()
                // isn't meant to be raced from multiple threads at once; see renderLock's doc.
                synchronized(renderLock) {
                    val canvas: Canvas = s.lockCanvas(null) ?: run {
                        Log.w(TAG, "blitToSurface($sessionId): lockCanvas() returned null")
                        blitFailing = true
                        return
                    }
                    try {
                        val sw = canvas.width.toFloat()
                        val sh = canvas.height.toFloat()
                        // Base letterbox fit * pinch-zoom on top (see setZoom / VncClient.blitToSurface).
                        // RustDeskScreen owns zoomScale/panX/panY and applies the SAME transform in its
                        // touch inverse-map, so taps land correctly while zoomed.
                        val scale = minOf(sw / bitmap.width, sh / bitmap.height) * zoomScale
                        val dw = bitmap.width * scale
                        val dh = bitmap.height * scale
                        // Horizontally centred, top-aligned vertically (not centred) — user
                        // preference; must match RustDeskScreen's sendAt inverse-map exactly, or
                        // touches land at the wrong remote position.
                        val ox = (sw - dw) / 2f + panX
                        val oy = panY
                        canvas.drawColor(Color.BLACK)
                        canvas.drawBitmap(bitmap, null, RectF(ox, oy, ox + dw, oy + dh), null)
                        if (pointerFbX >= 0 && pointerFbY >= 0) {
                            SyntheticCursor.draw(canvas, ox + pointerFbX * scale, oy + pointerFbY * scale)
                        }
                        if (!loggedFirstBlit) {
                            loggedFirstBlit = true
                            Log.i(TAG, "blitToSurface($sessionId): first frame drawn, canvas=${sw}x$sh bitmap=${bitmap.width}x${bitmap.height}")
                        }
                    } finally {
                        s.unlockCanvasAndPost(canvas)
                    }
                }
                blitFailing = false
            } catch (e: Exception) {
                // Surface torn down mid-blit (e.g. caller backgrounded) is expected and self-heals
                // on the next frame — but log it (rate-limited) so a PERSISTENT failure (e.g. the
                // parceled cross-process Surface never actually becoming usable) is visible instead
                // of silently showing a black view forever with no trace in logcat.
                blitFailing = true
                blitFailLogCount++
                if (blitFailLogCount <= 5 || blitFailLogCount % 50 == 0) {
                    Log.w(TAG, "blitToSurface($sessionId) failed (#$blitFailLogCount): ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        override fun updateSurface(newSurface: Surface?) {
            if (!isCallerAuthorized()) return
            surface = newSurface
            // A genuinely new Surface deserves an immediate retry, not the backoff interval left
            // over from the old one failing (e.g. display was off, screen just came back on with
            // a fresh Surface after the app resumed) — same reasoning as VncClient's identical fix.
            blitFailing = false
        }

        override fun sendMouse(x: Int, y: Int, mouseType: String?, buttons: String?) {
            if (!isCallerAuthorized()) return
            // "wheel" carries a notch delta in x/y (see NativeBridge.sendMouse's doc), not a real
            // position — don't let it teleport the synthetic cursor to (0, ±1).
            if (mouseType != "wheel") {
                pointerFbX = x
                pointerFbY = y
                val now = System.currentTimeMillis()
                if (now - lastExtraBlitMs >= 16L) {
                    lastExtraBlitMs = now
                    currentBitmap?.let { blitToSurface(it) }
                }
            }
            NativeBridge.sendMouse(sessionId, x, y, mouseType.orEmpty(), buttons.orEmpty())
        }

        override fun setZoom(scale: Float, panXValue: Float, panYValue: Float) {
            if (!isCallerAuthorized()) return
            zoomScale = scale.coerceAtLeast(0.1f)
            panX = panXValue
            panY = panYValue
            // Throttled the same as sendMouse's cursor-redraw (shared lastExtraBlitMs) — this used
            // to redraw unconditionally on every call, which is what made edge-panning (called on
            // every touch-move sample while zoomed) visibly lag: see lastExtraBlitMs's doc.
            val now = System.currentTimeMillis()
            if (now - lastExtraBlitMs >= 16L) {
                lastExtraBlitMs = now
                currentBitmap?.let { blitToSurface(it) }
            }
        }

        override fun inputKey(name: String?, down: Boolean, press: Boolean, ctrl: Boolean, alt: Boolean) {
            if (!isCallerAuthorized()) return
            NativeBridge.inputKey(sessionId, name.orEmpty(), down, press, ctrl, alt)
        }

        override fun inputString(value: String?) {
            if (!isCallerAuthorized()) return
            NativeBridge.inputString(sessionId, value.orEmpty())
        }

        override fun isAlive(): Boolean {
            if (!isCallerAuthorized()) return false
            return NativeBridge.isAlive(sessionId)
        }

        override fun getDisplayCount(): Int {
            if (!isCallerAuthorized()) return 0
            return NativeBridge.getDisplayCount(sessionId)
        }

        override fun switchDisplay(display: Int) {
            if (!isCallerAuthorized()) return
            currentDisplay = display
            // Force pumpVideo's "size unknown yet" branch to run again for the new display, and
            // let onConnected fire a second time with the new size — RustDeskViewModel needs the
            // fresh width/height for its own touch inverse-map, same as a real fresh connect.
            currentBitmap = null
            announcedConnected = false
            loggedFirstBlit = false
            NativeBridge.switchDisplay(sessionId, display)
        }

        override fun destroy() {
            if (!isCallerAuthorized()) return
            destroyInternal()
        }

        /** The real teardown — no [isCallerAuthorized] check, since [Service.onDestroy] (which
         *  calls this on every still-open session) is a local lifecycle callback, not a live
         *  incoming Binder transaction; [Binder.getCallingUid] there resolves to this process's
         *  OWN uid, which isn't in [ALLOWED_CALLER_PACKAGES] — calling the AIDL-gated [destroy]
         *  from there would silently no-op and leak the native session/thread. Same split as
         *  RemoteDesktopSessionService's destroy()/destroyInternal(). */
        fun destroyInternal() {
            running = false
            videoThread.interrupt()
            surface = null
            NativeBridge.disconnect(sessionId)
            openSessions.remove(this)
            demoteFromForegroundIfIdle()
        }
    }
}
