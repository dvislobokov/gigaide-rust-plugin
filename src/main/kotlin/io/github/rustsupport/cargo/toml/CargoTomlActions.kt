package io.github.rustsupport.cargo.toml

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.rustsupport.cargo.CargoCrateVersions
import io.github.rustsupport.cargo.SemVersion
import io.github.rustsupport.cargo.VersionReq
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.TomlPsiFactory

/** Ctrl + click on the name of a dependency: its Cargo.toml (in the registry sources, a git checkout or a path). */
class CargoTomlGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val element = source ?: return null
        val file = element.containingFile
        if (!CargoTomlPsi.isManifest(file)) return null
        val dependency = CargoTomlPsi.dependencyOfKey(element) ?: return null
        val manifest = file.virtualFile?.path ?: return null
        val path = CargoTomlPsi.manifestOf(element.project, manifest, dependency) ?: return null
        val target = LocalFileSystem.getInstance().findFileByPath(path.replace('\\', '/')) ?: return null
        return PsiManager.getInstance(element.project).findFile(target)?.let { arrayOf(it) }
    }
}

/**
 * A requirement a newer release does not satisfy, by the versions learned in this session (Check for Newer Versions, Check Dependency
 * Updates): nothing is fetched by the inspection itself.
 */
class CargoOutdatedDependencyInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (!CargoTomlPsi.isManifest(file)) return null
        val versions = CargoCrateVersions.getInstance()
        return CargoTomlPsi.dependencies(file as TomlFile).mapNotNull { dependency ->
            val literal = dependency.versionLiteral ?: return@mapNotNull null
            val latest = versions.latest(dependency.crateName) ?: return@mapNotNull null
            val req = dependency.version?.let(VersionReq::parse) ?: return@mapNotNull null
            val newest = SemVersion.parse(latest) ?: return@mapNotNull null
            if (!req.isOutdatedBy(newest)) return@mapNotNull null
            manager.createProblemDescriptor(
                literal, "A newer version of ${dependency.crateName} is available: $latest", isOnTheFly,
                arrayOf<LocalQuickFix>(UpdateDependencyVersionFix(latest)), ProblemHighlightType.WEAK_WARNING,
            )
        }.toTypedArray()
    }
}

/** Writes the version into the string of the requirement. */
class UpdateDependencyVersionFix(private val version: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Update the dependency version"
    override fun getName(): String = "Update to $version"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val literal = descriptor.psiElement as? TomlLiteral ?: return
        literal.replace(TomlPsiFactory(project, false).createLiteral("\"$version\""))
    }
}

/**
 * Alt+Enter on a dependency: `cargo info <crate>` (the network through cargo, with its proxy and registry mirror), the newest version
 * then highlighted where the requirement does not reach it.
 */
class CargoCheckNewerVersionsIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName(): String = "Check for newer versions (cargo info)"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        if (!CargoTomlPsi.isManifest(element.containingFile)) return false
        val dependency = CargoTomlPsi.dependencyAt(element) ?: return false
        if (dependency.path != null && dependency.versionLiteral == null) return false
        text = "Check for newer versions of ${dependency.crateName} (cargo info)"
        return true
    }

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val dependency = CargoTomlPsi.dependencyAt(element) ?: return
        CargoCrateVersions.checkCrate(project, dependency.crateName, element.containingFile.virtualFile?.parent?.path)
    }
}
