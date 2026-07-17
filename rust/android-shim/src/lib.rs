// M2 (plans/soft-frolicking-thimble.md): minimal connect — Remote ID + password, no video/input
// yet (M3/M4). Uses flutter_ffi's already-JNI-friendly sync functions directly (session_add_sync,
// session_close), plus our own additive `librustdesk::flutter::session_start_headless` (see that
// function's doc comment for why `session_start` itself can't be called from a plain JNI caller
// with no Dart isolate).

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jfloatArray, jint, jintArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use uuid::Uuid;

/// A Rust panic unwinding across an `extern "C"`/`extern "system"` boundary is undefined behavior
/// — the Rust runtime detects it and calls `abort()`, killing the whole process (confirmed
/// on-device: an empty Remote ID crashed the app outright rather than returning "ERR:...").
/// Every JNI entry point below wraps its real logic in this so a panic anywhere in the vendored
/// RustDesk core (e.g. on malformed input) turns into a safe fallback value instead of a crash.
fn guard<F: FnOnce() -> R, R>(fallback: R, f: F) -> R {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(e) => {
            let msg = e
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| e.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".to_string());
            eprintln!("[android-shim] caught panic, returning fallback: {msg}");
            fallback
        }
    }
}

/// Must be called once before `connect()` (both `InfoActivity` and `RustDeskSessionService` call
/// it on their own `onCreate()` — safe to call from both, guarded by `Once` below, since they run
/// in the same process). Mirrors what RustDesk's own Flutter app does once at startup via its
/// `main_init`/`main_set_home_dir` FRB bindings — which our headless shim never called at all
/// before this, since we bypass the whole Flutter/Dart init path. That silently skipped:
/// - Android logcat logging: `main_init`'s Android branch calls `android_logger::init_once(...)`
///   in debug builds (tag "ffi", level Debug) or `hbb_common::init_log` (file-based, not logcat)
///   in release builds — gated on Rust's own `debug_assertions`, same convention as every other
///   `cargo build` vs `cargo build --release`. Without this, RustDesk's own extensive internal
///   `log::info!`/`log::warn!` calls (which cover the connection/video/decode pipeline in detail)
///   went nowhere, leaving us blind to what was actually happening during a slow/black connect.
/// - `config::APP_HOME_DIR` / `APP_DIR`: left empty, meaning `Config::get_home()`-dependent logic
///   (config file persistence, potentially parts of the connection handshake) had no valid path to
///   work with on Android — a very plausible contributor to the reported "very long black screen
///   before eventually showing a picture" (retries/fallbacks rather than a hard failure).
/// - `common::test_nat_type()`/`test_rendezvous_server()`: primes NAT-type detection and
///   rendezvous-server latency — RustDesk uses these to pick a connection strategy; without them
///   it has no NAT-type info at all, plausibly adding real delay before falling back to relay.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_initialize<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
) {
    guard((), move || {
        static INIT: std::sync::Once = std::sync::Once::new();
        let dir: String = env
            .get_string(&files_dir)
            .map(|s| s.into())
            .unwrap_or_default();
        INIT.call_once(|| {
            librustdesk::flutter_ffi::main_init(dir.clone(), String::new());
            librustdesk::flutter_ffi::main_set_home_dir(dir);
        });
    })
}

#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_coreVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    guard(std::ptr::null_mut(), move || {
        let version = librustdesk::VERSION;
        env.new_string(version)
            .expect("Couldn't create Java string")
            .into_raw()
    })
}

/// Sets a self-hosted rendezvous/relay/API server + its public key. Same option keys RustDesk's
/// own Settings > Network screen uses internally (`main_set_option` under the hood) — empty
/// strings leave that particular setting untouched (so passing "" for all four just uses whatever
/// was set before, or RustDesk's own public default servers if nothing was ever set). Call this
/// once before `connect` if you want a custom server instead of the public default.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_setServerConfig<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id_server: JString<'local>,
    relay_server: JString<'local>,
    api_server: JString<'local>,
    key: JString<'local>,
) {
    guard((), move || {
        let id_server: String = env.get_string(&id_server).map(|s| s.into()).unwrap_or_default();
        let relay_server: String = env.get_string(&relay_server).map(|s| s.into()).unwrap_or_default();
        let api_server: String = env.get_string(&api_server).map(|s| s.into()).unwrap_or_default();
        let key: String = env.get_string(&key).map(|s| s.into()).unwrap_or_default();

        if !id_server.is_empty() {
            librustdesk::flutter_ffi::main_set_option("custom-rendezvous-server".to_string(), id_server);
        }
        if !relay_server.is_empty() {
            librustdesk::flutter_ffi::main_set_option("relay-server".to_string(), relay_server);
        }
        if !api_server.is_empty() {
            librustdesk::flutter_ffi::main_set_option("api-server".to_string(), api_server);
        }
        if !key.is_empty() {
            librustdesk::flutter_ffi::main_set_option("key".to_string(), key);
        }
    })
}

