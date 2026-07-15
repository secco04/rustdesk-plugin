package de.lobianco.saftssh.rustdesk

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer

private const val PREF_ID_SERVER = "id_server"
private const val PREF_RELAY_SERVER = "relay_server"
private const val PREF_API_SERVER = "api_server"
private const val PREF_KEY = "key"
private const val PREF_REMOTE_ID = "remote_id"

/** Minimal launcher activity so the APK is launchable/Play-Store-acceptable — the plugin works
 *  entirely as a bound service in normal use. This also doubles as the throwaway test harness
 *  Milestone 2 called for (plans/soft-frolicking-thimble.md): it calls [NativeBridge] directly,
 *  bypassing the AIDL service entirely, since RustDeskSessionService only accepts the main
 *  LobiShell app as a caller and there's no other app to test against yet (Milestone 6). */
class InfoActivity : Activity() {

    private var sessionId: String? = null
    private val statusHandler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView

    // ── Persisted across restarts (see saveFields/loadFields) — server config + Remote ID, but
    // deliberately NOT the password (never written to disk from this throwaway test panel). ──────
    private lateinit var idServerField: EditText
    private lateinit var relayServerField: EditText
    private lateinit var apiServerField: EditText
    private lateinit var keyField: EditText
    private lateinit var idField: EditText
    private val prefs: SharedPreferences by lazy { getSharedPreferences("rustdesk_test_panel", MODE_PRIVATE) }

    // ── M3 video test viewport ──────────────────────────────────────────────
    @Volatile private var videoSurface: Surface? = null
    @Volatile private var videoRunning = false
    private var videoThread: Thread? = null

