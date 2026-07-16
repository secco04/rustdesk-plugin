// IRustDeskFileTransferCallback.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.rustdesk;

/**
 * Callback for one file-transfer session (see IRustDeskSessionService.createFileTransferSession).
 * All methods are oneway — the plugin's own poll thread (draining NativeBridge.ftPollDirListing/
 * ftPollJobEvent/ftPollOverrideConfirm) calls these on its own thread, never blocking on the
 * caller.
 */
oneway interface IRustDeskFileTransferCallback {
    /** The file-transfer session actually reached the peer (mirrors IRustDeskSessionCallback's
     *  onConnected, but there's no video/display size here — just "ready to browse/transfer"). */
    void onConnected();

    /** The session failed to connect, or dropped after connecting. Same "no push disconnect signal
     *  in this headless setup" caveat as the main remote-control session — see
     *  RustDeskViewModel's CONNECT_TIMEOUT_MS doc for why a client-side timeout is still needed. */
    void onDisconnected(String reason);

    /** A directory listing arrived — JSON `{"id":Int,"path":String,"is_local":Bool,
     *  "only_count":Bool,"entries":[{"entry_type":Int,"name":String,"is_hidden":Bool,"size":Long,
     *  "modified_time":Long}]}`. entry_type: 0=Dir, 2=DirLink, 3=DirDrive, 4=File, 5=FileLink. */
    void onDirListing(String json);

    /** One job-lifecycle event — JSON is one of:
     *  `{"type":"progress","id":Int,"file_num":Int,"speed":Double,"finished_size":Double}`,
     *  `{"type":"done","id":Int,"file_num":Int,"savedUri":String|null}` (savedUri is set only for
     *  a completed DOWNLOAD once the plugin has inserted the file into MediaStore.Downloads — null
     *  for uploads, or on older Android where that wasn't possible, see downloadFile's doc), or
     *  `{"type":"error","id":Int,"file_num":Int,"err":String}` (also used for failures in the
     *  plugin's own local staging step — copying a picked file in, or saving a finished download
     *  out — which happen entirely on the plugin side before/after the native transfer). */
    void onJobEvent(String json);

    /** The peer already has a file of this name — the job is stalled until answered via
     *  answerOverrideConfirm. JSON: `{"id":Int,"file_num":Int,"to":String,"is_upload":Bool,
     *  "is_identical":Bool}`. */
    void onOverrideConfirm(String json);
}
