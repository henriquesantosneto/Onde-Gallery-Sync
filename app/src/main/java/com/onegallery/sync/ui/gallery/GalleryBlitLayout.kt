package com.onegallery.sync.ui.gallery

internal data class BlitSlot(
    val entryIndex: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val path: String?,
    val title: String?,
)

internal data class BlitLayout(
    val slots: List<BlitSlot>,
    val contentHeight: Int,
    val columns: Int,
    val cellSize: Int,
)

internal fun buildBlitLayout(
    count: Int,
    isHeader: (Int) -> Boolean,
    isYear: (Int) -> Boolean,
    pathAt: (Int) -> String?,
    titleAt: (Int) -> String?,
    width: Int,
    cellMin: Int,
    gap: Int,
    padStart: Int,
    padEnd: Int,
    padTop: Int,
    padBottom: Int,
    headerHeight: Int,
    yearHeaderHeight: Int,
): BlitLayout {
    val inner = (width - padStart - padEnd).coerceAtLeast(1)
    val stride = (cellMin + gap).coerceAtLeast(1)
    val columns = ((inner + gap) / stride).coerceAtLeast(1)
    val cell = ((inner - gap * (columns - 1)) / columns).coerceAtLeast(1)
    val slots = ArrayList<BlitSlot>(count)
    var y = padTop
    var column = 0
    for (index in 0 until count) {
        if (isHeader(index)) {
            if (column != 0) {
                y += cell + gap
                column = 0
            }
            val height = if (isYear(index)) yearHeaderHeight else headerHeight
            slots.add(
                BlitSlot(
                    entryIndex = index,
                    left = 0,
                    top = y,
                    width = width,
                    height = height,
                    path = null,
                    title = titleAt(index),
                ),
            )
            y += height
            continue
        }
        val left = padStart + column * (cell + gap)
        slots.add(
            BlitSlot(
                entryIndex = index,
                left = left,
                top = y,
                width = cell,
                height = cell,
                path = pathAt(index),
                title = null,
            ),
        )
        column++
        if (column == columns) {
            column = 0
            y += cell + gap
        }
    }
    if (column != 0) y += cell
    return BlitLayout(
        slots = slots,
        contentHeight = y + padBottom,
        columns = columns,
        cellSize = cell,
    )
}

internal fun blitFirstVisibleIndex(slots: List<BlitSlot>, scrollY: Int): Int {
    if (slots.isEmpty()) return 0
    var low = 0
    var high = slots.lastIndex
    var answer = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        val slot = slots[mid]
        if (slot.top + slot.height > scrollY) {
            answer = mid
            high = mid - 1
        } else {
            low = mid + 1
        }
    }
    return answer
}

internal fun blitPathAt(slots: List<BlitSlot>, x: Int, contentY: Int): String? {
    if (slots.isEmpty() || contentY < 0) return null
    var low = 0
    var high = slots.lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val slot = slots[mid]
        val bottom = slot.top + slot.height
        if (bottom <= contentY) {
            low = mid + 1
        } else if (slot.top > contentY) {
            high = mid - 1
        } else {
            found = mid
            break
        }
    }
    if (found < 0) return null
    var index = found
    while (index > 0) {
        val previous = slots[index - 1]
        if (previous.top > contentY || previous.top + previous.height <= contentY) break
        index--
    }
    while (index <= slots.lastIndex) {
        val slot = slots[index]
        if (slot.top > contentY) break
        if (contentY < slot.top + slot.height && x >= slot.left && x < slot.left + slot.width) {
            return slot.path
        }
        index++
    }
    return null
}
