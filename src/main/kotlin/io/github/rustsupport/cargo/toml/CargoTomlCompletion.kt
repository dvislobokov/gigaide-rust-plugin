package io.github.rustsupport.cargo.toml

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import io.github.rustsupport.RustIcons
import io.github.rustsupport.cargo.CargoCrateVersions
import io.github.rustsupport.cargo.CargoRegistry
import io.github.rustsupport.toolchain.RustToolchain
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.TomlTable
import java.util.concurrent.atomic.AtomicReference

/**
 * Completion in Cargo.toml from what is on disk only, never the network: the names of the crates cargo has seen (the cache of the
 * registry index, the downloaded archives), their versions, and the features of a dependency (`cargo metadata`, else the index).
 */
class CargoTomlCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(TomlFile::class.java)), Provider())
    }

    private class Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            if (parameters.originalFile.name != CargoTomlPsi.MANIFEST) return
            val position = parameters.position
            when (val parent = position.parent) {
                is TomlKeySegment -> {
                    val dependency = CargoTomlPsi.dependencyOfKey(parent) ?: return
                    val prefix = prefixIn(position, parameters.offset, 0)
                    val inTable = parent.parent?.parent is TomlKeyValue && parent.parent.parent.parent is TomlTable
                    completeNames(result.withPrefixMatcher(prefix), withVersion = inTable && dependency.versionLiteral == null)
                }
                is TomlLiteral -> {
                    val dependency = CargoTomlPsi.dependencyAt(parent) ?: return
                    val prefix = prefixIn(position, parameters.offset, 1)
                    val manifest = parameters.originalFile.virtualFile?.path ?: return
                    when {
                        dependency.versionLiteral == parent -> completeVersions(result.withPrefixMatcher(prefix), dependency.crateName)
                        dependency.features?.elements?.contains(parent) == true -> {
                            val project = parameters.originalFile.project
                            val present = dependency.features.elements.filterIsInstance<TomlLiteral>().filter { it != parent }.mapNotNull(CargoTomlPsi::stringValue).toSet()
                            val features = CargoTomlPsi.featuresOf(project, manifest, dependency).filterNot { it in present }
                            val sink = result.withPrefixMatcher(prefix)
                            features.forEach { sink.addElement(LookupElementBuilder.create(it).withIcon(AllIcons.Nodes.Property).withTypeText(dependency.crateName)) }
                            sink.stopHere()
                        }
                    }
                }
            }
        }

        /** The text of [position] before the caret, past [skip] characters of an opening quote. */
        private fun prefixIn(position: PsiElement, offset: Int, skip: Int): String {
            val end = (offset - position.textRange.startOffset).coerceIn(0, position.textLength)
            return position.text.substring(0, end).drop(skip)
        }

        private fun completeNames(result: CompletionResultSet, withVersion: Boolean) {
            for (name in crateNames()) {
                var element = LookupElementBuilder.create(name).withIcon(RustIcons.CARGO)
                if (withVersion) element = element.withInsertHandler { context, _ -> insertVersion(context, name) }
                result.addElement(element)
            }
            result.addLookupAdvertisement("Crates of the local registry cache; versions with Ctrl+Space inside the quotes")
        }

        /** `serde` → `serde = "1.0.229"`, the newest version known locally, when nothing follows the key yet. */
        private fun insertVersion(context: InsertionContext, name: String) {
            val document = context.document
            val tail = document.charsSequence.subSequence(context.tailOffset, minOf(document.textLength, context.tailOffset + 40)).toString()
            if (tail.trimStart(' ', '\t').startsWith("=")) return
            val version = CargoRegistry.versions(RustToolchain.cargoHome(), name).firstOrNull { !it.contains('-') } ?: CargoCrateVersions.getInstance().latest(name) ?: ""
            document.insertString(context.tailOffset, " = \"$version\"")
            context.editor.caretModel.moveToOffset(context.tailOffset + 4 + version.length)
        }

        private fun completeVersions(result: CompletionResultSet, crate: String) {
            val local = CargoRegistry.entries(RustToolchain.cargoHome(), crate).filterNot { it.yanked }.map { it.version }
            val latest = CargoCrateVersions.getInstance().latest(crate)
            val versions = (listOfNotNull(latest) + local).distinct()
            versions.forEachIndexed { index, version ->
                val element = LookupElementBuilder.create(version).withTypeText(if (version == latest) "latest (cargo)" else "local index").withIcon(RustIcons.CARGO)
                result.addElement(PrioritizedLookupElement.withPriority(element, (versions.size - index).toDouble()))
            }
            result.stopHere()
        }

        companion object {
            private const val NAMES_TTL_MS = 60_000L
            private val names = AtomicReference<Pair<Long, Set<String>>?>(null)

            /** The names of the local registry cache, read again at most once a minute (a walk of a few thousand files). */
            fun crateNames(): Set<String> {
                val cached = names.get()
                if (cached != null && System.currentTimeMillis() - cached.first < NAMES_TTL_MS) return cached.second
                return CargoRegistry.crateNames(RustToolchain.cargoHome()).also { names.set(System.currentTimeMillis() to it) }
            }
        }
    }
}
