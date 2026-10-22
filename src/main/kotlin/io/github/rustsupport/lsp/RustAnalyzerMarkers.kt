package io.github.rustsupport.lsp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.elementType
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.rustsupport.lang.RustFile
import io.github.rustsupport.lang.RustLexer
import io.github.rustsupport.lang.RustTokenTypes
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Position
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * Which names of a file get an icon of "implements" in the gutter, out of `textDocument/documentSymbol` of rust-analyzer; pure, for the
 * tests. A trait is a symbol of kind Interface, an impl block one of kind Object named `impl Trait for Type` (both seen live with
 * 1.99.1); the selection range of an impl is the name of its type, so the name of the trait is found by the lexer in the header.
 */
object RustImplMarkers {
    enum class Kind {
        /** The name of a trait: implemented by the impls of it. */
        TRAIT,
        /** A fn, const or type of a trait: implemented by the items of the impls of the trait. */
        TRAIT_MEMBER,
        /** The name of the trait in the header of `impl Trait for Type`: the impl implements it. */
        TRAIT_IMPL,
        /** An item of a trait impl: it implements the item of the trait. */
        IMPL_MEMBER,
    }

    /** A symbol of `textDocument/documentSymbol` with its positions as offsets of the text. */
    class Symbol(val name: String, val kind: Int, val start: Int, val selectionStart: Int, val children: List<Symbol> = emptyList())

    /** A name with an icon: [offset] is where its token starts, [trait] the trait it is about (null for a trait itself). */
    data class Item(val kind: Kind, val name: String, val offset: Int, val trait: String?)

    const val SYMBOL_INTERFACE = 11
    const val SYMBOL_OBJECT = 19

    fun items(symbols: List<Symbol>, text: CharSequence): List<Item> {
        val out = ArrayList<Item>()
        fun walk(list: List<Symbol>) {
            for (symbol in list) when (symbol.kind) {
                SYMBOL_INTERFACE -> if (nameAt(text, symbol.selectionStart, symbol.name)) {
                    out += Item(Kind.TRAIT, symbol.name, symbol.selectionStart, null)
                    symbol.children.filter { nameAt(text, it.selectionStart, it.name) }.forEach { out += Item(Kind.TRAIT_MEMBER, it.name, it.selectionStart, symbol.name) }
                }
                SYMBOL_OBJECT -> {
                    val trait = traitOfImpl(text, symbol.start, symbol.selectionStart) ?: continue
                    out += Item(Kind.TRAIT_IMPL, trait.second, trait.first, trait.second)
                    symbol.children.filter { nameAt(text, it.selectionStart, it.name) }.forEach { out += Item(Kind.IMPL_MEMBER, it.name, it.selectionStart, trait.second) }
                }
                // modules, functions with items inside them
                else -> walk(symbol.children)
            }
        }
        walk(symbols)
        return out
    }

    /**
     * Whether the text has [name] at [offset]: the server may answer about a text a keystroke older than the one the icons go on, and a
     * name that is not where the answer says is better without an icon than with one on the wrong token.
     */
    fun nameAt(text: CharSequence, offset: Int, name: String): Boolean {
        val clean = name.removePrefix("r#")
        if (offset < 0 || offset + clean.length > text.length) return false
        val at = if (text.startsWith("r#", offset)) offset + 2 else offset
        if (at + clean.length > text.length || text.subSequence(at, at + clean.length).toString() != clean) return false
        return at + clean.length == text.length || !Character.isJavaIdentifierPart(text[at + clean.length])
    }

    private fun CharSequence.startsWith(prefix: String, at: Int): Boolean = at + prefix.length <= length && subSequence(at, at + prefix.length).toString() == prefix

