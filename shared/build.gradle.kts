import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URLClassLoader
import java.sql.Connection
import java.sql.Driver
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

// Reads Supabase credentials from the gitignored local.properties (or env vars, for CI)
// and generates a Kotlin source file so they never live in tracked source.
val generatedSecretsDir = layout.buildDirectory.dir("generated/secrets/commonMain/kotlin")

val generateSupabaseSecrets by tasks.registering {
    val localProperties = Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { load(it) }
        }
    }
    val supabaseUrl = (localProperties.getProperty("supabase.url")
        ?: System.getenv("SUPABASE_URL")
        ?: "").also {
        if (it.isEmpty()) logger.warn("supabase.url is not set in local.properties or SUPABASE_URL env var")
    }
    val supabaseAnonKey = (localProperties.getProperty("supabase.anonKey")
        ?: System.getenv("SUPABASE_ANON_KEY")
        ?: "").also {
        if (it.isEmpty()) logger.warn("supabase.anonKey is not set in local.properties or SUPABASE_ANON_KEY env var")
    }

    val outputDir = generatedSecretsDir.get().asFile
    outputs.dir(outputDir)

    doLast {
        val outputFile = outputDir.resolve("com/cookinlet/belugas/SupabaseSecrets.kt")
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            |package com.cookinlet.belugas
            |
            |internal const val SUPABASE_URL: String = "$supabaseUrl"
            |internal const val SUPABASE_ANON_KEY: String = "$supabaseAnonKey"
            |""".trimMargin()
        )
    }
}

sqldelight {
    databases {
        create("BelugaDatabase") {
            packageName.set("com.cookinlet.belugas.db")
        }
    }
}

// =============================================================================================
// verifySightingEntityMigration -- the ACTUAL migration check for sightingEntity.
//
// SQLDelight's own generated `verifyCommonMainBelugaDatabaseMigration` task (part of the
// `verifySqlDelightMigration` aggregate) verifies nothing useful in this project, for two
// independent reasons, neither of which this task shares:
//
//   1. It always fails here -- and will on any non-admin Windows account, any machine, this
//      Gradle version -- because it runs its SQLite comparison inside a
//      `WorkerExecutor.processIsolation()` worker, and that forked worker's environment is
//      built from essentially nothing (confirmed by dumping it: not even TEMP/TMP survive,
//      only SystemRoot). sqlite-jdbc's native-library extraction falls back to
//      java.io.tmpdir, which with no TMP/TEMP resolves to the Windows system directory
//      itself -- unwritable by a standard user. This is
//      https://github.com/gradle/gradle/issues/22661, open since Nov 2022, still unfixed as
//      of Gradle 9.1.0 -- not a local misconfiguration (this machine's own TEMP/TMP are fine
//      at every level; a from-scratch worker process just never receives them).
//   2. Independent of (1): this project has never set `verifyMigrations = true` above (it
//      defaults to false), and has no checked-in schema-snapshot .db file for the plugin to
//      diff against. SQLDelight's real per-file comparison (VerifyMigrationTask.checkMigration,
//      an ObjectDifferDatabaseComparator diff) only runs once per snapshot file found via
//      findDatabaseFiles() -- with zero found, that loop runs zero times. Even with (1) fixed,
//      the task would go green having checked only that the CURRENT CREATE TABLE opens in a
//      real SQLite connection -- nothing about whether a migrated schema matches it.
//
// This task is what actually answers that question, verified by hand on 2026-09-04 (manual
// JDBC diff of the baseline+1.sqm path against the fresh CREATE TABLE, PRAGMA table_info and
// sqlite_master identical) and made permanent here so it runs on every `check` rather than
// depending on someone remembering to re-run it by hand next time 2.sqm exists.
// verifySqlDelightMigration/verifyCommonMainBelugaDatabaseMigration being green proves NOTHING
// about migration correctness in this project -- do not delete this task thinking the plugin
// already covers it.
//
// Deliberately has zero dependency on SQLDelight's own (non-public) internals -- runs no
// WorkerExecutor, no processIsolation, nothing but plain JDBC in the main Gradle daemon's own
// JVM (confirmed to have a real, correct java.io.tmpdir on this machine), against a driver jar
// this task resolves for itself. Reads SightingEntity.sq and every *.sqm in the same directory
// from disk at runtime -- adding 2.sqm, 3.sqm etc. as the schema evolves needs no change here,
// they're picked up and applied in numeric order automatically.
// =============================================================================================
val sqliteJdbcVerifierClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    sqliteJdbcVerifierClasspath(libs.sqlite.jdbc)
}

abstract class VerifySightingEntityMigrationTask : DefaultTask() {
    @get:InputFile
    abstract val sqFile: RegularFileProperty

    @get:InputFile
    abstract val baselineFile: RegularFileProperty

    @get:InputFiles
    abstract val migrationFiles: ConfigurableFileCollection

    @get:Classpath
    abstract val driverClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val successMarker: RegularFileProperty

    private val tableName = "sightingEntity"

    private fun stripLineComments(sql: String): String =
        sql.lineSequence().joinToString("\n") { it.substringBefore("--") }

    // Quote-aware split on top-level ';' -- sightingEntity has string DEFAULTs (e.g.
    // DEFAULT 'SELF') that must not be mistaken for a statement boundary.
    private fun splitStatements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        for (c in sql) {
            current.append(c)
            if (c == '\'') inSingleQuote = !inSingleQuote
            if (c == ';' && !inSingleQuote) {
                statements += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotBlank()) statements += current.toString()
        return statements.map { it.trim() }.filter { it.isNotEmpty() }
    }

    // .sq files also contain SQLDelight's own named-query syntax ("queryName:\nSELECT ...;"),
    // which is not valid raw SQL -- only the plain CREATE/ALTER schema statements can be
    // executed directly against a real JDBC connection.
    private fun schemaStatementsFromSqFile(text: String): List<String> =
        splitStatements(stripLineComments(text)).filter { it.startsWith("CREATE", ignoreCase = true) }

    private fun schemaStatementsFromPlainSqlFile(text: String): List<String> =
        splitStatements(stripLineComments(text))

    private fun executeAll(connection: Connection, statements: List<String>) {
        connection.createStatement().use { statement ->
            statements.forEach { statement.execute(it) }
        }
    }

    private fun tableInfo(connection: Connection): List<String> {
        val rows = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($tableName)").use { rs ->
                while (rs.next()) {
                    rows += "cid=${rs.getInt("cid")} name=${rs.getString("name")} " +
                        "type=${rs.getString("type")} notnull=${rs.getInt("notnull")} " +
                        "dflt_value=${rs.getString("dflt_value")} pk=${rs.getInt("pk")}"
                }
            }
        }
        check(rows.isNotEmpty()) {
            "PRAGMA table_info($tableName) returned no rows -- table missing or misnamed, " +
                "check the CREATE TABLE statement(s) actually ran"
        }
        return rows
    }

    // ALTER TABLE ADD COLUMN rewrites the stored CREATE TABLE text by splicing new columns in
    // right where the closing paren used to be, without reformatting -- so a column added after
    // a pretty-printed original's trailing newline-before-")" leaves a stray space before the
    // splice point (e.g. "positionSource TEXT , headingDegrees ...") that a fresh CREATE TABLE
    // written on one comma-separated flow never has. Collapsing whitespace runs alone doesn't
    // remove THAT particular space since it's already a single space, not a run -- also drop
    // any whitespace immediately before ',' or ')' so this artifact normalizes away without
    // hiding a real difference (column set/order/types/defaults still fully determine the rest
    // of this string either way).
    private fun normalizedSchemaSql(connection: Connection): String {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='$tableName'"
            ).use { rs ->
                check(rs.next()) { "no sqlite_master row for table $tableName" }
                return rs.getString("sql")
                    .replace(Regex("\\s+"), " ")
                    .replace(Regex("\\s+([,)])"), "$1")
                    .trim()
            }
        }
    }

    @TaskAction
    fun verify() {
        val driverJars = driverClasspath.files.map { it.toURI().toURL() }.toTypedArray()
        val driverClassLoader = URLClassLoader(driverJars, javaClass.classLoader)
        val driver = Class.forName("org.sqlite.JDBC", true, driverClassLoader)
            .getDeclaredConstructor().newInstance() as Driver

        fun connect(): Connection =
            driver.connect("jdbc:sqlite::memory:", Properties())
                ?: error("sqlite-jdbc driver refused jdbc:sqlite::memory:")

        val migratedStatements = mutableListOf<String>()
        migratedStatements += schemaStatementsFromPlainSqlFile(baselineFile.get().asFile.readText())
        migrationFiles.files
            .sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
            .forEach { file -> migratedStatements += schemaStatementsFromPlainSqlFile(file.readText()) }

        val freshStatements = schemaStatementsFromSqFile(sqFile.get().asFile.readText())

        connect().use { migratedConn ->
            executeAll(migratedConn, migratedStatements)
            val migratedInfo = tableInfo(migratedConn)
            val migratedSql = normalizedSchemaSql(migratedConn)

            connect().use { freshConn ->
                executeAll(freshConn, freshStatements)
                val freshInfo = tableInfo(freshConn)
                val freshSql = normalizedSchemaSql(freshConn)

                if (migratedInfo != freshInfo || migratedSql != freshSql) {
                    throw GradleException(
                        buildString {
                            appendLine(
                                "sightingEntity's migrated schema (${baselineFile.get().asFile.name} + " +
                                    "${migrationFiles.files.size} .sqm file(s)) does not match the fresh " +
                                    "CREATE TABLE in ${sqFile.get().asFile.name}."
                            )
                            appendLine()
                            appendLine("--- migrated PRAGMA table_info ---")
                            migratedInfo.forEach { appendLine(it) }
                            appendLine()
                            appendLine("--- fresh PRAGMA table_info ---")
                            freshInfo.forEach { appendLine(it) }
                            appendLine()
                            appendLine("--- migrated sqlite_master.sql (normalized) ---")
                            appendLine(migratedSql)
                            appendLine()
                            appendLine("--- fresh sqlite_master.sql (normalized) ---")
                            appendLine(freshSql)
                        }
                    )
                }
            }
        }

        val marker = successMarker.get().asFile
        marker.parentFile.mkdirs()
        marker.writeText("OK")
    }
}

val verifySightingEntityMigration by tasks.registering(VerifySightingEntityMigrationTask::class) {
    group = "verification"
    description = "Verifies sightingEntity's baseline+*.sqm schema matches the fresh CREATE TABLE " +
        "in SightingEntity.sq -- see the comment above this task's declaration for why " +
        "SQLDelight's own migration-verify task doesn't do this."
    val dbDir = layout.projectDirectory.dir("src/commonMain/sqldelight/com/cookinlet/belugas/db")
    sqFile.set(dbDir.file("SightingEntity.sq"))
    baselineFile.set(layout.projectDirectory.file("migrationVerification/sightingEntity-baseline-v1.sql"))
    migrationFiles.setFrom(dbDir.asFileTree.matching { include("*.sqm") })
    driverClasspath.setFrom(sqliteJdbcVerifierClasspath)
    successMarker.set(layout.buildDirectory.file("migrationVerification/sightingEntity-verified.txt"))
}

tasks.named("check") {
    dependsOn(verifySightingEntityMigration)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    android {
       namespace = "com.cookinlet.belugas.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        commonMain {
            kotlin.srcDir(generateSupabaseSecrets)
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
                implementation(libs.maplibre.compose)
                implementation(libs.maplibre.spatialk.geojson)
                implementation(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.bundled)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor3)

                // Shared Supabase & Ktor Core
                implementation(project.dependencies.platform(libs.supabase.bom))
                implementation(libs.supabase.postgrest)
                implementation(libs.supabase.storage)
                implementation(libs.supabase.realtime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.compose.uiTooling)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.ktor.client.android)
                implementation(libs.google.play.services.location)
                implementation(libs.kotlinx.coroutines.play.services)
                implementation(libs.sqldelight.driver.android)
                implementation(libs.androidx.camera.core)
                implementation(libs.androidx.camera.camera2)
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.androidx.camera.view)
                implementation(libs.androidx.exifinterface)
            }
        }
        iosMain {
            dependencies {
                // iOS Native HTTP Engine
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.driver.native)
            }
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}