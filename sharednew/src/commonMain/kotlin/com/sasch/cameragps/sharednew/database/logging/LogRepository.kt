package com.sasch.cameragps.sharednew.database.logging

import androidx.room.RoomDatabase
import com.sasch.cameragps.sharednew.database.LogDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * The in-app log store.
 *
 * Logging must never take the app down. Every database failure (for example the
 * iOS database file still being protected on a background relaunch before the
 * first unlock, or a full disk) is dropped here, and writing resumes as soon as
 * the database is reachable again. On Android, Logcat still receives every line
 * independently.
 *
 * Writes run one at a time on [scope], in call order, which also confines the
 * trim counter to a single thread.
 */
class LogRepository internal constructor(
    private val logDao: LogDao,
    private val scope: CoroutineScope,
) {
    constructor(databaseBuilder: RoomDatabase.Builder<LogDatabase>) : this(
        logDao = LogDatabase.getRoomDatabase(databaseBuilder).logDao(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
    )

    private var insertsSinceTrimCheck = 0

    fun insertLog(
        timestamp: Long,
        priority: Int,
        tag: String?,
        message: String,
        exception: String?
    ) {
        scope.launch {
            ignoringDatabaseFailures {
                logDao.insertLog(
                    LogEntry(
                        timestamp = timestamp,
                        priority = priority,
                        tag = tag,
                        message = message,
                        exception = exception
                    )
                )
                trimIfNeeded()
            }
        }
    }

    /**
     * Counting on every insert cost an extra query per log line; checking every
     * [TRIM_CHECK_INTERVAL] inserts keeps the table bounded just the same.
     */
    private suspend fun trimIfNeeded() {
        insertsSinceTrimCheck++
        if (insertsSinceTrimCheck < TRIM_CHECK_INTERVAL) return
        insertsSinceTrimCheck = 0
        if (logDao.getLogCount() > MAX_ENTRIES) {
            logDao.deleteOldLogs(KEEP_ENTRIES)
        }
    }

    fun getRecentLogs(limit: Int = 200): Flow<List<LogEntry>> =
        logDao.getRecentLogs(limit).catch { emit(emptyList()) }

    suspend fun clearAllLogs() {
        ignoringDatabaseFailures { logDao.clearAllLogs() }
    }

    private inline fun ignoringDatabaseFailures(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Deliberately not logged: reporting a logging failure through the
            // logger would recurse into the same failing database.
        }
    }

    internal companion object {
        const val TRIM_CHECK_INTERVAL = 50
        const val MAX_ENTRIES = 1000
        const val KEEP_ENTRIES = 500
    }
}
