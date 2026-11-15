package dev.juliainsight.repl

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.terminal.TerminalExecutionConsole
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.juliainsight.JuliaIcons
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel

// ---- REPL ---------------------------------------------------------------------------------------------------------------

class JuliaReplToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JuliaReplPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "REPL", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        JuliaReplService.getInstance(project).panel = panel
    }
}

class JuliaReplPanel(private val project: Project) : SimpleToolWindowPanel(false, true), com.intellij.openapi.Disposable {
    private val messageLabel = JBLabel("", SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }
    private var current: JComponent? = null

    init {
        val group = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Start / Restart Julia REPL", null, AllIcons.Actions.Restart) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = JuliaReplService.getInstance(project).restart()
            })
            add(object : DumbAwareAction("Interrupt (Ctrl+C)", null, AllIcons.Actions.Pause) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = JuliaReplService.getInstance(project).isRunning
                }
                override fun actionPerformed(e: AnActionEvent) = JuliaReplService.getInstance(project).interrupt()
            })
            add(object : DumbAwareAction("Stop", null, AllIcons.Actions.Suspend) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = JuliaReplService.getInstance(project).isRunning
                }
                override fun actionPerformed(e: AnActionEvent) = JuliaReplService.getInstance(project).stop()
            })
            add(object : DumbAwareAction("Clear", null, AllIcons.Actions.GC) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = JuliaReplService.getInstance(project).clear()
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("JuliaReplToolbar", group, false)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        showMessage("The Julia REPL is not running. Press ⟳ to start it, or send code from an editor with Ctrl+Enter.")
    }

    fun showMessage(text: String) {
        messageLabel.text = "<html><center>$text</center></html>"
        setMain(messageLabel)
    }

    /** Shows the terminal without taking keyboard focus, so Ctrl+Enter keeps working from the editor. */
    fun showConsole(console: TerminalExecutionConsole) {
        setMain(console.component)
    }

    fun processTerminated(exitCode: Int) {
        // keep the terminal visible so the output stays readable; just tell the user
        val c = current
        if (c == null || c == messageLabel) showMessage("Julia exited (code $exitCode). Press ⟳ to start a new REPL.")
    }

    private fun setMain(c: JComponent) {
        current = c
        setContent(c)
        revalidate()
        repaint()
    }

    override fun dispose() {
        if (JuliaReplService.getInstance(project).panel === this) JuliaReplService.getInstance(project).panel = null
    }
}

// ---- Plots --------------------------------------------------------------------------------------------------------------

class JuliaPlotsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JuliaPlotsPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class JuliaPlotsPanel(private val project: Project, private val toolWindow: ToolWindow) :
    SimpleToolWindowPanel(true, true), com.intellij.openapi.Disposable {
    private val plots = ArrayList<Pair<JuliaPlot, Any?>>()
    private var index = -1
    private val canvas = ImageCanvas()
    private val status = JBLabel("", SwingConstants.CENTER)

    init {
        val group = DefaultActionGroup().apply {
            add(simple("Previous Plot", AllIcons.Actions.Back, { index > 0 }) { show(index - 1) })
            add(simple("Next Plot", AllIcons.Actions.Forward, { index < plots.size - 1 }) { show(index + 1) })
            addSeparator()
            add(simple("Save Plot As…", AllIcons.Actions.MenuSaveall, { index >= 0 }) { save() })
            add(simple("Copy Image", AllIcons.Actions.Copy, { index >= 0 && plots[index].second is Image }) { copy() })
            add(simple("Delete Plot", AllIcons.General.Remove, { index >= 0 }) { delete() })
            add(simple("Clear All Plots", AllIcons.Actions.GC, { plots.isNotEmpty() }) {
                plots.clear()
                index = -1
                refresh()
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("JuliaPlotsToolbar", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        val main = JPanel(BorderLayout())
        main.add(canvas, BorderLayout.CENTER)
        main.add(status, BorderLayout.SOUTH)
        status.border = JBUI.Borders.empty(2)
        status.foreground = UIUtil.getContextHelpForeground()
        setContent(main)
        project.messageBus.connect(this).subscribe(JuliaPlotListener.TOPIC, JuliaPlotListener { add(it) })
        refresh()
    }

    private fun simple(text: String, icon: javax.swing.Icon, enabled: () -> Boolean, run: () -> Unit) =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private fun decode(p: JuliaPlot): Any? = try {
        if (p.mime == "image/png") ImageIO.read(ByteArrayInputStream(p.data))
        else {
            // SVG: let the IDE's icon loader render it (it scales losslessly when painted)
            val tmp = Files.createTempFile("julia-plot", ".svg")
            Files.write(tmp, p.data)
            tmp.toFile().deleteOnExit()
            IconLoader.findIcon(tmp.toUri().toURL(), false)
        }
    } catch (e: Exception) {
        null
    }

    private fun add(p: JuliaPlot) {
        plots += p to decode(p)
        if (plots.size > 100) plots.removeAt(0)
        show(plots.size - 1)
        if (!toolWindow.isVisible) toolWindow.show()
    }

    private fun show(i: Int) {
        index = i
        refresh()
    }

    private fun refresh() {
        if (index !in plots.indices) {
            canvas.image = null
            status.text = "Plots and images displayed by Julia (REPL or Run) appear here."
        } else {
            val (p, img) = plots[index]
            canvas.image = img
            status.text = "${index + 1} / ${plots.size}   ${p.title}" + if (img == null) "   (could not decode ${p.mime})" else ""
        }
        canvas.repaint()
    }

    private fun save() {
        val (p, _) = plots.getOrNull(index) ?: return
        val ext = if (p.mime == "image/png") "png" else "svg"
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Save Plot", "", ext), project)
            .save("plot.$ext") ?: return
        ApplicationManager.getApplication().executeOnPooledThread { Files.write(wrapper.file.toPath(), p.data) }
    }

    private fun copy() {
        val img = plots.getOrNull(index)?.second as? Image ?: return
        CopyPasteManager.getInstance().setContents(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any = img
        })
    }

    private fun delete() {
        if (index !in plots.indices) return
        plots.removeAt(index)
        index = minOf(index, plots.size - 1)
        refresh()
    }

    override fun dispose() {}

    /** Paints a PNG ([Image]) or an SVG ([javax.swing.Icon]) scaled to fit, keeping the aspect ratio. */
    private class ImageCanvas : JComponent() {
        var image: Any? = null

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = image ?: return
            val (iw, ih) = when (img) {
                is Image -> img.getWidth(null).toDouble() to img.getHeight(null).toDouble()
                is javax.swing.Icon -> img.iconWidth.toDouble() to img.iconHeight.toDouble()
                else -> return
            }
            if (iw <= 0 || ih <= 0) return
            val scale = minOf(width / iw, height / ih)
            val w = (iw * scale).toInt()
            val h = (ih * scale).toInt()
            val x = (width - w) / 2
            val y = (height - h) / 2
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.color = java.awt.Color.WHITE
            g2.fillRect(x, y, w, h)
            when (img) {
                is Image -> g2.drawImage(img, x, y, w, h, null)
                is javax.swing.Icon -> IconUtil.scale(img, this, scale.toFloat()).paintIcon(this, g2, x, y)
            }
        }
    }
}

// ---- Workspace -----------------------------------------------------------------------------------------------------------

class JuliaWorkspaceToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JuliaWorkspacePanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

class JuliaWorkspacePanel(private val project: Project, private val toolWindow: ToolWindow) :
    SimpleToolWindowPanel(true, true), com.intellij.openapi.Disposable {
    private var vars: List<JuliaVariable> = emptyList()
    private val model = object : AbstractTableModel() {
        override fun getRowCount() = vars.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = arrayOf("Name", "Type", "Value")[column]
        override fun getValueAt(row: Int, column: Int): Any = vars[row].let { arrayOf(it.name, it.type, it.value)[column] }
    }
    private val table = JBTable(model).apply {
        setShowGrid(false)
        emptyText.text = "Start the Julia REPL to see its variables"
    }
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    @Volatile private var refreshing = false

    init {
        val group = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) = refresh()
            })
            add(object : DumbAwareAction("Show Value in REPL", null, AllIcons.Actions.Execute) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
                override fun actionPerformed(e: AnActionEvent) {
                    val v = vars.getOrNull(table.selectedRow) ?: return
                    JuliaReplService.getInstance(project).execute("display(${v.name})")
                }
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("JuliaWorkspaceToolbar", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        setContent(JBScrollPane(table))
        schedule()
    }

    private fun schedule() {
        alarm.addRequest({
            if (toolWindow.isVisible) refresh()
            schedule()
        }, 2000)
    }

    fun refresh() {
        if (refreshing) return
        val bridge = JuliaBridgeServer.getInstance(project)
        if (!bridge.isReplConnected) {
            ApplicationManager.getApplication().invokeLater {
                vars = emptyList()
                model.fireTableDataChanged()
            }
            return
        }
        refreshing = true
        bridge.requestWorkspace().whenComplete { result, _ ->
            refreshing = false
            if (result == null) return@whenComplete // busy: keep the last values
            ApplicationManager.getApplication().invokeLater {
                if (result != vars) {
                    val sel = table.selectedRow.takeIf { it >= 0 }?.let { vars.getOrNull(it)?.name }
                    vars = result
                    model.fireTableDataChanged()
                    sel?.let { name -> vars.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) } }
                }
            }
        }
    }

    override fun dispose() {}
}
