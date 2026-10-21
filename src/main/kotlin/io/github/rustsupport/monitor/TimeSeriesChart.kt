package io.github.rustsupport.monitor

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.JComponent

/* The chart and its formats, ported from the Go and .NET siblings of this plugin (ten minutes instead of five). */

object ChartFormats {
    fun percent(value: Double): String = if (value < 10) String.format("%.1f%%", value) else "${value.toLong()}%"

    fun number(value: Double): String = when {
        value >= 100 || value == Math.floor(value) -> value.toLong().toString()
        value >= 1 -> String.format("%.1f", value)
        else -> String.format("%.2f", value)
    }

    fun bytes(value: Double): String = when {
        value >= 1024.0 * 1024 * 1024 -> String.format("%.2f GB", value / (1024.0 * 1024 * 1024))
        value >= 1024.0 * 1024 -> String.format("%.1f MB", value / (1024.0 * 1024))
        value >= 1024 -> String.format("%.0f KB", value / 1024)
        else -> "${value.toLong()} B"
    }

    /** The same in binary units, so that the scale reads "128 MB" and not "95,4 MB". */
    fun niceMaxBytes(value: Double): Double {
        var unit = 1.0
        while (value / unit >= 1024) unit *= 1024
        return listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 512.0, 1024.0).first { it * unit >= value } * unit
    }

    /** The top of the scale: 1, 2 or 5 times a power of ten, not below the largest value. */
    fun niceMax(value: Double): Double {
        if (value <= 0 || value.isNaN()) return 1.0
        val power = Math.pow(10.0, Math.floor(Math.log10(value)))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * power }.first { it >= value }
    }
}

/** The last ten minutes of a few series, one sample per second; a missing value (null) leaves a gap. */
class TimeSeriesChart(
    private val title: String,
    private val format: (Double) -> String,
    private val fixedMax: Double?,
    vararg series: Pair<String, Color>,
) : JComponent() {
    private val labels = series.map { it.first }
    private val colors = series.map { it.second }
    private val values = series.map { DoubleArray(CAPACITY) { Double.NaN } }
    private var size = 0

    /** Rounds the largest value up to the top of the scale. */
    var scale: (Double) -> Double = ChartFormats::niceMax

    init {
        preferredSize = Dimension(JBUI.scale(240), JBUI.scale(112))
        minimumSize = Dimension(JBUI.scale(120), JBUI.scale(96))
    }

    fun add(vararg sample: Double?) {
        values.forEachIndexed { index, array ->
            System.arraycopy(array, 1, array, 0, CAPACITY - 1)
            array[CAPACITY - 1] = sample.getOrNull(index) ?: Double.NaN
        }
        size = (size + 1).coerceAtMost(CAPACITY)
        repaint()
    }

    fun clear() {
        values.forEach { it.fill(Double.NaN) }
        size = 0
        repaint()
    }

    fun latest(seriesIndex: Int): Double? = values[seriesIndex][CAPACITY - 1].takeIf { !it.isNaN() }

    /** "working set 78.4 MB · GC heap 19.6 MB"; "no data" before the first value. */
    fun legend(): String = labels.indices.mapNotNull { index -> latest(index)?.let { "${labels[index]} ${format(it)}" } }.joinToString(" · ").ifEmpty { "no data" }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // a JComponent has no font of its own until it gets a parent
            val baseFont = font ?: UIUtil.getLabelFont()
            g.font = baseFont
            val metrics = g.fontMetrics
            val header = metrics.height * 2 + JBUI.scale(4)
            val left = JBUI.scale(2)
            val plotWidth = width - left * 2
            val plotHeight = height - header - JBUI.scale(2)
            if (plotWidth <= 0 || plotHeight <= 0) return

            g.color = UIUtil.getLabelForeground()
            g.font = baseFont.deriveFont(java.awt.Font.BOLD)
            g.drawString(title, left, metrics.ascent)
            g.font = baseFont
            g.color = UIUtil.getContextHelpForeground()
            g.drawString(legend(), left, metrics.height + metrics.ascent)

            val max = fixedMax ?: scale(values.maxOf { array -> array.filter { !it.isNaN() }.maxOrNull() ?: 0.0 })
            val scaleText = format(max)
            g.drawString(scaleText, width - left - metrics.stringWidth(scaleText), metrics.ascent)

            // The plot stands out from the tool window as in Rider: a panel lighter than the tool window in every theme.
            val arc = JBUI.scale(8)
            g.color = PLOT_BACKGROUND
            g.fillRoundRect(left, header, plotWidth, plotHeight, arc, arc)
            g.color = GRID_COLOR
            for (line in 1..3) {
                val y = header + plotHeight * line / 4
                g.drawLine(left + 1, y, left + plotWidth - 1, y)
            }
            g.color = JBColor.border()
            g.drawRoundRect(left, header, plotWidth - 1, plotHeight - 1, arc, arc)

            // the lines keep off the frame: a flat zero or a full scale would be drawn over it
            val inset = JBUI.scale(3)
            val clip = g.clip
            g.clipRect(left + 1, header + 1, plotWidth - 2, plotHeight - 2)

            values.forEachIndexed { index, array ->
                val path = Path2D.Double()
                var drawing = false
                var firstX = 0.0
                var lastX = 0.0
                for (i in 0 until CAPACITY) {
                    val value = array[i]
                    if (value.isNaN()) { drawing = false; continue }
                    val x = left + plotWidth * i.toDouble() / (CAPACITY - 1)
                    val y = header + inset + (plotHeight - inset * 2) * (1 - (value / max).coerceIn(0.0, 1.0))
                    if (drawing) path.lineTo(x, y) else { path.moveTo(x, y); if (firstX == 0.0) firstX = x }
                    drawing = true
                    lastX = x
                }
                if (lastX == 0.0) return@forEachIndexed
                g.color = colors[index]
                g.stroke = BasicStroke(JBUI.scale(1).toFloat() * 1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(path)
            }
            g.clip = clip
        } finally {
            g.dispose()
        }
    }

    companion object {
        const val CAPACITY = MonitorHistory.CAPACITY

        /** Lighter than the tool window in both themes: white on the light grey, a raised grey on the dark one. */
        val PLOT_BACKGROUND = JBColor(Color(0xFFFFFF), Color(0x393B40))
        val GRID_COLOR = JBColor(Color(0xEBECF0), Color(0x4A4D53))
    }
}
