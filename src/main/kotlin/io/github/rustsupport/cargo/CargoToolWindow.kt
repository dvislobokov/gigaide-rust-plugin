package io.github.rustsupport.cargo

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import io.github.rustsupport.RustIcons
import io.github.rustsupport.run.CargoConfigurationType
import io.github.rustsupport.run.CargoLauncher
import io.github.rustsupport.run.CargoRunConfiguration
import io.github.rustsupport.run.CargoTarget
import io.github.rustsupport.run.CargoTargetKind
import io.github.rustsupport.toolchain.RustProjectDetector
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class CargoToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CargoToolWindowPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false))
    }

    override fun shouldBeAvailable(project: Project): Boolean = RustProjectDetector.hasRust(project.basePath?.let { Path.of(it) })

    companion object {
        const val ID = "Cargo"
    }
}

/** What a row of the tree stands for. */
sealed class CargoTreeItem {
    data class Workspace(val data: CargoProjectData) : CargoTreeItem()
    data class Package(val ws: CargoProjectData, val pkg: CargoMetaPackage) : CargoTreeItem()
    data class Group(val title: String, val icon: Icon) : CargoTreeItem()
    data class Target(val ws: CargoProjectData, val pkg: CargoMetaPackage, val target: CargoTarget) : CargoTreeItem()
    data class Feature(val pkg: CargoMetaPackage, val name: String, val enables: List<String>) : CargoTreeItem()
    data class Dependency(val ws: CargoProjectData, val view: CargoDependencyView) : CargoTreeItem()
    data class Message(val text: String, val error: Boolean = false) : CargoTreeItem()
}

/** A node of the tree; only features have a working checkbox. A dependency gets its own dependencies when it is expanded. */
private class CargoNode(val item: CargoTreeItem) : CheckedTreeNode(item) {
    var childrenLoaded = item !is CargoTreeItem.Dependency

    init {
        isEnabled = item is CargoTreeItem.Feature
        isChecked = false
    }
}

/**
 * The Cargo tool window: the workspaces of `cargo metadata` with their members, targets, features (checked ones go to rust-analyzer
 * and to new run configurations) and dependencies with the resolved versions.
 */
class CargoToolWindowPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true), Disposable {
    private val root = CargoNode(CargoTreeItem.Message(""))
    private val model = DefaultTreeModel(root)
    private val tree: CheckboxTree
    private val status = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(4, 8)
    }

    init {
        Disposer.register(parent, this)
        tree = object : CheckboxTree(Renderer(), root, CheckboxTreeBase.CheckPolicy(false, false, false, false)) {
            override fun onNodeStateChanged(node: CheckedTreeNode?) {
                val cargoNode = node as? CargoNode ?: return
                val item = cargoNode.item as? CargoTreeItem.Feature ?: return
                CargoFeatureSettings.getInstance(project).set(item.pkg.name, item.name, cargoNode.isChecked)
            }

            override fun onDoubleClick(node: CheckedTreeNode?) {
                open((node as? CargoNode)?.item)
            }
        }
        tree.model = model
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                (event.path.lastPathComponent as? CargoNode)?.let(::loadChildren)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) {}
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) open(selectedItem())
            }
        })

        val expander = DefaultTreeExpander(tree)
        val actions = DefaultActionGroup().apply {
            add(RefreshAction())
            addSeparator()
            add(TargetAction("Run", "cargo run of the target (bench: cargo bench)", AllIcons.Actions.Execute, DefaultRunExecutor.getRunExecutorInstance(), ::runCommand))
            add(TargetAction("Debug", "Build the target and debug it (CodeLLDB)", AllIcons.Actions.StartDebugger, DefaultDebugExecutor.getDebugExecutorInstance(), ::debugCommand))
            add(TargetAction("Test", "cargo test of the target", AllIcons.Nodes.Test, DefaultRunExecutor.getRunExecutorInstance(), ::testCommand))
            add(TargetAction("Build", "cargo build of the target", AllIcons.Actions.Compile, DefaultRunExecutor.getRunExecutorInstance()) { "build" })
            addSeparator()
            add(FetchAction())
            ActionManager.getInstance().getAction("Rust.CheckDependencyUpdates")?.let(::add)
            addSeparator()
            add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
            add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("CargoToolWindow", actions, true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        setContent(JPanel(BorderLayout()).apply {
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        })

        val projects = CargoProjects.getInstance(project)
        project.messageBus.connect(this).subscribe(CargoProjects.TOPIC, CargoProjectsListener {
            ApplicationManager.getApplication().invokeLater({ rebuild() }, project.disposed)
        })
        rebuild()
        projects.ensureLoaded()
    }

    private fun rebuild() {
        val projects = CargoProjects.getInstance(project)
        val state = projects.state
        val expanded = TreeUtil.collectExpandedPaths(tree).map { path -> path.path.map { (it as? CargoNode)?.item?.let(::identity) } }
        root.removeAllChildren()
        when {
            state == null -> root.add(CargoNode(CargoTreeItem.Message("Loading cargo metadata…")))
            state.workspaces.isEmpty() -> root.add(CargoNode(CargoTreeItem.Message(state.message ?: "No Cargo workspace in the project", error = state.message != null)))
            else -> state.workspaces.forEach { ws -> root.add(workspaceNode(ws)) }
        }
        model.reload()
        status.text = when {
            projects.isLoading -> "Loading cargo metadata…"
            else -> state?.message?.takeIf { state.workspaces.isNotEmpty() }?.lineSequence()?.firstOrNull().orEmpty()
        }
        status.isVisible = status.text.isNotEmpty()
        status.toolTipText = state?.message
        // the members open by default, what the user expanded stays expanded
        if (expanded.isEmpty()) {
            for (i in 0 until root.childCount) tree.expandPath(TreePath(arrayOf(root, root.getChildAt(i))))
        } else restore(expanded)
    }

    private fun restore(expanded: List<List<String?>>) {
        for (path in expanded) {
            var node: CargoNode = root
            val nodes = arrayListOf<Any>(root)
            for (id in path.drop(1)) {
                loadChildren(node)
                node = node.children().asSequence().filterIsInstance<CargoNode>().firstOrNull { identity(it.item) == id } ?: break
                nodes += node
            }
            if (nodes.size == path.size) tree.expandPath(TreePath(nodes.toTypedArray()))
        }
    }

    private fun identity(item: CargoTreeItem): String = when (item) {
        is CargoTreeItem.Workspace -> "ws:" + item.data.root
        is CargoTreeItem.Package -> "pkg:" + item.pkg.id
        is CargoTreeItem.Group -> "group:" + item.title
        is CargoTreeItem.Target -> "target:" + item.target.kinds + item.target.name
        is CargoTreeItem.Feature -> "feature:" + item.name
        is CargoTreeItem.Dependency -> "dep:" + (item.view.pkg?.id ?: item.view.name)
        is CargoTreeItem.Message -> "message:" + item.text
    }

    private fun workspaceNode(ws: CargoProjectData): CargoNode {
        val node = CargoNode(CargoTreeItem.Workspace(ws))
        ws.members.sortedBy { it.name }.forEach { pkg -> node.add(packageNode(ws, pkg)) }
        if (!ws.hasDependencies) node.add(CargoNode(CargoTreeItem.Message("Dependencies offline unavailable: only declared ones are shown")))
        return node
    }

    private fun packageNode(ws: CargoProjectData, pkg: CargoMetaPackage): CargoNode {
        val node = CargoNode(CargoTreeItem.Package(ws, pkg))
        val targets = pkg.targets.filter { it.kind != null }
        if (targets.isNotEmpty()) node.add(CargoNode(CargoTreeItem.Group("Targets", AllIcons.Nodes.ConfigFolder)).apply {
            targets.sortedWith(compareBy({ targetOrder(it) }, { it.name })).forEach { add(CargoNode(CargoTreeItem.Target(ws, pkg, it))) }
        })
        if (pkg.features.isNotEmpty()) node.add(CargoNode(CargoTreeItem.Group("Features", AllIcons.Nodes.Property)).apply {
            val settings = CargoFeatureSettings.getInstance(project)
            pkg.features.forEach { (name, enables) ->
                add(CargoNode(CargoTreeItem.Feature(pkg, name, enables)).apply { isChecked = settings.isEnabled(pkg.name, name) })
            }
        })
        val dependencies = ws.dependencies(pkg.id)
        if (dependencies.isNotEmpty()) node.add(CargoNode(CargoTreeItem.Group("Dependencies", AllIcons.Nodes.PpLibFolder)).apply {
            dependencies.forEach { add(dependencyNode(ws, it)) }
        })
        return node
    }

    private fun dependencyNode(ws: CargoProjectData, view: CargoDependencyView): CargoNode {
        val node = CargoNode(CargoTreeItem.Dependency(ws, view))
        val pkg = view.pkg
        // a placeholder makes the node expandable; its real children are made when it is expanded (the graph can be large)
        if (view.resolved && pkg != null && ws.dependencies(pkg.id).isNotEmpty()) node.add(CargoNode(CargoTreeItem.Message("…")))
        else node.childrenLoaded = true
        return node
    }

    private fun loadChildren(node: CargoNode) {
        if (node.childrenLoaded) return
        node.childrenLoaded = true
        val item = node.item as? CargoTreeItem.Dependency ?: return
        node.removeAllChildren()
        item.view.pkg?.let { pkg -> item.ws.dependencies(pkg.id).filter { CargoDependencyKind.DEV !in it.kinds || it.kinds.size > 1 }.forEach { node.add(dependencyNode(item.ws, it)) } }
        model.nodeStructureChanged(node)
    }

    private fun targetOrder(target: CargoTarget): Int = when (target.kind) {
        CargoTargetKind.LIB -> 0
        CargoTargetKind.BIN -> 1
        CargoTargetKind.EXAMPLE -> 2
        CargoTargetKind.TEST -> 3
        CargoTargetKind.BENCH -> 4
        else -> 5
    }

    private fun selectedItem(): CargoTreeItem? = (tree.selectionPath?.lastPathComponent as? CargoNode)?.item

    /** A target or a dependency: its source (the crate root of a target, the manifest of a dependency); a package: its manifest. */
    private fun open(item: CargoTreeItem?) {
        val path = when (item) {
            is CargoTreeItem.Target -> item.target.srcPath
            is CargoTreeItem.Dependency -> item.view.pkg?.manifestPath
            is CargoTreeItem.Package -> item.pkg.manifestPath
            is CargoTreeItem.Workspace -> java.io.File(item.data.root, "Cargo.toml").path
            else -> null
        } ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path.replace('\\', '/'))
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path.replace('\\', '/')) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun runCommand(kind: CargoTargetKind): String? = when (kind) {
        CargoTargetKind.BIN, CargoTargetKind.EXAMPLE -> "run"
        CargoTargetKind.BENCH -> "bench"
        else -> null
    }

    private fun debugCommand(kind: CargoTargetKind): String? = when (kind) {
        CargoTargetKind.BIN, CargoTargetKind.EXAMPLE -> "run"
        CargoTargetKind.TEST, CargoTargetKind.LIB -> "test"
        else -> null
    }

    private fun testCommand(kind: CargoTargetKind): String? = when (kind) {
        CargoTargetKind.LIB, CargoTargetKind.BIN, CargoTargetKind.TEST, CargoTargetKind.EXAMPLE -> "test"
        CargoTargetKind.BENCH -> "bench"
        else -> null
    }

    private inner class TargetAction(
        text: String, description: String, icon: Icon, private val executor: Executor, private val command: (CargoTargetKind) -> String?,
    ) : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val target = selectedItem() as? CargoTreeItem.Target
            e.presentation.isEnabled = target?.target?.kind?.let(command) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val item = selectedItem() as? CargoTreeItem.Target ?: return
            val kind = item.target.kind ?: return
            CargoTargetLauncher.launch(project, item.ws, item.pkg, item.target, command(kind) ?: return, executor)
        }
    }

    private inner class RefreshAction : DumbAwareAction("Refresh", "Run cargo metadata again", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !CargoProjects.getInstance(project).isLoading
        }
        override fun actionPerformed(e: AnActionEvent) = CargoProjects.getInstance(project).refresh()
    }

    /** `cargo fetch`: the dependencies through the network, at the user's request (cargo applies its proxy and mirrors). */
    private inner class FetchAction : DumbAwareAction("Fetch Dependencies", "cargo fetch: download the dependencies of the workspace (network)", AllIcons.Actions.Download) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = CargoLauncher.run(project, "cargo fetch", "fetch")
    }

    private inner class Renderer : CheckboxTree.CheckboxTreeCellRenderer(true, false) {
        override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val item = (value as? CargoNode)?.item ?: return
            checkbox.isVisible = item is CargoTreeItem.Feature
            val text = textRenderer
            val grey = SimpleTextAttributes.GRAYED_ATTRIBUTES
            when (item) {
                is CargoTreeItem.Workspace -> {
                    text.icon = AllIcons.Nodes.ModuleGroup
                    text.append(java.io.File(item.data.root).name.ifEmpty { item.data.root }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    text.append("  " + item.data.root, grey)
                }
                is CargoTreeItem.Package -> {
                    text.icon = RustIcons.CARGO
                    text.append(item.pkg.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    text.append(" " + item.pkg.version, grey)
                }
                is CargoTreeItem.Group -> {
                    text.icon = item.icon
                    text.append(item.title)
                }
                is CargoTreeItem.Target -> {
                    text.icon = when (item.target.kind) {
                        CargoTargetKind.BIN -> AllIcons.RunConfigurations.Application
                        CargoTargetKind.LIB -> AllIcons.Nodes.PpLib
                        CargoTargetKind.TEST -> AllIcons.Nodes.Test
                        CargoTargetKind.EXAMPLE -> AllIcons.Nodes.Template
                        CargoTargetKind.BENCH -> AllIcons.Actions.Profile
                        else -> AllIcons.Nodes.Artifact
                    }
                    text.append(item.target.name)
                    text.append("  " + item.target.kinds.joinToString(", "), grey)
                }
                is CargoTreeItem.Feature -> {
                    text.append(item.name)
                    if (item.enables.isNotEmpty()) text.append("  " + item.enables.joinToString(", ", "[", "]"), grey)
                }
                is CargoTreeItem.Dependency -> {
                    val view = item.view
                    text.icon = if (view.pkg?.source == null && view.pkg != null) RustIcons.CARGO else AllIcons.Nodes.PpLib
                    text.append(view.name)
                    text.append(" " + view.version, grey)
                    val kinds = view.kinds.filter { it != CargoDependencyKind.NORMAL }
                    if (kinds.isNotEmpty()) text.append("  " + kinds.joinToString(", ") { it.metadataKind.orEmpty() }, SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                }
                is CargoTreeItem.Message -> {
                    text.icon = if (item.error) AllIcons.General.Warning else null
                    text.append(item.text, if (item.error) SimpleTextAttributes.ERROR_ATTRIBUTES else grey)
                }
            }
        }
    }

    override fun dispose() {}
}

/** A temporary Cargo configuration for a target of the tool window, started with [executor] (Run or Debug). EDT. */
object CargoTargetLauncher {
    fun launch(project: Project, ws: CargoProjectData, pkg: CargoMetaPackage, target: CargoTarget, command: String, executor: Executor) {
        val kind = target.kind ?: return
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration("$command ${target.name}", CargoConfigurationType.instance.factory)
        (settings.configuration as CargoRunConfiguration).options.apply {
            this.command = command
            packageName = pkg.name
            targetKind = kind
            targetName = target.name.takeIf { kind.hasName }
            features = CargoFeatureSettings.getInstance(project).runConfigurationFeatures(pkg.name)
            workingDirectory = ws.root
        }
        settings.isTemporary = true
        settings.isActivateToolWindowBeforeRun = true
        runManager.setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }
}
