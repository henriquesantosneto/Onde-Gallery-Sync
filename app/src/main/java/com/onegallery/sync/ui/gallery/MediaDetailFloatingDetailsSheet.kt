package com.onegallery.sync.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.sync.SyncCoordinator

/**
 * Painel de detalhes abaixo da foto: ocupa fração da tela e empurra a imagem para cima
 * (sem scrim sobre a foto).
 */
@Composable
fun MediaDetailPushDetailsSheet(
    canonicalPaths: List<String>,
    displayItems: List<GalleryDisplayItem>,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)

    val density = LocalDensity.current
    val dismissDragThresholdPx = with(density) { 96.dp.toPx() }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Color(0xFF0D0D0D))
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(onDismiss) {
                    detectVerticalDragGestures(
                        onDragStart = { dragOffsetPx = 0f },
                        onDragEnd = {
                            if (dragOffsetPx > dismissDragThresholdPx) {
                                onDismiss()
                            }
                            dragOffsetPx = 0f
                        },
                        onDragCancel = { dragOffsetPx = 0f },
                    ) { _, dragAmount ->
                        if (dragAmount > 0f) {
                            dragOffsetPx += dragAmount
                        }
                    }
                }
                .padding(top = 10.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Spacer(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.35f)),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            MediaSamsungDetailsContent(
                canonicalPaths = canonicalPaths,
                displayItems = displayItems,
                syncCoordinator = syncCoordinator,
                mediaCacheRepository = mediaCacheRepository,
            )
        }
    }
}
