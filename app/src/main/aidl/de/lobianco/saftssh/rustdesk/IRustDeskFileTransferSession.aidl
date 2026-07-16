// IRustDeskFileTransferSession.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.rustdesk;

/**
 * One file-transfer connection to a peer — separate from IRustDeskSession (remote control/video):
 * RustDesk's own wire protocol silently drops file-transfer messages sent over a normal control
 * connection, so this is backed by its own independent, separately-authenticated session
 * (NativeBridge.connectFileTransfer), not a mode flag on an existing one.
 *
 * File paths RustDesk's native core reads from/writes to must be real filesystem paths this
 * PROCESS (the plugin's) can open directly — it has no concept of Android content:// Uris. So:
 *  - uploadFile takes a content Uri the CALLER (main app) must have already granted this process
 *    read permission on (see its own doc) — the plugin copies it into its own private storage
 *    before handing a real path to the native layer.
 *  - downloadFile writes into the plugin's own private storage, then EITHER (Android 10+) inserts
 *    the finished file into MediaStore.Downloads itself (the default — no hand-back to the caller
 *    needed), OR, when [downloadFile]'s toCustomDestination is true, grants the caller a
 *    FileProvider content:// Uri onto its own temp copy instead (the dual-pane file manager's
 *    "paste into whatever local folder I'm browsing" flow, where the destination isn't the system
 *    Downloads folder) — see that param's own doc.
 *
 * All methods are oneway — the actual work happens on a background thread inside the plugin
 * process, reported back via IRustDeskFileTransferCallback rather than a return value, since a
 * real transfer or filesystem copy can take an unbounded time.
 */
interface IRustDeskFileTransferSession {
    /** Requests a listing of [path] on the PEER (remote) side. Fire-and-forget — the result
     *  arrives via the callback's onDirListing. */
    oneway void readRemoteDir(String path, boolean showHidden);

    /** Uploads [contentUri] (e.g. from ACTION_OPEN_DOCUMENT) to [remotePath] on the peer.
     *  [jobId] is chosen by the CALLER (any value unique among this session's in-flight jobs —
     *  RustDesk's own act_id is caller-assigned, not native-generated) and is echoed back in every
     *  onJobEvent for this transfer, so the caller can track progress.
     *
     *  The caller MUST have already granted this process (package
     *  de.lobianco.saftssh.rustdesk) read permission on [contentUri] — e.g.
     *  `context.grantUriPermission("de.lobianco.saftssh.rustdesk", uri,
     *  Intent.FLAG_GRANT_READ_URI_PERMISSION)` — before calling this, since SAF Uris are only
     *  readable by the app that received them from the picker (or one it's explicitly granted to).
     *  A failure to open [contentUri] (revoked/invalid) is reported as an onJobEvent error for
     *  [jobId], same channel as a native transfer failure. */
    oneway void uploadFile(int jobId, String contentUri, String remotePath, boolean includeHidden);

    /** Downloads [remotePath] from the peer. [suggestedFileName] names the finished file.
     *
     *  [toCustomDestination] = false (the default "Download" button): the finished file is
     *  inserted into MediaStore.Downloads (Android 10+) — on older Android, where creating a new
     *  Downloads entry without a broad storage permission isn't possible, the file is instead left
     *  in this process's own external-files directory and onJobEvent's "done" savedUri is null;
     *  the caller should tell the user where it landed in that case.
     *
     *  [toCustomDestination] = true (the dual-pane manager's "paste here" flow, where the target
     *  is an arbitrary local folder, not Downloads): the finished file is NOT moved into
     *  MediaStore at all. Instead onJobEvent's "done" savedUri is a FileProvider content:// Uri
     *  onto this process's own temp copy, already granted to the caller (this app's package) via
     *  grantUriPermission — the caller reads it with its own ContentResolver and copies it
     *  wherever the user is browsing (e.g. via DocumentFile), then MUST call
     *  [releaseDownloadedFile] with the same [jobId] once done, since this temp file is
     *  deliberately NOT cleaned up automatically in this mode (the caller may need more than a
     *  moment to stream a large file, and deleting out from under that read would corrupt it). */
    oneway void downloadFile(int jobId, String remotePath, String suggestedFileName, boolean includeHidden, boolean toCustomDestination);

    /** Deletes the temp file behind a [toCustomDestination] download's granted Uri (see
     *  [downloadFile]) — the caller MUST call this once it has finished reading that Uri, or the
     *  temp file leaks for the lifetime of this session. No-op if [jobId] doesn't refer to a
     *  pending custom-destination download (already released, wrong id, or a normal download that
     *  never needed this). */
    oneway void releaseDownloadedFile(int jobId);

    /** Answers a pending onOverrideConfirm prompt for [jobId]/[fileNum] — [overwrite] = replace the
     *  peer's existing file, [remember] = apply this answer to the rest of this job without asking
     *  again, [isUpload] must match the direction reported in the original prompt. */
    oneway void answerOverrideConfirm(int jobId, int fileNum, boolean overwrite, boolean remember, boolean isUpload);

    /** Cancels an in-progress job. */
    oneway void cancelJob(int jobId);

    /** Creates a directory at the full remote [path] on the peer. [jobId] ties the resulting
     *  onJobEvent (done/error) back to this call — NOT shown as a transfer in a progress list,
     *  this is a one-shot fire-and-forget action, but it reports through the same channel a
     *  transfer's completion does since the native layer has no separate signal for it. */
    oneway void createRemoteDir(int jobId, String path);

    /** Removes a single remote file ([isDir] = false) or empty remote directory ([isDir] = true)
     *  at [path]. Recursive directory deletion is intentionally out of scope — deleting a
     *  non-empty directory fails, reported as an onJobEvent error. [jobId] ties the result back to
     *  this call, same as [createRemoteDir]. */
    oneway void removeRemoteFile(int jobId, String path, boolean isDir);

    /** Renames the remote entry at [path] to [newName] (a bare name, not a full path — stays in
     *  the same directory). [jobId] ties the result back to this call, same as [createRemoteDir]. */
    oneway void renameRemoteFile(int jobId, String path, String newName);

    /** Tears down this file-transfer session (does not affect any remote-control IRustDeskSession
     *  to the same peer — they're independent connections). */
    oneway void destroy();
}
