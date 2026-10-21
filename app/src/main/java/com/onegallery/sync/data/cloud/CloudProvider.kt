package com.onegallery.sync.data.cloud

/** Provedor de nuvem ativo (um por instalação — ver [CloudSyncBackendRegistry]). */
enum class CloudProvider {
    ONEDRIVE,
    /** Fase 2+: backend Dropbox atrás do mesmo contrato. */
    DROPBOX,
}