/// Adds and starts a session for `id` (Remote ID) + `password`, using whatever server config was
/// last set via `setServerConfig` (or RustDesk's default public servers if that was never called).
/// Returns the session id as a UUID string on success, or "ERR:<message>" on failure (including a
/// caught panic — e.g. an empty/malformed Remote ID).
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_connect<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: JString<'local>,
    password: JString<'local>,
) -> jstring {
    guard(std::ptr::null_mut(), move || {
        let id: String = env
            .get_string(&id)
            .map(|s| s.into())
            .unwrap_or_default();
        let password: String = env
            .get_string(&password)
            .map(|s| s.into())
            .unwrap_or_default();

        let result = if id.is_empty() {
            "ERR:Remote ID must not be empty".to_string()
        } else {
            let session_id = Uuid::new_v4();
            let add_result = librustdesk::flutter_ffi::session_add_sync(
                session_id,
                id.clone(),
                false, // is_file_transfer
                false, // is_view_camera
                false, // is_port_forward
                false, // is_rdp (RustDesk's own connection-type flag, unrelated to Microsoft RDP)
                false, // is_terminal
                String::new(), // switch_uuid
                false, // force_relay
                password,
                false, // is_shared_password
                None,  // conn_token
            );
            if !add_result.0.is_empty() {
                format!("ERR:{}", add_result.0)
            } else {
                // Force VP9 before io_loop computes/sends our supported_decodings. On-device logcat
                // showed the host defaulting to AV1 (its "best" codec), which our stubbed aom.rs
                // can't decode — so every AV1 frame failed, the client renegotiated down to VP9
                // only after fail_counter hit 3, AND then had to wait for a fresh codec-switch
                // keyframe the peer sends on its own schedule (~50s observed) => "picture takes
                // forever". Persisting codec-preference=vp9 to this peer's PeerConfig here (exactly
                // what RustDesk's own header.tis codec-preference menu does via set_option) makes
                // the peer encode VP9 from frame 1 — no AV1 attempt, no codec switch, and the
                // stream's very first frame is already a decodable VP9 keyframe. "vp9" (not h264/
                // h265) because we have no hardware/mediacodec decoder yet — VP9 is our best
                // software-decodable option (av1 stubbed, h264/h265 need mediacodec). Set BEFORE
                // session_start_headless so the initial capabilities carry the preference.
                librustdesk::flutter_ffi::session_peer_option(
                    session_id,
                    "codec-preference".to_string(),
                    "vp9".to_string(),
                );
                // Negotiate the host's real mouse cursor ON, before session_start_headless builds
                // the login request. client.rs's login builder only sets
                // `msg.show_remote_cursor = Yes` when `get_toggle_option("show-remote-cursor")`
                // is true — we're not view_only and the default is false, so the host would never
                // send CursorData/CursorId at all and pollCursor would stay empty forever.
                //
                // NOTE this is deliberately NOT session_peer_option like codec-preference above:
                // session_peer_option -> set_option only inserts into PeerConfig's `options`
                // string map, whereas get_toggle_option("show-remote-cursor") reads the separate
                // typed `config.show_remote_cursor.v` bool — so a peer_option here would be
                // silently ignored. session_toggle_option -> LoginConfigHandler::toggle_option is
                // the only writer of that field.
                //
                // toggle_option TOGGLES (`v = !v`) and persists to the peer's PeerConfig on disk,
                // so a blind toggle would turn the cursor back OFF on the next connect to the
                // same peer. Read first and only flip when actually off, making this idempotent.
                if librustdesk::flutter_ffi::session_get_toggle_option(
                    session_id,
                    "show-remote-cursor".to_string(),
                ) != Some(true)
                {
                    librustdesk::flutter_ffi::session_toggle_option(
                        session_id,
                        "show-remote-cursor".to_string(),
                    );
                }
                match librustdesk::flutter::session_start_headless(&session_id, &id) {
                    Ok(()) => session_id.to_string(),
                    Err(e) => format!("ERR:{}", e),
                }
            }
        };
        env.new_string(result)
            .expect("Couldn't create Java string")
            .into_raw()
    })
}

/// Pre-connect online check — queries the rendezvous server directly for whether `id` is
/// currently reachable, without opening a session. Call `setServerConfig` first if using a custom
/// server (same as before `connect`). Blocks the calling thread for up to ~3s — call off the UI
/// thread. Returns 1 (online), 0 (offline), or -1 (unknown — the query itself failed, e.g. no
/// network; callers should treat this as "proceed with the normal connect attempt anyway", not as
/// a definite offline).
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_checkOnline<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: JString<'local>,
) -> jint {
    guard(-1, move || {
        let id: String = env.get_string(&id).map(|s| s.into()).unwrap_or_default();
        if id.is_empty() {
            return -1;
        }
        librustdesk::flutter::session_is_id_online(id)
    })
}

/// Bidirectional clipboard sync (plain text only): pushes local Android clipboard text so RustDesk's
/// own existing outgoing-clipboard polling loop (spawned automatically as part of normal peer-info
/// handling — not Flutter/Dart-isolate-specific, so it already runs for a headless session) picks
/// it up and sends it to the peer within ~333ms. Call this whenever Android's clipboard changes
/// (e.g. an OnPrimaryClipChangedListener). Not session-scoped — RustDesk's own buffer this feeds
/// isn't either.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_pushClipboardText<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) {
    guard((), move || {
        let text: String = env.get_string(&text).map(|s| s.into()).unwrap_or_default();
        scrap::android::ffi::push_outgoing_clipboard_text(text);
    })
}

/// Bidirectional clipboard sync: returns the last plain-text clipboard content received from the
/// peer, or null if nothing new since the last call (clear-on-read — poll this periodically, e.g.
/// alongside getFrame). Not session-scoped, same as pushClipboardText.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_pollRemoteClipboardText<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    guard(std::ptr::null_mut(), move || {
        match scrap::android::ffi::take_remote_clipboard_text() {
            Some(text) => env
                .new_string(text)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    })
}

