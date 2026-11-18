package com.nkls.nekovideo.components

import java.util.Locale

/** Reads attachment metadata and seeks over video data instead of decoding or copying it. */
internal class MatroskaCoverReader(
    private val fileSize: Long,
    private val readBytes: (position: Long, length: Int) -> ByteArray
) {
    private data class Element(val id: Long, val start: Long, val end: Long, val unknownSize: Boolean) {
        val size: Long get() = end - start
    }

    private data class Attachment(val name: String, val data: Element) {
        val priority: Int get() = when (name.lowercase(Locale.ROOT)) {
            "cover.jpg", "cover.jpeg", "cover.png" -> 0
            "cover_land.jpg", "cover_land.jpeg", "cover_land.png" -> 1
            "small_cover.jpg", "small_cover.jpeg", "small_cover.png" -> 2
            "small_cover_land.jpg", "small_cover_land.jpeg", "small_cover_land.png" -> 3
            else -> 4
        }
    }

    private var remainingElements = 4_096
    private val attachments = mutableListOf<Attachment>()
    private val visited = mutableSetOf<Long>()
    private val seekPositions = ArrayDeque<Long>()

    fun <T> readCover(decode: (ByteArray) -> T?): T? {
        val header = elementAt(0, fileSize)
        if (header.id != EBML_ID) return null
        var position = header.end
        var segment = elementAt(position, fileSize)
        while (segment.id == VOID_ID) {
            position = segment.end
            segment = elementAt(position, fileSize)
        }
        if (segment.id != SEGMENT_ID) return null

        position = segment.start
        while (position < segment.end) {
            val element = elementAt(position, segment.end)
            readTopLevel(position, element, segment)
            // SeekHead avoids visiting every Cluster in long videos.
            while (seekPositions.isNotEmpty()) {
                val target = seekPositions.removeFirst()
                if (target !in visited) readTopLevel(target, elementAt(target, segment.end), segment)
            }
            if (attachments.isNotEmpty()) break
            if (element.unknownSize) break
            position = element.end
        }
        var remainingBytes = MAX_ARTWORK_BYTES * 2L
        for (attachment in attachments.sortedBy { it.priority }) {
            if (attachment.data.size > remainingBytes) continue
            remainingBytes -= attachment.data.size
            val result = decode(readBytes(attachment.data.start, attachment.data.size.toInt()))
            if (result != null) return result
        }
        return null
    }

    private fun readTopLevel(position: Long, element: Element, segment: Element) {
        if (!visited.add(position)) return
        when (element.id) {
            ATTACHMENTS_ID -> children(element) { child ->
                if (child.id == ATTACHED_FILE_ID) readAttachment(child)
            }
            SEEK_HEAD_ID -> children(element) { seek ->
                if (seek.id == SEEK_ID) {
                    var targetId = 0L
                    var offset = -1L
                    children(seek) { field ->
                        when (field.id) {
                            SEEK_TARGET_ID -> targetId = unsignedInteger(field)
                            SEEK_POSITION_ID -> offset = unsignedInteger(field)
                        }
                    }
                    if ((targetId == ATTACHMENTS_ID || targetId == SEEK_HEAD_ID) &&
                        offset >= 0 && offset < segment.size) {
                        seekPositions.add(segment.start + offset)
                    }
                }
            }
        }
    }

    private fun readAttachment(element: Element) {
        var name = ""
        var mimeType = ""
        var data: Element? = null
        children(element) { field ->
            when (field.id) {
                FILE_NAME_ID -> name = text(field, 1_024)
                FILE_MIME_TYPE_ID -> mimeType = text(field, 128).lowercase(Locale.ROOT)
                FILE_DATA_ID -> data = field
            }
        }
        val imageData = data ?: return
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isImage = mimeType == "image/jpeg" || mimeType == "image/png" ||
            ((mimeType.isEmpty() || mimeType == "application/octet-stream") && extension in setOf("jpg", "jpeg", "png"))
        if (isImage && imageData.size in 1..MAX_ARTWORK_BYTES.toLong() && attachments.size < 32) {
            attachments += Attachment(name, imageData)
        }
    }

    private fun children(parent: Element, visit: (Element) -> Unit) {
        require(!parent.unknownSize)
        var position = parent.start
        while (position < parent.end) {
            val child = elementAt(position, parent.end)
            require(!child.unknownSize)
            visit(child)
            position = child.end
        }
    }

    private fun text(element: Element, maxLength: Int): String {
        if (element.size !in 1..maxLength.toLong()) return ""
        return readBytes(element.start, element.size.toInt()).toString(Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun unsignedInteger(element: Element): Long {
        if (element.size !in 1..8) return -1L
        var value = 0L
        for (byte in readBytes(element.start, element.size.toInt())) {
            if (value > Long.MAX_VALUE ushr 8) return -1L
            value = (value shl 8) or (byte.toLong() and 0xff)
        }
        return value
    }

    private fun elementAt(position: Long, parentEnd: Long): Element {
        require(remainingElements-- > 0 && position >= 0 && position < parentEnd)
        val header = readBytes(position, minOf(12, parentEnd - position).toInt())
        val idLength = variableIntegerLength(header[0], 4)
        require(header.size > idLength)
        var id = 0L
        repeat(idLength) { id = (id shl 8) or (header[it].toLong() and 0xff) }
        val sizeLength = variableIntegerLength(header[idLength], 8)
        require(header.size >= idLength + sizeLength)
        var size = header[idLength].toLong() and (0xff ushr sizeLength).toLong()
        for (index in 1 until sizeLength) {
            size = (size shl 8) or (header[idLength + index].toLong() and 0xff)
        }
        val start = position + idLength + sizeLength
        val unknownSize = size == (1L shl (7 * sizeLength)) - 1
        require(unknownSize || size <= parentEnd - start)
        return Element(id, start, if (unknownSize) parentEnd else start + size, unknownSize)
    }

    private fun variableIntegerLength(firstByte: Byte, maxLength: Int): Int {
        val value = firstByte.toInt() and 0xff
        var length = 1
        while (length <= maxLength && value and (0x80 ushr (length - 1)) == 0) length++
        require(length <= maxLength)
        return length
    }

    companion object {
        const val MAX_ARTWORK_BYTES = 32 * 1024 * 1024
        private const val EBML_ID = 0x1A45DFA3L
        private const val VOID_ID = 0xECL
        private const val SEGMENT_ID = 0x18538067L
        private const val ATTACHMENTS_ID = 0x1941A469L
        private const val ATTACHED_FILE_ID = 0x61A7L
        private const val FILE_NAME_ID = 0x466EL
        private const val FILE_MIME_TYPE_ID = 0x4660L
        private const val FILE_DATA_ID = 0x465CL
        private const val SEEK_HEAD_ID = 0x114D9B74L
        private const val SEEK_ID = 0x4DBBL
        private const val SEEK_TARGET_ID = 0x53ABL
        private const val SEEK_POSITION_ID = 0x53ACL
    }
}
