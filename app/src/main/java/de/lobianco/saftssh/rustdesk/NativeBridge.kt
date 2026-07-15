package de.lobianco.saftssh.rustdesk

/**
 * JNI bridge into `rust/android-shim` (see plans/soft-frolicking-thimble.md). Function names here
 * must match `Java_de_lobianco_saftssh_rustdesk_NativeBridge_<name>` in
 * rust/android-shim/src/lib.rs exactly.
 *
 * The native library isn't wired into this Gradle build yet (no rust-android-gradle-plugin task) —
 * until that's set up, copy
 * rust/android-shim/target/aarch64-linux-android/debug/libandroid_shim.so into
 * app/src/main/jniLibs/arm64-v8a/ by hand before building.
 */
object NativeBridge {
    init {
        System.loadLibrary("android_shim")
    }

    external fun coreVersion(): String

    /** Must be called once before [connect] — sets up Android logcat logging (debug builds: real
     *  logcat via android_logger; release builds: file-based, matching RustDesk's own upstream
     *  convention), the config home directory, and primes NAT-type/rendezvous-server latency
     *  detection. Mirrors what RustDesk's own Flutter app does once at startup via its
     *  main_init/main_set_home_dir bindings — our headless shim skipped this entirely before,
     *  which silently dropped all of RustDesk's own internal diagnostic logging and left
     *  Config::get_home()-dependent logic with no valid path. Safe to call more than once (and
     *  from multiple entry points in this process) — guarded by a Rust-side Once internally. */
    external fun initialize(filesDir: String)

    /** Self-hosted rendezvous/relay/API server + its public key. Empty string = leave that
     *  setting untouched. Call before [connect] to use a custom server instead of RustDesk's
     *  public default. */
    external fun setServerConfig(idServer: String, relayServer: String, apiServer: String, key: String)

    /** Returns a session id (UUID string) on success, or "ERR:<message>" on failure. */
    external fun connect(id: String, password: String): String

    /** Rough connectivity proxy — true once the session has produced at least one video frame.
     *  See IRustDeskSession.aidl's doc for why this isn't yet a proper connect/fail signal. */
    external fun isAlive(sessionId: String): Boolean

    /** [width, height] for `display`, or null if not known yet (poll again shortly — e.g. before
     *  the peer handshake completes). */
    external fun getDisplaySize(sessionId: String, display: Int): IntArray?

    /** Copies the next unread RGBA frame for `display` into a fresh byte array — R,G,B,A per
     *  pixel, which matches [android.graphics.Bitmap.Config.ARGB_8888]'s in-memory buffer layout,
     *  so it can be copied straight in via `Bitmap.copyPixelsFromBuffer`. Returns null if no new
     *  frame is ready yet (just poll again; this is normal, not an error). */
    external fun getFrame(sessionId: String, display: Int): ByteArray?

    external fun disconnect(sessionId: String)

    /** Mouse input. [mouseType] is "" (plain move), "down", "up", or "wheel"; [buttons] is "" or
     *  "left"/"right"/"wheel"/"back"/"forward" — matches `session_send_mouse`'s JSON `type`/
     *  `buttons` fields exactly. [x]/[y] are REMOTE framebuffer pixels — the caller must map from
     *  Surface pixels first (see the letterbox-fit math in [de.lobianco.saftssh.rustdesk.InfoActivity]). */
    external fun sendMouse(sessionId: String, x: Int, y: Int, mouseType: String, buttons: String)

    /** One named key press. [name] is RustDesk's own "VK_*" naming (e.g. "VK_BACK", "VK_RETURN",
     *  "VK_ESCAPE", "VK_TAB", "VK_LEFT"/"VK_UP"/"VK_RIGHT"/"VK_DOWN"). [press] = true sends a
     *  down+up pair in one call. */
    external fun inputKey(sessionId: String, name: String, down: Boolean, press: Boolean)

    /** Types a plain text string (e.g. from a soft-keyboard/IME commitText callback) — the remote
     *  side synthesizes the needed key events per character. */
    external fun inputString(sessionId: String, value: String)
}