/// Rough connectivity proxy for M2: true once the session has produced at least one video frame.
/// There is no push-based "connected" event available without a real Dart isolate (see
/// session_start_headless's doc comment) — a proper sync connection-state getter is still an open
/// item, to be refined once this is actually exercised against a real host device.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_isAlive<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jboolean {
    guard(JNI_FALSE, move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return JNI_FALSE;
        };
        if librustdesk::flutter_ffi::session_get_rgba_size(session_id, 0).0 > 0 {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

/// Quality/speed control — `value` is one of "best", "balanced", or "low" (RustDesk's own
/// `client.rs::get_image_quality_enum` values; anything else is silently ignored on the Rust
/// side). Wraps `flutter_ffi::session_set_image_quality`, which sends a live message to the
/// already-connected peer — no reconnect needed, same as `codec-preference` in `connect()`.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_setImageQuality<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    value: JString<'local>,
) {
    guard((), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return;
        };
        let value: String = env.get_string(&value).map(|s| s.into()).unwrap_or_default();
        librustdesk::flutter_ffi::session_set_image_quality(session_id, value);
    })
}

/// M3 (plans/soft-frolicking-thimble.md): [width, height] for `display`, or null if unknown yet
/// (e.g. before the peer handshake completes — poll again shortly).
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_getDisplaySize<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    display: jint,
) -> jintArray {
    guard(std::ptr::null_mut(), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return std::ptr::null_mut();
        };
        let (w, h) = librustdesk::flutter::session_get_display_size(session_id, display as usize);
        if w <= 0 || h <= 0 {
            return std::ptr::null_mut();
        }
        let arr = match env.new_int_array(2) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
        if env.set_int_array_region(&arr, 0, &[w, h]).is_err() {
            return std::ptr::null_mut();
        }
        arr.into_raw()
    })
}

/// M3: copies the next unread RGBA frame for `display` into a fresh Java byte array and marks it
/// consumed (`session_next_rgba`) so the decoder can write the following one. Returns null if no
/// new frame is ready (caller just polls again) — unlike `isAlive`'s rgba-size check (which
/// ignores the valid/unread flag), this is the real "there is a fresh frame" signal.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_getFrame<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    display: jint,
) -> jbyteArray {
    guard(std::ptr::null_mut(), move || {
        let session_id_str: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id_str) else {
            return std::ptr::null_mut();
        };
        let display = display as usize;
        let size = librustdesk::flutter_ffi::session_get_rgba_size(session_id, display).0;
        if size == 0 {
            return std::ptr::null_mut();
        }
        let Ok(c_session_id) = std::ffi::CString::new(session_id_str) else {
            return std::ptr::null_mut();
        };
        let ptr =
            librustdesk::flutter::session_get_rgba(c_session_id.as_ptr() as *const _, display);
        if ptr.is_null() {
            // Not a fresh frame right now (rgba_valid == false) — normal while waiting for the
            // next decoded frame, not an error.
            return std::ptr::null_mut();
        }
        let bytes: &[u8] = unsafe { std::slice::from_raw_parts(ptr, size) };
        let result = match env.byte_array_from_slice(bytes) {
            Ok(r) => r,
            Err(_) => return std::ptr::null_mut(),
        };
        librustdesk::flutter::session_next_rgba(session_id, display);
        result.into_raw()
    })
}

/// Host cursor shape. Returns the CURRENT remote cursor, but only if it changed since the last
/// call — null otherwise (clear-on-read polling, same contract as `pollRemoteClipboardText` and
/// `getFrame`), so the caller can poll this on its video-pump loop and only rebuild its Bitmap on
/// a non-null result. Also null before the host has sent any cursor, and when a `CursorId`
/// referenced a shape we don't have cached (evicted or never received) rather than returning
/// garbage.
///
/// Layout of the returned array: 4 little-endian i32s — `width`, `height`, `hotx`, `hoty` (16
/// bytes) — followed by exactly `width * height * 4` bytes of RGBA pixel data. RGBA matches
/// Android's `Bitmap.Config.ARGB_8888` in-memory byte order, so no swizzle is needed.
///
/// `session_id` is validated as a UUID for symmetry with the other exports, but the underlying
/// cursor cache in `librustdesk::flutter` is GLOBAL, not per-session (see `take_headless_cursor`'s
/// doc for why) — with one active session at a time this is equivalent.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_pollCursor<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jbyteArray {
    guard(std::ptr::null_mut(), move || {
        let session_id_str: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        if Uuid::parse_str(&session_id_str).is_err() {
            return std::ptr::null_mut();
        }
        match librustdesk::flutter::take_headless_cursor() {
            Some(bytes) => env
                .byte_array_from_slice(&bytes)
                .map(|r| r.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    })
}

/// Auto quality / connection stats (plans/soft-frolicking-thimble.md): the latest known connection
/// stats as a JSON string — see `librustdesk::flutter::take_headless_quality_status`'s doc for the
/// exact shape. Always non-null (an empty `"{}"` object, never `null`, when nothing is known yet)
/// since "no stats yet" is a normal, expected state for the first second or two of a session, not
/// an error the caller needs to branch on separately from "stats with some fields missing".
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_pollQualityStatus<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jstring {
    guard(std::ptr::null_mut(), move || {
        let session_id_str: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        if Uuid::parse_str(&session_id_str).is_err() {
            return std::ptr::null_mut();
        }
        let json = librustdesk::flutter::take_headless_quality_status();
        env.new_string(json)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    })
}

// ---------------------------------------------------------------------------------------------
// Audio (plans/soft-frolicking-thimble.md, "Audio" round): playback. `AudioHandler` (client.rs)
// decodes each incoming frame on its own dedicated thread — already headless-safe, no push_event
// involved (see that file's comments) — and pushes format/PCM into `librustdesk::flutter`'s
// global poll cache (same "global rather than per-session" tradeoff as the cursor/clipboard
// caches). These two exports drain it for the Kotlin side to feed an `android.media.AudioTrack`.
// ---------------------------------------------------------------------------------------------

/// [sampleRate, channels], or null if the format hasn't (re)negotiated since the last call —
/// clear-on-change polling, same contract as `pollCursor`. The caller should (re)build its
/// `AudioTrack` on a non-null result.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_pollAudioFormat<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jintArray {
    guard(std::ptr::null_mut(), move || {
        let session_id_str: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        if Uuid::parse_str(&session_id_str).is_err() {
            return std::ptr::null_mut();
        }
        let Some((sample_rate, channels)) = librustdesk::flutter::take_headless_audio_format() else {
            return std::ptr::null_mut();
        };
        let arr = match env.new_int_array(2) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
        if env
            .set_int_array_region(&arr, 0, &[sample_rate as jint, channels as jint])
            .is_err()
        {
            return std::ptr::null_mut();
        }
        arr.into_raw()
    })
}

