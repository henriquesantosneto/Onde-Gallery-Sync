package com.onegallery.sync.data.cloud

/** Formato do [RemoteCloudFile.contentHash] (dedup upload/recall). */
enum class RemoteContentHashKind {
    /** OneDrive Graph `file.hashes.quickXorHash`. */
    QUICK_XOR,
    /** Dropbox `content_hash` (API v2). */
    CONTENT_HASH,
    UNKNOWN,
}
