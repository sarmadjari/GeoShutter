package com.sasch.cameragps.sharednew.database.logging

import com.diamondedge.logging.LogLevel
import com.diamondedge.logging.VariableLogLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LogRepositoryTest {

    private class FakeLogDao : LogDao {
        val entries = mutableListOf<LogEntry>()
        var failWrites = false
        var failQueries = false
        var countQueries = 0

        override suspend fun insertLog(logEntry: LogEntry) {
            if (failWrites) throw IllegalStateException("database unavailable")
            entries += logEntry.copy(id = entries.size + 1L)
        }

        override fun getAllLogs(): Flow<List<LogEntry>> = flowOf(entries.toList())

        override fun getRecentLogs(limit: Int): Flow<List<LogEntry>> =
            if (failQueries) flow { throw IllegalStateException("database unavailable") }
            else flowOf(entries.takeLast(limit).reversed())

        override suspend fun deleteOldLogs(keepCount: Int) {
            val keep = entries.takeLast(keepCount)
            entries.clear()
            entries += keep
        }

        override suspend fun clearAllLogs() {
            if (failWrites) throw IllegalStateException("database unavailable")
            entries.clear()
        }

        override suspend fun getLogCount(): Int {
            countQueries++
            return entries.size
        }
    }

    private fun LogRepository.log(message: String) =
        insertLog(timestamp = 0, priority = 4, tag = "Test", message = message, exception = null)

    @Test
    fun databaseFailuresAreDroppedAndLaterWritesStillLand() = runTest {
        val dao = FakeLogDao().apply { failWrites = true }
        val repository = LogRepository(dao, backgroundScope)

        repository.log("lost")
        runCurrent()
        dao.failWrites = false
        repository.log("kept")
        runCurrent()

        assertEquals(listOf("kept"), dao.entries.map { it.message })
    }

    @Test
    fun writesKeepTheirCallOrder() = runTest {
        val dao = FakeLogDao()
        val repository = LogRepository(dao, backgroundScope)

        repeat(5) { repository.log("line $it") }
        runCurrent()

        assertEquals((0 until 5).map { "line $it" }, dao.entries.map { it.message })
    }

    @Test
    fun theTableIsCountedOnlyEveryTrimInterval() = runTest {
        val dao = FakeLogDao()
        val repository = LogRepository(dao, backgroundScope)

        repeat(LogRepository.TRIM_CHECK_INTERVAL - 1) { repository.log("line") }
        runCurrent()
        assertEquals(0, dao.countQueries)

        repository.log("line")
        runCurrent()
        assertEquals(1, dao.countQueries)
    }

    @Test
    fun oversizedTablesAreTrimmedToTheNewestEntries() = runTest {
        val dao = FakeLogDao()
        repeat(LogRepository.MAX_ENTRIES) {
            dao.insertLog(LogEntry(timestamp = 0, priority = 4, tag = null, message = "old", exception = null))
        }
        val repository = LogRepository(dao, backgroundScope)

        repeat(LogRepository.TRIM_CHECK_INTERVAL) { repository.log("new $it") }
        runCurrent()

        assertEquals(LogRepository.KEEP_ENTRIES, dao.entries.size)
        assertEquals("new ${LogRepository.TRIM_CHECK_INTERVAL - 1}", dao.entries.last().message)
    }

    @Test
    fun aFailingQueryShowsNoLogsInsteadOfCrashing() = runTest {
        val repository = LogRepository(FakeLogDao().apply { failQueries = true }, backgroundScope)

        assertEquals(emptyList(), repository.getRecentLogs().first())
    }

    @Test
    fun clearingFailuresAreDropped() = runTest {
        val dao = FakeLogDao().apply { failWrites = true }
        dao.entries += LogEntry(timestamp = 0, priority = 4, tag = null, message = "kept", exception = null)

        LogRepository(dao, backgroundScope).clearAllLogs()

        assertEquals(1, dao.entries.size)
    }

    @Test
    fun loggedWarningsAndErrorsKeepTheirStackTrace() = runTest {
        val dao = FakeLogDao()
        val logger = DatabaseLogger(
            LogRepository(dao, backgroundScope),
            VariableLogLevel(LogLevel.Verbose),
        )

        logger.error("Test", "boom", IllegalStateException("root cause"))
        logger.warn("Test", "careful", null)
        runCurrent()

        val exception = assertNotNull(dao.entries.first().exception)
        assertTrue(exception.contains("IllegalStateException"))
        assertTrue(exception.contains("root cause"))
        assertNull(dao.entries.last().exception)
    }
}
