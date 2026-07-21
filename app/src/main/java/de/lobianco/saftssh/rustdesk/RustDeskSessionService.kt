package de.lobianco.saftssh.rustdesk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

private const val TAG = "RustDeskSessionService"
private const val NOTIF_CHANNEL_ID = "rustdesk_session"
private const val NOTIF_ID = 3001
// blitToSurface() rate-limit when the Surface can't be locked (e.g. display off) — see that
// function's doc for why this can't be detected proactively.
private const val BLIT_RETRY_INTERVAL_MS = 2000L
// Clipboard changes are rare compared to video frames — no need to poll anywhere near as often.
private const val CLIPBOARD_POLL_INTERVAL_MS = 500L
// Opus frames arrive roughly every ~20-60ms — poll faster than video so the AudioTrack buffer
// never starves (an underrun is audible as a click/gap, unlike a slightly-late video frame).
private const val AUDIO_POLL_INTERVAL_MS = 20L
// Generous per-poll cap: at 48kHz stereo this is ~340ms of audio in one drain, far more than one
// poll interval could ever accumulate under normal conditions — just a safety bound, not a target.
private const val AUDIO_POLL_MAX_SAMPLES = 32_768
// NativeBridge.pollCursor's blob header: 4 little-endian i32 (width, height, hotx, hoty) ahead of
// the RGBA pixels. Must match the JNI export's layout exactly.
private const val CURSOR_HEADER_BYTES = 16
// Must match AndroidManifest.xml's <provider android:authorities="...">.
private const val FILE_PROVIDER_AUTHORITY = "de.lobianco.saftssh.rustdesk.fileprovider"

/** [toCustomDestination] mirrors the same-named [IRustDeskFileTransferSession.downloadFile] param
 *  — decides whether a finished download is finalized into MediaStore.Downloads or handed back as
 *  a FileProvider Uri (see that method's doc). File-scoped rather than nested inside
 *  RustDeskFileTransferSessionImpl since Kotlin doesn't allow nested classes inside an inner class. */
