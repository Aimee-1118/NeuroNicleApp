package com.example.neuronicleapp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryItem(
    val timestamp: Long,
    val subjectId: String,
    val csvPath: String?,
    val pngPath: String?
) {
    override fun toString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return "${sdf.format(Date(timestamp))} - $subjectId"
    }
}
