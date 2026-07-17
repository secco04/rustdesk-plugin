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

    /** Pre-connect online check — queries the rendezvous server directly for whether [id] is
     *  currently reachable, no session needed. Call [setServerConfig] first if using a custom
     *  server. Blocks the calling thread for up to ~3s — never call from the main thread. Returns
     *  1 (online), 0 (offline), or -1 (unknown — treat as "try connecting anyway", not offline). */
    external fun checkOnline(id: String): Int

    /** Bidirectional clipboard sync (plain text only) — pushes local Android clipboard content so
     *  RustDesk's own existing outgoing-clipboard polling loop (already running, no extra setup
     *  needed) picks it up and sends it to the peer within ~333ms. NOT session-scoped — call
     *  whenever Android's clipboard changes. */
    external fun pushClipboardText(text: String)

    /** Bidirectional clipboard sync: last plain-text clipboard content received from the peer, or
     *  null if nothing new since the last call (clear-on-read — poll periodically). NOT
     *  session-scoped, same as [pushClipboardText]. */
    external fun pollRemoteClipboardText(): String?

    /** Quality/speed — [value] is "best", "balanced", or "low" (RustDesk's own image-quality
     *  values). Live — sends a message to the already-connected peer, no reconnect needed. */
    external fun setImageQuality(sessionId: String, value: String)

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

    /** The host's real mouse cursor shape (I-beam, resize arrows, …), or null if it hasn't changed
     *  since the last call (clear-on-read — poll alongside [getFrame] and only rebuild the Bitmap
     *  on a non-null result). Layout: 4 little-endian ints — width, height, hotx, hoty — followed
     *  by width * height * 4 bytes of RGBA pixels, matching
     *  [android.graphics.Bitmap.Config.ARGB_8888]'s in-memory layout like [getFrame]'s. */
    external fun pollCursor(sessionId: String): ByteArray?

    external fun disconnect(sessionId: String)

    /** Mouse input. [mouseType] is "" (plain move), "down", "up", or "wheel"; [buttons] is "" or
     *  "left"/"right"/"wheel"/"back"/"forward" — matches `session_send_mouse`'s JSON `type`/
     *  `buttons` fields exactly. [x]/[y] are REMOTE framebuffer pixels — the caller must map from
     *  Surface pixels first (see the letterbox-fit math in [de.lobianco.saftssh.rustdesk.InfoActivity]). */
    external fun sendMouse(sessionId: String, x: Int, y: Int, mouseType: String, buttons: String)

    /** One named key press. [name] is RustDesk's own "VK_*" naming (e.g. "VK_BACK", "VK_RETURN",
     *  "VK_ESCAPE", "VK_TAB", "VK_LEFT"/"VK_UP"/"VK_RIGHT"/"VK_DOWN"). [press] = true sends a
     *  down+up pair in one call. [ctrl]/[alt] set the keystroke's actual modifier flags — needed
     *  for printable-character taps, whose remote injection path ignores a separately-held
     *  modifier key entirely (see IRustDeskSession.inputKey's doc). */
    external fun inputKey(sessionId: String, name: String, down: Boolean, press: Boolean, ctrl: Boolean, alt: Boolean)

    /** Types a plain text string (e.g. from a soft-keyboard/IME commitText callback) — the remote
     *  side synthesizes the needed key events per character. */
    external fun inputString(sessionId: String, value: String)

    /** Number of displays (monitors) the peer reported, or 0 if not known yet. */
    external fun getDisplayCount(sessionId: String): Int

    /** Latest known connection stats (speed/fps/delay/target_bitrate/codec_format) as a JSON
     *  string — see IRustDeskSession.getQualityStatus's doc for the exact shape. */
    external fun pollQualityStatus(sessionId: String): String?

    /** Switches which single display (0-based) this session captures/views — fire-and-forget; the
     *  new display's size/first frame arrive through the normal getDisplaySize/getFrame polling. */
    external fun switchDisplay(sessionId: String, display: Int)

    // ── Privacy mode ─────────────────────────────────────────────────────────────────────────

    /** Whether the connected peer advertised privacy-mode support at all. */
    external fun isPrivacyModeSupported(sessionId: String): Boolean

    /** The privacy-mode implementation key to pass to [setPrivacyMode] — never null, an empty
     *  string means unsupported/not known yet. */
    external fun getDefaultPrivacyModeImpl(sessionId: String): String

    /** Turns privacy mode on/off for the peer's own physical display. Fire-and-forget — the
     *  peer's actual resulting state is read back separately via [isPrivacyModeOn]. */
    external fun setPrivacyMode(sessionId: String, implKey: String, on: Boolean)

    /** Current confirmed privacy-mode state (reflects the peer's own reported outcome, not just
     *  whether it was requested). */
    external fun isPrivacyModeOn(sessionId: String): Boolean

    // ── Audio playback ──────────────────────────────────────────────────────────────────────

    /** [sampleRate, channels], or null if the peer's audio format hasn't (re)negotiated since the
     *  last call — clear-on-change polling. Rebuild the [android.media.AudioTrack] on a non-null
     *  result (the format CAN change mid-session, e.g. the peer switching audio devices). */
    external fun pollAudioFormat(sessionId: String): IntArray?

    /** Drains up to [maxSamples] interleaved f32 PCM samples decoded so far (FIFO), or an empty
     *  (not null) array if none are pending yet — unlike the other poll functions, "nothing new"
     *  is the normal, frequent case here (audio arrives in small ~20ms frames), so there's no
     *  null/non-null distinction worth branching on every poll. */
    external fun pollAudioPcm(sessionId: String, maxSamples: Int): FloatArray?

    /** Mutes/unmutes incoming audio — wraps RustDesk's own "disable-audio" peer option. Idempotent
     *  on the native side (reads the current state first, only flips if it doesn't already match),
     *  so calling this redundantly is harmless. */
    external fun setAudioMuted(sessionId: String, muted: Boolean)

    external fun isAudioMuted(sessionId: String): Boolean

    // ---------------------------------------------------------------------------------------------
    // File transfer (bidirectional Android <-> remote host).
    //
    // File transfer runs on its OWN session, SEPARATE from [connect]'s control/video session (the
    // host drops file actions on a non-file-transfer connection). Open it with [connectFileTransfer]
    // and close it with the normal [disconnect]. Everything is asynchronous: browse/transfer calls
    // are fire-and-forget, and results come back through the ft*Poll* getters (clear-on-read, same
    // contract as [pollCursor]/[pollRemoteClipboardText]) — poll them on a timer.
    // ---------------------------------------------------------------------------------------------

    /** Opens a dedicated FILE-TRANSFER session to [id] + [password]. Returns the new session id
     *  (UUID string) on success, or "ERR:<message>" on failure. Separate from [connect]'s session,
     *  so both can be open to the same peer at once; close it with [disconnect]. */
    external fun connectFileTransfer(id: String, password: String): String

    /** Requests a remote directory listing (fire-and-forget). Poll [ftPollDirListing] for the
     *  result. [showHidden] includes hidden/dot entries. */
    external fun ftReadRemoteDir(sessionId: String, path: String, showHidden: Boolean)

    /** SYNCHRONOUS local (Android-side) filesystem listing — no session/network, so no session id.
     *  Returns JSON directly, or null on error (path missing/unreadable). Shape:
     *  `{"id":Int,"path":String,"entries":[{"entry_type":Int,"name":String,"size":Long,
     *  "modified_time":Long}]}`. `entry_type` is the FileType enum int: 0=Dir, 2=DirLink,
     *  3=DirDrive, 4=File, 5=FileLink. This is the upstream sync-local shape and (unlike
     *  [ftPollDirListing]) has no is_local/only_count/is_hidden fields. */
    external fun ftReadLocalDir(path: String, showHidden: Boolean): String?

    /** Drains one pending remote directory-listing result for this session (FIFO clear-on-read), or
     *  null if none pending. JSON shape: `{"id":Int,"path":String,"is_local":Boolean,
     *  "only_count":Boolean,"entries":[{"entry_type":Int,"name":String,"is_hidden":Boolean,
     *  "size":Long,"modified_time":Long}]}`. `entry_type` is the FileType enum int (0=Dir,
     *  2=DirLink, 3=DirDrive, 4=File, 5=FileLink). A `only_count:true` entry is a local read-job
     *  preview count, not a browsable listing. */
    external fun ftPollDirListing(sessionId: String): String?

    /** Drains one pending job-status event for this session (FIFO clear-on-read), or null if none.
     *  JSON is one of:
     *   `{"type":"progress","id":Int,"file_num":Int,"speed":Double,"finished_size":Double}`
     *   `{"type":"done","id":Int,"file_num":Int}`
     *   `{"type":"error","id":Int,"file_num":Int,"err":String}`
     *  `id` is the job id passed to [ftSendFiles]. Poll on a timer while any job is active. */
    external fun ftPollJobEvent(sessionId: String): String?

    /** Takes the pending overwrite/resume-confirm prompt for this session (clear-on-read), or null
     *  if none. The job STALLS until answered via [ftAnswerOverrideConfirm]. JSON shape:
     *  `{"id":Int,"file_num":Int,"to":String,"is_upload":Boolean,"is_identical":Boolean}` — `id` is
     *  the job id, `to` the destination path, `is_identical` true when the existing file looks
     *  byte-identical (safe to skip). At most one prompt is outstanding at a time. */
    external fun ftPollOverrideConfirm(sessionId: String): String?

    /** Answers a pending overwrite prompt (see [ftPollOverrideConfirm]). [overwrite] = true writes
     *  over the existing file, false skips it. [remember] applies the same choice to the rest of
     *  the job's files without re-prompting. [jobId]/[fileNum]/[isUpload] come from the prompt JSON
     *  (`id`/`file_num`/`is_upload`). */
    external fun ftAnswerOverrideConfirm(sessionId: String, jobId: Int, fileNum: Int, overwrite: Boolean, remember: Boolean, isUpload: Boolean)

    /** Starts a transfer job. [jobId] is a caller-chosen unique int that ties later [ftPollJobEvent]
     *  events back to this call. Upload ([isUpload] = true): reads [localPath], writes to
     *  [remotePath] on the host. Download ([isUpload] = false): reads [remotePath], writes to
     *  [localPath]. BOTH paths are REAL filesystem paths — RustDesk has no SAF/URI support, so stage
     *  a SAF-picked upload into app-private storage first, and copy a finished download out of
     *  app-private storage into MediaStore afterward. [includeHidden] applies when the path is a
     *  directory. Returns true if the job was dispatched. Outcome/progress via [ftPollJobEvent]. */
    external fun ftSendFiles(sessionId: String, jobId: Int, localPath: String, remotePath: String, isUpload: Boolean, includeHidden: Boolean): Boolean

    /** Cancels the job [jobId] (upload or download). No-op for an unknown/finished job. */
    external fun ftCancelJob(sessionId: String, jobId: Int)

    /** Creates a directory at the full remote [path] on the host (fire-and-forget). [jobId] ties
     *  any resulting job event back to this call. */
    external fun ftCreateRemoteDir(sessionId: String, jobId: Int, path: String)

    /** Removes a single remote file ([isDir] = false) or empty remote directory ([isDir] = true) at
     *  [path]. Recursive directory deletion is intentionally out of scope. [jobId] ties any
     *  resulting job event back to this call. */
    external fun ftRemoveRemoteFile(sessionId: String, jobId: Int, path: String, isDir: Boolean)

    /** Renames a remote entry at [path] to [newName] (a bare name, not a full path). [jobId] ties
     *  any resulting job event back to this call. Fire-and-forget. */
    external fun ftRenameRemoteFile(sessionId: String, jobId: Int, path: String, newName: String)
}
