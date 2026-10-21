package io.github.rustsupport.lsp

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.psi.PsiFile
import io.github.rustsupport.toolchain.RustErrorCodes
import io.github.rustsupport.toolchain.RustErrorExplainer
import org.eclipse.lsp4j.Diagnostic

/**
 * The diagnostics of rust-analyzer as the platform shows them, plus "Explain E0308" on those that carry an error code of rustc
 * (`code`, or the code in `codeDescription.href` to doc.rust-lang.org): `rustc --explain` in an editor tab, no network.
 */
class RustAnalyzerDiagnosticsSupport : LspDiagnosticsSupport() {
    // createAnnotation, not customizeQuickFixes: that one is internal API of the platform
    override fun createAnnotation(holder: AnnotationHolder, diagnostic: Diagnostic, textRange: TextRange, quickFixes: List<IntentionAction>) {
        val code = diagnostic.code?.let { if (it.isLeft) it.left else it.right?.toString() }
        val errorCode = RustErrorCodes.fromDiagnostic(code, diagnostic.codeDescription?.href)
        super.createAnnotation(holder, diagnostic, textRange, if (errorCode == null) quickFixes else quickFixes + ExplainRustErrorFix(errorCode))
    }
}

/** Alt + Enter on an error with a code: its explanation by `rustc --explain`, after the fixes of the server. */
class ExplainRustErrorFix(private val code: String) : IntentionAction, LowPriorityAction {
    override fun getText(): String = "Explain $code"
    override fun getFamilyName(): String = "Explain Rust error code"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true
    override fun startInWriteAction(): Boolean = false
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) = RustErrorExplainer.show(project, code)
}
