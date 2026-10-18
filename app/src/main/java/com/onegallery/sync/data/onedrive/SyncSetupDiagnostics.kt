package com.onegallery.sync.data.onedrive

import android.content.Context

/** Resumo do que o aparelho expõe — para explicar por que OneDrive não aparece no seletor. */
data class SyncSetupDiagnostics(
    val samsungDevice: Boolean,
    val oneDriveInstalled: Boolean,
    val myFilesInstalled: Boolean,
    val documentTreeAvailable: Boolean,
    val cloudRootsCount: Int,
    val cloudRootTitles: List<String>,
) {
    val canPickOneDriveFolderViaSystem: Boolean
        get() = cloudRootsCount > 0

    fun summaryLines(): List<String> = buildList {
        add(if (samsungDevice) "Aparelho: Samsung" else "Aparelho: ${android.os.Build.MANUFACTURER}")
        add(if (oneDriveInstalled) "App OneDrive: instalado" else "App OneDrive: não instalado")
        add(if (myFilesInstalled) "Meus Arquivos: instalado" else "Meus Arquivos: não encontrado")
        add(
            if (cloudRootsCount > 0) {
                "Pastas na nuvem visíveis ao sistema: $cloudRootsCount (${cloudRootTitles.joinToString()})"
            } else {
                "Pastas OneDrive visíveis ao sistema: nenhuma (normal no Samsung)"
            },
        )
    }
}

object SyncSetupDiagnosticsFactory {
    fun create(context: Context): SyncSetupDiagnostics {
        val roots = OneDrivePickerHelper.listCloudRelatedRoots(context)
        return SyncSetupDiagnostics(
            samsungDevice = OneDrivePickerHelper.isSamsungDevice(),
            oneDriveInstalled = OneDrivePickerHelper.isOneDriveAppInstalled(context),
            myFilesInstalled = OneDrivePickerHelper.isSamsungMyFilesInstalled(context),
            documentTreeAvailable = OneDrivePickerHelper.isDocumentTreePickerAvailable(context),
            cloudRootsCount = roots.size,
            cloudRootTitles = roots.map { it.title },
        )
    }
}
