package com.nojaf.rider.plugins.fantomas

import arrow.core.Option
import com.intellij.openapi.Disposable
import net.swiftzer.semver.SemVer
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import java.util.concurrent.CompletableFuture

data class FSharpDiscriminatedUnion(val Case: String, val Fields: Array<String>)
data class EndOfLine(val end_of_line: String)
data class Configuration(val Case: String, val Fields: Array<EndOfLine>)
data class FormatDocumentRequest(
    val sourceCode: String,
    val filePath: String,
    val config: Configuration = Configuration("Some", arrayOf(EndOfLine("lf")))
)

data class FormatSelectionRange(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)
data class FormatSelectionRequest(val sourceCode: String, val filePath: String, val range: FormatSelectionRange)

sealed interface FormatDocumentResponse {
    data class Formatted(val fileName: String, val formattedContent: String) : FormatDocumentResponse
    data class Unchanged(val fileName: String) : FormatDocumentResponse
    data class Error(val fileName: String, val formattingError: String) : FormatDocumentResponse
    data class IgnoredFile(val fileName: String) : FormatDocumentResponse
    companion object Static {
        fun tryParse(rawResponse: FSharpDiscriminatedUnion): FormatDocumentResponse? {
            return when {
                rawResponse.Case == "Formatted" && rawResponse.Fields.size == 2 -> Formatted(
                    rawResponse.Fields[0], rawResponse.Fields[1]
                )
                rawResponse.Case == "Unchanged" && rawResponse.Fields.size == 1 -> Unchanged(rawResponse.Fields[0])
                rawResponse.Case == "Error" && rawResponse.Fields.size == 2 -> Error(
                    rawResponse.Fields[0], rawResponse.Fields[1]
                )
                rawResponse.Case == "IgnoredFile" && rawResponse.Fields.size == 1 -> IgnoredFile(rawResponse.Fields[0])
                else -> return null
            }
        }
    }
}

sealed interface FormatSelectionResponse {
    data class Formatted(val fileName: String, val formattedContent: String) : FormatSelectionResponse
    data class Error(val fileName: String, val formattingError: String) : FormatSelectionResponse
    companion object Static {
        fun tryParse(rawResponse: FSharpDiscriminatedUnion): FormatSelectionResponse? {
            return when {
                rawResponse.Case == "Formatted" && rawResponse.Fields.size == 2 -> Formatted(
                    rawResponse.Fields[0], rawResponse.Fields[1]
                )
                rawResponse.Case == "Error" && rawResponse.Fields.size == 2 -> Error(
                    rawResponse.Fields[0], rawResponse.Fields[1]
                )
                else -> return null
            }
        }
    }
}

@JsonSegment("fantomas")
interface FantomasDaemon {
    @JsonRequest("version")
    fun version(): CompletableFuture<String>

    @JsonRequest("formatDocument")
    fun formatDocument(request: FormatDocumentRequest): CompletableFuture<FSharpDiscriminatedUnion>

    @JsonRequest("formatSelection")
    fun formatSelection(request: FormatSelectionRequest): CompletableFuture<FSharpDiscriminatedUnion>

    @JsonRequest("configuration")
    fun configuration(): CompletableFuture<String>
}

@JvmInline
value class Folder(val value: String)

@JvmInline
value class FantomasVersion(val value: String)

sealed interface FantomasToolStartInfo {
    data class LocalTool(val workingDirectory: Folder) : FantomasToolStartInfo
    object GlobalTool : FantomasToolStartInfo
}

data class FantomasToolFound(val version: FantomasVersion, val startInfo: FantomasToolStartInfo)

class NoCompatibleVersionFound

val minimalVersion = SemVer.parse("4.6.0-alpha-004")

data class RunningFantomasTool(val process: Process, val client: FantomasDaemon, val startInfo: FantomasToolStartInfo)

enum class FantomasResponseCode {
    Formatted, UnChanged, Error, Ignored, Version, ToolNotFound, FileNotFound, FilePathIsNotAbsolute,

    // CancellationWasRequested,
    DaemonCreationFailed, SerializationError
}

data class FantomasResponse(val code: FantomasResponseCode, val filePath: String, val content: Option<String>)

interface FantomasService : Disposable {
    fun version(filePath: String): CompletableFuture<FantomasResponse>
    fun formatDocument(request: FormatDocumentRequest): CompletableFuture<FantomasResponse>
    fun clearCache()
}