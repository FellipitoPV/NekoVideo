package com.nkls.nekovideo.components.helpers

internal fun <T> List<T>.sortedByName(
    descending: Boolean = false,
    name: (T) -> String
): List<T> = sortedWith { left, right ->
    if (descending) {
        String.CASE_INSENSITIVE_ORDER.compare(name(right), name(left))
    } else {
        String.CASE_INSENSITIVE_ORDER.compare(name(left), name(right))
    }
}