/// Drains up to `maxSamples` interleaved f32 PCM samples decoded so far (FIFO order), or an empty
/// (not null) array if none are pending yet — unlike the other poll exports, "nothing new" is a
/// perfectly normal, frequent state here (audio arrives in small frequent frames), so it's not
/// worth a null/non-null distinction the caller would have to branch on every ~20ms.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_pollAudioPcm<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    max_samples: jint,
) -> jfloatArray {
    guard(std::ptr::null_mut(), move || {
        let session_id_str: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        if Uuid::parse_str(&session_id_str).is_err() {
            return std::ptr::null_mut();
        }
        let samples = librustdesk::flutter::take_headless_audio_pcm(max_samples.max(0) as usize);
        let arr = match env.new_float_array(samples.len() as i32) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
        if !samples.is_empty() && env.set_float_array_region(&arr, 0, &samples).is_err() {
            return std::ptr::null_mut();
        }
        arr.into_raw()
    })
}

/// Mute toggle — wraps `session_toggle_option`/`session_get_toggle_option("disable-audio")`
/// idempotently (read first, only flip if the current state doesn't already match `muted`), same
/// pattern `setCursorOptions`'s `show-remote-cursor` handling established: `toggle_option` really
/// does toggle-and-persist, so a blind call on every UI open would silently flip a user's earlier
/// choice back on the next connect to the same peer.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_setAudioMuted<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    muted: jboolean,
) {
    guard((), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return;
        };
        let want_muted = muted == JNI_TRUE;
        if librustdesk::flutter_ffi::session_get_toggle_option(session_id, "disable-audio".to_string())
            != Some(want_muted)
        {
            librustdesk::flutter_ffi::session_toggle_option(session_id, "disable-audio".to_string());
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_isAudioMuted<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jboolean {
    guard(JNI_FALSE, move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return JNI_FALSE;
        };
        if librustdesk::flutter_ffi::session_get_toggle_option(session_id, "disable-audio".to_string())
            == Some(true)
        {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

// ---------------------------------------------------------------------------------------------
// Privacy mode (plans/soft-frolicking-thimble.md): blanks the peer's own physical display while
// connected, so someone standing at the host can't see what's being done remotely. The toggle and
// state-read primitives already existed fully in upstream (`session_toggle_privacy_mode`,
// `session_get_toggle_option("privacy-mode")` — the SAME generic function `isAudioMuted` above
// already uses for a different option name) — only the two "does the peer even support this, and
// with which impl" getters were missing headless, added to flutter.rs this round.
// ---------------------------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_isPrivacyModeSupported<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jboolean {
    guard(JNI_FALSE, move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return JNI_FALSE;
        };
        if librustdesk::flutter::session_is_privacy_mode_supported(session_id) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

/// The privacy-mode implementation key to use for this peer (see
/// `librustdesk::flutter::session_default_privacy_mode_impl`'s doc), or an empty string if the
/// peer doesn't support privacy mode / hasn't been parsed yet — never null, so the caller can
/// treat "unsupported" and "not known yet" identically without a separate null check.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_getDefaultPrivacyModeImpl<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jstring {
    guard(std::ptr::null_mut(), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return std::ptr::null_mut();
        };
        let impl_key = librustdesk::flutter::session_default_privacy_mode_impl(session_id);
        env.new_string(impl_key)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    })
}

/// Turns privacy mode on/off for the peer's own physical display. [implKey] should come from
/// [getDefaultPrivacyModeImpl] — sending an impl_key the peer doesn't actually support is a no-op
/// on the peer's side (it just reports back "not supported" via its own state, doesn't error).
/// Fire-and-forget: the peer's ACTUAL resulting state (it can take a moment, or fail — e.g. no
/// permission on the host) is read back separately via [isPrivacyModeOn].
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_setPrivacyMode<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    impl_key: JString<'local>,
    on: jboolean,
) {
    guard((), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return;
        };
        let impl_key: String = env
            .get_string(&impl_key)
            .map(|s| s.into())
            .unwrap_or_default();
        librustdesk::flutter_ffi::session_toggle_privacy_mode(session_id, impl_key, on == JNI_TRUE);
    })
}

