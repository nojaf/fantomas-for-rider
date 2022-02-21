package com.nojaf.rider.plugins.fantomas

import arrow.core.*
import com.intellij.openapi.diagnostic.Logger
import net.swiftzer.semver.SemVer
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.File
import java.io.IOException
import java.nio.file.Paths
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
private fun runToolListCmd(logger: Logger, workingDir: Folder, globalFlag: Boolean): Either<String, FantomasVersion> {
    fun runCommand(workingDir: Folder, globalFlag: Boolean): Either<String, List<String>> {
        try {
            val proc = (if (globalFlag) ProcessBuilder("dotnet", "tool", "list", "-g") else ProcessBuilder(
                "dotnet", "tool", "list"
            )).directory(File(workingDir.value)).redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE).let { builder ->
                    val env = builder.environment()
                    env["DOTNET_CLI_UI_LANGUAGE"] = "en-us"
                    builder
                }.start().also {
                    it.waitFor(2, TimeUnit.MINUTES)
                }
            if (proc.exitValue() != 0) {
                val error = proc.errorStream.bufferedReader().readText(); println(error)
                logger.error(error)
            }
            return proc.inputStream.bufferedReader().readLines().right()
        } catch (e: IOException) {
            logger.error(e)
            return e.toString().left()
        }
    }

    val listType = (if (globalFlag) "global" else "local")

    return runCommand(workingDir, globalFlag).map { lines ->
        val cmd = if (globalFlag) "dotnet tool list -g" else "dotnet tool list"
        val linesConcat = lines.joinToString("\n");
        logger.info("Running $cmd returned:\n$linesConcat")
        val fantomasVersionEntry: Option<Either<String, FantomasVersion>> =
            // First two lines are table header
            lines.drop(2).mapNotNull { line ->
                val parts = line.trim().split(" ").filter { it.isBlank().not() }
                if (parts.size >= 2 && (parts[0] == "fantomas-tool" || parts[0] == "fantomas")) parts[1] else null
            }.firstOrNone().map {
                if (isCompatibleFantomasVersion(it)) FantomasVersion(it).right()
                else "Could not find any compatible install of fantomas or fantomas-tool, got $it for $listType list.".left()
            }
        return fantomasVersionEntry.getOrElse { "Could not find any install of fantomas or fantomas-tool in $listType list.".left() }
    }
}

private val isWindows = (System.getProperty("os.name").startsWith("Windows"))

fun findFantomasTool(logger: Logger, workingDir: Folder): Either<NoCompatibleVersionFound, FantomasToolFound> {
    return runToolListCmd(logger, workingDir, false)
        .map {
            FantomasToolFound(
                it,
                FantomasToolStartInfo.LocalTool(workingDir)
            )
        }
        .flatMapLeft {
            when (val globalResult = runToolListCmd(logger, workingDir, true)) {
                is Either.Left -> globalResult.value.left()
                is Either.Right -> {
                    FantomasToolFound(globalResult.value, FantomasToolStartInfo.GlobalTool).right()
                }
            }
        }.mapLeft { e: String -> NoCompatibleVersionFound(e) }
}

fun createFor(logger: Logger, startInfo: FantomasToolStartInfo): Either<String, RunningFantomasTool> {
    val processStart = when (startInfo) {
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
    }

    try {
        processStart.redirectInput()
        processStart.redirectOutput()
        processStart.redirectError()

        val p = processStart.start()

        val builder =
            Launcher.Builder<FantomasDaemon>().setLocalService(Object()).setRemoteInterface(FantomasDaemon::class.java)
                .setInput(p.inputStream).setOutput(p.outputStream).validateMessages(true).create()
        builder.startListening()
        val client = builder.remoteProxy

        return try {
            val version = client.version().get()
            logger.info("Start fantomas-tool with version $version")
            RunningFantomasTool(p, client, startInfo).right()
        } catch (ex: Exception) {
            ex.toString().left()
        }

    } catch (e: IOException) {
        return e.toString().left()
    }
}