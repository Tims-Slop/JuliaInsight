package dev.juliainsight.sdk

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import dev.juliainsight.settings.JuliaProjectSettings
import dev.juliainsight.settings.JuliaSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

data class JuliaVersion(val major: Int, val minor: Int, val patch: Int, val raw: String) : Comparable<JuliaVersion> {
    val majorMinor: String get() = "$major.$minor"
    override fun compareTo(other: JuliaVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString(): String = raw

    companion object {
        private val RE = Regex("""(\d+)\.(\d+)\.(\d+)(\S*)""")
        fun parse(s: String): JuliaVersion? = RE.find(s)?.let {
            JuliaVersion(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.value)
        }
    }
}

data class JuliaInstallation(val executable: String, val version: JuliaVersion)

/** Finds Julia on Windows, macOS and Linux (juliaup, official installers, Homebrew, PATH). */
@Service(Service.Level.APP)
class JuliaInstallations {
    private val LOG = logger<JuliaInstallations>()
    private val versionCache = ConcurrentHashMap<String, Pair<Long, JuliaVersion?>>()
    @Volatile private var detected: String? = null
    @Volatile private var detectedDone = false

    private val exeName = if (SystemInfo.isWindows) "julia.exe" else "julia"
    private val home: Path = Paths.get(System.getProperty("user.home"))

    /** Candidate executables, best first (does not run anything). */
    fun candidates(): List<String> {
        val result = LinkedHashSet<String>()
        fun add(p: Path?) {
            if (p != null && p.isRegularFile()) result += p.toAbsolutePath().toString()
        }
        // juliaup
        add(home.resolve(".juliaup").resolve("bin").resolve(exeName))
        // PATH (on macOS the IDE loads the login shell environment)
        PathEnvironmentVariableUtil.findInPath(exeName)?.let { add(it.toPath()) }
        EnvironmentUtil.getValue("PATH")?.split(File.pathSeparatorChar)?.forEach { dir ->
            if (dir.isNotBlank()) runCatching { add(Paths.get(dir, exeName)) }
        }
        when {
            SystemInfo.isMac -> {
                add(Paths.get("/opt/homebrew/bin/julia"))
                add(Paths.get("/usr/local/bin/julia"))
                for (apps in listOf(Paths.get("/Applications"), home.resolve("Applications"))) {
                    versionedDirs(apps, "Julia-") { it.fileName.toString().removeSuffix(".app") }
                        .forEach { add(it.resolve("Contents/Resources/julia/bin/julia")) }
                }
            }
            SystemInfo.isWindows -> {
                val local = System.getenv("LOCALAPPDATA")?.let { Paths.get(it) }
                if (local != null) {
                    add(local.resolve("Microsoft/WindowsApps/julia.exe"))
                    add(local.resolve("Programs/Julia/bin/julia.exe"))
                    versionedDirs(local.resolve("Programs"), "Julia-") { it.fileName.toString() }
                        .forEach { add(it.resolve("bin/julia.exe")) }
                }
                for (pf in listOfNotNull(System.getenv("ProgramFiles"), "C:\\Program Files")) {
                    versionedDirs(Paths.get(pf), "Julia-") { it.fileName.toString() }.forEach { add(it.resolve("bin/julia.exe")) }
                }
            }
            else -> {
                add(Paths.get("/usr/local/bin/julia"))
                add(Paths.get("/usr/bin/julia"))
                versionedDirs(Paths.get("/opt"), "julia") { it.fileName.toString() }.forEach { add(it.resolve("bin/julia")) }
            }
        }
        return result.toList()
    }

    private fun versionedDirs(parent: Path, prefix: String, name: (Path) -> String): List<Path> {
        if (!parent.isDirectory()) return emptyList()
        return runCatching {
            parent.listDirectoryEntries().filter { it.fileName.toString().startsWith(prefix) && it.isDirectory() }
                .sortedByDescending { JuliaVersion.parse(name(it)) ?: JuliaVersion(0, 0, 0, "") }
        }.getOrDefault(emptyList())
    }

