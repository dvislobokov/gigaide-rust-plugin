package io.github.rustsupport.cargo.toml

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.rustsupport.cargo.CargoProjects
import io.github.rustsupport.cargo.CargoRegistry
import io.github.rustsupport.cargo.CargoTomlSections
import io.github.rustsupport.cargo.SemVersion
import io.github.rustsupport.cargo.VersionReq
import io.github.rustsupport.toolchain.RustToolchain
import org.toml.lang.psi.TomlArray
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlHeaderOwner
import org.toml.lang.psi.TomlInlineTable
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlKeyValueOwner
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.TomlTable
import org.toml.lang.psi.ext.TomlLiteralKind
import org.toml.lang.psi.ext.kind
import java.io.File

/**
 * A dependency of Cargo.toml: `serde = "1"`, `serde = { version = "1", features = [...] }` in a dependency table, or a
 * `[dependencies.serde]` table. [crateName] is `package = ...` when the key renames the crate.
 */
data class CargoTomlDependency(
    val key: String,
    val crateName: String,
    val keyElement: PsiElement,
    val versionLiteral: TomlLiteral?,
    val features: TomlArray?,
    val path: String?,
) {
    val version: String? get() = versionLiteral?.let(CargoTomlPsi::stringValue)
}

/** Cargo.toml on the PSI of the TOML plugin (loaded only with it). */
object CargoTomlPsi {
    const val MANIFEST = "Cargo.toml"

    fun isManifest(file: PsiFile?): Boolean = file is TomlFile && file.name == MANIFEST

    fun stringValue(literal: TomlLiteral): String? = (literal.kind as? TomlLiteralKind.String)?.value

    private fun headerOf(owner: TomlHeaderOwner): List<String> = owner.header.key?.segments?.map { it.name ?: it.text }.orEmpty()

    /** Every dependency of the manifest, in every dependency table. */
    fun dependencies(file: TomlFile): List<CargoTomlDependency> = file.children.filterIsInstance<TomlTable>().flatMap(::dependencies)

    private fun dependencies(table: TomlTable): List<CargoTomlDependency> {
        val header = headerOf(table)
        if (CargoTomlSections.isDependencyTable(header)) {
            return table.entries.mapNotNull { kv ->
                val segment = kv.key.segments.singleOrNull() ?: return@mapNotNull null
                val key = segment.name ?: segment.text
                when (val value = kv.value) {
                    is TomlInlineTable -> describe(key, segment, value)
                    is TomlLiteral -> CargoTomlDependency(key, key, segment, value, null, null)
                    else -> CargoTomlDependency(key, key, segment, null, null, null)
                }
            }
        }
        val key = CargoTomlSections.dependencyOfTable(header) ?: return emptyList()
        val segment = table.header.key?.segments?.lastOrNull() ?: return emptyList()
        return listOf(describe(key, segment, table))
    }

    private fun describe(key: String, keyElement: PsiElement, owner: TomlKeyValueOwner): CargoTomlDependency {
        fun entry(name: String): TomlKeyValue? = owner.entries.firstOrNull { it.key.segments.singleOrNull()?.let { s -> s.name ?: s.text } == name }
        val crate = (entry("package")?.value as? TomlLiteral)?.let(::stringValue) ?: key
        val path = (entry("path")?.value as? TomlLiteral)?.let(::stringValue)
        return CargoTomlDependency(key, crate, keyElement, entry("version")?.value as? TomlLiteral, entry("features")?.value as? TomlArray, path)
    }

    /** The dependency whose key, version or features [element] is in. */
    fun dependencyAt(element: PsiElement): CargoTomlDependency? {
        val file = element.containingFile as? TomlFile ?: return null
        if (!isManifest(file) && file.originalFile.name != MANIFEST) return null
        val table = PsiTreeUtil.getParentOfType(element, TomlTable::class.java, false) ?: return null
        return dependencies(table).firstOrNull { dep ->
            PsiTreeUtil.isAncestor(dep.keyElement, element, false) || dep.versionLiteral?.let { PsiTreeUtil.isAncestor(it, element, false) } == true ||
                dep.features?.let { PsiTreeUtil.isAncestor(it, element, false) } == true
        }
    }

    /** The key segment [element] is in, when it is the key of a dependency. */
    fun dependencyOfKey(element: PsiElement): CargoTomlDependency? {
        val segment = PsiTreeUtil.getParentOfType(element, TomlKeySegment::class.java, false) ?: return null
        return dependencyAt(segment)?.takeIf { it.keyElement == segment }
    }

    /**
     * The manifest of the package a dependency of [manifestPath] means: a `path` dependency's own, the package `cargo metadata`
     * resolved, else the newest unpacked source in the registry that satisfies the requirement.
     */
    fun manifestOf(project: Project, manifestPath: String, dependency: CargoTomlDependency): String? {
        dependency.path?.let { path ->
            val dir = File(path).let { if (it.isAbsolute) it else File(File(manifestPath).parentFile, path) }
            File(dir, MANIFEST).takeIf { it.isFile }?.let { return it.canonicalPath }
        }
        val projects = CargoProjects.getInstance(project)
        val state = projects.state
        if (state == null) projects.ensureLoaded()
        state?.let { s -> (s.workspaceOf(manifestPath) ?: s.workspaces.firstOrNull())?.dependencyPackage(manifestPath, dependency.key)?.let { return it.manifestPath } }
        val req = dependency.version?.let(VersionReq::parse)
        val sources = CargoRegistry.sourceManifests(RustToolchain.cargoHome(), dependency.crateName)
        return (sources.firstOrNull { (version, _) -> req == null || SemVersion.parse(version)?.let(req::matches) == true } ?: sources.firstOrNull())?.second?.toString()
    }

    /** The features of a dependency: of the package cargo metadata resolved, else of the newest cached release that fits the requirement. */
    fun featuresOf(project: Project, manifestPath: String, dependency: CargoTomlDependency): List<String> {
        val projects = CargoProjects.getInstance(project)
        val state = projects.state
        if (state == null) projects.ensureLoaded()
        state?.let { s -> (s.workspaceOf(manifestPath) ?: s.workspaces.firstOrNull())?.dependencyPackage(manifestPath, dependency.key)?.let { pkg -> return pkg.features.keys.toList() } }
        val req = dependency.version?.let(VersionReq::parse)
        val entries = CargoRegistry.entries(RustToolchain.cargoHome(), dependency.crateName).filterNot { it.yanked }
        val entry = entries.firstOrNull { e -> req == null || SemVersion.parse(e.version)?.let(req::matches) == true } ?: entries.firstOrNull()
        return entry?.features?.keys?.sorted().orEmpty()
    }
}
