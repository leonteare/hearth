package im.flume.hearth.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room checks the migrated table against the entity at runtime and crashes on any mismatch, so the
 * hand-written 3→4 SQL is checked here against the schema Room exported for version 4.
 */
class MigrationTest {
    private fun createSql(version: Int, table: String): String {
        val file = File("schemas/im.flume.hearth.data.AppDatabase/$version.json")
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val entities = root["database"]!!.jsonObject["entities"]!!.jsonArray
        val entity = entities.map { it.jsonObject }.first { (it["tableName"]!!.jsonPrimitive.content) == table }
        return (entity as JsonObject)["createSql"]!!.jsonPrimitive.content
    }

    /** Column definitions from a CREATE TABLE statement, keyed by name, quotes stripped. */
    private fun columns(sql: String): Map<String, String> =
        sql.substringAfter("(").substringBeforeLast("PRIMARY KEY").split(", ")
            .map { it.trim().trimEnd(',') }.filter { it.isNotEmpty() }
            .associate { def -> def.substringBefore(" ").trim('`') to def.substringAfter(" ").trim() }

    @Test
    fun `migration 3 to 4 adds exactly the new playlist columns`() {
        val before = columns(createSql(3, "playlists"))
        val after = columns(createSql(4, "playlists"))
        val added = AppDatabase.MIGRATION_3_4_SQL.associate { stmt ->
            assertTrue(stmt.startsWith("ALTER TABLE playlists ADD COLUMN "))
            val def = stmt.removePrefix("ALTER TABLE playlists ADD COLUMN ")
            def.substringBefore(" ") to def.substringAfter(" ")
        }
        assertEquals(after - before.keys, added)
    }

    @Test
    fun `migration 4 to 5 matches the exported schema`() {
        val before = columns(createSql(4, "downloads"))
        val after = columns(createSql(5, "downloads"))
        val prefix = "ALTER TABLE downloads ADD COLUMN "
        val added = AppDatabase.MIGRATION_4_5_SQL.filter { it.startsWith(prefix) }.associate { stmt ->
            val def = stmt.removePrefix(prefix)
            def.substringBefore(" ") to def.substringAfter(" ")
        }
        assertEquals(after - before.keys, added)
        // Room's createSql uses a TABLE_NAME placeholder; the migration creates the table under its real name.
        val prefs = createSql(5, "song_download_prefs").replace("`\${TABLE_NAME}`", "`song_download_prefs`")
        assertTrue(AppDatabase.MIGRATION_4_5_SQL.contains(prefs))
    }

    @Test
    fun `migration 5 to 6 matches the exported schema`() {
        val before = columns(createSql(5, "songs"))
        val after = columns(createSql(6, "songs"))
        val prefix = "ALTER TABLE songs ADD COLUMN "
        val added = AppDatabase.MIGRATION_5_6_SQL.filter { it.startsWith(prefix) }.associate { stmt ->
            val def = stmt.removePrefix(prefix)
            def.substringBefore(" ") to def.substringAfter(" ")
        }
        assertEquals(after - before.keys, added)
        val requests = createSql(6, "requests").replace("`\${TABLE_NAME}`", "`requests`")
        assertTrue(AppDatabase.MIGRATION_5_6_SQL.contains(requests))
        // Every other table is unchanged.
        val tables = listOf("albums", "artists", "playlists", "playlist_songs", "downloads", "pinned", "play_history", "pending_scrobbles", "lyrics", "song_download_prefs")
        tables.forEach { assertEquals(it, createSql(5, it), createSql(6, it)) }
    }

    @Test
    fun `migration 6 to 7 adds the request wishes, defaulting to the old behaviour`() {
        val before = columns(createSql(6, "requests"))
        val after = columns(createSql(7, "requests"))
        val prefix = "ALTER TABLE requests ADD COLUMN "
        val added = AppDatabase.MIGRATION_6_7_SQL.associate { stmt ->
            assertTrue(stmt.startsWith(prefix))
            val def = stmt.removePrefix(prefix)
            def.substringBefore(" ").trim('`') to def.substringAfter(" ")
        }
        assertEquals(after - before.keys, added)
        assertEquals("INTEGER NOT NULL DEFAULT 1", added["like"])
        assertEquals("INTEGER NOT NULL DEFAULT 1", added["toPhone"])
        // Every other table is unchanged.
        val tables = listOf("songs", "albums", "artists", "playlists", "playlist_songs", "downloads", "pinned", "play_history", "pending_scrobbles", "lyrics", "song_download_prefs")
        tables.forEach { assertEquals(it, createSql(6, it), createSql(7, it)) }
    }

