// IRustDeskSessionService.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.rustdesk;

import android.view.Surface;
import de.lobianco.saftssh.rustdesk.IRustDeskSession;
import de.lobianco.saftssh.rustdesk.IRustDeskSessionCallback;

interface IRustDeskSessionService {
    /**
     * Connects to a remote RustDesk host via its Remote ID + password, optionally through a
     * self-hosted rendezvous/relay/API server (blank = RustDesk's public default servers — see
     * NativeBridge.setServerConfig's doc). Starts blitting decoded video frames onto [surface]
     * immediately once the peer handshake completes; [callback] is notified of progress/connect/
     * disconnect (see IRustDeskSessionCallback's doc) since native teardown/connect can't be
     * awaited synchronously without risking an ANR on this Binder call.
     *
     * Returns null if the connection could not even be started (see logcat for the underlying
     * error) — same contract as IRemoteDesktopSessionService.createSession.
     */
    IRustDeskSession createSession(
        String id,
        String password,
        String idServer,
        String relayServer,
        String apiServer,
        String key,
        in Surface surface,
        in IRustDeskSessionCallback callback
    );
}
