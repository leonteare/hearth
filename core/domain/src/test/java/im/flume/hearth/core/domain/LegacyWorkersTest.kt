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
    fun `every worker in the app has its old name mapped`() {
        val workers = currentWorkers()
        assertTrue("The scanner found no workers; it is broken", workers.size >= 6)
        assertEquals(workers, LegacyWorkers.NAMES.values.toSet())
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
