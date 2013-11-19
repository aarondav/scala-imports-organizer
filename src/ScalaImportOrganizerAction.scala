import java.util.Objects

import scala.{Ordering, Some}
import scala.collection.{Seq, mutable}
import scala.collection.JavaConversions._

import com.google.common.collect._
import com.intellij.openapi.actionSystem._
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi._
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.codeInspection.unusedInspections.ScalaOptimizeImportsFix
import org.jetbrains.plugins.scala.lang.psi.ScImportsHolder
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.{ScImportExpr, ScImportStmt}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.packaging.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory

case class Import(qualifier: String, name: String, rename: Option[String], expr: ScImportExpr)
  extends Comparable[Import] {

  override def compareTo(other: Import): Int = comparableName.compareTo(other.comparableName)

  /**
   * Order _ before anything else, and order final packages above subpackages.
   * We do the latter, such that "java.util" is ordered before "java.awt.blah" because
   * we want something like
   *     import org.apache.spark.deploy.{ApplicationDescription, ExecutorState}
   *     import org.apache.spark.deploy.DeployMessages._
   * instead of inline ordering, which would have the two broken up by the DeployMessages import.
   * (Note that the period character comes before all other word characters, and underscore comes after.)
   */
  def comparableName = qualifier + ".." + (if (name == "_") "" else name)

  override def equals(other: Any): Boolean = {
    if (other == null || !other.isInstanceOf[Import]) { return false }
    val o = other.asInstanceOf[Import]
    Objects.equals(qualifier, o.qualifier) &&
      Objects.equals(name, o.name) &&
      Objects.equals(rename, o.rename) &&
      Objects.equals(expr, o.expr)
  }

  override def hashCode() = {
    Objects.hash(qualifier, name, rename)
  }
}

class ScalaImportOrganizerAction extends AnAction("Scala Import Organizer") {

  def actionPerformed(event: AnActionEvent) {
    ApplicationManager.getApplication.runWriteAction(getRunnableWrapper(event.getProject, new Runnable {
      def run() {
        hahaDoShit(event)
      }
    }))
  }

  def getRunnableWrapper(project: Project, runnable: Runnable): Runnable = {
    new Runnable() {
      override def run() {
        CommandProcessor.getInstance().executeCommand(
          project, runnable, "organize imports", ActionGroup.EMPTY_GROUP)
      }
    }
  }

  def getImportHolder(ref: PsiElement, file: ScalaFile): ScImportsHolder = {
    PsiTreeUtil.getParentOfType(ref, classOf[ScPackaging]) match {
      case null => file.asInstanceOf[ScImportsHolder]
      case packaging: ScPackaging => packaging
    }
  }

  def hahaDoShit(event: AnActionEvent) {
    val psiClass: PsiClass = getPsiClassFromContext(event)
  }

  private def getPsiClassFromContext(e: AnActionEvent): PsiClass = {
    val psiFile: PsiFile = e.getData(LangDataKeys.PSI_FILE)
    val editor: Editor = e.getData(PlatformDataKeys.EDITOR)
    if (psiFile == null || editor == null || !psiFile.isInstanceOf[ScalaFile]) {
      return null
    }

    val offset: Int = editor.getCaretModel.getOffset
    val elementAt: PsiElement = psiFile.findElementAt(offset)
    System.out.println("Hello > " + elementAt)
    val scalaFile = psiFile.asInstanceOf[ScalaFile]
    val importHolder = getImportHolder(elementAt, scalaFile)

    if (ScalaImportsStyleSettings.getInstance(scalaFile.getProject).optimizeImports) {
      new ScalaOptimizeImportsFix().invoke(scalaFile.getProject, editor, scalaFile)
    }

    val importMap = TreeMultimap.create[String, Import]()
    val priorImports = mutable.ArrayBuffer[ScImportExpr]()
    if (scalaFile != null) {
      val imports = importHolder.getImportStatements
      for (st <- imports) {
        for (expr <- st.importExprs) {
          priorImports.add(expr)

          val qualifier = Option(expr.qualifier).map(_.qualName).getOrElse("")
          val selectors = expr.getNames.toList ++ (if (expr.singleWildcard) Seq("_") else Nil) //expr.reference.map(_.qualName)
          println(">> " + selectors + " / " + expr.singleWildcard)
          val renameRegex = "\\s*([\\w]+)\\s*=>\\s*([\\w]+)\\s*".r
          val standardRegex = "\\s*([\\w]+)\\s*".r

          selectors.map {
            case renameRegex(name, rename) => Import(qualifier, name, Some(rename), expr)
            case standardRegex(name) => Import(qualifier, name, None, expr)
          }.foreach(selector => importMap.put(qualifier, selector))
        }
      }

      val filteredImportMap = filterImportMap(importMap)
      println("Filtered!" + filteredImportMap)

      val importStyle = ScalaImportsStyleSettings.getInstance(scalaFile.getProject).importStyle + "\n\nimport *"

      val toWrite = new ImportStyleProcessor(importStyle).process(scalaFile.getManager, filteredImportMap)

      val firstImport: PsiElement = PsiTreeUtil.getChildOfType(importHolder, classOf[ScImportStmt])
      var finalElem: PsiElement = null
      for (write <- toWrite) {
        println("Write: " + write)
        finalElem = importHolder.addBefore(write, firstImport)
      }
      priorImports.foreach(_.deleteExpr())

      var nextElem = finalElem.getNextSibling
      val extraWhitespace = mutable.ArrayBuffer[PsiWhiteSpace]()
      while (nextElem != null) {
        println(" >> " + nextElem.getClass + " / " + nextElem.getText + " : " + nextElem.isInstanceOf[PsiWhiteSpace])
        nextElem match {
          case whitespace: PsiWhiteSpace =>
            extraWhitespace += nextElem.asInstanceOf[PsiWhiteSpace]
            nextElem = nextElem.getNextSibling
          case _ =>
            nextElem = null
        }
      }

      extraWhitespace.head.replace(makeNewLineElement(scalaFile.getManager))
      extraWhitespace.tail.foreach(_.delete())
    }
    else {
      System.out.println("SCALA FILE NULL!")
    }
    return null
  }

