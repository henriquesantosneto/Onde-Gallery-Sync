package com.onegallery.sync.ui.navigation

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Base64 URL-safe — caminhos com `/` não quebram segmentos da rota Navigation. */
fun encodeBrowseNavKey(raw: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))

fun decodeBrowseNavKey(encoded: String): String =
    String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
