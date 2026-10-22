package io.github.rustsupport.debugger

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The CodeLLDB release the plugin offers to download: the VSIX of a platform from the GitHub releases of vadimcn/codelldb. A VSIX is a
 * zip; what the adapter needs of it is `extension/adapter` (the adapter), `extension/lldb` (its LLDB with Python), `extension/lang_support`
 * (the Rust formatters loader: the adapter imports it from the folder next to `adapter`).
 */
object CodeLldbRelease {
    const val VERSION = "1.12.3"
    const val REPOSITORY_URL = "https://github.com/vadimcn/codelldb/releases"

    /** A platform of the release; [sizeMb] is the size of its VSIX, for the confirmation before the download. */
    enum class Asset(val id: String, val sizeMb: Int) {
        WIN32_X64("win32-x64", 49),
        LINUX_X64("linux-x64", 53),
        LINUX_ARM64("linux-arm64", 52),
        LINUX_ARMHF("linux-armhf", 50),
        DARWIN_X64("darwin-x64", 46),
        DARWIN_ARM64("darwin-arm64", 43);

        val fileName: String get() = "codelldb-$id.vsix"
    }

    /** The asset of an OS (`windows`, `linux`, `mac`) and of `os.arch`; null where there is none (Windows on ARM, 32-bit x86). */
    fun asset(os: String, arch: String): Asset? {
        val cpu = when (arch.lowercase()) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            "arm", "armv7", "armv7l", "armhf" -> "armhf"
            else -> return null
        }
        return when (os) {
            "windows" -> Asset.WIN32_X64.takeIf { cpu == "x64" }
            "linux" -> when (cpu) {
                "x64" -> Asset.LINUX_X64
                "arm64" -> Asset.LINUX_ARM64
                else -> Asset.LINUX_ARMHF
            }
            "mac" -> when (cpu) {
                "x64" -> Asset.DARWIN_X64
                "arm64" -> Asset.DARWIN_ARM64
                else -> null
            }
            else -> null
        }
    }

    fun currentAsset(): Asset? = asset(currentOs(), System.getProperty("os.arch").orEmpty())

    fun currentOs(): String = when {
        SystemInfo.isWindows -> "windows"
        SystemInfo.isMac -> "mac"
        else -> "linux"
    }

    fun downloadUrl(asset: Asset, version: String = VERSION): String = "$REPOSITORY_URL/download/v$version/${asset.fileName}"

    /** What of the VSIX is unpacked, as paths inside `extension/`. */
    val UNPACKED_FOLDERS = listOf("adapter/", "lldb/", "lang_support/")

    /** The entry of the VSIX as the path it gets under the install directory, or null when it is not needed. */
    fun unpackedPath(entry: String): String? {
        val normalized = entry.replace('\\', '/')
        if (!normalized.startsWith("extension/") || normalized.endsWith("/") || ".." in normalized.split('/')) return null
        val path = normalized.removePrefix("extension/")
        return path.takeIf { p -> UNPACKED_FOLDERS.any { p.startsWith(it) } }
    }
}

/** Where a CodeLLDB comes from: what Settings shows next to the path. */
enum class CodeLldbSource(val title: String) {
    SETTINGS("the path in Settings"),
    VS_CODE("VS Code extension"),
    VS_CODE_INSIDERS("VS Code Insiders extension"),
    CURSOR("Cursor extension"),
    VSCODIUM("VSCodium extension"),
    DOWNLOADED("downloaded by the plugin"),
}

/** A CodeLLDB adapter found on this machine. [version] is null when the place does not tell it (a path set by hand). */
data class CodeLldbInstallation(val adapter: Path, val version: String?, val source: CodeLldbSource) {
    fun describe(): String = "CodeLLDB" + (version?.let { " $it" } ?: "") + " from " + source.title
}

/**
 * Finds CodeLLDB before offering a download, in order: the path of the settings; the CodeLLDB extension of VS Code, VS Code Insiders,
 * Cursor or VSCodium (its newest version; its LLDB is the `lldb` folder of the same extension, which the adapter finds by itself);
 * the one the plugin downloaded into the system directory of the IDE. Pure but for the file system, for the tests.
 */
