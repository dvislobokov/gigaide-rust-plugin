package io.github.rustsupport.cargo

/**
 * A version of a crate: `1.2.3`, `1.0.0-beta.2+build`. Lenient: `1.2` is `1.2.0`, a leading `v` is dropped. Pure.
 * Ordering follows SemVer: a pre-release is below its release, the build metadata does not count.
 */
data class SemVersion(val major: Long, val minor: Long, val patch: Long, val pre: String? = null) : Comparable<SemVersion> {
    val isPreRelease: Boolean get() = pre != null

    override fun compareTo(other: SemVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        return when {
            pre == other.pre -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> comparePre(pre, other.pre)
        }
    }

    override fun toString(): String = "$major.$minor.$patch" + (pre?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(text: String): SemVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return SemVersion(major.toLongOrNull() ?: return null, minor.toLongOrNull() ?: 0, patch.toLongOrNull() ?: 0, pre.ifEmpty { null })
        }

        /** Dot-separated identifiers: numbers numerically and below words, words by text, a longer list wins a tie. */
        private fun comparePre(a: String, b: String): Int {
            val left = a.split('.')
            val right = b.split('.')
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i].toLongOrNull()
                val y = right[i].toLongOrNull()
                val c = when {
                    x != null && y != null -> x.compareTo(y)
                    x != null -> -1
                    y != null -> 1
                    else -> left[i].compareTo(right[i])
                }
                if (c != 0) return c
            }
            return left.size.compareTo(right.size)
        }
    }
}

/**
 * A version requirement of Cargo.toml: `1.2` (caret by default), `^0.3`, `~1.2.3`, `=1.0.0`, `>=1, <2`, `1.*`, `*`. Pure.
 * See https://doc.rust-lang.org/cargo/reference/specifying-dependencies.html.
 */
class VersionReq private constructor(private val comparators: List<Comparator>) {
    private enum class Op { CARET, TILDE, EXACT, GT, GE, LT, LE, WILDCARD }

    /** A comparator with a partial version: [minor] and [patch] null when not written. */
    private data class Comparator(val op: Op, val major: Long?, val minor: Long?, val patch: Long?, val pre: String?) {
        fun lower(): SemVersion = SemVersion(major ?: 0, minor ?: 0, patch ?: 0, pre)

        fun matches(v: SemVersion): Boolean {
            if (major == null) return true
            val low = lower()
            return when (op) {
                Op.EXACT -> when {
                    minor == null -> v.major == major
                    patch == null -> v.major == major && v.minor == minor
                    else -> v.compareTo(low) == 0
                }
                Op.GT -> when {
                    minor == null -> v.major > major
                    patch == null -> v.major > major || (v.major == major && v.minor > minor)
                    else -> v > low
                }
                Op.GE -> v >= low
                Op.LT -> v < low
                Op.LE -> when {
                    minor == null -> v.major <= major
                    patch == null -> v.major < major || (v.major == major && v.minor <= minor)
                    else -> v <= low
                }
                Op.TILDE -> v >= low && if (minor == null) v.major == major else v.major == major && v.minor == minor
                Op.WILDCARD -> if (minor == null) v.major == major else v.major == major && v.minor == minor
                Op.CARET -> v >= low && when {
                    major > 0 || minor == null -> v.major == major
                    minor > 0 || patch == null -> v.major == 0L && v.minor == minor
                    else -> v.major == 0L && v.minor == 0L && v.patch == patch
                }
            }
        }
    }

    /** Whether [version] satisfies every comparator; a pre-release only when some comparator names a pre-release of the same x.y.z. */
    fun matches(version: SemVersion): Boolean {
        if (version.isPreRelease && comparators.none { it.pre != null && it.major == version.major && it.minor == version.minor && it.patch == version.patch }) return false
        return comparators.all { it.matches(version) }
    }

    /** The smallest version the requirement names: `1.2` → 1.2.0. */
    fun minimum(): SemVersion = comparators.filter { it.op != Op.LT && it.op != Op.LE }.maxOfOrNull { it.lower() } ?: SemVersion(0, 0, 0)

    /**
     * [latest] is a release the requirement does not allow and newer than what it names: an update needs an edit of Cargo.toml.
     * A newer version the requirement already allows is `cargo update`'s business, not the manifest's.
     */
    fun isOutdatedBy(latest: SemVersion): Boolean = !latest.isPreRelease && !matches(latest) && latest > minimum()

    companion object {
        private val PART = Regex("""^(=|>=|<=|>|<|\^|~)?\s*v?(\*|x|X|\d+)(?:\.(\*|x|X|\d+))?(?:\.(\*|x|X|\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(text: String): VersionReq? {
            val parts = text.split(',').map { it.trim() }
            if (parts.isEmpty() || parts.any { it.isEmpty() }) return null
            val comparators = parts.map { part ->
                val match = PART.matchEntire(part) ?: return null
                val (opText, majorText, minorText, patchText, pre) = match.destructured
                fun number(s: String): Long? = if (s.isEmpty() || s == "*" || s == "x" || s == "X") null else s.toLong()
                val major = number(majorText)
                val minor = if (major == null) null else number(minorText)
                val patch = if (minor == null) null else number(patchText)
                val wildcard = listOf(majorText, minorText, patchText).any { it == "*" || it == "x" || it == "X" }
                val op = when (opText) {
                    "=" -> Op.EXACT
                    ">" -> Op.GT
                    ">=" -> Op.GE
                    "<" -> Op.LT
                    "<=" -> Op.LE
                    "~" -> Op.TILDE
                    "^" -> Op.CARET
                    else -> if (wildcard) Op.WILDCARD else Op.CARET
                }
                Comparator(op, major, minor, patch, pre.ifEmpty { null }.takeIf { patch != null })
            }
            return VersionReq(comparators)
        }
    }
}
