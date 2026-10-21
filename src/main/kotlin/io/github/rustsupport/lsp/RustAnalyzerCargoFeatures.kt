package io.github.rustsupport.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.github.rustsupport.cargo.CargoFeaturesListener

/**
 * Features were checked in the Cargo tool window: rust-analyzer of this project restarts, its options carrying them as
 * `cargo.features` ([RustAnalyzerOptions.build]). A restart rather than `didChangeConfiguration`: a new feature set means a new
 * `cargo metadata` and new build scripts, which the server does on start anyway.
 */
class RustAnalyzerCargoFeaturesListener(private val project: Project) : CargoFeaturesListener {
    override fun cargoFeaturesChanged() {
        ApplicationManager.getApplication().invokeLater({ RustAnalyzerIntegrationProvider.restart(project) }, project.disposed)
    }
}
