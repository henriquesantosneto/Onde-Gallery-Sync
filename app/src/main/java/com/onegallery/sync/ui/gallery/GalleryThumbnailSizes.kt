package com.onegallery.sync.ui.gallery

/** Lado máximo ao gerar miniatura para grade / prefetch (sync e rolagem). */
const val GALLERY_GRID_THUMB_MAX_SIDE_PX = 512

/** Detalhe em tela cheia: decode progressivo acima disso usa tile view. */
const val GALLERY_DETAIL_SUBSAMPLING_MIN_BYTES = 2L * 1024L * 1024L