  // Remove duplicates and imports that are subsumed by wildcards.
  def filterImportMap(importMap: TreeMultimap[String, Import]): TreeMultimap[String, Import] = {
    val filteredMap = TreeMultimap.create[String, Import]()
    val it = importMap.values().iterator()
    while (it.hasNext) {
      val imp = it.next()
      it.remove()
      val pkg = Sets.union(importMap.get(imp.qualifier), filteredMap.get(imp.qualifier))
      val isRedundant = pkg.contains(imp) || pkg.exists(_.name == "_")
      if (!isRedundant || imp.rename.isDefined) {
        println("Not redundant (" + imp.name + "): " + pkg.exists(_.name == "_") + " / " + pkg)
        filteredMap.put(imp.qualifier, imp)
      } else {
        println("Found redundant import: " + imp.qualifier + imp.name)
      }
    }
    filteredMap
  }

  class ImportStyleProcessor(importStyle: String) {
    val exactTopLevelImportRegex = "import ([^\\*\\.])+".r
    val exactImportRegex = "import ([^\\*]*[^\\.\\*])\\.([\\w]+)".r
    val wildcardImportRegex = "import ([^\\*]*[^\\.\\*])\\.\\*".r
    val matchAllRegex = "import \\*".r
    val newLine = "\\s*".r

    def process(manager: PsiManager, importMap: TreeMultimap[String, Import]): Seq[PsiElement] = {
      val elements = mutable.ArrayBuffer[PsiElement]()
      var newlineBuffered = false
      val bucketedImports = bucketImports(manager, importMap)
      var joinedImport = mutable.ArrayBuffer[Import]()
      for (line <- importStyle.lines) {
        if (line.stripMargin.isEmpty) {
          if (joinedImport.size > 0) {
            elements += makeImportElement(manager, joinedImport)
            joinedImport.clear()
          }
          newlineBuffered = true
        } else {
          val sortedImports = bucketedImports.removeAll(line)
          for (imp: Import <- sortedImports) {
            if (newlineBuffered) {
              println()
              elements += makeNewLineElement(manager)
              newlineBuffered = false
            }

            if (joinedImport.size > 0 && joinedImport.head.qualifier != imp.qualifier) {
              elements += makeImportElement(manager, joinedImport)
              joinedImport.clear()
            }

            joinedImport.add(imp)
          }
        }
      }

      elements.toSeq
    }

    def processLine(line: String, importMap: TreeMultimap[String, Import]): Seq[Import] = line match {
      case exactTopLevelImportRegex(selector) =>
        importMap.get("").find(_ == selector).toSeq
      case exactImportRegex(prefix, selector) =>
        importMap.get(prefix).find(_.name == selector).toSeq
      case wildcardImportRegex(prefix) =>
        val matches = importMap.keySet().
          filter(qualifier => qualifier == prefix || qualifier.startsWith(prefix + "."))
        matches.flatMap(qualifier => importMap.get(qualifier)).toSeq
      case matchAllRegex() =>
        importMap.keySet().flatMap(qualifier => importMap.get(qualifier)).toSeq
      case e @ _ => throw new IllegalStateException(s"Invalid import style: `$e`")
    }

    def bucketImports(manager: PsiManager, importMap: TreeMultimap[String, Import]): TreeMultimap[String, Import] = {
      val reverseLengthOrdering = Ordering.by[(String, Set[Import]), (Int, String)]{ case (s, i) => (s.size, s) }.reverse
      val buckets = importStyle.lines.filter(_.contains("import"))
      val bucketMap = buckets.map(line => (line, processLine(line, importMap).toSet)).toList.sorted(reverseLengthOrdering)
      val uniqueBuckets = TreeMultimap.create[String, Import]()
      for (imp <- importMap.values()) {
        val firstMatchingBucket = bucketMap.find { case (line, bucket) => bucket.contains(imp) }.get // It should exist!
        uniqueBuckets.put(firstMatchingBucket._1, imp)
      }

      assert (uniqueBuckets.values().size() == importMap.values().size())
      uniqueBuckets
    }
  }

  def makeNewLineElement(manager: PsiManager): PsiElement = {
    ScalaPsiElementFactory.createNewLine(manager, "\n\n")
  }

  def makeImportElement(manager: PsiManager, imports: Seq[Import]): ScImportStmt = {
    assert(imports.size > 0)
    val firstImport = imports.head
    val qualifier = if (firstImport.qualifier == "") "" else firstImport.qualifier + "."
    val selector =
      if (imports.size == 1 && firstImport.rename.isEmpty) {
        firstImport.name
      } else {
        // This will sort imports such that the wildcard is LAST, which is very important for renames.
        val sortedImports = imports.toList.sorted(Ordering.by[Import, String](_.name))
        val importString = sortedImports.map { imp =>
          val renameString = imp.rename.map(" => " + _).getOrElse("")
          imp.name + renameString
        }.mkString(", ")
        "{" + importString + "}"
      }
    ScalaPsiElementFactory.createImportFromText("import " + qualifier + selector, manager)
  }
}