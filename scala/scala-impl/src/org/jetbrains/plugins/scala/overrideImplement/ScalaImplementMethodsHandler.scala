package org.jetbrains.plugins.scala
package overrideImplement

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.scala.overrideImplement.ScalaOIUtil.invokeOverrideImplement

/**
* User: Alexander Podkhalyuzin
* Date: 08.07.2008
*/

class ScalaImplementMethodsHandler extends ScalaCodeInsightActionHandler {
  override def startInWriteAction: Boolean = false

  def invoke(project: Project, editor: Editor, file: PsiFile): Unit =
    invokeOverrideImplement(project, editor, file, isImplement = true)
}