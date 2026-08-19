package com.cookinlet.belugas

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import belugas.shared.generated.resources.Res
import kotlinx.coroutines.runBlocking

class MBTilesReader(private val filePath: String) {
    private val driver = BundledSQLiteDriver()
    private var connection: SQLiteConnection? = null

    fun open() {
        connection = driver.open(filePath)
    }

    /**
     * Reads compressed vector tile bytes (pbf/mvt) from SQLite 'tiles' table
     * Note: MBTiles uses TMS coordinate system.
     */
    fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        val conn = connection ?: return null
        val sql = "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?"
        
        return try {
            conn.prepare(sql).use { stmt ->
                stmt.bindInt(1, z)
                stmt.bindInt(2, x)
                stmt.bindInt(3, y)
                if (stmt.step()) {
                    stmt.getBlob(0)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun close() {
        connection?.close()
        connection = null
    }
}

object OfflineTileServer {
    private var server: EmbeddedServer<*, *>? = null
    const val PORT = 8080

    fun start(mbTilesFilePath: String) {
        if (server != null) return

        val reader = MBTilesReader(mbTilesFilePath)
        reader.open()

        server = embeddedServer(CIO, port = PORT, host = "127.0.0.1") {
            routing {
                // Vector tile endpoint: http://127.0.0.1:8080/tiles/{z}/{x}/{y}.pbf
                get("/tiles/{z}/{x}/{y}.pbf") {
                    val z = call.parameters["z"]?.toIntOrNull()
                    val x = call.parameters["x"]?.toIntOrNull()
                    val rawY = call.parameters["y"]?.toIntOrNull()

                    if (z == null || x == null || rawY == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    // Convert standard XYZ tile coordinate to TMS (MBTiles standard)
                    val tmsY = (1 shl z) - 1 - rawY

                    val tileBytes = withContext(Dispatchers.Default) {
                        reader.getTile(z, x, tmsY)
                    }

                    if (tileBytes != null) {
                        call.respondBytes(
                            bytes = tileBytes,
                            contentType = ContentType.parse("application/x-protobuf")
                        )
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}

fun getOfflineMapStyleJson(): String {
    return """
    {
      "version": 8,
      "sources": {
        "beluga_region_offline": {
          "type": "vector",
          "tiles": [
            "http://127.0.0.1:8080/tiles/{z}/{x}/{y}.pbf"
          ],
          "minzoom": 0,
          "maxzoom": 14
        }
      },
      "layers": [
        {
          "id": "background",
          "type": "background",
          "paint": { "background-color": "#002B36" }
        },
        {
          "id": "water",
          "type": "fill",
          "source": "beluga_region_offline",
          "source-layer": "water",
          "paint": { "fill-color": "#004D4D" }
        },
        {
          "id": "land",
          "type": "fill",
          "source": "beluga_region_offline",
          "source-layer": "landcover",
          "paint": { "fill-color": "#1A252C" }
        }
      ]
    }
    """.trimIndent()
}

// Minimal, valid MapLibre JSON style using public OpenStreetMap tiles.
// Used as a bulletproof safety net if local asset reading fails.
private const val FALLBACK_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    {
      "id": "osm-tiles",
      "type": "raster",
      "source": "osm",
      "minzoom": 0,
      "maxzoom": 19
    }
  ]
}
"""

/**
 * Loads the map style JSON with a safety fallback.
 * Attempts to load 'warn_style.json' from resources, failing over to 
 * a standard OpenStreetMap raster style if anything goes wrong.
 */
fun getWarnStyleJson(): String {
    return try {
        val rawJson = runBlocking { 
            Res.readBytes("files/warn_style.json").decodeToString() 
        }

        if (rawJson.isNotBlank() && rawJson.trimStart().startsWith("{")) {
            rawJson
        } else {
            println("WARN_STYLE: Asset loaded but contains invalid or empty JSON. Falling back.")
            FALLBACK_STYLE_JSON
        }
    } catch (e: Exception) {
        // Log basic error info without full stack trace to keep console clean for known missing assets
        println("WARN_STYLE INFO: Custom style 'warn_style.json' not found or failed to load (${e.message}). Using OSM default.")
        FALLBACK_STYLE_JSON
    }
}