    /**
     * The offset and the name of the trait in the header of an impl that starts at [start] and whose type is at [typeStart] (or, with no
     * sensible type position, ends at its `{`): the last name at the top level of the angle brackets before a `for` that is not `for<'a>`.
     * Null for an inherent impl. `impl<T: Display> fmt::Debug for Wrapper<T>` gives `Debug`; `impl !Send for X` gives `Send`.
     */
    fun traitOfImpl(text: CharSequence, start: Int, typeStart: Int): Pair<Int, String>? {
        if (start < 0 || start >= text.length) return null
        val end = if (typeStart > start) typeStart.coerceAtMost(text.length) else text.length
        val lexer = RustLexer()
        lexer.start(text, start, end, 0)
        var seenImpl = false
        var depth = 0
        var last: Pair<Int, String>? = null
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            val token = text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()
            when {
                type == RustTokenTypes.WHITE_SPACE || type in RustTokenTypes.COMMENTS -> {}
                !seenImpl -> if (type == RustTokenTypes.KEYWORD && token == "impl") seenImpl = true
                type == RustTokenTypes.LBRACE || type == RustTokenTypes.SEMICOLON -> return null
                type == RustTokenTypes.OPERATOR && token != "->" && token != "=>" -> depth = (depth + token.count { it == '<' } - token.count { it == '>' }).coerceAtLeast(0)
                type == RustTokenTypes.IDENTIFIER && depth == 0 -> last = lexer.tokenStart to token
                type == RustTokenTypes.KEYWORD && token == "for" && depth == 0 -> {
                    lexer.advance()
                    while (lexer.tokenType == RustTokenTypes.WHITE_SPACE) lexer.advance()
                    val hrtb = lexer.tokenType == RustTokenTypes.OPERATOR && text[lexer.tokenStart] == '<'
                    if (!hrtb) return last
                    // `for<'a>`: a bound, not the type; its brackets are read as any others (the `<` is not skipped)
                    continue
                }
            }
            lexer.advance()
        }
        return null
    }

    /** Whether a trait or an item of a trait or of an impl is not where the answer says: the answer is about an older text. */
    fun misplaced(symbols: List<Symbol>, text: CharSequence): Boolean = symbols.any { symbol ->
        when (symbol.kind) {
            SYMBOL_INTERFACE -> !nameAt(text, symbol.selectionStart, symbol.name) || symbol.children.any { !nameAt(text, it.selectionStart, it.name) }
            SYMBOL_OBJECT -> symbol.children.any { !nameAt(text, it.selectionStart, it.name) }
            else -> misplaced(symbol.children, text)
        }
    }

    /** What the icon says. */
    fun tooltip(item: Item, implementations: Int?): String = when (item.kind) {
        Kind.TRAIT -> "Implemented in ${count(implementations)}"
        Kind.TRAIT_MEMBER -> "Implemented in ${count(implementations)}"
        Kind.TRAIT_IMPL -> "Implements trait ${item.name}"
        Kind.IMPL_MEMBER -> "Implements ${item.trait}::${item.name}"
    }

    private fun count(n: Int?): String = when (n) {
        null -> "impls"
        1 -> "1 impl"
        else -> "$n impls"
    }

    /** Whether an item asks the server how many implementations it has: only the trait side, the other side always has its one. */
    fun countsImplementations(kind: Kind): Boolean = kind == Kind.TRAIT || kind == Kind.TRAIT_MEMBER

    /** Lines and columns of a text (UTF-16 columns, as the protocol counts and a Java string does). */
    class LineIndex(private val text: CharSequence) {
        private val starts: IntArray = run {
            val list = ArrayList<Int>()
            list += 0
            for (i in text.indices) if (text[i] == '\n') list += i + 1
            list.toIntArray()
        }

        fun offset(line: Int, character: Int): Int {
            if (line < 0) return 0
            if (line >= starts.size) return text.length
            val lineEnd = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
            return (starts[line] + character).coerceAtMost(lineEnd)
        }

        /** Line and column of an offset. */
        fun position(offset: Int): Pair<Int, Int> {
            val clamped = offset.coerceIn(0, text.length)
            var low = 0
            var high = starts.size - 1
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (starts[mid] <= clamped) low = mid else high = mid - 1
            }
            return low to clamped - starts[low]
        }
    }
}

/** An icon of a name: what it is, and for the trait side how many implementations the server has found. */
class RustImplMarker(val item: RustImplMarkers.Item, val implementations: Int?)

/**
 * The icons of the files, by the offsets of their names. The server is asked in the background once per change of a file (one
 * `documentSymbol`, one `implementation` per trait and trait item); the highlighting is restarted when the answers are in. Until then the
 * icons of the previous text stay on the names that are still where they were.
 */
