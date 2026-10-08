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
}
