package com.onegallery.sync.ui.gallery

import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import okio.BufferedSource

/**
 * Miniatura de vídeo é JPEG gravado com o nome do clipe (`.mp4`).
 * Sem este decoder o Coil manda o arquivo ao VideoFrameDecoder e a rolagem fecha o app.
 */
internal class CachedThumbImageDecoderFactory : Decoder.Factory {
    override fun create(
        result: SourceResult,
        options: Options,
        imageLoader: ImageLoader,
    ): Decoder? {
        val mime = result.mimeType
        if (mime != null && mime.startsWith("image/")) return null
        if (!result.source.source().startsWithImageMagic()) return null
        return BitmapFactoryDecoder(result.source, options)
    }
}

private fun BufferedSource.startsWithImageMagic(): Boolean {
    val peek = peek()
    if (!peek.request(3)) return false
    val count = when {
        peek.request(12) -> 12
        peek.request(8) -> 8
        else -> 3
    }
    val header = ByteArray(count)
    return runCatching {
        peek.readFully(header)
        header.hasImageMagic()
    }.getOrDefault(false)
}

internal fun ByteArray.hasImageMagic(): Boolean {
    if (size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) return true
    if (size >= 8 && this[0] == 0x89.toByte() && this[1] == 0x50.toByte()) return true
    if (size >= 6 && this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte()) return true
    if (size >= 12 &&
        this[0] == 'R'.code.toByte() &&
        this[1] == 'I'.code.toByte() &&
        this[8] == 'W'.code.toByte()
    ) {
        return true
    }
    return false
}
