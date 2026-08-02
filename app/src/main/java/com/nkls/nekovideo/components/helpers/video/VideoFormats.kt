package com.nkls.nekovideo.components.helpers

val supportedVideoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "wmv", "m4v", "3gp", "flv")

fun mimeTypeForVideoFileName(fileName: String): String {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "wmv" -> "video/x-ms-wmv"
        "3gp" -> "video/3gpp"
        "flv" -> "video/x-flv"
        else -> "video/mp4"
    }
}