/// Current confirmed privacy-mode state — reflects the peer's own reported outcome (see
/// `client::io_loop`'s `handle_back_msg_privacy_mode`), not just "did we ask for it", so a
/// rejected/failed request correctly reads back false rather than whatever was last requested.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_isPrivacyModeOn<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jboolean {
    guard(JNI_FALSE, move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return JNI_FALSE;
        };
        if librustdesk::flutter_ffi::session_get_toggle_option(session_id, "privacy-mode".to_string())
            == Some(true)
        {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

/// Whether the peer advertised a camera to view (`PeerInfo.platform_additions`'s own
/// `"support_view_camera"` flag). Call this on the CONTROL session (the one from `connect()`) once
/// connected — it decides whether to show a "View Camera" entry point at all before opening the
/// dedicated view-camera session via `connectViewCamera`.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_isViewCameraSupported<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jboolean {
    guard(JNI_FALSE, move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return JNI_FALSE;
        };
        if librustdesk::flutter::session_is_view_camera_supported(session_id) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

/// M4 (plans/soft-frolicking-thimble.md): mouse input. `mouseType` is one of "" (plain move),
/// "down", "up", "wheel" — matches `flutter_ffi::session_send_mouse`'s JSON `type` field exactly
/// (see that function's `match` for the full list; unrecognized strings are silently ignored on
/// the Rust side, same as upstream's own Flutter client would send). `buttons` is "" (none). "left",
/// "right", "wheel", "back", or "forward". `x`/`y` are REMOTE framebuffer pixels, not Surface
/// pixels — the caller is responsible for the letterbox/zoom inverse mapping (see
/// remote-desktop-plugin's VncClient.sendPointerEvent for the established pattern).
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_sendMouse<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    x: jint,
    y: jint,
    mouse_type: JString<'local>,
    buttons: JString<'local>,
) {
    guard((), move || {
        let session_id_str: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id_str) else {
            return;
        };
        let mouse_type: String = env
            .get_string(&mouse_type)
            .map(|s| s.into())
            .unwrap_or_default();
        let buttons: String = env
            .get_string(&buttons)
            .map(|s| s.into())
            .unwrap_or_default();
        let mut obj = serde_json::Map::new();
        obj.insert("x".to_string(), serde_json::Value::String(x.to_string()));
        obj.insert("y".to_string(), serde_json::Value::String(y.to_string()));
        if !mouse_type.is_empty() {
            obj.insert("type".to_string(), serde_json::Value::String(mouse_type));
        }
        if !buttons.is_empty() {
            obj.insert("buttons".to_string(), serde_json::Value::String(buttons));
        }
        let msg = serde_json::Value::Object(obj).to_string();
        librustdesk::flutter_ffi::session_send_mouse(session_id, msg);
    })
}

/// M4: one named key press/release. `name` is RustDesk's own `VK_*` naming convention (see
/// `client.rs`'s `KEY_MAP` for the full table — e.g. "VK_BACK", "VK_RETURN", "VK_ESCAPE", "VK_TAB",
/// "VK_LEFT"/"VK_UP"/"VK_RIGHT"/"VK_DOWN", "VK_A".."VK_Z"/"VK_0".."VK_9"). `press` = true sends a
/// down+up pair in one call (used for the special-key bar); false requires a separate down/up call
/// each. `ctrl`/`alt` are forwarded into `session_input_key`'s own modifier flags — NOT redundant
/// with a separately-sent "VK_CONTROL down" event: RustDesk's key-injection path
/// (`keyboard::client::legacy_modifiers`) sets the actual KeyEvent's modifier bits from these
/// params directly, and for printable characters (`Key::Chr`/`Key::_Raw` — i.e. any VK_A.."VK_Z"/
/// VK_0.."VK_9" tap) the remote synthesizes the keystroke via Unicode/character injection, which
/// bypasses OS-level modifier-key tracking entirely — a separately-held Ctrl key has NO effect on
/// it. Confirmed on-device: Ctrl-latched + typed "a" produced a literal "a" on the remote, not
/// Ctrl+A, until these flags were threaded through (previously hardcoded to false here).
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_inputKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    name: JString<'local>,
    down: jboolean,
    press: jboolean,
    ctrl: jboolean,
    alt: jboolean,
) {
    guard((), move || {
        let session_id_str: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id_str) else {
            return;
        };
        let name: String = env.get_string(&name).map(|s| s.into()).unwrap_or_default();
        librustdesk::flutter_ffi::session_input_key(
            session_id,
            name,
            down == JNI_TRUE,
            press == JNI_TRUE,
            alt == JNI_TRUE,
            ctrl == JNI_TRUE,
            false,
            false,
        );
    })
}

/// M4: types a plain text string (e.g. from a soft-keyboard/IME commitText callback) — the remote
/// side synthesizes the necessary key events per character, so this avoids reimplementing a full
/// unicode-to-VK_* mapping table for ordinary typing.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_inputString<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    value: JString<'local>,
) {
    guard((), move || {
        let session_id_str: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id_str) else {
            return;
        };
        let value: String = env.get_string(&value).map(|s| s.into()).unwrap_or_default();
        librustdesk::flutter_ffi::session_input_string(session_id, value);
    })
}

/// Multi-monitor (lobishell-android): number of displays the peer reported, or 0 if not known yet
/// (poll again shortly after connect — same "before the peer handshake completes" caveat as
/// getDisplaySize).
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_getDisplayCount<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jint {
    guard(0, move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return 0;
        };
        librustdesk::flutter::session_get_display_count(session_id)
    })
}

