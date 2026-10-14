package app.bolbol.tv.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * Hard rule 11: every migration runs on a real SQLite database built from the OLD version's exported schema JSON, with
 * user data in it, and must (1) keep every row and (2) end with exactly the NEW version's schema as Room checks it
 * (columns, types, NOT NULL, primary key, indices). Runs on the JVM with sqlite-jdbc.
 */
class MigrationTest {
    private lateinit var db: Connection

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun close() = db.close()

    @Test fun v1ToV2KeepsProgressSnapshotsPrefsAndMyList() {
        createFromSchema(1)
        val tables = listOf("progress", "title_snapshot", "prefs", "subtitle_style", "subtitle_offset", "home_cache", "my_list")
        exec("INSERT INTO progress VALUES ('353371:0:0', 353371, 0, 0, 2832000, 6120000, 1760000000000, 0, 'local')")
        exec("INSERT INTO progress VALUES ('9001:1:2', 9001, 1, 2, 600000, 2700000, 1760000001000, 1, 'remote')")
        exec(
            "INSERT INTO title_snapshot VALUES (353371, 'MOVIE', 'The Naked Gun 2025', 'مرد عینکی', " +
                "'https://img/p.jpg', NULL, 2025, 6.9, 1, 1, 0, 1760000000000)",
        )
        exec("INSERT INTO title_snapshot VALUES (9001, 'SERIES', 'Lego One Piece', '', NULL, NULL, NULL, NULL, 0, 1, 1, 5)")
        exec("INSERT INTO prefs VALUES (0, 'DUBBED', 720, 1.0)")
        exec("INSERT INTO prefs VALUES (353371, NULL, 1080, 1.25)")
        exec("INSERT INTO subtitle_style VALUES (1, 1, 1.3, 'YELLOW', 'BOX', 1, 'BOTTOM')")
        exec("INSERT INTO subtitle_offset VALUES (353371, -1500)")
        exec("INSERT INTO home_cache VALUES (1, '{\"rows\":[]}', 1760000000000)")
        exec("INSERT INTO my_list VALUES (77, 'MOVIE', 'Heat', 'مخمصه', 'https://img/h.jpg', NULL, 1995, 8.3, 0, 1, 1760000000000, 0)")
        exec("INSERT INTO my_list VALUES (78, NULL, 'Gone', '', NULL, NULL, NULL, NULL, 0, 0, 1760000000001, 2)")
        val before = tables.associateWith(::dump)

        Migrations.MIGRATION_1_2.migrate(supportDb())

        tables.forEach { assertEquals("rows of $it", before.getValue(it), dump(it)) }
        assertSchemaIs(2)
        // The new table takes a row the way DownloadDao writes it.
        exec(
            "INSERT INTO download VALUES ('353371:0:0', 353371, 0, 0, 'MOVIE', 'The Naked Gun 2025', 'مرد عینکی', NULL, NULL, " +
                "2025, 6.9, 1, 1, NULL, 720, 'SUBTITLED', 'https://cdn/x.mp4', 'https://cdn/x.vtt', 'WEB-DL 720p', '1.2 GB', " +
                "'QUEUED', 0, NULL, NULL, 1760000002000, '/files/downloads/353371_0_0/video.mp4', NULL, NULL, NULL)",
        )
        assertEquals(listOf(listOf<Any?>("353371:0:0", "QUEUED")), query("SELECT `key`, status FROM download"))
    }

    @Test fun migrationChainReachesTheLatestExportedSchema() {
        val latest = schemaDir().listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        var version = 1
        for (m in Migrations.ALL.sortedBy { it.startVersion }) {
            assertEquals("migrations must chain", version, m.startVersion)
            version = m.endVersion
        }
        assertEquals("a migration is missing for the newest schema JSON", latest, version)
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun schemaDir() = File("schemas/app.bolbol.tv.core.database.BolbolDatabase")

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File(schemaDir(), "$version.json").readText()).jsonObject.getValue("database").jsonObject

    private fun entities(version: Int) = schema(version).getValue("entities").jsonArray.map { it.jsonObject }

    private fun createFromSchema(version: Int) {
        for (e in entities(version)) {
            val table = e.getValue("tableName").jsonPrimitive.content
            exec(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { idx ->
                exec(idx.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
    }

    /** What Room's TableInfo validation compares: columns (type affinity, NOT NULL, pk position) and indices. */
    private fun assertSchemaIs(version: Int) {
        val expectedTables = entities(version).map { it.getValue("tableName").jsonPrimitive.content }.toSet()
        val actualTables = query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
            .map { it[0] as String }.toSet()
        assertEquals(expectedTables, actualTables)
        for (e in entities(version)) {
            val table = e.getValue("tableName").jsonPrimitive.content
            val pk = e.getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }
            val expectedColumns = e.getValue("fields").jsonArray.map { it.jsonObject }.map { f ->
                val name = f.getValue("columnName").jsonPrimitive.content
                // Room's JSON leaves "notNull" out when it is false.
                val notNull = f["notNull"]?.jsonPrimitive?.boolean ?: false
                listOf(name, f.getValue("affinity").jsonPrimitive.content, notNull, pk.indexOf(name) + 1)
            }.sortedBy { it[0] as String }
            val actualColumns = query("PRAGMA table_info(`$table`)")
                .map { r -> listOf(r[1], r[2], (r[3] as Number).toInt() == 1, (r[5] as Number).toInt()) }
                .sortedBy { it[0] as String }
            assertEquals("columns of $table", expectedColumns, actualColumns)

            val expectedIndices = e["indices"]?.jsonArray?.map { it.jsonObject }?.map { idx ->
                Triple(
                    idx.getValue("name").jsonPrimitive.content,
                    idx["unique"]?.jsonPrimitive?.boolean ?: false,
                    idx.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
                )
            }.orEmpty().toSet()
            val actualIndices = query("PRAGMA index_list(`$table`)")
                .filter { (it[3] as String) == "c" } // created by CREATE INDEX (not the pk's autoindex)
                .map { r ->
                    val name = r[1] as String
                    Triple(name, (r[2] as Number).toInt() == 1, query("PRAGMA index_info(`$name`)").map { it[2] as String })
                }.toSet()
            assertEquals("indices of $table", expectedIndices, actualIndices)
        }
        assertTrue(schema(version).getValue("version").jsonPrimitive.int == version)
    }

    private fun exec(sql: String) {
        db.createStatement().use { it.execute(sql) }
    }

    private fun query(sql: String): List<List<Any?>> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
        }
    }

    private fun dump(table: String): List<List<Any?>> = query("SELECT * FROM `$table` ORDER BY 1")

    /** Just enough of SupportSQLiteDatabase for a migration: execSQL goes to the JDBC connection. */
    private fun supportDb(): SupportSQLiteDatabase = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        when (method.name) {
            "execSQL" -> exec(args!![0] as String).let { null }
            else -> throw UnsupportedOperationException("migration used ${method.name}")
        }
    } as SupportSQLiteDatabase
}
