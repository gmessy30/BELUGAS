package com.cookinlet.belugas

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes

// One finished export, ready to hand to the platform's share/save UI.
sealed class ExportArtifact {
    abstract val fileName: String
    abstract val mimeType: String

    data class Csv(override val fileName: String, val content: String) : ExportArtifact() {
        override val mimeType: String = "text/csv"
    }

    data class Zip(override val fileName: String, val bytes: ByteArray) : ExportArtifact() {
        override val mimeType: String = "application/zip"
    }
}

private fun csvEscape(value: String): String {
    return if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
}

// photoFilenames maps a record's id to the filename it was actually saved under inside the
// zip's photos/ folder -- only populated for records whose photo download succeeded, so a
// failed single download degrades to a blank cell rather than failing the whole export.
private fun buildCsv(
    records: List<SightingRecord>,
    includePhotoColumn: Boolean,
    photoFilenames: Map<String, String>
): String {
    val header = listOf(
        "id", "observed_at", "lat", "lng", "heading", "heading_degrees", "heading_source",
        "heading_accuracy_degrees", "distance_bucket", "distance_radius_meters",
        "count_whites", "count_greys", "count_calves", "count_unknown", "observer_type"
    ) + if (includePhotoColumn) listOf("photo_filename") else emptyList()

    val sb = StringBuilder()
    sb.append(header.joinToString(",", transform = ::csvEscape))
    sb.append("\r\n")
    for (r in records) {
        val row = mutableListOf(
            r.id,
            r.observedAtEpochMs?.let { formatDateTime(it) } ?: "",
            r.lat?.toString() ?: "",
            r.lng?.toString() ?: "",
            r.heading ?: "",
            r.headingDegrees?.toString() ?: "",
            r.headingSource ?: "",
            r.headingAccuracyDegrees?.toString() ?: "",
            r.distanceBucket ?: "",
            r.distanceRadiusMeters?.toString() ?: "",
            r.countWhites.toString(),
            r.countGreys.toString(),
            r.countCalves.toString(),
            r.countUnknown.toString(),
            r.observerType ?: ""
        )
        if (includePhotoColumn) {
            row.add(photoFilenames[r.id] ?: "")
        }
        sb.append(row.joinToString(",", transform = ::csvEscape))
        sb.append("\r\n")
    }
    return sb.toString()
}

private fun sanitizeForFilename(raw: String): String {
    val cleaned = raw.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_' }.joinToString("")
    return cleaned.ifBlank { "sighting" }
}

object ExportBuilder {
    private val httpClient by lazy { HttpClient() }

    /**
     * Builds the finished export artifact. When [includePhotos] is false this is just the CSV
     * (no reason to pay for a zip with nothing else in it). When true, downloads each photo's
     * already-public Supabase Storage bytes and bundles them alongside the CSV in a zip -- a
     * photo whose download fails is simply left out (blank photo_filename cell) rather than
     * failing the whole export, since a partial export is more useful than none.
     */
    suspend fun build(
        records: List<SightingRecord>,
        includePhotos: Boolean,
        onPhotoProgress: (downloaded: Int, total: Int) -> Unit = { _, _ -> }
    ): ExportArtifact {
        val timestamp = currentTimeMillis()

        if (!includePhotos) {
            val csv = buildCsv(records, includePhotoColumn = false, photoFilenames = emptyMap())
            return ExportArtifact.Csv("belugas_export_$timestamp.csv", csv)
        }

        val withPhoto = records.filter { !it.photoUrl.isNullOrBlank() }
        val photoFilenames = mutableMapOf<String, String>()
        val photoEntries = mutableListOf<Pair<String, ByteArray>>()
        val usedFilenames = mutableSetOf<String>()

        withPhoto.forEachIndexed { index, record ->
            val bytes = downloadPhotoBytes(record.photoUrl!!)
            if (bytes != null) {
                var candidate = "${sanitizeForFilename(record.id)}.jpg"
                // Guard against two records sanitizing to the same name (e.g. both blank ids)
                // silently overwriting each other in the zip.
                if (!usedFilenames.add(candidate)) {
                    candidate = "${sanitizeForFilename(record.id)}_$index.jpg"
                    usedFilenames.add(candidate)
                }
                photoEntries.add("photos/$candidate" to bytes)
                photoFilenames[record.id] = candidate
            }
            onPhotoProgress(index + 1, withPhoto.size)
        }

        val csv = buildCsv(records, includePhotoColumn = true, photoFilenames = photoFilenames)
        val entries = mutableListOf("sightings.csv" to csv.encodeToByteArray())
        entries.addAll(photoEntries)
        val zipBytes = ZipWriter.build(entries)
        return ExportArtifact.Zip("belugas_export_$timestamp.zip", zipBytes)
    }

    private suspend fun downloadPhotoBytes(url: String): ByteArray? {
        return try {
            httpClient.get(url).readRawBytes()
        } catch (e: Exception) {
            println("EXPORT_PHOTO_DOWNLOAD_ERROR: [${e::class.simpleName}] ${e.message}")
            null
        }
    }
}

