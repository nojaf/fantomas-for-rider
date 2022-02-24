package com.nojaf.rider.plugins.fantomas

import arrow.core.*
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.util.concurrent.CompletableFuture

private sealed interface FantomasServiceError
private object FileDoesNotExist : FantomasServiceError
private object FilePathIsNotAbsolute : FantomasServiceError
private data class InCompatibleVersionFound(val message: String) : FantomasServiceError
private data class DaemonCouldNotBeStarted(val reason: String) : FantomasServiceError

private fun getFolderFor(filePath: String): Either<FantomasServiceError, Folder> {
    val file = File(filePath)
    return if (!file.exists()) {
        FileDoesNotExist.left()
    } else if (!file.isAbsolute) {
        FilePathIsNotAbsolute.left()
    } else {
        Folder(file.parent).right()
    }
}

private fun mapFantomasServiceErrorToFantomasResponse(
    filePath: String, error: FantomasServiceError
): CompletableFuture<FantomasResponse> {
    val response = when (error) {
        is FileDoesNotExist -> FantomasResponse(FantomasResponseCode.FileNotFound, filePath, None)
        is FilePathIsNotAbsolute -> FantomasResponse(FantomasResponseCode.FilePathIsNotAbsolute, filePath, None)
        is InCompatibleVersionFound -> FantomasResponse(FantomasResponseCode.ToolNotFound, filePath, None)
        is DaemonCouldNotBeStarted -> FantomasResponse(
            FantomasResponseCode.DaemonCreationFailed,
            filePath,
            error.reason.some()
        )
    }
    return CompletableFuture.completedFuture(response)
}

class LspFantomasService : FantomasService {
    private val logger = logger<LspFantomasService>()
    private val daemons = mutableMapOf<FantomasVersion, RunningFantomasTool>()
    private val folderToVersion = mutableMapOf<Folder, FantomasToolFound>()

    private fun getDaemonFromFolder(folder: Folder,  dotnetCliPath: String?): Either<FantomasServiceError, RunningFantomasTool> {
        fun findOrCreateDaemon(toolFound: FantomasToolFound): Either<FantomasServiceError, RunningFantomasTool> {
            return when (val daemon = createFor(logger, toolFound.startInfo)) {
                is Either.Left -> DaemonCouldNotBeStarted(daemon.value).left()
                is Either.Right -> {
                    daemons[toolFound.version] = daemon.value
                    daemon.value.right()
                }
            }
        }

        when (val existingVersion = folderToVersion[folder].toOption()) {
            is None -> {
                return when (val version = findFantomasTool(logger, folder, dotnetCliPath)) {
                    is Either.Left -> InCompatibleVersionFound(version.value.message).left()
                    is Either.Right -> {
                        folderToVersion[folder] = version.value
                        findOrCreateDaemon(version.value)
                    }
                }
            }
            is Some -> {
                return when (val existingDaemon = daemons[existingVersion.value.version].toOption()) {
                    is None -> {
                        // weird situation where the version is know but the daemon is not.
                        findOrCreateDaemon(existingVersion.value)
                    }
                    is Some -> existingDaemon.value.right()
                }
            }
        }
    }

    private fun getDaemon(filePath: String, dotnetCliPath: String?): Either<FantomasServiceError, RunningFantomasTool> {
        return getFolderFor(filePath).flatMap { folder -> getDaemonFromFolder(folder, dotnetCliPath) }
    }

    override fun version(request: VersionRequest): CompletableFuture<FantomasResponse> {
        return when (val daemon = getDaemon(request.filePath, request.dotnetCliPath)) {
            is Either.Left -> mapFantomasServiceErrorToFantomasResponse(request.filePath, daemon.value)
            is Either.Right -> {
                daemon.value.client.version()
                    .thenApply { version -> FantomasResponse(FantomasResponseCode.Version, request.filePath, version.some()) }
            }
        }
    }

    override fun formatDocument(request: FormatDocumentRequest): CompletableFuture<FantomasResponse> {
        return when (val daemon = getDaemon(request.filePath, request.dotnetCliPath)) {
            is Either.Left -> mapFantomasServiceErrorToFantomasResponse(request.filePath, daemon.value)
            is Either.Right -> {
                return daemon.value.client.formatDocument(request).thenApply {
                    when (val response = FormatDocumentResponse.tryParse(it)) {
                        is FormatDocumentResponse.Formatted -> FantomasResponse(
                            FantomasResponseCode.Formatted, request.filePath, response.formattedContent.toOption()
                        )
                        is FormatDocumentResponse.Unchanged -> FantomasResponse(
                            FantomasResponseCode.UnChanged, request.filePath, None
                        )
                        is FormatDocumentResponse.Error -> FantomasResponse(
                            FantomasResponseCode.Error, request.filePath, response.formattingError.toOption()
                        )
                        is FormatDocumentResponse.IgnoredFile -> FantomasResponse(
                            FantomasResponseCode.Ignored, request.filePath, none()
                        )
                        null -> FantomasResponse(FantomasResponseCode.SerializationError, request.filePath, None)
                    }
                }
            }
        }
    }

    override fun clearCache() {
        daemons.forEach { entry ->
            entry.value.process.destroy()
        }
        daemons.clear()
        folderToVersion.clear()
    }

    override fun dispose() {
        clearCache()
    }
}