/// Multi-monitor (lobishell-android): switches which single display this session captures/views.
/// `is_desktop = false` in the underlying call (matches RustDesk's own mobile/viewer-style client,
/// not its multi-window desktop client) — appropriate here since we only ever render one display
/// at a time, never several side by side. Fire-and-forget: the peer's reply (new size, first frame
/// of the new display) arrives through the same getDisplaySize/getFrame polling path as a fresh
/// connect: getDisplaySize returns 0x0 again until the peer_info catches up.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_switchDisplay<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    display: jint,
) {
    guard((), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return;
        };
        librustdesk::flutter_ffi::session_switch_display(false, session_id, vec![display]);
    })
}

#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_disconnect<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) {
    guard((), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        if let Ok(session_id) = Uuid::parse_str(&session_id) {
            librustdesk::flutter_ffi::session_close(session_id);
        }
    })
}

// ---------------------------------------------------------------------------------------------
// File transfer (bidirectional Android <-> remote host).
//
// File transfer needs a SECOND, fully separate session from the control/video one: RustDesk keys
// sessions by (peer_id, ConnType) and the HOST silently drops FileAction messages on any
// connection that didn't identify itself as ConnType::FILE_TRANSFER at login. So this uses its
// own SessionID (fresh Uuid) and calls session_add_sync with is_file_transfer = true.
//
// All the actual protocol/algorithm already exists in flutter_ffi + the blanket
// `impl FileManager for Session<T>` — these exports are thin wrappers. Results (dir listings, job
// progress/done/error, overwrite prompts) come back asynchronously through the poll caches added
// in librustdesk::flutter (ft_take_*), since our headless session never sets an event_stream.
// Poll them the same clear-on-read way as pollCursor/pollRemoteClipboardText.
// ---------------------------------------------------------------------------------------------

/// Opens a dedicated FILE-TRANSFER session to `id` (Remote ID) + `password`. Returns the new
/// session id as a UUID string on success, or "ERR:<message>" on failure. This is a SEPARATE
/// session from `connect()`'s control/video session (different ConnType), so a device can have
/// both open to the same peer at once; disconnect it with the normal `disconnect(sessionId)`.
///
/// Unlike `connect()`, this deliberately does NOT poke codec-preference or show-remote-cursor —
/// RustDesk sends no video/cursor on a file-transfer connection (get_option_message returns None
/// for ConnType::FILE_TRANSFER), so those would be meaningless here.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_connectFileTransfer<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: JString<'local>,
    password: JString<'local>,
) -> jstring {
    guard(std::ptr::null_mut(), move || {
        let id: String = env.get_string(&id).map(|s| s.into()).unwrap_or_default();
        let password: String = env
            .get_string(&password)
            .map(|s| s.into())
            .unwrap_or_default();

        let result = if id.is_empty() {
            "ERR:Remote ID must not be empty".to_string()
        } else {
            let session_id = Uuid::new_v4();
            let add_result = librustdesk::flutter_ffi::session_add_sync(
                session_id,
                id.clone(),
                true,  // is_file_transfer -> ConnType::FILE_TRANSFER
                false, // is_view_camera
                false, // is_port_forward
                false, // is_rdp
                false, // is_terminal
                String::new(), // switch_uuid
                false, // force_relay
                password,
                false, // is_shared_password
                None,  // conn_token
            );
            if !add_result.0.is_empty() {
                format!("ERR:{}", add_result.0)
            } else {
                match librustdesk::flutter::session_start_headless(&session_id, &id) {
                    Ok(()) => session_id.to_string(),
                    Err(e) => format!("ERR:{}", e),
                }
            }
        };
        env.new_string(result)
            .expect("Couldn't create Java string")
            .into_raw()
    })
}

/// Requests a remote directory listing (fire-and-forget). The result is delivered asynchronously —
/// poll `ftPollDirListing(sessionId)` for it. `showHidden` includes dotfiles/hidden entries.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_ftReadRemoteDir<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    path: JString<'local>,
    show_hidden: jboolean,
) {
    guard((), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return;
        };
        let path: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
        librustdesk::flutter_ffi::session_read_remote_dir(session_id, path, show_hidden == JNI_TRUE);
    })
}

/// SYNCHRONOUS local (Android-side) directory listing — reads the real filesystem directly, no
/// session/network involved (hence no session id param). Returns a JSON string, or null on error
/// (path missing/unreadable). JSON shape: `{"id":<int>,"path":"<str>","entries":[{"entry_type":
/// <int>,"name":"<str>","size":<u64>,"modified_time":<u64>}]}` (same shape RustDesk's own
/// make_fd_to_json emits; entry_type is the FileType enum int: 0=Dir,2=DirLink,3=DirDrive,4=File,
/// 5=FileLink). Note this listing has NO "is_local"/"only_count"/"is_hidden" fields — it's the
/// upstream sync-local format, distinct from the async ftPollDirListing JSON shape.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_ftReadLocalDir<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    show_hidden: jboolean,
) -> jstring {
    guard(std::ptr::null_mut(), move || {
        let path: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
        // session_read_local_dir_sync ignores its session id (pure fs access); pass a nil Uuid.
        let json = librustdesk::flutter_ffi::session_read_local_dir_sync(
            Uuid::nil(),
            path,
            show_hidden == JNI_TRUE,
        );
        if json.is_empty() {
            return std::ptr::null_mut();
        }
        env.new_string(json)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    })
}