    /** The configured executable, or the auto-detected one. May run `julia --version` (call off the EDT). */
    fun executable(): String? {
        val configured = JuliaSettings.getInstance().state.juliaExecutable.trim()
        if (configured.isNotEmpty()) return configured
        if (!detectedDone) {
            detected = candidates().firstOrNull { version(it) != null }
            detectedDone = true
        }
        return detected
    }

    /** Cheap variant that never starts a process (for UI and quick checks). */
    fun executableNoProcess(): String? {
        val configured = JuliaSettings.getInstance().state.juliaExecutable.trim()
        if (configured.isNotEmpty()) return configured
        return if (detectedDone) detected else candidates().firstOrNull()
    }

    fun resetDetection() {
        detectedDone = false
        detected = null
        versionCache.clear()
    }

    /** Non-blocking: the installation if executable and version are already known, else null. */
    fun installationIfCached(): JuliaInstallation? {
        val configured = JuliaSettings.getInstance().state.juliaExecutable.trim()
        val exe = configured.ifEmpty { if (detectedDone) detected else null } ?: return null
        val v = versionCache[exe]?.second ?: return null
        return JuliaInstallation(exe, v)
    }

    fun installation(): JuliaInstallation? {
        val exe = executable() ?: return null
        val v = version(exe) ?: return null
        return JuliaInstallation(exe, v)
    }

    fun version(executable: String): JuliaVersion? {
        val f = File(executable)
        val stamp = if (f.exists()) f.lastModified() else -1L
        versionCache[executable]?.let { (s, v) -> if (s == stamp) return v }
        val v = try {
            val cmd = GeneralCommandLine(executable, "--startup-file=no", "--version").withCharset(Charsets.UTF_8)
            val out = CapturingProcessHandler(cmd).runProcess(60_000)
            if (out.exitCode == 0) JuliaVersion.parse(out.stdout) else null
        } catch (e: Exception) {
            LOG.info("Cannot run $executable --version: ${e.message}")
            null
        }
        versionCache[executable] = stamp to v
        return v
    }

    /** First entry of JULIA_DEPOT_PATH, or ~/.julia. */
    fun depotPath(): Path {
        val env = EnvironmentUtil.getValue("JULIA_DEPOT_PATH") ?: System.getenv("JULIA_DEPOT_PATH")
        val first = env?.split(File.pathSeparatorChar)?.firstOrNull { it.isNotBlank() }
        return if (first != null) Paths.get(first) else home.resolve(".julia")
    }

    companion object {
        @JvmStatic fun getInstance(): JuliaInstallations = service()

        /** The Julia environment (directory with Project.toml) for [project]: configured, or detected from the project root. */
        fun environmentFor(project: Project): String? {
            val configured = JuliaProjectSettings.getInstance(project).state.environmentPath?.trim().orEmpty()
            if (configured.isNotEmpty()) return configured
            val base = project.basePath?.let { Paths.get(it) } ?: project.guessProjectDir()?.path?.let { Paths.get(it) } ?: return null
            if (isEnvironment(base)) return base.toString()
            // a single sub-directory that is an environment (e.g. repo/MyPackage.jl/)
            val subs = runCatching { base.listDirectoryEntries().filter { it.isDirectory() && isEnvironment(it) } }.getOrDefault(emptyList())
            return subs.singleOrNull()?.toString()
        }

        /** An existing directory to run Julia in: the project directory, else a content root, else the home directory. */
        fun workingDirectory(project: Project): Path {
            project.basePath?.let { Paths.get(it) }?.takeIf { it.isDirectory() }?.let { return it }
            runCatching {
                com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots
                    .firstOrNull { it.isInLocalFileSystem && it.isDirectory }?.toNioPath()
            }.getOrNull()?.let { return it }
            return Paths.get(System.getProperty("user.home"))
        }

        fun isEnvironment(dir: Path): Boolean =
            Files.exists(dir.resolve("Project.toml")) || Files.exists(dir.resolve("JuliaProject.toml"))

        fun exists(path: String): Boolean = runCatching { Paths.get(path).exists() }.getOrDefault(false)
    }
}
