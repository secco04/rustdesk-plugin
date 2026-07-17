// IRustDeskSession.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.rustdesk;

import android.view.Surface;

/**
 * One live RustDesk connection. Milestone 6 (plans/soft-frolicking-thimble.md): video + input,
 * mirroring IRemoteDesktopSession (VNC/RDP/Proxmox VE plugin)'s shape — a Surface to render onto
 * plus pointer/keyboard forwarding — adapted to RustDesk's own mouse/key message contracts (see
 * NativeBridge.sendMouse/inputKey/inputString's docs for the exact wire format of each).
 */
interface IRustDeskSession {
    /** Swaps in a fresh Surface (e.g. after a SurfaceView resize) — the session keeps blitting
     *  onto whatever Surface was set most recently. oneway: matches
     *  IRemoteDesktopSession.updateSurface's reasoning (no caller-visible result, no need to block). */
    oneway void updateSurface(in Surface surface);

    /** Mouse input. [mouseType] is "" (plain move), "down", "up", or "wheel"; [buttons] is "" or
     *  "left"/"right"/"wheel"/"back"/"forward" — see NativeBridge.sendMouse's doc. [x]/[y] are
     *  REMOTE framebuffer pixels; the caller does the Surface-pixel inverse mapping. */
    oneway void sendMouse(int x, int y, String mouseType, String buttons);

    /** One named key press — RustDesk's own "VK_*" naming (see NativeBridge.inputKey's doc).
     *  [ctrl]/[alt] must be passed here (not just as separate VK_CONTROL/VK_MENU down/up calls) —
     *  RustDesk's own key-injection path sets a keystroke's actual modifier bits from these flags,
     *  and for printable-character taps (VK_A.."VK_Z"/VK_0.."VK_9") the remote synthesizes via
     *  Unicode/character injection, which ignores any separately-held modifier key entirely. */
    oneway void inputKey(String name, boolean down, boolean press, boolean ctrl, boolean alt);

    /** Types a plain text string (e.g. from a soft-keyboard/IME commitText callback). */
    oneway void inputString(String value);

    /** Pinch-zoom transform applied on top of the plugin's base letterbox fit, mirroring
     *  IRemoteDesktopSession.setZoom / VncClient.blitToSurface: [scale] is relative to the base fit
     *  (1.0 = no zoom), [panX]/[panY] are Surface-local pixel offsets. The caller (RustDeskScreen)
     *  is the source of truth for these and must apply the SAME transform in its own touch→
     *  framebuffer inverse-mapping so taps still land correctly while zoomed. */
    oneway void setZoom(float scale, float panX, float panY);

    /** True once the session has produced at least one video frame — a rough connectivity proxy.
     *  Prefer the pushed IRustDeskSessionCallback.onConnected for UI state; this exists for
     *  polling fallback, same as IRemoteDesktopSession.isAlive(). */
    boolean isAlive();

    /** Number of displays (monitors) the peer reported, or 0 if not known yet (poll again shortly
     *  after connect). Quick synchronous native field read, same cost class as isAlive(). */
    int getDisplayCount();

    /** Latest known connection stats as a JSON object — RustDesk's own periodic TestDelay
     *  ping-pong and stats tick already produce this, no extra request to the peer needed. Layout:
     *  `{"speed":"1.2MB/s","fps":30,"delay":45,"target_bitrate":2000,"codec_format":"VP9"}` — any
     *  field not yet known is simply omitted, never `"{}"` is possible before anything has arrived.
     *  Meant for an auto-quality heuristic and/or a latency/bandwidth readout in the UI. */
    String getQualityStatus();

    /** Switches which single display (0-based index) this session captures/views — we only ever
     *  render one display at a time, never several side by side. oneway: fire-and-forget, same as
     *  setZoom; the new display's size/first frame arrive through the normal getDisplaySize/
     *  getFrame-equivalent polling path the plugin already drives internally. */
    oneway void switchDisplay(int display);

    /** Quality/speed — [value] is "best", "balanced", or "low" (RustDesk's own image-quality
     *  values; anything else is silently ignored on the Rust side). Live — sends a message to the
     *  already-connected peer, no reconnect needed. */
    oneway void setQuality(String value);

    /** How the mouse pointer is drawn over the remote picture.
     *
     *  [mode] is "host", "local", or "both":
     *   - "host"  — draw the REAL cursor bitmap the peer sends (so an I-beam over text or a resize
     *               arrow over a window edge is actually visible, which is the whole point). Falls
     *               back to the synthetic arrow until the first cursor shape arrives from the peer,
     *               so the pointer is never invisible (a host that never sends one — e.g. if the
     *               show-remote-cursor negotiation didn't take — then just behaves like "local").
     *   - "local" — only the plugin's own synthetic arrow (the pre-existing behaviour).
     *   - "both"  — host cursor plus the synthetic arrow on top of it.
     *  Anything else is treated as "host".
     *
     *  [syntheticScale] scales BOTH cursors, 1.0 = default. The cursors are deliberately drawn at a
     *  size independent of the current letterbox/pinch-zoom scale — a remote 32px cursor scaled
     *  down by the fit-to-view factor would be near-invisible on a phone — so this is the only
     *  control over how big they appear.
     *
     *  oneway: pure render state, same fire-and-forget reasoning as setZoom. */
    oneway void setCursorOptions(String mode, float syntheticScale);

    /** Mutes/unmutes incoming audio — wraps RustDesk's own "disable-audio" peer option. oneway,
     *  same fire-and-forget reasoning as setZoom/setCursorOptions. */
    oneway void setAudioMuted(boolean muted);

    /** Current mute state. Quick synchronous native field read, same cost class as isAlive(). */
    boolean isAudioMuted();

    /** Whether the connected peer advertised privacy-mode support at all (blanking the peer's own
     *  physical display while remotely controlled). Quick synchronous field read, same cost class
     *  as isAlive(). */
    boolean isPrivacyModeSupported();

    /** The privacy-mode implementation key to pass to setPrivacyMode — never null, an empty
     *  string means unsupported/not known yet (the peer can offer several; this picks whichever it
     *  listed first, since there's no UI here to choose among multiple). */
    String getDefaultPrivacyModeImpl();

    /** Turns privacy mode on/off for the peer's own physical display. [implKey] should come from
     *  getDefaultPrivacyModeImpl — sending one the peer doesn't support is a harmless no-op on its
     *  side. oneway: fire-and-forget, same reasoning as setZoom/setCursorOptions — the peer's
     *  actual resulting state (it can take a moment, or fail — e.g. no permission on the host) is
     *  read back separately via isPrivacyModeOn. */
    oneway void setPrivacyMode(String implKey, boolean on);

    /** Current confirmed privacy-mode state — reflects the peer's own reported outcome, not just
     *  whether it was requested, so a rejected/failed request correctly reads back false. */
    boolean isPrivacyModeOn();

    /** Whether the connected peer advertised a camera to view. Call on a normal control session
     *  (from createSession, not createViewCameraSession) once connected — decides whether to offer
     *  a "View Camera" entry point at all before opening the dedicated view-camera session. Quick
     *  synchronous native field read, same cost class as isAlive(). */
    boolean isViewCameraSupported();

    /** Tears down the connection. oneway: native teardown can block for an unbounded time (same
     *  reasoning as IRemoteDesktopSession.destroy() in the VNC/RDP/Proxmox VE plugin). */
    oneway void destroy();
}
