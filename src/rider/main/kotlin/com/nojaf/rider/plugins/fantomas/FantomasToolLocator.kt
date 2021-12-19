package com.nojaf.rider.plugins.fantomas

import arrow.core.*
import net.swiftzer.semver.SemVer
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.File
import java.io.IOException
import java.lang.Exception
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.TimeUnit

private fun <A, B> Either<A, B>.flatMapLeft(f: () -> Either<A, B>): Either<A, B> {
    return when (this) {
        is Either.Right -> this
        is Either.Left -> f()
    }
}

private fun isCompatibleFantomasVersion(version: String): Boolean {
    val semanticVersion = SemVer.parse(version)
    return semanticVersion.compareTo(minimalVersion) > -1
}

// Either return a compatible Fantomas version of an error for the log
private fun runToolListCmd(workingDir: Folder, globalFlag: Boolean): Either<String, FantomasVersion> {
    fun runCommand(workingDir: Folder, globalFlag: Boolean): Either<String, List<String>> {
        try {
            val proc =
                (if (globalFlag) ProcessBuilder("dotnet", "tool", "list", "-g") else ProcessBuilder(
                    "dotnet",
                    "tool",
                    "list"
                ))
                    .directory(File(workingDir.value))
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE).let { builder ->
                        val env = builder.environment()
                        env["DOTNET_CLI_UI_LANGUAGE"] = "en-us"
                        builder
                    }
                    .start()
                    .also {
                        it.waitFor(2, TimeUnit.MINUTES)
                    }
            if (proc.exitValue() != 0) {
                val error = proc.errorStream.bufferedReader().readText(); println(error)
            }
            return proc.inputStream.bufferedReader().readLines().right()
        } catch (e: IOException) {
            return e.toString().left()
        }
    }

    val listType = (if (globalFlag) "global" else "local")

    return runCommand(workingDir, globalFlag).map { lines ->
        val fantomasVersionEntry: Option<Either<String, FantomasVersion>> =
            // First two lines are table header
            lines
                .drop(2)
                .mapNotNull { line ->
                    val parts = line.trim().split(" ").filter { it.isBlank().not() }
                    if (parts.size >= 2 && (parts[0] == "fantomas-tool" || parts[0] == "fantomas")) parts[1] else null
                }
                .firstOrNone()
                .map {
                    if (isCompatibleFantomasVersion(it)) FantomasVersion(it).right()
                    else "Could not find any compatible install of fantomas or fantomas-tool, got $it for $listType list.".left()
                }
        return fantomasVersionEntry.getOrElse { "Could not find any install of fantomas or fantomas-tool in $listType list.".left() }
    }
}

private val isWindows = (System.getProperty("os.name").startsWith("Windows"))

private fun fantomasVersionOnPath(): Option<Pair<FantomasVersion, FantomasExecutableFile>> {
    val pathSplitter = if (isWindows) ';' else ':'
    val pathEnv = System.getenv("PATH").orEmpty()
    val fantomasExecutableFile =
        pathEnv.split(pathSplitter).mapNotNull { folder ->
            if (isWindows) {
                val fantomasExe = Paths.get(folder, "fantomas.exe")
                val fantomasToolExe = Paths.get(folder, "fantomas-tool.exe")
                if (File(fantomasExe.toUri()).exists()) fantomasExe else if (File(fantomasToolExe.toUri()).exists()) fantomasToolExe else null
            } else {
                null
            }
        }.firstOrNone()

    fun getVersion(fantomasExecutableFile: Path): Option<String> {
        try {
            val proc =
                ProcessBuilder(fantomasExecutableFile.toString(), "--version")
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()
                    .also {
                        it.waitFor(1, TimeUnit.MINUTES)
                    }
            if (proc.exitValue() != 0) {
                val error = proc.errorStream.bufferedReader().readText(); println(error)
            }
            return proc.inputStream.bufferedReader().readText().lowercase(Locale.getDefault()).replace("fantomas v", "")
                .split("+")[0].trim().some()
        } catch (e: IOException) {
            return none()
        }
    }
    // "No compatible fantomas version found on PATH ($pathEnv)".left()
    return fantomasExecutableFile
        .flatMap { file -> getVersion(file).map { Pair(file, it) } }
        .flatMap { (file, version) ->
            if (isCompatibleFantomasVersion(version)) Pair(
                FantomasVersion(version),
                FantomasExecutableFile(file.toString())
            ).some() else none()
        }
}

fun findFantomasTool(workingDir: Folder): Either<NoCompatibleVersionFound, FantomasToolFound> {
    return runToolListCmd(workingDir, false)
        .map { FantomasToolFound(it, FantomasToolStartInfo.LocalTool(workingDir)) }
        .flatMapLeft {
            when (val globalResult = runToolListCmd(workingDir, true)) {
                is Either.Left -> NoCompatibleVersionFound().left()
                is Either.Right -> {
                    FantomasToolFound(globalResult.value, FantomasToolStartInfo.GlobalTool).right()
                }
            }
        }
        .flatMapLeft {
            when (val onPathResult = fantomasVersionOnPath()) {
                is None -> NoCompatibleVersionFound().left()
                is Some -> {
                    FantomasToolFound(
                        onPathResult.value.first,
                        FantomasToolStartInfo.ToolOnPath(onPathResult.value.second)
                    ).right()
                }
            }
        }
        .mapLeft { _error -> NoCompatibleVersionFound() }
}

fun createFor(startInfo: FantomasToolStartInfo): Either<String, RunningFantomasTool> {
//    val writer = PrintWriter(System.out)
    val processStart =
        when (startInfo) {
            is FantomasToolStartInfo.LocalTool -> ProcessBuilder("dotnet", "fantomas", "--daemon").directory(
                File(
                    startInfo.workingDirectory.value
                )
            )
            is FantomasToolStartInfo.GlobalTool -> {
                val userProfile = System.getProperty("user.home")
                val fantomasExecutableFile = if (isWindows) "fantomas.exe" else "fantomas"
                val fantomasExecutableFilePath =
                    Paths.get(userProfile, ".dotnet", "tools", fantomasExecutableFile).toString()
                ProcessBuilder(fantomasExecutableFilePath, "--daemon")
            }
            is FantomasToolStartInfo.ToolOnPath -> ProcessBuilder("fantomas", "--daemon")
        }

    try {
        processStart.redirectInput()
        processStart.redirectOutput()
        processStart.redirectError()

        val p = processStart.start()

        val builder =
            Launcher.Builder<FantomasDaemon>()
                .setLocalService(Object())
                .setRemoteInterface(FantomasDaemon::class.java)
                .setInput(p.inputStream)
                .setOutput(p.outputStream)
                .validateMessages(true)
                // .traceMessages(writer)
                .create()
        builder.startListening()
        val client = builder.remoteProxy

        return try {
            val version = client.version().get()
            RunningFantomasTool(p, client, startInfo).right()
        } catch (ex: Exception) {
            ex.toString().left()
        }

    } catch (e: IOException) {
        return e.toString().left()
    }
}