    private fun entities(version: Int) =
        Json.parseToJsonElement(File("schemas/im.flume.hearth.data.AppDatabase/$version.json").readText()).jsonObject["database"]!!
            .jsonObject["entities"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `migration 7 to 8 creates exactly the spoken word tables and their indices`() {
        val v7 = entities(7).map { it["tableName"]!!.jsonPrimitive.content }.toSet()
        val added = entities(8).filter { it["tableName"]!!.jsonPrimitive.content !in v7 }
        // Room's createSql names the table through a placeholder; the migration uses the real name.
        val expected = added.flatMap { e ->
            val table = e["tableName"]!!.jsonPrimitive.content
            val index = e["indices"]?.jsonArray.orEmpty().map { it.jsonObject["createSql"]!!.jsonPrimitive.content }
            (listOf(e["createSql"]!!.jsonPrimitive.content) + index).map { it.replace("`\${TABLE_NAME}`", "`$table`") }
        }
        assertEquals(expected.sorted(), AppDatabase.MIGRATION_7_8_SQL.sorted())
        assertEquals(9, added.size)
        assertTrue(added.all { it["tableName"]!!.jsonPrimitive.content.startsWith("abs_") })
        // Every existing table is unchanged.
        v7.forEach { assertEquals(it, createSql(7, it), createSql(8, it)) }
    }

    @Test
    fun `migration 8 to 9 adds the episode feed link and guid`() {
        val before = columns(createSql(8, "abs_episodes"))
        val after = columns(createSql(9, "abs_episodes"))
        val prefix = "ALTER TABLE abs_episodes ADD COLUMN "
        val added = AppDatabase.MIGRATION_8_9_SQL.filter { it.startsWith(prefix) }.associate { stmt ->
            val def = stmt.removePrefix(prefix)
            def.substringBefore(" ").trim('`') to def.substringAfter(" ")
        }
        assertEquals(after - before.keys, added)
        // Episodes are fetched again so the new columns fill in.
        assertTrue(AppDatabase.MIGRATION_8_9_SQL.contains("UPDATE abs_podcasts SET episodesFetchedAt = 0"))
        val tables = entities(8).map { it["tableName"]!!.jsonPrimitive.content }
        assertEquals(tables.toSet(), entities(9).map { it["tableName"]!!.jsonPrimitive.content }.toSet())
        tables.filter { it != "abs_episodes" }.forEach { assertEquals(it, createSql(8, it), createSql(9, it)) }
        // The indices stay as they were.
        val idx = { v: Int -> entities(v).first { it["tableName"]!!.jsonPrimitive.content == "abs_episodes" }["indices"].toString() }
        assertEquals(idx(8), idx(9))
    }

    @Test
    fun `migration 9 to 10 creates exactly the phone audiobook tables`() {
        val v9 = entities(9).map { it["tableName"]!!.jsonPrimitive.content }.toSet()
        val added = entities(10).filter { it["tableName"]!!.jsonPrimitive.content !in v9 }
        val expected = added.flatMap { e ->
            val table = e["tableName"]!!.jsonPrimitive.content
            val index = e["indices"]?.jsonArray.orEmpty().map { it.jsonObject["createSql"]!!.jsonPrimitive.content }
            (listOf(e["createSql"]!!.jsonPrimitive.content) + index).map { it.replace("`\${TABLE_NAME}`", "`$table`") }
        }
        assertEquals(expected.sorted(), AppDatabase.MIGRATION_9_10_SQL.sorted())
        assertEquals(setOf("local_books", "local_book_files", "local_book_progress"), added.map { it["tableName"]!!.jsonPrimitive.content }.toSet())
        // Every existing table is unchanged.
        v9.forEach { assertEquals(it, createSql(9, it), createSql(10, it)) }
    }

    @Test
    fun `migration 10 to 11 adds the phone book details and keeps current names as the files' own`() {
        val before = columns(createSql(10, "local_books"))
        val after = columns(createSql(11, "local_books"))
        val prefix = "ALTER TABLE local_books ADD COLUMN "
        val added = AppDatabase.MIGRATION_10_11_SQL.filter { it.startsWith(prefix) }.associate { stmt ->
            val def = stmt.removePrefix(prefix)
            def.substringBefore(" ").trim('`') to def.substringAfter(" ")
        }
        assertEquals(after - before.keys, added)
        assertTrue(AppDatabase.MIGRATION_10_11_SQL.contains("UPDATE local_books SET fileTitle = title, fileAuthor = author"))
        val tables = entities(10).map { it["tableName"]!!.jsonPrimitive.content }
        assertEquals(tables.toSet(), entities(11).map { it["tableName"]!!.jsonPrimitive.content }.toSet())
        tables.filter { it != "local_books" }.forEach { assertEquals(it, createSql(10, it), createSql(11, it)) }
    }
}
