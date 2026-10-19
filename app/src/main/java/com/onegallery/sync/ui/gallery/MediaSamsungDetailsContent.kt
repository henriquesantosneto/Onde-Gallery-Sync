package com.onegallery.sync.ui.gallery

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.sync.SyncCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun loadMediaDetailInfos(
    context: Context,
    canonicalPaths: List<String>,
    displayItems: List<GalleryDisplayItem>,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
): List<MediaDetailInfo> {
    return canonicalPaths.mapNotNull { path ->
        val entity = syncCoordinator.getMetadata(path) ?: return@mapNotNull null
        val display = displayItems.find { it.canonicalPath == path }
            ?: GalleryMapper.toDisplayItem(entity, mediaCacheRepository)
        buildMediaDetailInfo(entity, display, context)
    }
}

/** Conteúdo Samsung (fundo escuro) reutilizado na grade, mapa e modais. */
@Composable
fun MediaSamsungDetailsContent(
    canonicalPaths: List<String>,
    displayItems: List<GalleryDisplayItem>,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    modifier: Modifier = Modifier,
    error: String? = null,
    footer: (@Composable () -> Unit)? = null,
    contentLevel: MediaDetailContentLevel = MediaDetailContentLevel.Full,
    showAdvancedToggle: Boolean = true,
    /** false quando um [verticalScroll] pai já rola o conteúdo (evita crash). */
    scrollContentInternally: Boolean = true,
) {
    val context = LocalContext.current
    var infos by remember(canonicalPaths) { mutableStateOf<List<MediaDetailInfo>>(emptyList()) }
    var loading by remember(canonicalPaths) { mutableStateOf(true) }

    LaunchedEffect(canonicalPaths, displayItems) {
        loading = true
        infos = withContext(Dispatchers.IO) {
            loadMediaDetailInfos(context, canonicalPaths, displayItems, syncCoordinator, mediaCacheRepository)
        }
        loading = false
    }

    when {
        loading -> {
            CircularProgressIndicator(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(32.dp),
            )
        }
        infos.isEmpty() -> {
            Text(
                "Não foi possível carregar os detalhes.",
                color = Color.White.copy(alpha = 0.85f),
                modifier = modifier.padding(24.dp),
            )
        }
        infos.size == 1 -> {
            SamsungDetailBlock(
                info = infos.first(),
                error = error,
                modifier = modifier,
                contentLevel = contentLevel,
                showAdvancedToggle = showAdvancedToggle,
                scrollContentInternally = scrollContentInternally,
            )
            footer?.invoke()
        }
        else -> {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(start = 0.dp, end = 0.dp, bottom = 24.dp),
            ) {
                item {
                    Text(
                        "${infos.size} itens selecionados",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    )
                }
                items(infos, key = { it.displayName + it.storagePathLabel }) { info ->
                    SamsungDetailBlock(info = info, error = null)
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = Color.White.copy(alpha = 0.12f),
                    )
                }
                footer?.let { footerComposable ->
                    item { footerComposable() }
                }
            }
        }
    }
}

@Composable
private fun SamsungDetailBlock(
    info: MediaDetailInfo,
    error: String?,
    modifier: Modifier = Modifier,
    contentLevel: MediaDetailContentLevel = MediaDetailContentLevel.Full,
    showAdvancedToggle: Boolean = true,
    scrollContentInternally: Boolean = true,
) {
    var showAdvanced by remember(info.displayName) { mutableStateOf(false) }
    MediaDetailSamsungSheet(
        info = info,
        loading = false,
        error = error,
        showAdvanced = showAdvanced,
        onToggleAdvanced = { showAdvanced = !showAdvanced },
        showExpandedContent = true,
        contentLevel = contentLevel,
        showAdvancedToggle = showAdvancedToggle,
        contentOnDark = true,
        scrollContentInternally = scrollContentInternally,
        modifier = modifier,
    )
}