class CodeLldbLocator(
    private val home: Path,
    private val downloadRoot: Path,
    private val windows: Boolean = SystemInfo.isWindows,
) {
    private val executable = adapterName(windows)

    fun find(configured: String?): CodeLldbInstallation? =
        configured?.takeIf { it.isNotBlank() }?.let(::fromSettings) ?: fromEditors() ?: downloaded()

    /** A path set by hand: the executable itself, or the folder of an extension (or of its `adapter`). */
    fun fromSettings(configured: String): CodeLldbInstallation? {
        val path = Path.of(configured.trim().trim('"'))
        val adapter = listOf(path, path.resolve(executable), path.resolve("adapter").resolve(executable)).firstOrNull { Files.isRegularFile(it) } ?: return null
        return CodeLldbInstallation(adapter, null, CodeLldbSource.SETTINGS)
    }

    fun fromEditors(): CodeLldbInstallation? = EDITORS.firstNotNullOfOrNull { (folder, source) ->
        newestExtension(home.resolve(folder).resolve("extensions"))?.let { (dir, version) -> CodeLldbInstallation(dir.resolve("adapter").resolve(executable), version, source) }
    }

    fun downloaded(version: String = CodeLldbRelease.VERSION): CodeLldbInstallation? {
        val dir = downloadRoot.resolve(version)
        val adapter = dir.resolve("adapter").resolve(executable)
        return if (Files.isRegularFile(dir.resolve(INSTALLED_MARKER)) && Files.isRegularFile(adapter)) CodeLldbInstallation(adapter, version, CodeLldbSource.DOWNLOADED) else null
    }

    /** `vadimcn.vscode-lldb-1.11.8` (maybe with a platform suffix) with an adapter in it: the newest version. */
    private fun newestExtension(extensions: Path): Pair<Path, String>? {
        if (!Files.isDirectory(extensions)) return null
        val found = runCatching { Files.list(extensions).use { stream -> stream.toList() } }.getOrDefault(emptyList())
            .mapNotNull { dir -> extensionVersion(dir.fileName.toString())?.let { dir to it } }
            .filter { (dir, _) -> Files.isRegularFile(dir.resolve("adapter").resolve(executable)) }
        return found.maxWithOrNull { a, b -> compareVersions(a.second, b.second) }
    }

    companion object {
        const val INSTALLED_MARKER = ".installed"
        const val EXTENSION_ID = "vadimcn.vscode-lldb"

        val EDITORS = listOf(
            ".vscode" to CodeLldbSource.VS_CODE,
            ".vscode-insiders" to CodeLldbSource.VS_CODE_INSIDERS,
            ".cursor" to CodeLldbSource.CURSOR,
            ".vscode-oss" to CodeLldbSource.VSCODIUM,
        )

        private val EXTENSION_DIR = Regex("""^${Regex.escape(EXTENSION_ID)}-(\d+(?:\.\d+)*)(?:-.*)?$""", RegexOption.IGNORE_CASE)

        fun extensionVersion(folder: String): String? = EXTENSION_DIR.matchEntire(folder)?.groupValues?.get(1)

        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        /** `<system>/rust-support/codelldb`: one folder per version under it. */
        fun downloadRoot(): Path = Path.of(PathManager.getSystemPath(), "rust-support", "codelldb")

        fun default(): CodeLldbLocator = CodeLldbLocator(Path.of(System.getProperty("user.home")), downloadRoot())

        /** The adapter the debugger uses now, or null: then the download is offered. Touches the file system only. */
        fun current(): CodeLldbInstallation? = default().find(RustDebuggerSettings.getInstance().codelldbPath)

        fun adapterName(windows: Boolean = SystemInfo.isWindows): String = if (windows) "codelldb.exe" else "codelldb"

        fun isExecutable(path: Path): Boolean = File(path.toString()).let { it.isFile && it.canExecute() }
    }
}