@Service(Service.Level.PROJECT)
class RustImplMarkersService(private val project: Project) {
    private class Entry(val stamp: Long, val markers: Map<Int, RustImplMarker>)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val computing = ConcurrentHashMap.newKeySet<String>()

    fun markers(file: VirtualFile, stamp: Long): Map<Int, RustImplMarker> {
        val entry = entries[file.url]
        if (entry?.stamp != stamp && computing.add(file.url)) ApplicationManager.getApplication().executeOnPooledThread { compute(file, stamp, 0) }
        return entry?.markers.orEmpty()
    }

    private fun compute(file: VirtualFile, stamp: Long, attempt: Int) {
        var retry = false
        try {
            if (project.isDisposed) return
            val client = RustAnalyzer.client(project) ?: return
            val text = ReadAction.compute<CharSequence?, RuntimeException> {
                FileDocumentManager.getInstance().getDocument(file)?.takeIf { file.isValid && it.modificationStamp == stamp }?.immutableCharSequence
            } ?: return
            val index = RustImplMarkers.LineIndex(text)
            val answer = documentSymbols(client, file) ?: return
            val symbols = answer.map { symbol(it, index) }
            val items = RustImplMarkers.items(symbols, text)
            val markers = HashMap<Int, RustImplMarker>()
            var asked = 0
            for (item in items) {
                if (project.isDisposed) return
                if (!RustImplMarkers.countsImplementations(item.kind)) {
                    markers[item.offset] = RustImplMarker(item, null)
                    continue
                }
                if (asked++ >= MAX_QUESTIONS) continue
                val (line, column) = index.position(item.offset)
                val count = RustAnalyzer.implementation(client, file, Position(line, column), TIMEOUT_MS).size
                if (count > 0) markers[item.offset] = RustImplMarker(item, count)
            }
            // a name the answer has not found where it says: the server has not seen the last keystroke yet, ask again in a moment
            retry = attempt < RETRIES && RustImplMarkers.misplaced(symbols, text)
            val previous = entries.put(file.url, Entry(stamp, markers))
            if (previous != null && same(previous.markers, markers)) return
            if (ApplicationManager.getApplication().isUnitTestMode) return
            ApplicationManager.getApplication().invokeLater({
                if (file.isValid) PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
            }, project.disposed)
        } catch (e: ProcessCanceledException) {
            retry = attempt < RETRIES
        } finally {
            computing.remove(file.url)
            if (retry && !project.isDisposed) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule({
                    if (!project.isDisposed && computing.add(file.url)) compute(file, stamp, attempt + 1)
                }, RETRY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun same(a: Map<Int, RustImplMarker>, b: Map<Int, RustImplMarker>): Boolean =
        a.size == b.size && a.all { (offset, marker) -> b[offset]?.let { it.item == marker.item && it.implementations == marker.implementations } == true }

    private fun documentSymbols(client: LspClient, file: VirtualFile): List<DocumentSymbol>? = try {
        client.sendRequestSync(TIMEOUT_MS) { it.textDocumentService.documentSymbol(DocumentSymbolParams(client.getDocumentIdentifier(file))) }
            ?.mapNotNull { if (it.isRight) it.right else null }
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        RustAnalyzerLog.getInstance(project).error("textDocument/documentSymbol has failed: ${e.message ?: e.javaClass.simpleName}")
        null
    }

    private fun symbol(symbol: DocumentSymbol, index: RustImplMarkers.LineIndex): RustImplMarkers.Symbol = RustImplMarkers.Symbol(
        symbol.name.orEmpty(), symbol.kind?.value ?: 0,
        index.offset(symbol.range.start.line, symbol.range.start.character),
        index.offset(symbol.selectionRange.start.line, symbol.selectionRange.start.character),
        symbol.children.orEmpty().map { symbol(it, index) },
    )

    private companion object {
        const val TIMEOUT_MS = 5_000
        const val MAX_QUESTIONS = 200
        const val RETRIES = 2
        const val RETRY_MS = 700L
    }
}

/**
 * The icons of an IDE that knows the type system, from what rust-analyzer knows: "implemented" at a trait and at its items that have
 * implementations, "implements" at the trait of an `impl Trait for Type` and at the items of such an impl. A click goes to the other
 * side through the server, in the background, with a chooser when there is more than one target.
 */
class RustImplLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName(): String = "Implemented and implementing (rust-analyzer)"
    override fun getIcon() = AllIcons.Gutter.ImplementedMethod

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.elementType != RustTokenTypes.IDENTIFIER) return null
        val file = (element.containingFile as? RustFile)?.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
        val project = element.project
        val offset = element.textRange.startOffset
        val marker = project.service<RustImplMarkersService>().markers(file, document.modificationStamp)[offset] ?: return null
        val item = marker.item
        // icons of an older text stay only on the names that have not moved
        if (element.text != item.name && element.text != "r#${item.name}") return null
        val tooltip = RustImplMarkers.tooltip(item, marker.implementations)
        val implemented = item.kind == RustImplMarkers.Kind.TRAIT || item.kind == RustImplMarkers.Kind.TRAIT_MEMBER
        val title = when (item.kind) {
            RustImplMarkers.Kind.TRAIT, RustImplMarkers.Kind.TRAIT_MEMBER -> "Implementations of ${item.name}"
            RustImplMarkers.Kind.TRAIT_IMPL -> "Trait ${item.name}"
            RustImplMarkers.Kind.IMPL_MEMBER -> "${item.trait}::${item.name}"
        }
        val navigation = GutterIconNavigationHandler<PsiElement> { event, clicked ->
            val kind = item.kind
            RustTargets.navigator(project) { RustTargets.of(clicked, kind) }.navigate(event, title, project)
        }
        return LineMarkerInfo(
            element, element.textRange, if (implemented) AllIcons.Gutter.ImplementedMethod else AllIcons.Gutter.ImplementingMethod,
            { tooltip }, navigation, GutterIconRenderer.Alignment.RIGHT, { tooltip },
        )
    }
}