    // ── M4 input: Surface-pixel → remote-framebuffer-pixel mapping, set by blitToSurface on
    // every draw (mirrors remote-desktop-plugin's VncClient renderScale/renderOffsetX/Y). ──────
    @Volatile private var fbWidth = 0
    @Volatile private var fbHeight = 0
    @Volatile private var renderScale = 1f
    @Volatile private var renderOffsetX = 0f
    @Volatile private var renderOffsetY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeBridge.initialize(filesDir.absolutePath)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply { setColor(Color.rgb(18, 20, 28)) }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 39, 52))
                cornerRadius = 32f
            }
        }

        val title = TextView(this).apply {
            text = "LobiShell\nRustDesk Plugin"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        }

        val subtitle = TextView(this).apply {
            text = "RustDesk remote desktop engine (AGPL-3.0)"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(170, 180, 200))
            setPadding(0, 0, 0, 32)
        }

        val description = TextView(this).apply {
            text = """
                This plugin vendors RustDesk's own open-source client core
                (github.com/rustdesk/rustdesk, AGPL-3.0) to provide RustDesk
                remote-desktop connectivity for the LobiShell app, with
                LobiShell's own interface instead of RustDesk's.

                This plugin only works together with LobiShell in normal
                use. The test panel below talks to the native core directly,
                for verifying the connect path before LobiShell integration
                (Milestone 6) exists.
            """.trimIndent()
            textSize = 15f
            setTextColor(Color.rgb(220, 225, 235))
            setLineSpacing(8f, 1f)
        }

        val openAppButton = Button(this).apply {
            text = "Open LobiShell"
            setPadding(0, 24, 0, 0)
            setOnClickListener {
                val launchIntent = packageManager.getLaunchIntentForPackage("de.lobianco.saftssh")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this@InfoActivity, "LobiShell isn't installed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val badge = TextView(this).apply {
            text = "PLUGIN COMPONENT"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(120, 200, 255))
            setPadding(0, 32, 0, 0)
        }

        card.addView(title)
        card.addView(subtitle)
        card.addView(description)
        card.addView(openAppButton)
        card.addView(testPanel())
        card.addView(videoViewport())
        card.addView(keyboardPanel())
        card.addView(badge)

        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        super.onDestroy()
        statusHandler.removeCallbacksAndMessages(null)
        stopVideo()
        sessionId?.let { NativeBridge.disconnect(it) }
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    /** Persists the server config + Remote ID fields so they survive an app restart — deliberately
     *  NOT the password (never written to disk from this test panel). Called on Connect and on
     *  onPause, so values are saved even if the user navigates away without connecting. */
    private fun saveFields() {
        prefs.edit()
            .putString(PREF_ID_SERVER, idServerField.text.toString())
            .putString(PREF_RELAY_SERVER, relayServerField.text.toString())
            .putString(PREF_API_SERVER, apiServerField.text.toString())
            .putString(PREF_KEY, keyField.text.toString())
            .putString(PREF_REMOTE_ID, idField.text.toString())
            .apply()
    }

    private fun loadFields() {
        idServerField.setText(prefs.getString(PREF_ID_SERVER, ""))
        relayServerField.setText(prefs.getString(PREF_RELAY_SERVER, ""))
        apiServerField.setText(prefs.getString(PREF_API_SERVER, ""))
        keyField.setText(prefs.getString(PREF_KEY, ""))
        idField.setText(prefs.getString(PREF_REMOTE_ID, ""))
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(150, 160, 180))
        setPadding(0, 24, 0, 6)
    }

    private fun field(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(120, 128, 145))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        background = GradientDrawable().apply {
            setColor(Color.rgb(24, 27, 38))
            cornerRadius = 12f
        }
        setPadding(24, 16, 24, 16)
    }

    private fun testPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }

        panel.addView(sectionLabel("Custom server (optional — blank = RustDesk's public default)"))
        idServerField = field("ID/rendezvous server, e.g. my-server.example.com")
        relayServerField = field("Relay server (optional, defaults to ID server)")
        apiServerField = field("API server (optional)")
        keyField = field("Server public key")
        panel.addView(idServerField)
        panel.addView(relayServerField)
        panel.addView(apiServerField)
        panel.addView(keyField)

        panel.addView(sectionLabel("Connect"))
        idField = field("Remote ID")
        val passwordField = field("Password", password = true)
        panel.addView(idField)
        panel.addView(passwordField)

        loadFields()

        statusText = TextView(this).apply {
            text = "Not connected."
            textSize = 14f
            setTextColor(Color.rgb(170, 180, 200))
            setPadding(0, 16, 0, 16)
        }

        val connectButton = Button(this)
        val disconnectButton = Button(this).apply {
            text = "Disconnect"
            isEnabled = false
        }

        connectButton.apply {
            text = "Connect"
            setOnClickListener {
                saveFields()
                NativeBridge.setServerConfig(
                    idServerField.text.toString().trim(),
                    relayServerField.text.toString().trim(),
                    apiServerField.text.toString().trim(),
                    keyField.text.toString().trim(),
                )
                val id = idField.text.toString().trim()
                val password = passwordField.text.toString()
                statusText.text = "Connecting to $id ..."
                isEnabled = false
                Thread {
                    val result = NativeBridge.connect(id, password)
                    runOnUiThread {
                        isEnabled = true
                        if (result.startsWith("ERR:")) {
                            statusText.text = "Failed: ${result.removePrefix("ERR:")}"
                        } else {
                            sessionId = result
                            disconnectButton.isEnabled = true
                            statusText.text = "Session $result started — waiting for first video frame " +
                                "(isAlive() proxy; see NativeBridge's doc)..."
                            pollAlive()
                            startVideo(result)
                        }
                    }
                }.start()
            }
        }

        disconnectButton.setOnClickListener {
            sessionId?.let { NativeBridge.disconnect(it) }
            sessionId = null
            statusHandler.removeCallbacksAndMessages(null)
            stopVideo()
            disconnectButton.isEnabled = false
            statusText.text = "Disconnected."
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(connectButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(disconnectButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        panel.addView(buttonRow)
        panel.addView(statusText)

        return panel
    }

    private fun pollAlive() {
        val id = sessionId ?: return
        statusHandler.postDelayed({
            if (sessionId != id) return@postDelayed
            val alive = NativeBridge.isAlive(id)
            statusText.text = if (alive) {
                "Session $id: video frame received (connection is working)."
            } else {
                "Session $id: connecting / no video frame yet..."
            }
            pollAlive()
        }, 1000)
    }

    /** M3 (plans/soft-frolicking-thimble.md) test viewport: a fixed-height SurfaceView to prove the
     *  RGBA→Bitmap→Surface path works before wiring it into the AIDL contract / main app (M6) —
     *  same "throwaway test harness first" approach M2 used for connect. */
    private fun videoViewport(): SurfaceView {
        return SurfaceView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (280 * resources.displayMetrics.density).toInt()
            )
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    videoSurface = holder.surface
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    videoSurface = holder.surface
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    videoSurface = null
                }
            })
            setOnTouchListener { _, event -> handleTouch(event); true }
        }
    }

    /** M4: maps a touch event's Surface-local pixels back to remote framebuffer pixels using the
     *  letterbox geometry [blitToSurface] last computed, then sends it as a left-button mouse
     *  down/move/up — the same mapping VncClient.sendPointerEvent does for VNC. Single-finger drag
     *  only (no gestures/multi-touch yet); good enough to prove input round-trips end to end. */
    private fun handleTouch(event: MotionEvent) {
        val id = sessionId ?: return
        if (fbWidth <= 0 || fbHeight <= 0) return
        val fbX = ((event.x - renderOffsetX) / renderScale).toInt().coerceIn(0, fbWidth - 1)
        val fbY = ((event.y - renderOffsetY) / renderScale).toInt().coerceIn(0, fbHeight - 1)
        val type = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_MOVE -> ""
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> "up"
            else -> return
        }
        val buttons = if (type == "") "" else "left"
        // Called directly on the caller's thread (touch events already dispatch off the UI thread
        // here since this is a plain View.OnTouchListener callback on the main thread — a quick,
        // non-blocking JNI call, same as VncClient.sendPointerEvent does synchronously from its
        // Binder-thread caller). Spawning a Thread per touch event would let fast drags reorder at
        // the Rust side with no ordering guarantee between threads — worse than the small
        // in-line JNI cost.
        NativeBridge.sendMouse(id, fbX, fbY, type, buttons)
    }

    /** M4: a plain text field + "Send Text" button (routes through [NativeBridge.inputString], so
     *  ordinary typing needs no VK_*-per-character mapping) plus a row of special keys that DO need
     *  [NativeBridge.inputKey] — same split remote-desktop-plugin's own special-key bar uses. */
    private fun keyboardPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        panel.addView(sectionLabel("Keyboard (test)"))

        val textField = field("Type text, then tap Send")
        panel.addView(textField)
        val sendTextButton = Button(this).apply {
            text = "Send Text"
            setOnClickListener {
                val id = sessionId ?: return@setOnClickListener
                val text = textField.text.toString()
                if (text.isNotEmpty()) {
                    NativeBridge.inputString(id, text)
                    textField.text.clear()
                }
            }
        }
        panel.addView(sendTextButton)

        val specialKeys = listOf(
            "Esc" to "VK_ESCAPE",
            "Tab" to "VK_TAB",
            "Back" to "VK_BACK",
            "Enter" to "VK_RETURN",
            "←" to "VK_LEFT",
            "↑" to "VK_UP",
            "↓" to "VK_DOWN",
            "→" to "VK_RIGHT",
        )
        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        for ((label, vkName) in specialKeys) {
            keyRow.addView(
                Button(this).apply {
                    text = label
                    textSize = 12f
                    setOnClickListener {
                        val id = sessionId ?: return@setOnClickListener
                        NativeBridge.inputKey(id, vkName, true, true, false, false)
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        panel.addView(keyRow)

        return panel
    }

    /** Polls [NativeBridge.getDisplaySize] until the peer handshake has produced one, then polls
     *  [NativeBridge.getFrame] in a tight loop, copying each frame into a Bitmap and blitting it
     *  onto [videoSurface] — the same centred-letterbox pattern remote-desktop-plugin's
     *  VncClient.blitToSurface uses. */
    private fun startVideo(sessionId: String) {
        stopVideo()
        videoRunning = true
        videoThread = Thread({
            var currentBitmap: Bitmap? = null
            while (videoRunning) {
                val bitmap = currentBitmap ?: run {
                    val size = NativeBridge.getDisplaySize(sessionId, 0)
                    if (size == null || size.size != 2 || size[0] <= 0 || size[1] <= 0) {
                        Thread.sleep(200)
                        return@run null
                    }
                    val (w, h) = size[0] to size[1]
                    val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    currentBitmap = fresh
                    runOnUiThread { statusText.text = "Video: ${w}x$h — waiting for frames..." }
                    fresh
                } ?: continue
                val frame = NativeBridge.getFrame(sessionId, 0)
                if (frame == null) {
                    Thread.sleep(16)
                    continue
                }
                if (frame.size == bitmap.width * bitmap.height * 4) {
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame))
                    blitToSurface(bitmap)
                }
            }
        }, "RustDesk-video-$sessionId").apply { isDaemon = true; start() }
    }

    private fun stopVideo() {
        videoRunning = false
        videoThread?.interrupt()
        videoThread = null
    }

    private fun blitToSurface(bitmap: Bitmap) {
        val surface = videoSurface ?: return
        if (!surface.isValid) return
        try {
            val canvas: Canvas = surface.lockCanvas(null) ?: return
            try {
                val sw = canvas.width.toFloat()
                val sh = canvas.height.toFloat()
                val scale = minOf(sw / bitmap.width, sh / bitmap.height)
                val dw = bitmap.width * scale
                val dh = bitmap.height * scale
                val ox = (sw - dw) / 2f
                val oy = (sh - dh) / 2f
                fbWidth = bitmap.width
                fbHeight = bitmap.height
                renderScale = scale
                renderOffsetX = ox
                renderOffsetY = oy
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, null, RectF(ox, oy, ox + dw, oy + dh), null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (_: Exception) {
            // Surface torn down mid-blit (e.g. Activity backgrounded) — next frame just retries.
        }
    }
}