/// Drains one pending remote directory-listing result for this session (FIFO clear-on-read), or
/// null if none pending. JSON shape: `{"id":<int>,"path":"<str>","is_local":<bool>,"only_count":
/// <bool>,"entries":[{"entry_type":<int>,"name":"<str>","is_hidden":<bool>,"size":<u64>,
/// "modified_time":<u64>}]}`. `entry_type` is the FileType enum int (0=Dir,2=DirLink,3=DirDrive,
/// 4=File,5=FileLink). `is_local`/`only_count` are usually false/false for a remote listing; a
/// `only_count:true` entry is a local read-job preview count, not a browsable listing.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_ftPollDirListing<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jstring {
    guard(std::ptr::null_mut(), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return std::ptr::null_mut();
        };
        match librustdesk::flutter::ft_take_dir_listing(&session_id) {
            Some(json) => env
                .new_string(json)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    })
}

/// Drains one pending job-status event for this session (FIFO clear-on-read), or null if none.
/// JSON is one of:
///   `{"type":"progress","id":<int>,"file_num":<int>,"speed":<f64>,"finished_size":<f64>}`
///   `{"type":"done","id":<int>,"file_num":<int>}`
///   `{"type":"error","id":<int>,"file_num":<int>,"err":"<str>"}`
/// `id` is the job id passed to `ftSendFiles`. A multi-file job emits per-file done/error plus
/// periodic progress ticks. Poll this on a timer while any job is active.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_ftPollJobEvent<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jstring {
    guard(std::ptr::null_mut(), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return std::ptr::null_mut();
        };
        match librustdesk::flutter::ft_take_job_event(&session_id) {
            Some(json) => env
                .new_string(json)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    })
}

/// Takes the pending overwrite/resume-confirm prompt for this session (clear-on-read), or null if
/// none pending. The job STALLS until answered via `ftAnswerOverrideConfirm`. JSON shape:
/// `{"id":<int>,"file_num":<int>,"to":"<str>","is_upload":<bool>,"is_identical":<bool>}` — `id` is
/// the job id, `to` the destination path already present, `is_identical` true when the existing
/// file looks byte-identical (safe to skip). Only one prompt is outstanding at a time.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_ftPollOverrideConfirm<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
) -> jstring {
    guard(std::ptr::null_mut(), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return std::ptr::null_mut();
        };
        match librustdesk::flutter::ft_take_override_confirm(&session_id) {
            Some(json) => env
                .new_string(json)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    })
}

/// Answers a pending overwrite prompt (see `ftPollOverrideConfirm`). `overwrite = true` writes over
/// the existing file (resume-from-start), `false` skips this file. `remember` applies the same
/// choice to the rest of the files in the job without prompting again. `jobId`/`fileNum`/`isUpload`
/// come straight from the prompt's JSON (`id`/`file_num`/`is_upload`).
///
/// NOTE: deviates from the originally-sketched `(jobId, skip, offsetBlock)` shape — the real
/// underlying call is `session_set_confirm_override_file(session, act_id, file_num, need_override,
/// remember, is_upload)`. There is no caller-supplied offset in this code path (upstream always
/// sends OffsetBlk(0) when overwriting), and `file_num`/`is_upload` are required, so this exposes
/// the real parameters instead. `skip` == `!overwrite`.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_ftAnswerOverrideConfirm<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    job_id: jint,
    file_num: jint,
    overwrite: jboolean,
    remember: jboolean,
    is_upload: jboolean,
) {
    guard((), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return;
        };
        librustdesk::flutter_ffi::session_set_confirm_override_file(
            session_id,
            job_id,
            file_num,
            overwrite == JNI_TRUE,
            remember == JNI_TRUE,
            is_upload == JNI_TRUE,
        );
    })
}

/// Starts a transfer job. `jobId` is a caller-chosen i32 that ties later job events back to this
/// call (keep it unique per active job). Uploads read `localPath` and write it to `remotePath` on
/// the host; downloads read `remotePath` and write it to `localPath`. BOTH directions use real
/// filesystem paths — RustDesk's fs layer has no SAF/URI support, so the Kotlin side must stage a
/// SAF-picked upload into app-private storage first, and copy a finished download out of
/// app-private storage into MediaStore afterward. `includeHidden` includes hidden files when the
/// path is a directory. Returns true if the job was dispatched (valid session id + inputs), false
/// otherwise. Job outcome/progress arrives via `ftPollJobEvent`.
///
/// (Uses `session_send_files`, which both creates the job AND sends the network start message —
/// `session_add_job` only queues a "waiting" job without sending, so it is NOT used here.)
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_ftSendFiles<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    job_id: jint,
    local_path: JString<'local>,
    remote_path: JString<'local>,
    is_upload: jboolean,
    include_hidden: jboolean,
) -> jboolean {
    guard(JNI_FALSE, move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return JNI_FALSE;
        };
        let local_path: String = env
            .get_string(&local_path)
            .map(|s| s.into())
            .unwrap_or_default();
        let remote_path: String = env
            .get_string(&remote_path)
            .map(|s| s.into())
            .unwrap_or_default();
        // is_remote flips the source/destination roles inside io_loop:
        //   upload  (is_remote=false): read local `path`, send to remote `to`.
        //   download (is_remote=true): write local `to`, read from remote `path`.
        let is_upload = is_upload == JNI_TRUE;
        let (path, to, is_remote) = if is_upload {
            (local_path, remote_path, false)
        } else {
            (remote_path, local_path, true)
        };
        librustdesk::flutter_ffi::session_send_files(
            session_id,
            job_id,
            path,
            to,
            0, // file_num: start from the first file
            include_hidden == JNI_TRUE,
            is_remote,
            false, // _is_dir (unused by the underlying call)
        );
        JNI_TRUE
    })
}