/** Targets of the server as tokens of the files, and the chooser they are shown in. */
object RustTargets {
    private const val TIMEOUT_MS = 15_000

    /** What a click on the icon of [element] leads to; asks the server: call in the background. */
    fun of(element: PsiElement, kind: RustImplMarkers.Kind): List<PsiElement> {
        val (project, file, position) = ReadAction.compute<Triple<Project, VirtualFile, Position>?, RuntimeException> { at(element) } ?: return emptyList()
        val client = RustAnalyzer.client(project) ?: return emptyList()
        val places = when (kind) {
            RustImplMarkers.Kind.TRAIT, RustImplMarkers.Kind.TRAIT_MEMBER -> RustAnalyzer.implementation(client, file, position, TIMEOUT_MS)
            RustImplMarkers.Kind.TRAIT_IMPL -> RustAnalyzer.definition(client, file, position, TIMEOUT_MS)
            // for an item of a trait impl the declaration is the item of the trait (seen live)
            RustImplMarkers.Kind.IMPL_MEMBER -> RustAnalyzer.declaration(client, file, position, TIMEOUT_MS)
        }
        return elements(project, places)
    }

    /** Implementations of what is at [position] of [file]; asks the server: call in the background. */
    fun implementations(project: Project, file: VirtualFile, position: Position): List<PsiElement> {
        val client = RustAnalyzer.client(project) ?: return emptyList()
        return elements(project, RustAnalyzer.implementation(client, file, position, TIMEOUT_MS))
    }

    fun elements(project: Project, places: List<RustAnalyzer.Place>): List<PsiElement> =
        places.mapNotNull { place -> ReadAction.compute<PsiElement?, RuntimeException> { if (project.isDisposed) null else RustAnalyzer.element(project, place) } }

    /** The project, file and position of a token. Needs read access. */
    fun at(element: PsiElement): Triple<Project, VirtualFile, Position>? {
        if (!element.isValid) return null
        val file = element.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return Triple(element.project, file, RustAnalyzer.position(document, element.textRange.startOffset))
    }

    /**
     * A chooser of tokens shown as the lines they are on (`impl Shape for Circle {`) with the file and line: a bare token says nothing
     * of which impl it is. The supplier is run by the navigator in the background, with a progress.
     */
    fun navigator(project: Project, targets: () -> Collection<PsiElement>): PsiTargetNavigator<PsiElement> =
        PsiTargetNavigator(Supplier { if (project.isDisposed) emptyList() else targets() }).presentationProvider { presentation(it) }

