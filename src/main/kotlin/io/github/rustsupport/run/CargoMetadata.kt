package io.github.rustsupport.run

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger
import io.github.rustsupport.toolchain.RustTool
import io.github.rustsupport.toolchain.RustToolchain
import java.io.File

/** A target of a package: [kinds] as cargo names them (`bin`, `lib`, `test`, `example`, `bench`, `proc-macro`, `custom-build`). */
data class CargoTarget(val name: String, val kinds: List<String>, val srcPath: String) {
    /** How the target is selected on the command line: `--bin name`; null for a build script, which is never selected. */
    val kind: CargoTargetKind? get() = kinds.firstNotNullOfOrNull(CargoTargetKind::ofMetadataKind)
}

data class CargoPackage(val name: String, val manifestPath: String, val targets: List<CargoTarget>, val features: List<String>) {
    val directory: String get() = File(manifestPath).parent.orEmpty()
}

data class CargoWorkspace(val root: String, val packages: List<CargoPackage>)

/** `cargo metadata --format-version 1 --no-deps`: the packages of the workspace and their targets. */
object CargoMetadata {
    private val LOG = logger<CargoMetadata>()

    /** The JSON of `cargo metadata`; null when it is not that. Pure. */
    fun parse(json: String): CargoWorkspace? {
        val root = runCatching { JsonParser.parseString(json.trim()) as? JsonObject }.getOrNull() ?: return null
        val packages = root.array("packages")?.mapNotNull { element ->
            val pkg = element as? JsonObject ?: return@mapNotNull null
            val name = pkg.string("name") ?: return@mapNotNull null
            val targets = pkg.array("targets")?.mapNotNull { t ->
                val target = t as? JsonObject ?: return@mapNotNull null
                CargoTarget(target.string("name") ?: return@mapNotNull null, target.array("kind")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(), target.string("src_path").orEmpty())
            }.orEmpty()
            val features = (pkg.get("features") as? JsonObject)?.keySet()?.sorted().orEmpty()
            CargoPackage(name, pkg.string("manifest_path").orEmpty(), targets, features)
        } ?: return null
        return CargoWorkspace(root.string("workspace_root").orEmpty(), packages)
    }

    /**
     * Runs `cargo metadata` in [directory]: blocks for up to a few seconds, call off the EDT. `--offline`: the plugin never reaches the
     * network on its own, and `--no-deps` needs nothing from it anyway.
     */
    fun load(directory: String): CargoWorkspace? {
        val cargo = RustTool.CARGO.find() ?: return null
        return try {
            val output = ExecUtil.execAndGetOutput(RustToolchain.commandLine(cargo, directory, "metadata", "--format-version", "1", "--no-deps", "--offline"), 30_000)
            if (output.exitCode != 0) {
                LOG.info("cargo metadata in $directory has failed: ${output.stderr.take(500)}")
                null
            } else parse(output.stdout)
        } catch (e: Exception) {
            LOG.info("cargo metadata in $directory has failed: $e")
            null
        }
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
}

/** What is read from a `Cargo.toml` without a TOML parser: enough for a configuration made without rust-analyzer. Pure. */
object CargoManifest {
    private val SECTION = Regex("""^\s*\[\s*([^\]]+?)\s*]\s*(#.*)?$""")
    private val NAME = Regex("""^\s*name\s*=\s*["']([^"']+)["']""")

    /** `name` of the `[package]` section. */
    fun packageName(text: String): String? {
        var section: String? = null
        for (line in text.lineSequence()) {
            SECTION.matchEntire(line)?.let { section = it.groupValues[1]; return@let }
            if (section == "package") NAME.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** The manifest has a `[workspace]` section: its directory is the root of a workspace. */
    fun isWorkspace(text: String): Boolean = text.lineSequence().any { SECTION.matchEntire(it)?.groupValues?.get(1) == "workspace" }
}
