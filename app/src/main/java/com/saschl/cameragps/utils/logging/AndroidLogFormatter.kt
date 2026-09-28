package com.saschl.cameragps.utils.logging

import android.util.Log
import com.sasch.cameragps.sharednew.database.logging.LogRepository
import com.sasch.cameragps.sharednew.logging.LogFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AndroidLogFormatter(private val logRepository: LogRepository) : LogFormatter {
    override fun format(): Flow<List<String>> {
        return logRepository.let { repo ->
            repo.getRecentLogs().map { logEntry ->
                // One formatter per emitted list instead of one per line.
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                logEntry.map {
                    val date = formatter.format(Date(it.timestamp))
                    "[$date] [${priorityToString(it.priority)}] ${it.tag ?: "App"}: ${it.message}" +
                            (it.exception?.let { "\n$it" } ?: "")
                }

            }
        }
    }

    private fun priorityToString(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> priority.toString()
    }
}