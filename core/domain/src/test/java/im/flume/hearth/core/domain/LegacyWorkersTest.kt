package im.flume.hearth.core.domain

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LegacyWorkersTest {

    /** Every worker class in the app's sources, by full name, found the way WorkManager would save it. */
    private fun currentWorkers(): Set<String> {
        // Tests run from their module's folder; the repo root is the nearest folder with settings.gradle.kts.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val worker = Regex("""^(?:internal )?class (\w+)\([^)]*\)\s*:\s*(?:Coroutine)?Worker\(""", RegexOption.MULTILINE)
        return root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/java/") }
            .flatMap { file ->
                val text = file.readText()
                val pkg = Regex("""^package (\S+)""", RegexOption.MULTILINE).find(text)!!.groupValues[1]
                worker.findAll(text).map { "$pkg.${it.groupValues[1]}" }
            }
            .toSet()
    }

    @Test
    fun `every old name leads to a worker in the app's sources`() {
        val workers = currentWorkers()
        assertTrue("The scanner found no workers; it is broken", workers.size >= 6)
        val stale = LegacyWorkers.NAMES.values.toSet() - workers
        assertTrue("Old names lead to workers that moved or were renamed: $stale", stale.isEmpty())
    }

    @Test
    fun `every worker a phone on 2_20_1 can have saved is mapped`() {
        // All the workers 2.20.1 had, under their names then. A worker added after the package move needs no entry.
        val released = setOf("download.DownloadWorker", "requests.RequestsWorker", "spoken.SpokenSyncWorker",
            "spoken.SpokenDownloadWorker", "spoken.OutboxWorker", "spoken.LocalBookUploadWorker")
        assertEquals(released.map { "im.flume.hearth.$it" }.toSet(), LegacyWorkers.NAMES.keys)
    }

    @Test
    fun `every new name is a worker class that exists`() {
        for (name in LegacyWorkers.NAMES.values) {
            assertTrue(name, ListenableWorker::class.java.isAssignableFrom(Class.forName(name)))
        }
    }

    @Test
    fun `old names keep the class name and only lose the module package`() {
        for ((old, new) in LegacyWorkers.NAMES) {
            assertEquals(old.substringAfterLast('.'), new.substringAfterLast('.'))
            assertTrue(old, old.startsWith("im.flume.hearth.") && !old.startsWith("im.flume.hearth.domain."))
        }
    }

    @Test
    fun `a current name is left to the default factory`() {
        assertNull(LegacyWorkers.currentName("im.flume.hearth.domain.music.download.DownloadWorker"))
        assertEquals("im.flume.hearth.domain.music.download.DownloadWorker", LegacyWorkers.currentName("im.flume.hearth.download.DownloadWorker"))
    }
}
