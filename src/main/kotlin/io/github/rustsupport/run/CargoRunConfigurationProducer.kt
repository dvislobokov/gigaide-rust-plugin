package io.github.rustsupport.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.rustsupport.lang.RustFile
import io.github.rustsupport.lang.RustTokenTypes

/** What a place of the code runs: a runnable of rust-analyzer, or `fn main` of a binary found without it. */
data class CargoRunTarget(val name: String, val invocation: CargoInvocation, val workingDirectory: String?, val environment: Map<String, String> = emptyMap())

object CargoRunTargets {
    /** The target of [element]: the runnable whose name it is, else the innermost runnable around it, else `fn main` of a binary. */
    fun of(element: PsiElement): CargoRunTarget? {
        val file = element.containingFile as? RustFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val runnables = CargoRunnablesCache.getInstance(file.project).let { it.runnables(virtualFile, document) ?: it.lastKnown(virtualFile) }
        if (!runnables.isNullOrEmpty()) {
            val offset = element.textRange.startOffset
            val located = runnables.filter { it.nameStart != null && it.rangeStart != null && it.rangeEnd != null }
            val runnable = located.firstOrNull { CargoRunnables.offset(document, it.nameStart!!) == offset }
                ?: located.filter { CargoRunnables.offset(document, it.rangeStart!!) <= offset && offset < CargoRunnables.offset(document, it.rangeEnd!!) }
                    .minByOrNull { CargoRunnables.offset(document, it.rangeEnd!!) - CargoRunnables.offset(document, it.rangeStart!!) }
            if (runnable != null) return CargoRunTarget(runnable.configurationName(), runnable.invocation(), runnable.cwd, runnable.environment)
        }
        // nothing from rust-analyzer: the binary the file is, when it has a `fn main`
        if (runnables.isNullOrEmpty() && document.immutableCharSequence.contains("fn main")) return CargoMainTargets.of(virtualFile)
        return null
    }

    /** The runnable whose name starts at [element], for the gutter; null when rust-analyzer has not told yet. */
    fun runnableNamedBy(element: PsiElement, file: VirtualFile): CargoRunnable? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val runnables = CargoRunnablesCache.getInstance(element.project).runnables(file, document) ?: return null
        val offset = element.textRange.startOffset
        return runnables.firstOrNull { it.nameStart != null && CargoRunnables.offset(document, it.nameStart) == offset }
    }

    /** Whether the gutter is up to rust-analyzer for [file]: it has answered for it. */
    fun knowsRunnables(element: PsiElement, file: VirtualFile): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        return !CargoRunnablesCache.getInstance(element.project).runnables(file, document).isNullOrEmpty()
    }
}

/** `fn main` of `src/main.rs`, `src/bin/x.rs`, `src/bin/x/main.rs`, `examples/x.rs` without rust-analyzer: the package is read from `Cargo.toml`. */
object CargoMainTargets {
    /** The kind and the name of the target of a file, by the conventions of cargo: [packageName] for `src/main.rs`. Pure. */
    fun targetOf(relativePath: String, packageName: String): Pair<CargoTargetKind, String>? {
        val path = relativePath.replace('\\', '/')
        return when {
            path == "src/main.rs" -> CargoTargetKind.BIN to packageName
            path.startsWith("src/bin/") && path.count { it == '/' } == 2 && path.endsWith(".rs") -> CargoTargetKind.BIN to path.removePrefix("src/bin/").removeSuffix(".rs")
            path.startsWith("src/bin/") && path.count { it == '/' } == 3 && path.endsWith("/main.rs") -> CargoTargetKind.BIN to path.removePrefix("src/bin/").substringBefore('/')
            path.startsWith("examples/") && path.count { it == '/' } == 1 && path.endsWith(".rs") -> CargoTargetKind.EXAMPLE to path.removePrefix("examples/").removeSuffix(".rs")
            path.startsWith("examples/") && path.count { it == '/' } == 2 && path.endsWith("/main.rs") -> CargoTargetKind.EXAMPLE to path.removePrefix("examples/").substringBefore('/')
            else -> null
        }
    }

