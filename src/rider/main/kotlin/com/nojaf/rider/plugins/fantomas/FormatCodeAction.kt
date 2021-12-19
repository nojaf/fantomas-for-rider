package com.nojaf.rider.plugins.fantomas

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages
import com.jetbrains.rdclient.editors.getPsiFile
import com.jetbrains.rider.actions.base.RiderAnAction


class FormatCodeAction : RiderAnAction("FormatWithFantomas", "Format with Fantomas", "Format the current file with Fantomas") {
    private val logger = logger<FormatCodeAction>()
    private val fantomasService = LspFantomasService()

    override fun update(e: AnActionEvent) {
//        val project = e.project
//        val document = project?.projectFile
//        if (document != null) {
//            val ext = document.extension.orEmpty()
//            e.presentation.isEnabledAndVisible = ext == ".fs"
//        }
        val document = e.getData(CommonDataKeys.EDITOR)?.document
        val file = e.getData(CommonDataKeys.EDITOR)?.getPsiFile()?.virtualFile?.path
        e.presentation.isEnabledAndVisible = true
    }

    override fun beforeActionPerformedUpdate(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val document = e.getData(CommonDataKeys.EDITOR)?.document
        val psiFile = e.getData(CommonDataKeys.EDITOR)?.getPsiFile()
        val text = psiFile?.text
        val path = psiFile?.virtualFile?.path
        if (text != null && path != null) {
            val response = fantomasService.formatDocument(FormatDocumentRequest(text, path)).get()
            when (response.code) {
                FantomasResponseCode.Formatted -> response.content.tap {
                    val r = Runnable { document?.replaceString(0, text.length, it) }
                    WriteCommandAction.runWriteCommandAction(project, r)
                }
            }
        }

        // Messages.showMessageDialog("Fantomas by an LPS daemon", "Huuuuuuuuuuuuuuup", Messages.getInformationIcon())
        //currentProject, dlgMsg.toString(), dlgTitle, Messages.getInformationIcon());
    }
}