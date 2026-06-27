package com.drawing

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.awt.Point
import java.awt.Rectangle
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight diagnostic logger for Drawing drawing problems.
 *
 * It writes to:
 *   <project>/.idea/drawing-diagnostics.log
 * or, if the project path is unavailable:
 *   <user.home>/Drawing-diagnostics.log
 *
 * Keep this class temporary. It is meant to find geometry/rendering/persistence issues,
 * not to stay as permanent high-volume logging.
 */
object DrawingDiagnosticLog {
    private val logger = Logger.getInstance("DrawingDiagnostics")
    private val configured = AtomicBoolean(false)
    private val handlersInstalled = AtomicBoolean(false)
    private val lastLogByKey = ConcurrentHashMap<String, Long>()
    @Volatile private var logPath: Path? = null

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val verboseEnabled = System.getProperty("drawing.diagnostics.verbose") == "true" ||
        System.getenv("DRAWING_DIAGNOSTICS_VERBOSE") == "true"

    fun configure(project: Project?) {
        if (configured.getAndSet(true)) return

        val preferredPath = project?.basePath
            ?.let { Paths.get(it, ".idea", "drawing-diagnostics.log") }
        val fallbackPath = Paths.get(System.getProperty("user.home"), "Drawing-diagnostics.log")
        logPath = preferredPath ?: fallbackPath

        val projectPath = project?.basePath ?: "<none>"
        info(
            category = "START",
            message = "Drawing diagnostics started path=$logPath project=$projectPath"
        )
    }

    fun installGlobalHandlers(project: Project?) {
        configure(project)
        if (!handlersInstalled.compareAndSet(false, true)) return

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error(
                category = "UNCAUGHT",
                message = "thread=${thread.name} state=${thread.state} priority=${thread.priority}",
                throwable = throwable
            )
            previousHandler?.uncaughtException(thread, throwable)
        }

        info("START", "Drawing uncaught exception handler installed")
    }

    fun info(category: String, message: String) {
        if (!verboseEnabled) return
        write("INFO", category, message, null)
    }

    fun warn(category: String, message: String) {
        write("WARN", category, message, null)
    }

    fun error(category: String, message: String, throwable: Throwable? = null) {
        write("ERROR", category, message, throwable)
    }

    fun sample(key: String, minIntervalMs: Long = 500L, category: String, message: () -> String) {
        if (!verboseEnabled) return
        val now = System.currentTimeMillis()
        val previous = lastLogByKey[key]
        if (previous != null && now - previous < minIntervalMs) return
        lastLogByKey[key] = now
        write("INFO", category, message(), null)
    }

    fun strokeSummary(stroke: StrokePath?): String {
        if (stroke == null) return "stroke=<null>"
        val points = stroke.points
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        val kind = stroke.kind?.name ?: "freehand"
        return "strokeId=${stroke.id} kind=$kind filled=${stroke.filled} width=${stroke.width} points=${points.size} " +
            "lines=${lineRange(points)} anchors[first=${anchorSummary(first)} last=${anchorSummary(last)}]"
    }

    fun pointSummary(points: List<Point>): String {
        if (points.isEmpty()) return "points=0"
        val bounds = bounds(points)
        return "points=${points.size} bounds=${rectSummary(bounds)} first=${points.first()} last=${points.last()} maxJump=${maxJump(points)}"
    }

    fun geometrySummary(geometry: StrokeGeometryContent?): String {
        if (geometry == null) return "geometry=<null>"
        return "geometry bounds=${rectSummary(geometry.bounds)} hasPath=${geometry.path != null} polygonPoints=${geometry.polygon?.npoints ?: 0} signature=${geometry.foldLayoutSignature}"
    }

    fun rectSummary(rect: Rectangle?): String {
        if (rect == null) return "<null>"
        return "x=${rect.x},y=${rect.y},w=${rect.width},h=${rect.height}"
    }

    private fun write(level: String, category: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(LocalDateTime.now().format(timestampFormat))
            append(" [")
            append(level)
            append("] [")
            append(category)
            append("] ")
            append(message.replace('\n', ' '))
            throwable?.let {
                append(" throwable=")
                append(it::class.java.name)
                append(": ")
                append(it.message)
            }
            append('\n')
            if (throwable != null) {
                append(stackTraceToString(throwable))
                append('\n')
            }
        }

        when (level) {
            "ERROR" -> logger.error(line, throwable)
            "WARN" -> logger.warn(line)
            else -> logger.info(line)
        }

        val path = logPath ?: Paths.get(System.getProperty("user.home"), "Drawing-diagnostics.log")
        runCatching {
            Files.createDirectories(path.parent)
            rotateIfNeeded(path)
            Files.write(
                path,
                line.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }.onFailure {
            logger.warn("Drawing diagnostics could not write to $path", it)
        }
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd()
    }

    private fun rotateIfNeeded(path: Path) {
        if (!Files.exists(path)) return
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        if (size < 2_000_000L) return
        val rotated = path.resolveSibling(path.fileName.toString() + ".old")
        runCatching { Files.deleteIfExists(rotated) }
        runCatching { Files.move(path, rotated) }
    }

    private fun anchorSummary(anchor: AnchorPoint?): String {
        if (anchor == null) return "<none>"
        return "line=${anchor.line},col=${anchor.column},dx=${anchor.dx},dy=${anchor.dy},offset=${anchor.offset},outside=${anchor.outsideCode}"
    }

    private fun lineRange(points: List<AnchorPoint>): String {
        if (points.isEmpty()) return "<empty>"
        val minLine = points.minOf { it.line }
        val maxLine = points.maxOf { it.line }
        return "$minLine..$maxLine"
    }

    private fun bounds(points: List<Point>): Rectangle {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (point in points) {
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }
        return Rectangle(minX, minY, (maxX - minX).coerceAtLeast(1), (maxY - minY).coerceAtLeast(1))
    }

    private fun maxJump(points: List<Point>): Int {
        var max = 0.0
        for (index in 1 until points.size) {
            max = maxOf(max, points[index - 1].distance(points[index]))
        }
        return max.toInt()
    }
}