    fun navigator(targets: List<PsiElement>): PsiTargetNavigator<PsiElement> = PsiTargetNavigator(targets).presentationProvider { presentation(it) }

    private fun presentation(element: PsiElement): TargetPresentation {
        val file = element.containingFile
        val document = file?.viewProvider?.document
        val offset = element.textRange.startOffset
        val line = document?.getLineNumber(offset.coerceIn(0, document.textLength))
        val text = if (document != null && line != null) {
            val start = document.getLineStartOffset(line)
            val lineText = document.charsSequence.subSequence(start, document.getLineEndOffset(line)).toString()
            derived(lineText, offset - start) ?: lineText.trim()
        } else element.text
        val location = listOfNotNull(file?.name, line?.let { (it + 1).toString() }).joinToString(":")
        return TargetPresentation.builder(text.ifEmpty { element.text }).locationText(location, file?.getIcon(0)).presentation()
    }

    private val DERIVE = Regex("""^\s*#\[\s*derive\s*\(""")

    /** The derives of the compiler: rust-analyzer gives their impls the name in the attribute, not the whole item. */
    private val BUILTIN_DERIVES = setOf("Clone", "Copy", "Debug", "Default", "Hash", "PartialEq", "Eq", "PartialOrd", "Ord")

    /**
     * `#[derive(Debug)]` for the place of an impl made by a derive on [line]: such impls have no source of their own, so the whole line
     * would be the same for all of them (seen by a user: three `#[derive(Debug, Clone, Deserialize)]`). rust-analyzer gives an impl of a
     * builtin derive the name of its trait in the attribute ([column] on it), and one of a proc macro (`Serialize` of serde) the whole
     * item from its `#` (seen in VS Code as well): that one is told by the derives of the list that are not builtin. Null for any other place.
     */
    fun derived(line: String, column: Int): String? {
        val list = DERIVE.find(line) ?: return null
        if (column < line.length && line[column] == '#' && line.substring(0, column).isBlank()) {
            val names = line.substring(list.range.last + 1).substringBefore(')').split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val procMacros = names.filter { it.substringAfterLast("::") !in BUILTIN_DERIVES }
            return procMacros.takeIf { it.isNotEmpty() }?.joinToString(", ", "#[derive(", ")]")
        }
        if (column < list.range.last + 1 || column >= line.length || !Character.isJavaIdentifierPart(line[column])) return null
        var start = column
        while (start > 0 && (Character.isJavaIdentifierPart(line[start - 1]) || line[start - 1] == ':')) start--
        var end = column
        while (end < line.length && (Character.isJavaIdentifierPart(line[end]) || line[end] == ':')) end++
        return "#[derive(${line.substring(start, end)})]"
    }
}

/**
 * Go to Implementation (Ctrl+Alt+B) in a Rust file: `textDocument/implementation` at the caret (a trait, a trait item, a type). The
 * action of the platform finds its source through the PSI (`TargetElementUtil`), which the flat file of tokens has none of, so a search
 * of implementations ([com.intellij.psi.search.searches.DefinitionsScopedSearch]) would never be called; this action takes its
 * shortcut ([RustGotoImplementationPromoter]) and is switched off outside Rust files, where the one of the platform goes on as always.
 */
class RustGotoImplementationAction : RustEditorAction() {
    override fun perform(context: RustEditorContext) {
        val (project, editor, file) = Triple(context.project, context.editor, context.file)
        val position = context.position()
        val navigated = RustTargets.navigator(project) { RustTargets.implementations(project, file, position) }.navigate(editor, "Choose Implementation")
        if (!navigated && !editor.isDisposed) HintManager.getInstance().showInformationHint(editor, "No implementations found")
    }
}

/** With the caret in a Rust file, Ctrl+Alt+B is [RustGotoImplementationAction]; elsewhere it is disabled and the action of the IDE runs. */
class RustGotoImplementationPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
        val ours = actions.filterIsInstance<RustGotoImplementationAction>()
        return if (ours.isEmpty()) null else ours + actions.filterNot { it is RustGotoImplementationAction }
    }
}
