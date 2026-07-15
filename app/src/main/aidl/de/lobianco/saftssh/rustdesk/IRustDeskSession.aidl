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

    /** Switches which single display (0-based index) this session captures/views — we only ever
     *  render one display at a time, never several side by side. oneway: fire-and-forget, same as
     *  setZoom; the new display's size/first frame arrive through the normal getDisplaySize/
     *  getFrame-equivalent polling path the plugin already drives internally. */
    oneway void switchDisplay(int display);

    /** Quality/speed — [value] is "best", "balanced", or "low" (RustDesk's own image-quality
     *  values; anything else is silently ignored on the Rust side). Live — sends a message to the
     *  already-connected peer, no reconnect needed. */
    oneway void setQuality(String value);

    /** Tears down the connection. oneway: native teardown can block for an unbounded time (same
     *  reasoning as IRemoteDesktopSession.destroy() in the VNC/RDP/Proxmox VE plugin). */
    oneway void destroy();
}
