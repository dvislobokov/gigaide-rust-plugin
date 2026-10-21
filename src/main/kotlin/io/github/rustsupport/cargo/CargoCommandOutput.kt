package io.github.rustsupport.cargo

/** A feature of a crate as `cargo info` lists it: `+` marks the ones `default` enables. */
data class CargoInfoFeature(val name: String, val enables: List<String>, val default: Boolean)

/**
 * What `cargo info <crate>` says of a crate. [version] is the version cargo describes (the locked one inside a workspace that
 * depends on it), [latest] the newest release it knows of: the same version when cargo prints no `(latest ...)`.
 */
data class CargoInfo(val name: String, val description: String, val version: String, val latest: String, val features: List<CargoInfoFeature>)

/**
 * The text output of `cargo info` (stable since cargo 1.82). Pure. Its shape, as cargo 1.99 prints it:
 * ```
 * serde #serde #serialization #no_std
 * A generic serialization/deserialization framework
 * version: 1.0.200 (latest 1.0.229)
 * license: MIT OR Apache-2.0
 * ...
 * features:
 *  +default      = [std]
 *   std          = [serde_core/std]
 * note: to see how you depend on serde, run `cargo tree --invert serde@1.0.200`
 * ```
 */
object CargoInfoParser {
    private val FIELD = Regex("""^([a-z][a-z.-]*):(?:\s+(.*))?$""")
    private val VERSION = Regex("""^v?(\S+?)(?:\s*\((.*)\))?$""")
    private val LATEST = Regex("""latest\s+v?([0-9][0-9A-Za-z.+-]*)""")
    private val FEATURE = Regex("""^\s*(\+)?\s*([^\s=]+)\s*=\s*\[(.*)]\s*$""")

    fun parse(text: String): CargoInfo? {
        val lines = text.lines().map { it.trimEnd() }.filterNot { it.startsWith("warning:") || it.trimStart().startsWith("Updating ") || it.trimStart().startsWith("Downloaded ") || it.trimStart().startsWith("Downloading ") }
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return null
        val name = lines[first].trim().substringBefore(' ').takeIf { it.isNotEmpty() && !it.endsWith(":") } ?: return null
        val description = StringBuilder()
        var version: String? = null
        var latest: String? = null
        val features = ArrayList<CargoInfoFeature>()
        var inFeatures = false
        var seenField = false
        for (line in lines.drop(first + 1)) {
            if (line.isBlank()) continue
            if (inFeatures && line.first().isWhitespace()) {
                FEATURE.matchEntire(line)?.let { m ->
                    val enables = m.groupValues[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    features += CargoInfoFeature(m.groupValues[2], enables, m.groupValues[1] == "+")
                }
                continue
            }
            inFeatures = false
            val field = FIELD.matchEntire(line)
            if (field == null) {
                if (!seenField) description.append(if (description.isEmpty()) "" else " ").append(line.trim())
                continue
            }
            seenField = true
            val value = field.groupValues[2].trim()
            when (field.groupValues[1]) {
                "version" -> VERSION.matchEntire(value)?.let { m ->
                    version = m.groupValues[1]
                    latest = LATEST.find(m.groupValues[2])?.groupValues?.get(1)
                }
                "features" -> inFeatures = true
            }
        }
        val described = version ?: return null
        return CargoInfo(name, description.toString(), described, latest ?: described, features)
    }
}

/** A line of `cargo update --dry-run --verbose`: a package of the lock file, what it would move to, the newest release beyond its requirement. */
data class CargoUpdateEntry(val name: String, val current: String, val compatible: String?, val available: String?) {
    /** The newest version cargo has named for the crate. */
    val latest: String get() = listOfNotNull(current, compatible, available).maxWithOrNull(compareBy(nullsFirst()) { SemVersion.parse(it) }) ?: current
}

/**
 * The status lines of `cargo update --dry-run --verbose` (on stderr). Pure. Shapes:
 * ```
 *     Updating crates.io index
 *      Locking 2 packages to latest compatible versions
 *     Updating anyhow v1.0.80 -> v1.0.104
 *     Updating rand v0.8.4 -> v0.8.5 (available: v0.9.2)
 *    Unchanged base64 v0.21.7 (available: v0.22.1)
 *  Downgrading foo v1.2.0 -> v1.1.0
 *       Adding bar v0.1.0
 *     Removing baz v0.2.0
 * warning: aborting update due to dry run
 * ```
 */
object CargoUpdateParser {
    private val LINE = Regex("""^\s*(Updating|Downgrading|Unchanged|Adding|Removing)\s+(\S+)\s+v(\d\S*)(?:\s*->\s*v(\d\S*))?(?:\s*\(available:\s*v(\d[^)\s]*)\))?.*$""")

    fun parse(text: String): List<CargoUpdateEntry> = text.lineSequence().mapNotNull { line ->
        val m = LINE.matchEntire(line) ?: return@mapNotNull null
        if (m.groupValues[1] == "Removing") return@mapNotNull null
        CargoUpdateEntry(m.groupValues[2], m.groupValues[3], m.groupValues[4].ifEmpty { null }, m.groupValues[5].ifEmpty { null })
    }.toList()
}