private data class DownloadTempFile(val file: File, val suggestedName: String, val toCustomDestination: Boolean)
// The peer's cursor bitmap is drawn at its native pixel size times this, times the user's cursor
// size setting — NOT times the letterbox/zoom scale (see blitToSurface for why). 2.0 makes a
// typical 32px desktop cursor ~64px on a phone, roughly matching the synthetic arrow's own size.
private const val HOST_CURSOR_BASE_SCALE = 2.0f
// Consecutive failures required before the above backoff engages — see consecutiveBlitFailures'
// doc. pumpVideo's pendingResizeRedraw retry (a static host screen with no new frames, e.g. while
// the IME/key-bar/input-box animates in) loops at a plain Thread.sleep(16) cadence — a Surface
// resize's BufferQueue commonly takes 100-300ms to settle, i.e. ~10-20 failed attempts at that
// cadence. The old threshold of 3 (~48ms) was reliably blown through by an ordinary resize alone,
// tripping the SAME BLIT_RETRY_INTERVAL_MS backoff meant only for a genuinely stuck Surface
// (display off) and freezing the visible cursor/redraw for up to that whole interval — reported as
// "1-3s before the mouse/stream responds again" on every IME/key-bar/input-box toggle. 60 (~1s of
// retries at the 16ms cadence) comfortably outlasts a resize settle while still escalating for a
// real persistent failure well within a second.
private const val BLIT_FAILURE_ESCALATION_THRESHOLD = 60
// File-transfer events (dir listings, job progress/done/error, overwrite prompts) are far rarer
// than video frames — no need to poll anywhere near that often.
private const val FILE_TRANSFER_POLL_INTERVAL_MS = 250L

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
    private val openFileTransferSessions = CopyOnWriteArrayList<RustDeskFileTransferSessionImpl>()

    override fun onCreate() {
        super.onCreate()
        NativeBridge.initialize(filesDir.absolutePath)
    }

    override fun onBind(intent: Intent?): IBinder = serviceStub

    override fun onDestroy() {
        openSessions.forEach { runCatching { it.destroyInternal() } }
        openSessions.clear()
        openFileTransferSessions.forEach { runCatching { it.destroyInternal() } }
        openFileTransferSessions.clear()
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
        if (openSessions.isEmpty() && openFileTransferSessions.isEmpty()) stopForeground(STOP_FOREGROUND_REMOVE)
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

        override fun createFileTransferSession(
            id: String,
            password: String,
            idServer: String,
            relayServer: String,
            apiServer: String,
            key: String,
            callback: IRustDeskFileTransferCallback?
        ): IRustDeskFileTransferSession? {
            if (!isCallerAuthorized()) return null
            NativeBridge.setServerConfig(idServer, relayServer, apiServer, key)
            val result = NativeBridge.connectFileTransfer(id, password)
            if (result.startsWith("ERR:")) {
                val reason = result.removePrefix("ERR:")
                Log.e(TAG, "connectFileTransfer($id) failed: $reason")
                runCatching { callback?.onDisconnected(reason) }
                return null
            }
            // Deliberately NOT calling callback.onConnected() here — same reasoning as the main
            // createSession(): this only means the request was accepted and headless start was
            // invoked, not that the peer handshake actually succeeded. There's no video "first
            // frame" equivalent to wait for here; the caller should issue an initial readRemoteDir
            // and treat the first onDirListing arrival as its real "connected" signal (with its own
            // client-side timeout, same pattern as RustDeskViewModel.CONNECT_TIMEOUT_MS).
            val session = RustDeskFileTransferSessionImpl(result, callback)
            openFileTransferSessions.add(session)
            promoteToForeground()
            return session
        }

        override fun createViewCameraSession(
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
            val result = NativeBridge.connectViewCamera(id, password)
            if (result.startsWith("ERR:")) {
                val reason = result.removePrefix("ERR:")
                Log.e(TAG, "connectViewCamera($id) failed: $reason")
                runCatching { callback?.onDisconnected(reason) }
                return null
            }
            val session = RustDeskSessionImpl(result, surface, callback)
            openSessions.add(session)
            promoteToForeground()
            return session
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
        // Diagnostic (Round 26): logs whenever the canvas (Surface) dimensions actually change, to
        // confirm on-device whether opening the IME / special-key bar really resizes the Surface
        // (so blitToSurface re-fits the whole picture into the smaller area) or whether the Surface
        // stays full-size and the bars just overlay the bottom — the open question behind the
        // reported "bars still cover the view".
        private var lastLoggedCanvasW = 0
        private var lastLoggedCanvasH = 0
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

        // ── Cursor rendering options (see IRustDeskSession.setCursorOptions) ──
        // "host" (default) draws the peer's REAL cursor bitmap once one arrives — the only way to
        // see an I-beam over text or a resize arrow over a window edge, which is exactly why this
        // was asked for. Until then (and forever, if the peer never sends one) it falls back to the
        // synthetic arrow, so the pointer is never invisible.
        @Volatile private var cursorMode = "host"
        @Volatile private var cursorSizeScale = 1f
        // Latest cursor shape received from the peer, rebuilt only when NativeBridge.pollCursor
        // reports a change (it returns null while unchanged). Guarded by renderLock: the video-pump
        // thread builds/reads it while Binder threads can trigger redraws concurrently.
        private var hostCursorBitmap: Bitmap? = null
        private var hostCursorHotX = 0
        private var hostCursorHotY = 0
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
        // Counts CONSECUTIVE failures before escalating to the long backoff above — added after a
        // reported lag every time the video area is resized (IME show/hide, key-bar toggle
        // — see RustDeskScreen's bottomBarHeight/keyBarHeight padding). A resize makes the
        // SurfaceView's underlying BufferQueue briefly reject a buffer sized for the surface's
        // PREVIOUS dimensions (confirmed via on-device logcat: "rejecting buffer" native errors
        // correlating exactly with IME/key-bar toggles) — a purely transient, self-healing hiccup
        // that used to immediately trip the SAME BLIT_RETRY_INTERVAL_MS (2s) backoff meant for the
        // display-off scenario, causing a multi-second visible freeze on every single toggle.
        // Escalating only after several consecutive failures keeps the real display-off protection
        // (task #43) while no longer punishing a one-off resize-induced miss.
        @Volatile private var consecutiveBlitFailures = 0
        // Set when a resize's immediate redraw (see updateSurface) couldn't land because the
        // just-resized BufferQueue transiently rejected the buffer — makes pumpVideo re-attempt the
        // redraw of the last frame on its idle (no-new-frame) path until it succeeds, so a STATIC
        // remote screen re-fits into the newly shrunk Surface without waiting for a server frame or
        // any user input.
        @Volatile private var pendingResizeRedraw = false
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

        // Bidirectional clipboard sync (plain text only). Android's clipboard is a SYSTEM-WIDE
        // service, not per-app — reading/writing it from THIS plugin process observes/affects the
        // exact same clipboard the main app (or any other app) sees, so no AIDL round-trip is
        // needed for either direction; it's all local to this process. NativeBridge.pushClipboardText/
        // pollRemoteClipboardText are deliberately NOT session-scoped in the Rust layer (see their
        // own docs) — tied to THIS session's lifecycle here only because there's realistically one
        // active connection at a time.
        private val clipboardManager: ClipboardManager? = getSystemService(ClipboardManager::class.java)
        // Guards against the obvious feedback loop: writing the remote's clipboard content into
        // Android's local clipboard fires our OWN listener below, which would otherwise immediately
        // push that same content right back out as if it were new local content.
        @Volatile private var lastClipboardSetByUs: String? = null
        private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            val text = clipboardManager?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty() && text != lastClipboardSetByUs) {
                NativeBridge.pushClipboardText(text)
            }
        }
        private val clipboardPollThread = Thread({ pumpClipboard() }, "RustDesk-clipboard-$sessionId").apply {
            isDaemon = true
            start()
        }

        // Built lazily by pumpAudio() once the peer's format is known (and rebuilt whenever it
        // changes mid-session) — there's no fixed format to construct against up front, unlike
        // e.g. a local media player. Only ever touched from the audio poll thread itself, so no
        // extra synchronization beyond @Volatile (destroyInternal() reads/releases it from the
        // Binder thread that called destroy(), after audioPollThread.interrupt() has already been
        // issued — a benign race with the poll thread's own final iteration at worst).
        @Volatile private var audioTrack: AudioTrack? = null
        private var audioSampleRate = 0
        private var audioChannels = 0

        private val audioPollThread = Thread({ pumpAudio() }, "RustDesk-audio-$sessionId").apply {
            isDaemon = true
            start()
        }

        init {
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        }

        /** Polls decoded PCM (and format changes) from the native side and streams it to an
         *  android.media.AudioTrack in STREAM/WRITE_BLOCKING mode. Mirrors pumpClipboard's shape;
         *  runs noticeably more often since audio has much tighter latency/continuity needs than
         *  clipboard polling. */
        private fun pumpAudio() {
            try {
                while (running) {
                    NativeBridge.pollAudioFormat(sessionId)?.let { format ->
                        val sampleRate = format.getOrElse(0) { 0 }
                        val channels = format.getOrElse(1) { 0 }
                        if (sampleRate > 0 && channels > 0 && (sampleRate != audioSampleRate || channels != audioChannels)) {
                            Log.i(TAG, "pumpAudio($sessionId): format changed to ${sampleRate}Hz/${channels}ch")
                            audioTrack?.let { runCatching { it.stop(); it.release() } }
                            audioTrack = buildAudioTrack(sampleRate, channels)
                            audioSampleRate = sampleRate
                            audioChannels = channels
                        }
                    }
                    val track = audioTrack
                    if (track != null) {
                        val samples = NativeBridge.pollAudioPcm(sessionId, AUDIO_POLL_MAX_SAMPLES)
                        if (samples != null && samples.isNotEmpty()) {
                            // WRITE_BLOCKING is intentional here — this thread does nothing else
                            // that latency-sensitive input touches, so blocking until the track
                            // has room is the simplest way to pace playback to real time (the same
                            // backpressure a blocking write to a sound device always provides).
                            runCatching { track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) }
                                .onFailure { Log.w(TAG, "pumpAudio($sessionId): AudioTrack.write failed: ${it.message}") }
                        }
                    }
                    Thread.sleep(AUDIO_POLL_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                // destroy() interrupts this thread to stop it promptly — same reasoning as
                // pumpVideo's/pumpClipboard's identical catch.
                Log.i(TAG, "pumpAudio($sessionId) interrupted — stopping cleanly")
            }
        }

        private fun buildAudioTrack(sampleRate: Int, channels: Int): AudioTrack {
            val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
                .coerceAtLeast(sampleRate * channels) // at least ~1s worth as a floor against a bogus/tiny getMinBufferSize result
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 4) // f32 = 4 bytes/sample
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        }

        /** Polls the peer's clipboard text at a much coarser interval than video (clipboard
         *  changes are rare compared to frames) and writes it into Android's system clipboard. */
        private fun pumpClipboard() {
            try {
                while (running) {
                    val text = NativeBridge.pollRemoteClipboardText()
                    if (text != null) {
                        Log.i(TAG, "pumpClipboard($sessionId): got ${text.length} chars from peer, writing to local clipboard")
                        lastClipboardSetByUs = text
                        clipboardManager?.setPrimaryClip(ClipData.newPlainText("RustDesk", text))
                    }
                    Thread.sleep(CLIPBOARD_POLL_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                // destroy() interrupts this thread to stop it promptly — a clean shutdown, same
                // reasoning as pumpVideo's identical catch (see its doc).
                Log.i(TAG, "pumpClipboard($sessionId) interrupted — stopping cleanly")
            }
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
                    // Cursor shape changes arrive independently of video frames (moving the mouse
                    // over a text field changes the cursor without necessarily redrawing anything),
                    // so poll it every iteration rather than only alongside a new frame.
                    val cursorChanged = pollHostCursor()
                    val frame = NativeBridge.getFrame(sessionId, display)
                    if (frame == null) {
                        // No new server frame — but if a recent resize's redraw couldn't land yet
                        // (see pendingResizeRedraw / updateSurface), keep retrying it here so a
                        // static remote screen still re-fits into the shrunk Surface without needing
                        // any input.
                        if (pendingResizeRedraw) {
                            pendingResizeRedraw = currentBitmap?.let { !blitToSurface(it) } ?: false
                        } else if (cursorChanged) {
                            // A new cursor shape with no new frame (static screen) still has to be
                            // drawn, or the I-beam/resize arrow wouldn't appear until something else
                            // happened to trigger a redraw.
                            currentBitmap?.let { blitToSurface(it) }
                        }
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

        /** Picks up a new cursor shape from the peer, if one arrived since the last call. Returns
         *  true when [hostCursorBitmap] actually changed, so the caller knows a redraw is needed
         *  even with no new video frame.
         *
         *  NativeBridge.pollCursor returns null while unchanged (clear-on-read polling, same shape
         *  as getFrame / pollRemoteClipboardText), else a blob of 4 little-endian i32 — width,
         *  height, hotx, hoty — followed by width*height*4 RGBA bytes. */
        private fun pollHostCursor(): Boolean {
            val blob = NativeBridge.pollCursor(sessionId) ?: return false
            if (blob.size < CURSOR_HEADER_BYTES) {
                Log.w(TAG, "pollCursor($sessionId): blob too small (${blob.size} bytes), ignoring")
                return false
            }
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val w = buf.int
            val h = buf.int
            val hotX = buf.int
            val hotY = buf.int
            if (w <= 0 || h <= 0 || blob.size != CURSOR_HEADER_BYTES + w * h * 4) {
                Log.w(TAG, "pollCursor($sessionId): malformed cursor w=$w h=$h size=${blob.size}, ignoring")
                return false
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // The peer's cursor pixels are RGBA, which is exactly ARGB_8888's in-memory byte
            // layout — same as getFrame's video pixels, so no channel swizzle is needed. buf's
            // position is already past the header, which is where copyPixelsFromBuffer reads from.
            bmp.copyPixelsFromBuffer(buf)
            synchronized(renderLock) {
                hostCursorBitmap = bmp
                hostCursorHotX = hotX
                hostCursorHotY = hotY
            }
            return true
        }

        /** Draws [bitmap] into the current Surface at the base letterbox fit (recomputed from the
         *  CURRENT canvas size every call) times the pinch-zoom transform. Returns true only if the
         *  frame actually reached the Surface — updateSurface / pumpVideo use that to know whether a
         *  post-resize redraw landed or still needs retrying (see pendingResizeRedraw). */
        private fun blitToSurface(bitmap: Bitmap): Boolean {
            val s = surface ?: run {
                Log.w(TAG, "blitToSurface($sessionId): no surface set yet — nothing to draw onto")
                return false
            }
            if (!s.isValid) {
                Log.w(TAG, "blitToSurface($sessionId): surface is not valid (torn down?), skipping frame")
                return false
            }
            val now = System.currentTimeMillis()
            if (blitFailing && now - lastBlitAttemptMs < BLIT_RETRY_INTERVAL_MS) return false
            lastBlitAttemptMs = now
            try {
                // Serializes against the OTHER threads that can call this concurrently (video-pump
                // thread vs. Binder threadpool threads via sendMouse/setZoom) — Surface.lockCanvas()
                // isn't meant to be raced from multiple threads at once; see renderLock's doc.
                synchronized(renderLock) {
                    val canvas: Canvas = s.lockCanvas(null) ?: run {
                        Log.w(TAG, "blitToSurface($sessionId): lockCanvas() returned null")
                        registerBlitFailure()
                        return false
                    }
                    try {
                        val sw = canvas.width.toFloat()
                        val sh = canvas.height.toFloat()
                        if (canvas.width != lastLoggedCanvasW || canvas.height != lastLoggedCanvasH) {
                            Log.i(TAG, "blitToSurface($sessionId): canvas size now ${canvas.width}x${canvas.height} (was ${lastLoggedCanvasW}x$lastLoggedCanvasH)")
                            lastLoggedCanvasW = canvas.width
                            lastLoggedCanvasH = canvas.height
                        }
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
                            val cx = ox + pointerFbX * scale
                            val cy = oy + pointerFbY * scale
                            val host = hostCursorBitmap
                            // Draw the peer's real cursor whenever we have one and the mode wants it.
                            val drawHost = host != null && (cursorMode == "host" || cursorMode == "both")
                            if (host != null && drawHost) {
                                // Deliberately sized independently of `scale` (the letterbox fit *
                                // pinch zoom): a 32px remote cursor multiplied by a typical
                                // fit-to-view factor of ~0.3 would render ~10px on a phone, far too
                                // small to tell an I-beam from an arrow — which is the entire point
                                // of showing the host's shape. cursorSizeScale is the user's control.
                                val cs = HOST_CURSOR_BASE_SCALE * cursorSizeScale
                                // hotx/hoty is the cursor's "active point" (the arrow's tip, an
                                // I-beam's centre) and must land exactly on the pointer position.
                                val left = cx - hostCursorHotX * cs
                                val top = cy - hostCursorHotY * cs
                                canvas.drawBitmap(
                                    host, null,
                                    RectF(left, top, left + host.width * cs, top + host.height * cs),
                                    null,
                                )
                            }
                            // Synthetic arrow: always in "local"/"both", and as the fallback in
                            // "host" mode until a real cursor arrives (or forever, if the peer never
                            // sends one) so the pointer is never invisible — see setCursorOptions.
                            if (cursorMode == "local" || cursorMode == "both" || !drawHost) {
                                SyntheticCursor.draw(canvas, cx, cy, cursorSizeScale)
                            }
                        }
                        if (!loggedFirstBlit) {
                            loggedFirstBlit = true
                            Log.i(TAG, "blitToSurface($sessionId): first frame drawn, canvas=${sw}x$sh bitmap=${bitmap.width}x${bitmap.height}")
                        }
                    } finally {
                        s.unlockCanvasAndPost(canvas)
                    }
                }
                consecutiveBlitFailures = 0
                blitFailing = false
                return true
            } catch (e: Exception) {
                // Surface torn down mid-blit (e.g. caller backgrounded) is expected and self-heals
                // on the next frame — but log it (rate-limited) so a PERSISTENT failure (e.g. the
                // parceled cross-process Surface never actually becoming usable) is visible instead
                // of silently showing a black view forever with no trace in logcat.
                registerBlitFailure()
                blitFailLogCount++
                if (blitFailLogCount <= 5 || blitFailLogCount % 50 == 0) {
                    Log.w(TAG, "blitToSurface($sessionId) failed (#$blitFailLogCount): ${e.javaClass.simpleName}: ${e.message}")
                }
                return false
            }
        }

        /** Registers one blit failure — only escalates to the long [BLIT_RETRY_INTERVAL_MS] backoff
         *  once [BLIT_FAILURE_ESCALATION_THRESHOLD] failures happen in a row (see
         *  consecutiveBlitFailures' doc for why: a single resize-triggered miss self-heals on its
         *  own next frame and shouldn't cost a multi-second stall). */
        private fun registerBlitFailure() {
            consecutiveBlitFailures++
            blitFailing = consecutiveBlitFailures >= BLIT_FAILURE_ESCALATION_THRESHOLD
        }

        override fun updateSurface(newSurface: Surface?) {
            if (!isCallerAuthorized()) return
            surface = newSurface
            // A genuinely new Surface deserves an immediate retry, not the backoff interval left
            // over from the old one failing (e.g. display was off, screen just came back on with
            // a fresh Surface after the app resumed) — same reasoning as VncClient's identical fix.
            blitFailing = false
            consecutiveBlitFailures = 0
            // updateSurface also fires on every RESIZE (RustDeskScreen calls it from
            // SurfaceHolder.surfaceChanged) — opening the IME / special-key bar / text-input bar
            // shrinks this Surface (see RustDeskScreen's bottomStackHeight padding). blitToSurface
            // recomputes its base letterbox fit from the CURRENT canvas size every call, so
            // redrawing the last frame here re-fits the WHOLE remote screen into the shrunk area
            // (scaled down — host taskbar and cursor stay visible, nothing hidden behind the bars).
            // Without this, a STATIC remote desktop (no new server frames, no mouse/zoom event)
            // kept the last frame drawn at the OLD, larger size until the user moved the cursor,
            // which is what re-triggered a redraw — reported as having to "reposition first" before
            // the bottom of the picture reappeared. If the just-resized BufferQueue transiently
            // rejects this immediate redraw (see consecutiveBlitFailures' doc), pendingResizeRedraw
            // makes pumpVideo keep retrying until it lands, so it re-fits within a frame or two with
            // no input either way.
            pendingResizeRedraw = currentBitmap?.let { !blitToSurface(it) } ?: false
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

        override fun ctrlAltDel() {
            if (!isCallerAuthorized()) return
            NativeBridge.ctrlAltDel(sessionId)
        }

        override fun isAlive(): Boolean {
            if (!isCallerAuthorized()) return false
            return NativeBridge.isAlive(sessionId)
        }

        override fun getDisplayCount(): Int {
            if (!isCallerAuthorized()) return 0
            return NativeBridge.getDisplayCount(sessionId)
        }

        override fun getQualityStatus(): String? {
            if (!isCallerAuthorized()) return null
            return NativeBridge.pollQualityStatus(sessionId)
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

        override fun setQuality(value: String?) {
            if (!isCallerAuthorized()) return
            NativeBridge.setImageQuality(sessionId, value.orEmpty())
        }

        override fun setCursorOptions(mode: String?, syntheticScale: Float) {
            if (!isCallerAuthorized()) return
            cursorMode = when (mode) {
                "local", "both", "host" -> mode
                else -> "host"
            }
            // Guard against a nonsense scale from a bad caller making the cursor invisible or
            // absurdly huge; the settings UI already clamps, this is just defence in depth.
            cursorSizeScale = syntheticScale.coerceIn(0.25f, 5f)
            // Pure render state — redraw the last frame immediately so the change is visible at
            // once, rather than only on the next server frame (a static remote screen sends none;
            // same reasoning as updateSurface's redraw).
            currentBitmap?.let { blitToSurface(it) }
        }

        override fun setAudioMuted(muted: Boolean) {
            if (!isCallerAuthorized()) return
            NativeBridge.setAudioMuted(sessionId, muted)
        }

        override fun isAudioMuted(): Boolean {
            if (!isCallerAuthorized()) return false
            return NativeBridge.isAudioMuted(sessionId)
        }

        override fun isPrivacyModeSupported(): Boolean {
            if (!isCallerAuthorized()) return false
            return NativeBridge.isPrivacyModeSupported(sessionId)
        }

        override fun getDefaultPrivacyModeImpl(): String {
            if (!isCallerAuthorized()) return ""
            return NativeBridge.getDefaultPrivacyModeImpl(sessionId)
        }

        override fun setPrivacyMode(implKey: String, on: Boolean) {
            if (!isCallerAuthorized()) return
            NativeBridge.setPrivacyMode(sessionId, implKey, on)
        }

        override fun isPrivacyModeOn(): Boolean {
            if (!isCallerAuthorized()) return false
            return NativeBridge.isPrivacyModeOn(sessionId)
        }

        override fun isViewCameraSupported(): Boolean {
            if (!isCallerAuthorized()) return false
            return NativeBridge.isViewCameraSupported(sessionId)
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
            clipboardPollThread.interrupt()
            audioPollThread.interrupt()
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
            audioTrack?.let { runCatching { it.stop(); it.release() } }
            audioTrack = null
            surface = null
            NativeBridge.disconnect(sessionId)
            openSessions.remove(this)
            demoteFromForegroundIfIdle()
        }
    }

    /** One file-transfer connection to a peer — see IRustDeskFileTransferSession's doc for why
     *  this is a wholly separate session type from [RustDeskSessionImpl] (remote control/video),
     *  not a mode flag on it. A single background thread both polls the native layer for events
     *  (dir listings / job progress-done-error / overwrite prompts) and does the local staging
     *  work uploads/downloads need (copying a picked file in, inserting a finished download into
     *  MediaStore.Downloads) — none of this is remotely as latency-sensitive as video, so one
     *  thread per session is plenty; unlike RustDeskSessionImpl there's no separate render loop to
     *  keep responsive. */
    private inner class RustDeskFileTransferSessionImpl(
        private val sessionId: String,
        private val callback: IRustDeskFileTransferCallback?,
    ) : IRustDeskFileTransferSession.Stub() {

        @Volatile private var running = true
        // Fires callback.onConnected() exactly once, the first time ANY event is actually drained
        // from the native layer — the earliest real evidence this session reached the peer at all
        // (connectFileTransfer succeeding only means the request was accepted locally, same
        // caveat as the main session's createSession). No video "first frame" equivalent exists
        // here, so this is the closest analogue; the caller is expected to kick off an initial
        // readRemoteDir right after creating the session so this actually has something to wait for.
        @Volatile private var announcedConnected = false

        // Tracks local staging files this session created, so they can be cleaned up once the
        // matching job finishes (success or failure) instead of leaking temp files across a long
        // session. Upload: the copy made from the caller's granted content Uri, deleted once the
        // native side has read it (job done/error either way). Download: the temp file the native
        // side is writing into, moved into MediaStore.Downloads (or left in place pre-Android 10)
        // once the transfer completes.
        private val uploadTempFiles = java.util.concurrent.ConcurrentHashMap<Int, File>()
        private val downloadTempFiles = java.util.concurrent.ConcurrentHashMap<Int, DownloadTempFile>()

        private val pollThread = Thread({ pumpFileTransferEvents() }, "RustDesk-ft-$sessionId").apply {
            isDaemon = true
            start()
        }

        private fun pumpFileTransferEvents() {
            try {
                while (running) {
                    var gotAny = false

                    NativeBridge.ftPollDirListing(sessionId)?.let { json ->
                        gotAny = true
                        Log.i(TAG, "ftPollDirListing($sessionId): got a listing (${json.length} chars)")
                        runCatching { callback?.onDirListing(json) }
                            .onFailure { Log.w(TAG, "onDirListing callback failed: ${it.message}") }
                    }

                    NativeBridge.ftPollJobEvent(sessionId)?.let { json ->
                        gotAny = true
                        handleJobEvent(json)
                    }

                    NativeBridge.ftPollOverrideConfirm(sessionId)?.let { json ->
                        gotAny = true
                        runCatching { callback?.onOverrideConfirm(json) }
                            .onFailure { Log.w(TAG, "onOverrideConfirm callback failed: ${it.message}") }
                    }

                    if (gotAny && !announcedConnected) {
                        announcedConnected = true
                        runCatching { callback?.onConnected() }
                    }

                    Thread.sleep(FILE_TRANSFER_POLL_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                // destroy() interrupts this thread to stop it promptly — a clean shutdown, same
                // reasoning as pumpVideo's identical catch in RustDeskSessionImpl.
                Log.i(TAG, "pumpFileTransferEvents($sessionId) interrupted — stopping cleanly")
            }
        }

        /** Intercepts done/error events for jobs THIS session is staging locally (uploads/
         *  downloads), to clean up temp files and — for a finished download — insert it into
         *  MediaStore.Downloads, before forwarding an (possibly augmented) event to the caller. Any
         *  job not in [uploadTempFiles]/[downloadTempFiles] (e.g. a plain remote-to-remote action
         *  like create-dir/rename) is forwarded unchanged. */
        private fun handleJobEvent(json: String) {
            val obj = runCatching { JSONObject(json) }.getOrNull()
            val type = obj?.optString("type")
            val jobId = obj?.optInt("id") ?: -1

            if (type == "done" || type == "error") {
                uploadTempFiles.remove(jobId)?.let { tempFile ->
                    tempFile.delete()
                }
                downloadTempFiles.remove(jobId)?.let { pending ->
                    if (type == "done") {
                        val savedUri = if (pending.toCustomDestination) {
                            grantDownloadedFileUri(jobId, pending.file)
                        } else {
                            saveDownloadToMediaStore(pending.file, pending.suggestedName)
                        }
                        val augmented = JSONObject(json).put("savedUri", savedUri)
                        runCatching { callback?.onJobEvent(augmented.toString()) }
                            .onFailure { Log.w(TAG, "onJobEvent callback failed: ${it.message}") }
                        return
                    } else {
                        pending.file.delete()
                    }
                }
            }
            runCatching { callback?.onJobEvent(json) }
                .onFailure { Log.w(TAG, "onJobEvent callback failed: ${it.message}") }
        }

        /** Moves a finished download from its private temp path into the shared Downloads
         *  collection (Android 10+ — no storage permission needed to create a NEW entry there,
         *  unlike reading/modifying other apps' entries). Returns the resulting content:// Uri, or
         *  null on failure OR on Android <10, where MediaStore.Downloads doesn't exist and this app
         *  has no broad storage permission to write the real shared Downloads folder directly —
         *  the finished file is left at [tempFile] (this process's own external-files directory)
         *  in that case; the caller should tell the user it landed in the plugin's own storage
         *  rather than the system Downloads folder. */
        private fun saveDownloadToMediaStore(tempFile: File, suggestedName: String): String? {
            if (Build.VERSION.SDK_INT < 29) return null
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, suggestedName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                contentResolver.openOutputStream(uri)?.use { out ->
                    tempFile.inputStream().use { it.copyTo(out) }
                } ?: return null
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                tempFile.delete()
                uri.toString()
            } catch (e: Exception) {
                Log.w(TAG, "saveDownloadToMediaStore($suggestedName) failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

        override fun readRemoteDir(path: String, showHidden: Boolean) {
            if (!isCallerAuthorized()) return
            Log.i(TAG, "readRemoteDir($sessionId, \"$path\")")
            NativeBridge.ftReadRemoteDir(sessionId, path, showHidden)
        }

        override fun uploadFile(jobId: Int, contentUri: String, remotePath: String, includeHidden: Boolean) {
            if (!isCallerAuthorized()) return
            // Runs on its own thread, not the poll thread — copying a large file must not stall
            // event draining for the rest of this session's jobs.
            Thread({
                val tempFile = File(filesDir, "ft_upload_$jobId")
                try {
                    val uri = Uri.parse(contentUri)
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw java.io.IOException("openInputStream returned null for $contentUri")
                    uploadTempFiles[jobId] = tempFile
                    val started = NativeBridge.ftSendFiles(sessionId, jobId, tempFile.absolutePath, remotePath, true, includeHidden)
                    if (!started) {
                        uploadTempFiles.remove(jobId)
                        tempFile.delete()
                        reportLocalError(jobId, "Failed to start upload")
                    }
                } catch (e: Exception) {
                    uploadTempFiles.remove(jobId)
                    tempFile.delete()
                    reportLocalError(jobId, "Couldn't read the picked file: ${e.message}")
                }
            }, "RustDesk-ft-upload-$jobId").apply { isDaemon = true; start() }
        }

        override fun downloadFile(jobId: Int, remotePath: String, suggestedFileName: String, includeHidden: Boolean, toCustomDestination: Boolean) {
            if (!isCallerAuthorized()) return
            val tempFile = File(filesDir, "ft_download_$jobId")
            downloadTempFiles[jobId] = DownloadTempFile(tempFile, suggestedFileName, toCustomDestination)
            val started = NativeBridge.ftSendFiles(sessionId, jobId, tempFile.absolutePath, remotePath, false, includeHidden)
            if (!started) {
                downloadTempFiles.remove(jobId)
                reportLocalError(jobId, "Failed to start download")
            }
        }

        /** See [downloadFile]'s doc for [toCustomDestination] — grants the caller (the main app)
         *  read access to [tempFile] via [de.lobianco.saftssh.rustdesk.fileProviderAuthority]'s
         *  FileProvider, and remembers it in [pendingReleaseFiles] so [releaseDownloadedFile] can
         *  clean it up once the caller has finished reading it. Never auto-deletes on its own — the
         *  caller may need more than a moment to stream a large file. */
        private fun grantDownloadedFileUri(jobId: Int, tempFile: File): String? {
            return try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@RustDeskSessionService,
                    FILE_PROVIDER_AUTHORITY,
                    tempFile,
                )
                grantUriPermission("de.lobianco.saftssh", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                pendingReleaseFiles[jobId] = uri to tempFile
                uri.toString()
            } catch (e: Exception) {
                Log.w(TAG, "grantDownloadedFileUri($jobId) failed: ${e.javaClass.simpleName}: ${e.message}")
                tempFile.delete()
                null
            }
        }

        /** Uris/files handed back via [grantDownloadedFileUri], awaiting the caller's
         *  [releaseDownloadedFile] call. */
        private val pendingReleaseFiles = java.util.concurrent.ConcurrentHashMap<Int, Pair<Uri, File>>()

        override fun releaseDownloadedFile(jobId: Int) {
            if (!isCallerAuthorized()) return
            pendingReleaseFiles.remove(jobId)?.let { (uri, file) ->
                runCatching { revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                file.delete()
            }
        }

        /** Synthesizes an onJobEvent "error" for a failure that happens entirely on this side
         *  (local copy-in for an upload, before the native transfer even starts) — same channel a
         *  native-side transfer failure reports through, so the caller doesn't need two error
         *  paths. file_num=0 since this fails before any per-file accounting exists yet. */
        private fun reportLocalError(jobId: Int, message: String) {
            val json = JSONObject().apply {
                put("type", "error")
                put("id", jobId)
                put("file_num", 0)
                put("err", message)
            }.toString()
            runCatching { callback?.onJobEvent(json) }
                .onFailure { Log.w(TAG, "onJobEvent callback failed: ${it.message}") }
        }

        override fun answerOverrideConfirm(jobId: Int, fileNum: Int, overwrite: Boolean, remember: Boolean, isUpload: Boolean) {
            if (!isCallerAuthorized()) return
            NativeBridge.ftAnswerOverrideConfirm(sessionId, jobId, fileNum, overwrite, remember, isUpload)
        }

        override fun cancelJob(jobId: Int) {
            if (!isCallerAuthorized()) return
            NativeBridge.ftCancelJob(sessionId, jobId)
            uploadTempFiles.remove(jobId)?.delete()
            downloadTempFiles.remove(jobId)?.file?.delete()
            pendingReleaseFiles.remove(jobId)?.let { (uri, file) ->
                runCatching { revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                file.delete()
            }
        }

        override fun createRemoteDir(jobId: Int, path: String) {
            if (!isCallerAuthorized()) return
            NativeBridge.ftCreateRemoteDir(sessionId, jobId, path)
        }

        override fun removeRemoteFile(jobId: Int, path: String, isDir: Boolean) {
            if (!isCallerAuthorized()) return
            NativeBridge.ftRemoveRemoteFile(sessionId, jobId, path, isDir)
        }

        override fun renameRemoteFile(jobId: Int, path: String, newName: String) {
            if (!isCallerAuthorized()) return
            NativeBridge.ftRenameRemoteFile(sessionId, jobId, path, newName)
        }

        override fun destroy() {
            if (!isCallerAuthorized()) return
            destroyInternal()
        }

        /** See RustDeskSessionImpl.destroyInternal's doc for why this has no [isCallerAuthorized]
         *  check — the same split applies here. */
        fun destroyInternal() {
            running = false
            pollThread.interrupt()
            uploadTempFiles.values.forEach { it.delete() }
            uploadTempFiles.clear()
            downloadTempFiles.values.forEach { it.file.delete() }
            downloadTempFiles.clear()
            pendingReleaseFiles.values.forEach { (uri, file) ->
                runCatching { revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                file.delete()
            }
            pendingReleaseFiles.clear()
            NativeBridge.disconnect(sessionId)
            openFileTransferSessions.remove(this)
            demoteFromForegroundIfIdle()
        }
    }
}