// Table-based CRC-32 (IEEE 802.3 / zip's checksum), needed because kotlin.io has no CRC32 in
// commonMain (java.util.zip.CRC32 is JVM-only).
private object Crc32 {
    private val table = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) {
                c = if (c and 1 != 0) (0xEDB88320L.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            t[n] = c
        }
    }

    fun compute(bytes: ByteArray): Long {
        var crc = 0xFFFFFFFF.toInt()
        for (b in bytes) {
            val index = (crc xor b.toInt()) and 0xFF
            crc = table[index] xor (crc ushr 8)
        }
        return (crc.toLong() xor 0xFFFFFFFFL) and 0xFFFFFFFFL
    }
}

/**
 * Minimal pure-Kotlin zip writer producing STORED (uncompressed) entries only -- no DEFLATE, so
 * no compression library dependency is needed on either platform. Good enough for bundling a
 * small CSV + a handful of already-compressed JPEGs (compressing already-compressed image bytes
 * again buys nothing), and every entry format detail below (signatures, field order/widths,
 * fixed 1980-01-01 mod date since a real timestamp isn't meaningful here) follows the standard
 * PKZIP APPNOTE local/central-directory layout so any unzip tool can read the result.
 */
private object ZipWriter {
    private const val LOCAL_FILE_HEADER_SIG = 0x04034b50L
    private const val CENTRAL_FILE_HEADER_SIG = 0x02014b50L
    private const val END_OF_CENTRAL_DIR_SIG = 0x06054b50L
    private const val FIXED_DOS_DATE = 0x21 // 1980-01-01, encoded as (year-1980)<<9 | month<<5 | day

    private class ByteBuffer {
        val bytes = ArrayList<Byte>()
        fun writeShort(v: Int) {
            bytes.add((v and 0xFF).toByte())
            bytes.add(((v ushr 8) and 0xFF).toByte())
        }
        fun writeInt(v: Long) {
            bytes.add((v and 0xFF).toByte())
            bytes.add(((v ushr 8) and 0xFF).toByte())
            bytes.add(((v ushr 16) and 0xFF).toByte())
            bytes.add(((v ushr 24) and 0xFF).toByte())
        }
        fun writeBytes(b: ByteArray) { b.forEach { bytes.add(it) } }
        val size: Long get() = bytes.size.toLong()
        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private class EntryInfo(val nameBytes: ByteArray, val crc: Long, val size: Int, val offset: Long)

    fun build(entries: List<Pair<String, ByteArray>>): ByteArray {
        val buf = ByteBuffer()
        val centralInfos = mutableListOf<EntryInfo>()

        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val crc = Crc32.compute(data)
            val offset = buf.size
            buf.writeInt(LOCAL_FILE_HEADER_SIG)
            buf.writeShort(20) // version needed to extract
            buf.writeShort(0) // general purpose flags
            buf.writeShort(0) // compression method: stored
            buf.writeShort(0) // mod time
            buf.writeShort(FIXED_DOS_DATE)
            buf.writeInt(crc)
            buf.writeInt(data.size.toLong()) // compressed size
            buf.writeInt(data.size.toLong()) // uncompressed size
            buf.writeShort(nameBytes.size)
            buf.writeShort(0) // extra field length
            buf.writeBytes(nameBytes)
            buf.writeBytes(data)
            centralInfos.add(EntryInfo(nameBytes, crc, data.size, offset))
        }

        val centralDirStart = buf.size
        for (info in centralInfos) {
            buf.writeInt(CENTRAL_FILE_HEADER_SIG)
            buf.writeShort(20) // version made by
            buf.writeShort(20) // version needed to extract
            buf.writeShort(0) // general purpose flags
            buf.writeShort(0) // compression method: stored
            buf.writeShort(0) // mod time
            buf.writeShort(FIXED_DOS_DATE)
            buf.writeInt(info.crc)
            buf.writeInt(info.size.toLong())
            buf.writeInt(info.size.toLong())
            buf.writeShort(info.nameBytes.size)
            buf.writeShort(0) // extra field length
            buf.writeShort(0) // comment length
            buf.writeShort(0) // disk number start
            buf.writeShort(0) // internal file attributes
            buf.writeInt(0) // external file attributes
            buf.writeInt(info.offset)
            buf.writeBytes(info.nameBytes)
        }
        val centralDirSize = buf.size - centralDirStart

        buf.writeInt(END_OF_CENTRAL_DIR_SIG)
        buf.writeShort(0) // this disk number
        buf.writeShort(0) // disk with central dir start
        buf.writeShort(centralInfos.size) // entries on this disk
        buf.writeShort(centralInfos.size) // total entries
        buf.writeInt(centralDirSize)
        buf.writeInt(centralDirStart)
        buf.writeShort(0) // comment length

        return buf.toByteArray()
    }
}
