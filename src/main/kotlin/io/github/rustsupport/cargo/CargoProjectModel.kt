package io.github.rustsupport.cargo

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.rustsupport.run.CargoTarget
import java.io.File

/** The sections of Cargo.toml a dependency is declared in; [metadataKind] is `kind` of `cargo metadata` (null for a normal one). */
enum class CargoDependencyKind(val metadataKind: String?, val title: String) {
    NORMAL(null, "dependencies"), DEV("dev", "dev-dependencies"), BUILD("build", "build-dependencies");

    companion object {
        fun ofMetadata(kind: String?): CargoDependencyKind = entries.firstOrNull { it.metadataKind == kind } ?: NORMAL
    }
}

/** A dependency as the manifest declares it: [name] is the package, [rename] the key when `package = ...` renamed it. */
data class CargoDeclaredDependency(
    val name: String, val req: String, val kind: CargoDependencyKind, val rename: String?, val optional: Boolean, val features: List<String>,
) {
    /** The key in Cargo.toml. */
    val key: String get() = rename ?: name
}

data class CargoMetaPackage(
    val id: String,
    val name: String,
    val version: String,
    /** `registry+https://...`, `git+...`; null for a path package (a member of the workspace, or a local one). */
    val source: String?,
    val manifestPath: String,
    val targets: List<CargoTarget>,
    val features: Map<String, List<String>>,
    val dependencies: List<CargoDeclaredDependency>,
) {
    val directory: String get() = File(manifestPath).parent.orEmpty()
}

/** An edge of the resolved graph: [packageId] is depended on as [kinds]. */
data class CargoResolvedDependency(val packageId: String, val kinds: Set<CargoDependencyKind>)

/** A dependency as the tool window shows it: the package when it is known, else just the declaration ([version] the requirement). */
data class CargoDependencyView(val name: String, val version: String, val kinds: Set<CargoDependencyKind>, val pkg: CargoMetaPackage?, val resolved: Boolean)

/**
 * A workspace as `cargo metadata --format-version 1` describes it. [resolve] is null with `--no-deps`: then the dependencies are only
 * the declarations of the members, without versions nor transitive ones.
 */
data class CargoProjectData(
    val root: String,
    val packages: Map<String, CargoMetaPackage>,
    val memberIds: List<String>,
    val resolve: Map<String, List<CargoResolvedDependency>>?,
) {
    val members: List<CargoMetaPackage> get() = memberIds.mapNotNull { packages[it] }
    val hasDependencies: Boolean get() = resolve != null

    /** What [packageId] depends on: normal ones first, then build and dev ones, each by name. */
    fun dependencies(packageId: String): List<CargoDependencyView> {
        val graph = resolve
        if (graph != null) {
            return graph[packageId].orEmpty().mapNotNull { edge ->
                val pkg = packages[edge.packageId] ?: return@mapNotNull null
                CargoDependencyView(pkg.name, pkg.version, edge.kinds, pkg, true)
            }.sortedWith(compareBy({ it.kinds.minOf(CargoDependencyKind::ordinal) }, { it.name }))
        }
        val pkg = packages[packageId] ?: return emptyList()
        return pkg.dependencies.groupBy { it.name }.map { (name, decls) ->
            CargoDependencyView(name, decls.first().req, decls.map { it.kind }.toSet(), packages.values.firstOrNull { it.name == name && it.source == null }, false)
        }.sortedWith(compareBy({ it.kinds.minOf(CargoDependencyKind::ordinal) }, { it.name }))
    }

    /** The package of the manifest at [manifestPath], compared without regard to separators and case. */
    fun packageOfManifest(manifestPath: String): CargoMetaPackage? = packages.values.firstOrNull { samePath(it.manifestPath, manifestPath) }

    /**
     * The package a manifest means by the dependency key [key]: through the resolved graph of the package of that manifest when it is
     * one, else the newest package of that name (a `[workspace.dependencies]` entry of a virtual manifest).
     */
    fun dependencyPackage(manifestPath: String, key: String): CargoMetaPackage? {
        val owner = packageOfManifest(manifestPath)
        val declared = owner?.dependencies?.firstOrNull { it.key == key }
        val name = declared?.name ?: key
        if (owner != null) {
            resolve?.get(owner.id)?.mapNotNull { packages[it.packageId] }?.firstOrNull { it.name == name }?.let { return it }
            // --no-deps: only the path dependencies are known
        }
        return packages.values.filter { it.name == name }.maxWithOrNull(compareBy(nullsFirst()) { SemVersion.parse(it.version) })
    }

    companion object {
        fun samePath(a: String, b: String): Boolean = normalize(a) == normalize(b)

        private fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/').lowercase()

        /** The JSON of `cargo metadata --format-version 1`, with or without `--no-deps`; null when it is not that. Pure. */
        fun parse(json: String): CargoProjectData? {
            val root = runCatching { JsonParser.parseString(json.trim()) as? JsonObject }.getOrNull() ?: return null
            val packageArray = root.array("packages") ?: return null
            val packages = LinkedHashMap<String, CargoMetaPackage>()
            for (element in packageArray) {
                val pkg = element as? JsonObject ?: continue
                val id = pkg.string("id") ?: continue
                val name = pkg.string("name") ?: continue
                val targets = pkg.array("targets")?.mapNotNull { t ->
                    val target = t as? JsonObject ?: return@mapNotNull null
                    CargoTarget(target.string("name") ?: return@mapNotNull null, target.strings("kind"), target.string("src_path").orEmpty())
                }.orEmpty()
                val features = LinkedHashMap<String, List<String>>()
                (pkg.get("features") as? JsonObject)?.entrySet()?.sortedBy { it.key }?.forEach { (k, v) -> features[k] = (v as? JsonArray)?.let(::strings).orEmpty() }
                val dependencies = pkg.array("dependencies")?.mapNotNull { d ->
                    val dep = d as? JsonObject ?: return@mapNotNull null
                    CargoDeclaredDependency(
                        dep.string("name") ?: return@mapNotNull null, dep.string("req").orEmpty(), CargoDependencyKind.ofMetadata(dep.string("kind")),
                        dep.string("rename"), dep.get("optional")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false, dep.strings("features"),
                    )
                }.orEmpty()
                packages[id] = CargoMetaPackage(id, name, pkg.string("version").orEmpty(), pkg.string("source"), pkg.string("manifest_path").orEmpty(), targets, features, dependencies)
            }
            val members = root.strings("workspace_members").filter { it in packages }.ifEmpty { packages.values.filter { it.source == null }.map { it.id } }
            val resolve = (root.get("resolve") as? JsonObject)?.array("nodes")?.mapNotNull { n ->
                val node = n as? JsonObject ?: return@mapNotNull null
                val id = node.string("id") ?: return@mapNotNull null
                val deps = node.array("deps")?.mapNotNull { d ->
                    val dep = d as? JsonObject ?: return@mapNotNull null
                    val kinds = dep.array("dep_kinds")?.mapNotNull { (it as? JsonObject)?.let { k -> CargoDependencyKind.ofMetadata(k.string("kind")) } }?.toSet()
                    CargoResolvedDependency(dep.string("pkg") ?: return@mapNotNull null, kinds?.ifEmpty { null } ?: setOf(CargoDependencyKind.NORMAL))
                }.orEmpty()
                id to deps
            }?.toMap()
            return CargoProjectData(root.string("workspace_root").orEmpty(), packages, members, resolve)
        }

        private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
        private fun JsonObject.strings(name: String): List<String> = array(name)?.let(::strings).orEmpty()
        private fun strings(array: JsonArray): List<String> = array.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
    }
}
