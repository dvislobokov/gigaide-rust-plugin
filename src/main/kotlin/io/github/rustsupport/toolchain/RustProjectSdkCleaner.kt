package io.github.rustsupport.toolchain

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Path

/**
 * A directory opened in IntelliJ IDEA gets the default JDK as the project SDK, and the IDE indexes its classes: some 40 000 files for a
 * Rust project that has no Java (seen live on komodo, 46 000 files scanned for 2 600 of the project). The JDK is taken off such a project
 * once, with Undo in the balloon; a project with Java or Kotlin, or one where the user said Undo, keeps it.
 */
class RustProjectSdkCleaner : ProjectActivity {
    override suspend fun execute(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(KEEP_KEY)) return
        val sdk = ProjectRootManager.getInstance(project).projectSdk ?: return
        if (sdk.sdkType.name != JAVA_SDK_TYPE) return
        val root = project.basePath?.let { Path.of(it) } ?: return
        if (!RustProjectDetector.hasRust(root) || RustProjectDetector.hasJvmCode(root)) return

        val name = sdk.name
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || ProjectRootManager.getInstance(project).projectSdk?.name != name) return@invokeLater
            WriteAction.run<RuntimeException> { ProjectRootManager.getInstance(project).projectSdk = null }
            // to .idea/misc.xml now, not at the next autosave: an IDE that is killed would give the JDK back at the next start
            project.scheduleSave()
            NotificationGroupManager.getInstance().getNotificationGroup(RustToolchainInstaller.NOTIFICATION_GROUP)
                .createNotification("JDK removed from the Rust project", "The project has no Java: the classes of JDK $name are not indexed any more.", NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimpleExpiring("Undo") {
                    properties.setValue(KEEP_KEY, true)
                    ProjectJdkTable.getInstance().findJdk(name)?.let { jdk -> WriteAction.run<RuntimeException> { ProjectRootManager.getInstance(project).projectSdk = jdk } }
                })
                .notify(project)
        }, project.disposed)
    }

    private companion object {
        const val JAVA_SDK_TYPE = "JavaSDK"
        const val KEEP_KEY = "rust.keepProjectJdk"
    }
}