    fun of(file: VirtualFile): CargoRunTarget? {
        var directory = file.parent
        var depth = 0
        while (directory != null && depth < 4) {
            val manifest = directory.findChild("Cargo.toml")
            if (manifest != null) {
                val text = runCatching { VfsUtilCore.loadText(manifest) }.getOrNull() ?: return null
                val packageName = CargoManifest.packageName(text) ?: return null
                val relative = VfsUtilCore.getRelativePath(file, directory) ?: return null
                val (kind, name) = targetOf(relative, packageName) ?: return null
                val invocation = CargoInvocation(listOf("run"), packageName = packageName, targetKind = kind, targetName = name)
                return CargoRunTarget("Run $name", invocation, workspaceRoot(directory))
            }
            directory = directory.parent
            depth++
        }
        return null
    }

    /** The nearest directory up from the package with a `[workspace]` manifest, or the package itself. */
    private fun workspaceRoot(packageDirectory: VirtualFile): String {
        var directory: VirtualFile? = packageDirectory
        var depth = 0
        while (directory != null && depth < 6) {
            val manifest = directory.findChild("Cargo.toml")
            if (manifest != null && runCatching { CargoManifest.isWorkspace(VfsUtilCore.loadText(manifest)) }.getOrDefault(false)) return directory.path
            directory = directory.parent
            depth++
        }
        return packageDirectory.path
    }

    /** [element] is the name of `fn main`. */
    fun isMainName(element: PsiElement): Boolean {
        if (element.elementType != RustTokenTypes.IDENTIFIER || element.text != "main") return false
        val previous = PsiTreeUtil.prevVisibleLeaf(element) ?: return false
        return previous.elementType == RustTokenTypes.KEYWORD && previous.text == "fn"
    }
}

class CargoRunConfigurationProducer : LazyRunConfigurationProducer<CargoRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = CargoConfigurationType.instance.factory

    override fun setupConfigurationFromContext(configuration: CargoRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val element = context.psiLocation ?: return false
        val target = CargoRunTargets.of(element) ?: return false
        val invocation = target.invocation
        configuration.options.apply {
            command = invocation.command.joinToString(" ")
            packageName = invocation.packageName
            targetKind = invocation.targetKind
            targetName = invocation.targetName
            features = invocation.features
            allFeatures = invocation.allFeatures
            noDefaultFeatures = invocation.noDefaultFeatures
            cargoArguments = invocation.cargoArguments.takeIf { it.isNotEmpty() }?.let(com.intellij.util.execution.ParametersListUtil::join)
            programArguments = invocation.programArguments.takeIf { it.isNotEmpty() }?.let(com.intellij.util.execution.ParametersListUtil::join)
            workingDirectory = target.workingDirectory
            environment = LinkedHashMap(target.environment)
        }
        configuration.name = target.name
        return true
    }

    override fun isConfigurationFromContext(configuration: CargoRunConfiguration, context: ConfigurationContext): Boolean {
        val element = context.psiLocation ?: return false
        val target = CargoRunTargets.of(element) ?: return false
        return configuration.invocation() == target.invocation &&
            normalize(configuration.workingDirectory()) == normalize(target.workingDirectory ?: configuration.project.basePath.orEmpty())
    }

    private fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/').lowercase()
}

/** ▶ in the gutter at the names rust-analyzer runs (`fn main`, tests, test modules, benchmarks), or at `fn main` while it has not told. */
class CargoRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.elementType != RustTokenTypes.IDENTIFIER) return null
        val file = element.containingFile as? RustFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val runnable = CargoRunTargets.runnableNamedBy(element, virtualFile)
        if (runnable != null) {
            val icon = if (runnable.label.startsWith("test-mod")) AllIcons.RunConfigurations.TestState.Run_run else AllIcons.RunConfigurations.TestState.Run
            return Info(icon, ExecutorAction.getActions(0)) { runnable.label }
        }
        if (CargoMainTargets.isMainName(element) && !CargoRunTargets.knowsRunnables(element, virtualFile) && CargoMainTargets.of(virtualFile) != null) {
            return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { "Run the program" }
        }
        return null
    }
}
