// M2 (plans/soft-frolicking-thimble.md): minimal connect — Remote ID + password, no video/input
// yet (M3/M4). Uses flutter_ffi's already-JNI-friendly sync functions directly (session_add_sync,
// session_close), plus our own additive `librustdesk::flutter::session_start_headless` (see that
// function's doc comment for why `session_start` itself can't be called from a plain JNI caller
// with no Dart isolate).

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jintArray, jstring, JNI_FALSE, JNI_TRUE};
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
/// each (used nowhere yet here, kept for parity with `session_input_key`'s own signature).
#[no_mangle]
pub extern "system" fn Java_de_lobianco_saftssh_rustdesk_NativeBridge_inputKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_id: JString<'local>,
    name: JString<'local>,
    down: jboolean,
    press: jboolean,
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
            false,
            false,
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
