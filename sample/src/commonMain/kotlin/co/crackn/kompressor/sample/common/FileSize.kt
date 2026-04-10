package co.crackn.kompressor.sample.common

import kotlin.math.roundToInt

/** Formats a byte count into a human-readable string (e.g., "2.4 MB", "380 KB"). */
fun formatFileSize(bytes: Long): String {
    if (bytes < 0) return "0 B"
    if (bytes < 1_024) return "$bytes B"
    val kb = bytes / 1_024.0
    if (kb < 1_024) return "${(kb * 10).roundToInt() / 10.0} KB"
    val mb = kb / 1_024.0
    if (mb < 1_024) return "${(mb * 10).roundToInt() / 10.0} MB"
    val gb = mb / 1_024.0
    if (gb < 1_024) return "${(gb * 10).roundToInt() / 10.0} GB"
    val tb = gb / 1_024.0
    return "${(tb * 10).roundToInt() / 10.0} TB"
}
