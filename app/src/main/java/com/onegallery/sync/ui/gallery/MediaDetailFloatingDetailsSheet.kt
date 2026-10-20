package com.onegallery.sync.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.sync.SyncCoordinator

@Composable
fun MediaDetailPushDetailsSheet(
    canonicalPaths: List<String>,
    displayItems: List<GalleryDisplayItem>,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    panelFraction: Float,
    contentLevel: MediaDetailContentLevel,
    onPanelFractionChange: (Float) -> Unit,
    onPanelDragEnd: (velocityYPxPerSec: Float) -> Unit,
    onPanelDragStart: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (canonicalPaths.isEmpty() || panelFraction <= 0f) return

    BackHandler(onBack = {
        onPanelFractionChange(0f)
        onPanelDragEnd(0f)
    })

    val configuration = LocalConfiguration.current
    val screenHeightPx = with(LocalDensity.current) {
        configuration.screenHeightDp.dp.toPx()
    }
    val fractionBase = { panelFraction }

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
                .height(44.dp)
                .detailPanelVerticalDrag(
                    enabled = true,
                    screenHeightPx = screenHeightPx,
                    fractionBase = fractionBase,
                    onDragStart = onPanelDragStart,
                    onDragFraction = onPanelFractionChange,
                    onDragEnd = onPanelDragEnd,
                )
                .padding(top = 10.dp, bottom = 6.dp),
            contentAlignment = Alignment.TopCenter,
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
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            MediaSamsungDetailsContent(
                canonicalPaths = canonicalPaths,
                displayItems = displayItems,
                syncCoordinator = syncCoordinator,
                mediaCacheRepository = mediaCacheRepository,
                contentLevel = contentLevel,
                showAdvancedToggle = false,
                scrollContentInternally = true,
                scrollBelowMapOnly = false,
                firstSectionDragModifier = Modifier,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
            )
        }
    }
}
