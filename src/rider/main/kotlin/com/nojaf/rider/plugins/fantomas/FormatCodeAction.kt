package com.nojaf.rider.plugins.fantomas

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.jetbrains.rdclient.editors.getPsiFile
import com.jetbrains.rider.actions.base.RiderAnAction
import com.jetbrains.rider.runtime.RiderDotNetActiveRuntimeHost

class FormatCodeAction :
    RiderAnAction("FormatWithFantomas", "Format with Fantomas", "Format the current file with Fantomas") {
    private val logger = logger<FormatCodeAction>()
    private val fantomasService = service<FantomasService>()

    private fun isFSharpFile(path: String): Boolean {
        return arrayOf(".fs", ".fsi", ".fsx").any { path.endsWith(it, ignoreCase = true) }
    }

    override fun update(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.EDITOR)?.getPsiFile()
        val path = psiFile?.virtualFile?.path
        e.presentation.isEnabledAndVisible = if (path != null) isFSharpFile(path) else false
    }

    override fun beforeActionPerformedUpdate(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.EDITOR)?.getPsiFile()
        val path = psiFile?.virtualFile?.path
        e.presentation.isEnabledAndVisible = if (path != null) isFSharpFile(path) else false
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        val showInfoMessage = Runnable {
            NotificationGroupManager.getInstance().getNotificationGroup("Fantomas")
                .createNotification(title, content, type).setListener(NotificationListener.UrlOpeningListener(true))
                .notify(project)
        }
        WriteCommandAction.runWriteCommandAction(project, showInfoMessage)
    }

    private fun handleResponse(
        project: Project, document: Document, path: String, originalText: String, response: FantomasResponse
    ) {
        when (response.code) {
            FantomasResponseCode.Formatted -> response.content.tap {
                val replaceText = Runnable { document.replaceString(0, originalText.length, it) }
                WriteCommandAction.runWriteCommandAction(project, replaceText)
            }
            FantomasResponseCode.UnChanged -> logger.info("$path is unchanged")
            FantomasResponseCode.Error -> {
                response.content.tap {
                    logger.warn(it)
                    notify(
                        project,
                        "Error while formatting file",
                        "$path could not be formatted.\n$it",
                        NotificationType.ERROR
                    )
                }
            }
            FantomasResponseCode.Ignored -> {
                logger.info("$path is ignored by .fantomasignore")
                notify(
                    project,
                    "File is ignored",
                    "$path is matched by a .fantomasignore file.",
                    NotificationType.INFORMATION
                )
            }
            FantomasResponseCode.Version -> {
                response.content.tap { logger.info("Found version $it") }
            }
            FantomasResponseCode.ToolNotFound -> {
                response.content.tap { logger.error(it) }
                notify(
                    project,
                    "No compatible tool was found.",
                    """<html>Could not find a local of global install of a compatible fantomas-tool.
Please read <a href="https://github.com/fsprojects/fantomas/blob/master/docs/Daemon%20mode.md" target="_blank">the documentation</a> for more details.""",
                    NotificationType.ERROR
                )
            }
            FantomasResponseCode.FileNotFound -> {
                logger.error("File \"$path\" was not found.")
                notify(project, "File not found", "File \"$path\" was not found.", NotificationType.ERROR)
            }
            FantomasResponseCode.FilePathIsNotAbsolute -> {
                logger.error("File \"$path\" is not absolute.")
                notify(project, "File is not absolute", "File \"$path\" is not absolute.", NotificationType.ERROR)
            }
            FantomasResponseCode.DaemonCreationFailed -> {
                response.content.tap { logger.error(it) }
                notify(
                    project,
                    "Could not launch Fantomas daemon",
                    "The compatible daemon could not be started. Check the logs for more information.",
                    NotificationType.ERROR
                )
            }
            FantomasResponseCode.SerializationError -> {
                response.content.tap { logger.error(it) }
                notify(
                    project,
                    "Serialization error",
                    "An LSP message could not be serialized. Check the logs for more information.",
                    NotificationType.ERROR
                )
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val document = e.getData(CommonDataKeys.EDITOR)?.document
        val psiFile = e.getData(CommonDataKeys.EDITOR)?.getPsiFile()
        val text = psiFile?.text
        val path = psiFile?.virtualFile?.path
        if (project != null && document != null && text != null && path != null) {
            val dotnetCliPath = RiderDotNetActiveRuntimeHost.getInstance(project).dotNetCoreRuntime.value?.cliExePath
            fantomasService.formatDocument(FormatDocumentRequest(text, path, dotnetCliPath = dotnetCliPath)).handle { response, ex ->
                if (ex != null) {
                    logger.error(ex)
                } else {
                    handleResponse(project, document, path, text, response)
                }
            }
        }
    }
}