/// Cancels the job with id `jobId` (upload or download). Safe to call for an already-finished or
/// unknown job (no-op).
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_ftCancelJob<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    job_id: jint,
) {
    guard((), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return;
        };
        librustdesk::flutter_ffi::session_cancel_job(session_id, job_id);
    })
}

/// Creates a directory named `path` on the remote host (fire-and-forget; `path` is the full remote
/// path to create).
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_ftCreateRemoteDir<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    job_id: jint,
    path: JString<'local>,
) {
    guard((), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return;
        };
        let path: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
        // is_remote = true -> create on the host.
        librustdesk::flutter_ffi::session_create_dir(session_id, job_id, path, true);
    })
}

/// Removes a single remote file (`isDir = false`) or empty remote directory (`isDir = true`) at
/// `path`. `jobId` ties any resulting job event back to this call. NOTE: this only handles a
/// single file / empty dir — recursive directory deletion (read-all-files then per-file remove) is
/// intentionally out of scope for this pass.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_ftRemoveRemoteFile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    job_id: jint,
    path: JString<'local>,
    is_dir: jboolean,
) {
    guard((), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return;
        };
        let path: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
        if is_dir == JNI_TRUE {
            // Remove an empty remote directory. is_remote = true.
            librustdesk::flutter_ffi::session_remove_all_empty_dirs(session_id, job_id, path, true);
        } else {
            // Remove a single remote file. file_num 0, is_remote = true.
            librustdesk::flutter_ffi::session_remove_file(session_id, job_id, path, 0, true);
        }
    })
}

/// Renames/moves a remote entry at `path` to `newName` (a name, not a full path — RustDesk joins
/// it against the parent). Fire-and-forget.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_ftRenameRemoteFile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    job_id: jint,
    path: JString<'local>,
    new_name: JString<'local>,
) {
    guard((), move || {
        let session_id: String = env
            .get_string(&session_id)
            .map(|s| s.into())
            .unwrap_or_default();
        let Ok(session_id) = Uuid::parse_str(&session_id) else {
            return;
        };
        let path: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
        let new_name: String = env
            .get_string(&new_name)
            .map(|s| s.into())
            .unwrap_or_default();
        librustdesk::flutter_ffi::session_rename_file(session_id, job_id, path, new_name, true);
    })
}

// ---------------------------------------------------------------------------------------------
// View Camera (read-only: view a camera device attached to the host, not the host's screen).
//
// Same idea as file transfer: a THIRD, fully separate session keyed by ConnType::VIEW_CAMERA, so
// it can be open alongside (or instead of) the control/video session to the same peer. Unlike
// file transfer, the host DOES stream video here (the camera feed instead of the screen), and
// that video arrives through the exact same VideoFrame handling as the control session — display
// index, frame decode, and the getDisplaySize/getFrame/isAlive polling surface are all generic
// over SessionID and require no view-camera-specific branching (confirmed by reading client.rs:
// `is_view_camera` only affects the login request's ConnType and a `record_screen(..., is_view_camera)`
// bool passed straight through to the same recorder used for the control session). So this is
// "just" a login-time flag: reuse getFrame/getDisplaySize/isAlive/destroy for everything after
// connect.
// ---------------------------------------------------------------------------------------------

/// Opens a dedicated VIEW-CAMERA session to `id` (Remote ID) + `password`. Returns the new session
/// id as a UUID string on success, or "ERR:<message>" on failure. Read-only: no mouse/keyboard
/// input is meaningful on a camera feed, so callers should only poll getDisplaySize/getFrame and
/// call disconnect() when done — do not wire up sendMouse/inputKey for this session.
///
/// Like `connect()`, pokes codec-preference=vp9 (camera frames are still real video, subject to
/// the same AV1-stub decode gap). Deliberately does NOT poke show-remote-cursor — there is no
/// cursor to overlay on a camera feed.
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_connectViewCamera<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: JString<'local>,
    password: JString<'local>,
) -> jstring {
    guard(std::ptr::null_mut(), move || {
        let id: String = env.get_string(&id).map(|s| s.into()).unwrap_or_default();
        let password: String = env
            .get_string(&password)
            .map(|s| s.into())
            .unwrap_or_default();

        let result = if id.is_empty() {
            "ERR:Remote ID must not be empty".to_string()
        } else {
            let session_id = Uuid::new_v4();
            let add_result = librustdesk::flutter_ffi::session_add_sync(
                session_id,
                id.clone(),
                false, // is_file_transfer
                true,  // is_view_camera -> ConnType::VIEW_CAMERA
                false, // is_port_forward
                false, // is_rdp
                false, // is_terminal
                String::new(), // switch_uuid
                false, // force_relay
                password,
                false, // is_shared_password
                None,  // conn_token
            );
            if !add_result.0.is_empty() {
                format!("ERR:{}", add_result.0)
            } else {
                // See connect()'s own comment for why this is needed at all: without it the host
                // defaults to AV1, which our stubbed aom.rs can't decode.
                librustdesk::flutter_ffi::session_peer_option(
                    session_id,
                    "codec-preference".to_string(),
                    "vp9".to_string(),
                );
                match librustdesk::flutter::session_start_headless(&session_id, &id) {
                    Ok(()) => session_id.to_string(),
                    Err(e) => format!("ERR:{}", e),
                }
            }
        };
        env.new_string(result)
            .expect("Couldn't create Java string")
            .into_raw()
    })
}
