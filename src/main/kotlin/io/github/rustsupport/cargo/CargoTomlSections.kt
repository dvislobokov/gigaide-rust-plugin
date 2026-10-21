package io.github.rustsupport.cargo

/** Which tables of Cargo.toml hold dependencies, by the segments of their header. Pure, without the TOML plugin. */
object CargoTomlSections {
    private val DEPENDENCY_TABLES = setOf("dependencies", "dev-dependencies", "build-dependencies", "dev_dependencies", "build_dependencies")

    /** `[dependencies]`, `[dev-dependencies]`, `[target.'cfg(windows)'.dependencies]`, `[workspace.dependencies]`. */
    fun isDependencyTable(header: List<String>): Boolean = when (header.size) {
        1 -> header[0] in DEPENDENCY_TABLES
        2 -> header[0] == "workspace" && header[1] == "dependencies"
        3 -> header[0] == "target" && header[2] in DEPENDENCY_TABLES
        else -> false
    }

    /** `[dependencies.serde]` and the like: the key of the dependency the table is of, else null. */
    fun dependencyOfTable(header: List<String>): String? =
        if (header.size >= 2 && isDependencyTable(header.dropLast(1))) header.last() else null
}
