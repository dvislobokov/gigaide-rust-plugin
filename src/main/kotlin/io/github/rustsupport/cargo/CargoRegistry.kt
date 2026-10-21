package io.github.rustsupport.cargo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** A release of a crate as the registry index lists it. */
data class IndexEntry(val version: String, val yanked: Boolean, val features: Map<String, List<String>>, val rustVersion: String? = null)

/**
 * What cargo keeps of the registry on disk (`$CARGO_HOME/registry`): the cache of the index (`index/<registry>/.cache/`, one file
 * per crate it has looked up), the downloaded archives (`cache/<registry>/<name>-<version>.crate`) and their sources
 * (`src/<registry>/<name>-<version>/`). Only read, never fetched: completion must not reach the network.
 */
object CargoRegistry {
    private val CRATE_FILE = Regex("""^(.+?)-(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.+-]*)?)\.crate$""")
    private val SOURCE_DIR = Regex("""^(.+?)-(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.+-]*)?)$""")

    /**
     * One file of the index cache. Its layout (cargo `index_cache`): a byte of the cache version, then (version 3 and later) four
     * bytes of the index format version, the index version (an etag or a commit) ended by a NUL, then pairs of a version and the JSON
     * line of that release, each ended by a NUL. Pure.
     */
    fun parseCacheFile(bytes: ByteArray): List<IndexEntry> {
        if (bytes.isEmpty()) return emptyList()
        var pos = if (bytes[0].toInt() >= 3) 5 else 1
        pos = nextNul(bytes, pos) + 1 // the index version
        val entries = ArrayList<IndexEntry>()
        while (pos < bytes.size) {
            val versionEnd = nextNul(bytes, pos)
            val jsonEnd = nextNul(bytes, versionEnd + 1)
            if (versionEnd >= bytes.size || jsonEnd > bytes.size) break
            val json = String(bytes, versionEnd + 1, jsonEnd - versionEnd - 1, Charsets.UTF_8)
            parseIndexLine(json)?.let { entries += it }
            pos = jsonEnd + 1
        }
        return entries
    }

    /** A line of the index: `{"name":..,"vers":..,"features":{..},"features2":{..},"yanked":..}`. Pure. */
    fun parseIndexLine(json: String): IndexEntry? {
        val obj = runCatching { JsonParser.parseString(json) as? JsonObject }.getOrNull() ?: return null
        val version = obj.get("vers")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val features = LinkedHashMap<String, List<String>>()
        for (key in listOf("features", "features2")) {
            (obj.get(key) as? JsonObject)?.entrySet()?.forEach { (name, value) ->
                features[name] = value.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
            }
        }
        val yanked = obj.get("yanked")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        return IndexEntry(version, yanked, features, obj.get("rust_version")?.takeIf { it.isJsonPrimitive }?.asString)
    }

    /** Where the index keeps a crate: `1/a`, `2/ab`, `3/a/abc`, `se/rd/serde` (lower case). Pure. */
    fun indexPath(name: String): String {
        val n = name.lowercase()
        return when (n.length) {
            1 -> "1/$n"
            2 -> "2/$n"
            3 -> "3/${n[0]}/$n"
            else -> "${n.substring(0, 2)}/${n.substring(2, 4)}/$n"
        }
    }

    /** `aes-gcm-0.10.3.crate` → (aes-gcm, 0.10.3). Pure. */
    fun parseCrateFileName(fileName: String): Pair<String, String>? = CRATE_FILE.matchEntire(fileName)?.let { it.groupValues[1] to it.groupValues[2] }

    /** The names of the crates cargo has seen: the files of the index caches and the downloaded archives, sorted. */
    fun crateNames(cargoHome: Path): Set<String> {
        val names = HashSet<String>()
        registries(cargoHome, "index").forEach { index ->
            val cache = index.resolve(".cache")
            if (cache.isDirectory()) runCatching {
                Files.walk(cache, 4).use { stream -> stream.filter { it.isRegularFile() && !it.name.startsWith(".") }.forEach { names += it.name } }
            }
        }
        registries(cargoHome, "cache").forEach { dir ->
            dir.toFile().list()?.forEach { file -> parseCrateFileName(file)?.let { names += it.first } }
        }
        return names.toSortedSet()
    }

    /** The releases of [name] the index caches know, merged over the registries, with the versions of downloaded archives. */
    fun entries(cargoHome: Path, name: String): List<IndexEntry> {
        val byVersion = LinkedHashMap<String, IndexEntry>()
        val relative = indexPath(name)
        registries(cargoHome, "index").forEach { index ->
            val file = index.resolve(".cache").resolve(relative)
            if (file.isRegularFile()) runCatching { parseCacheFile(Files.readAllBytes(file)) }.getOrNull()?.forEach { byVersion.putIfAbsent(it.version, it) }
        }
        registries(cargoHome, "cache").forEach { dir ->
            dir.toFile().list()?.forEach { file ->
                parseCrateFileName(file)?.takeIf { it.first.equals(name, ignoreCase = true) }?.let { byVersion.putIfAbsent(it.second, IndexEntry(it.second, false, emptyMap())) }
            }
        }
        return byVersion.values.sortedWith(compareByDescending(nullsFirst()) { SemVersion.parse(it.version) })
    }

    /** The versions of [name] known locally, the newest first, yanked ones left out. */
    fun versions(cargoHome: Path, name: String): List<String> = entries(cargoHome, name).filterNot { it.yanked }.map { it.version }

    /** The manifests of the unpacked sources of [name] (`registry/src/<registry>/<name>-<version>/Cargo.toml`), the newest first. */
    fun sourceManifests(cargoHome: Path, name: String): List<Pair<String, Path>> = registries(cargoHome, "src").flatMap { dir ->
        dir.toFile().listFiles().orEmpty().mapNotNull { child ->
            val (crate, version) = SOURCE_DIR.matchEntire(child.name)?.destructured ?: return@mapNotNull null
            val manifest = File(child, "Cargo.toml")
            if (crate.equals(name, ignoreCase = true) && manifest.isFile) version to manifest.toPath() else null
        }
    }.sortedWith(compareByDescending(nullsFirst()) { SemVersion.parse(it.first) })

    private fun registries(cargoHome: Path, kind: String): List<Path> =
        cargoHome.resolve("registry").resolve(kind).toFile().listFiles().orEmpty().filter { it.isDirectory }.map { it.toPath() }

    private fun nextNul(bytes: ByteArray, from: Int): Int {
        var i = from
        while (i < bytes.size && bytes[i].toInt() != 0) i++
        return i
    }
}
