// IRustDeskSessionCallback.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.rustdesk;

/** Mirrors IRemoteDesktopSessionCallback (VNC/RDP/Proxmox VE plugin) — pushed status updates for
 *  a session created via IRustDeskSessionService.createSession. oneway: these are fire-and-forget
 *  notifications, the caller must never block a native/video-pump thread waiting on them. */
oneway interface IRustDeskSessionCallback {
    void onProgress(String message);

    /** Fired once the peer handshake has produced a known display size (see
     *  NativeBridge.getDisplaySize) — the point at which a Surface actually starts receiving
     *  frames, not merely "connect() returned". */
    void onConnected(int width, int height);

    void onDisconnected(String reason);
}
