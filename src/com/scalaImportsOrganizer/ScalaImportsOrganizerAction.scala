package com.scalaImportsOrganizer

import scala.collection.{Seq, mutable}
import scala.collection.JavaConversions._

import com.google.common.collect.{Sets, TreeMultimap}
import com.intellij.openapi.actionSystem._
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi._
import com.intellij.psi.util.PsiTreeUtil
import com.scalaImportsOrganizer.ScalaImportsOrganizerAction.Import
import org.jetbrains.plugins.scala.codeInspection.unusedInspections.ScalaOptimizeImportsFix
import org.jetbrains.plugins.scala.lang.psi.ScImportsHolder
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.{ScImportExpr, ScImportStmt}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.packaging.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory

/**
 * Attempts a best-effort organization of Scala imports.
 * By default, shows up in the Tools menu and with the shortcut Ctrl+Shift+O, as in Organize.
 *
 * Things we do:
 *   - Bucket imports based on style configuration, then sort within each bucket.
 *   - Place multiple imports from the same package on the same line.
 *   - Correctly handle renaming imports with wildcards.
 *       We will ensure that all renames come before the final wildcard.
 *       We will not attempt to de-duplicate imports that utilize renaming.
 *   - Correctly handle all import formats from the Scala language specification.
 *       Who the hell uses the "import java.util._, java.awt._" style, though?
 *   - Optionally remove unused imports using the Intellij Scala Import Optimizer.
 *   - Attempt to maintain correct spacing before and after the imports.
 *       This is actually based on the initial spacing, so if the pre/post imports
 *       spacing is wrong, you should only have to fix it once.
 *
 * Things we don't do:
 *  - Detect non-commutative imports.
 *      If your project is fucked up enough to have these, this plugin won't save you.
 *  - Allow arbitrary spacing between import groups.
 *      One extra new line should be enough.
 *
 * TODO: Optimize some.
 */
class ScalaImportsOrganizerAction extends AnAction("Scala Import Organizer") {

  class ScalaImportsOrganizer(scalaFile: ScalaFile, importHolder: ScImportsHolder, event: AnActionEvent) {
    val manager = scalaFile.getManager
    val project = scalaFile.getProject
    val settings = ScalaImportsStyleSettings.getInstance(project)
    val optimizeImports = settings.optimizeImports
    /** Add an implicit "import *" to the imports. */
    val importStyle = settings.importStyle + "\n\nimport *"

    def organizeImports() {
      if (optimizeImports) { removeUnusedImports(scalaFile, event) }

      val (fullImportMap, priorImportExprs) = accumulateImports(importHolder)
      val filteredImportMap = removeRedundant(fullImportMap)
      val bucketedImports = bucketImports(filteredImportMap)

      val statements = produceImportStatements(bucketedImports)
      val finalImport = insertImportStatements(statements)
      removePriorImports(priorImportExprs, finalImport)
    }

    private def removeUnusedImports(scalaFile: ScalaFile, e: AnActionEvent) {
      val editor: Editor = e.getData(PlatformDataKeys.EDITOR)
      new ScalaOptimizeImportsFix().invoke(e.getProject, editor, scalaFile)
    }

    /**
     * Accumulate top-level imports into a map of {qualifier => imports}.
     * Additonally record the actual import expressions we parsed.
     * TODO: Probably don't actually need to use TreeMultimap here.
     */
    private def accumulateImports(importHolder: ScImportsHolder): (TreeMultimap[String, Import], Seq[ScImportExpr]) = {
      val importMap = TreeMultimap.create[String, Import]()
      val priorImportExprs = mutable.ArrayBuffer[ScImportExpr]()
      for (st <- importHolder.getImportStatements) {
        for (expr <- st.importExprs) {
          priorImportExprs.add(expr)

          val qualifier = Option(expr.qualifier).map(_.qualName).getOrElse("")
          val selectors = expr.getNames.toList ++ (if (expr.singleWildcard) Seq("_") else Nil)

          val renameRegex = "\\s*([\\w]+)\\s*=>\\s*([\\w]+)\\s*".r
          val standardRegex = "\\s*([\\w]+)\\s*".r
          selectors.map {
            case renameRegex(name, rename) => Import(qualifier, name, Some(rename), expr)
            case standardRegex(name) => Import(qualifier, name, None, expr)
          }.foreach(selector => importMap.put(qualifier, selector))
        }
      }
      (importMap, priorImportExprs)
    }

    /** Remove duplicates and imports that are subsumed by wildcards. Never remove renames. Clears importMap.  */
    // TODO: Remove leading whitespace!
    private def removeRedundant(importMap: TreeMultimap[String, Import]): TreeMultimap[String, Import] = {
      val filteredMap = TreeMultimap.create[String, Import]()
      val it = importMap.values().iterator()
      while (it.hasNext) {
        val imp = it.next()
        it.remove()
        val pkg = Sets.union(importMap.get(imp.qualifier), filteredMap.get(imp.qualifier))
        val isRedundant = pkg.contains(imp) || pkg.exists(_.name == "_")
        if (!isRedundant || imp.rename.isDefined) {
          filteredMap.put(imp.qualifier, imp)
        }
      }
      filteredMap
    }

    /** Inserts all the statements right before the first original import. */
    private def insertImportStatements(statements: Seq[PsiElement]): PsiElement = {
      val firstImport = PsiTreeUtil.getChildOfType(importHolder, classOf[ScImportStmt])
      var finalElem: PsiElement = null
      for (statement <- statements) {
        finalElem = importHolder.addBefore(statement, firstImport)
      }
      finalElem
    }

    private def removePriorImports(priorImports: Seq[ScImportExpr], finalElem: PsiElement) {
      priorImports.foreach(_.deleteExpr())

      if (finalElem != null) {
        var nextElem = finalElem.getNextSibling
        val extraWhitespace = mutable.ArrayBuffer[PsiWhiteSpace]()
        while (nextElem != null) {
          nextElem match {
            case whitespace: PsiWhiteSpace =>
              extraWhitespace += nextElem.asInstanceOf[PsiWhiteSpace]
              nextElem = nextElem.getNextSibling
            case _ =>
              nextElem = null
          }
        }

        if (extraWhitespace.size >= 1) {
          extraWhitespace.head.replace(makeNewLineElement())
          extraWhitespace.tail.foreach(_.delete())
        }
      }
    }

    /**
     * Produces a set of ordered AST elements for the set of imports, with separating whitespace.
     * We buffer the extra newline between import buckets, so there can never be more than 1,
     * and buckets with no matches do not cause extra whitespace to appear.
     * Additionally, we utilize the strict ordering of imports to merge imports with identical
     * qualifiers into a single line.
     */
    private def produceImportStatements(bucketedImports: TreeMultimap[String, Import]): Seq[PsiElement] = {
      val statements = mutable.ArrayBuffer[PsiElement]()
      var newlineBuffered = false
      // Buffers all imports with the same qualifier to write them on the same line.
      var bufferedQualifier: String = null
      val bufferedImports = mutable.ArrayBuffer[Import]()

      def emitImportStatement() {
        if (bufferedImports.size > 0) {
          statements += makeImportStatement(bufferedImports)
          bufferedImports.clear()
        }
      }

      for (bucket <- importStyle.lines) {
        if (bucket.stripMargin.isEmpty && (bufferedImports.size() > 0 || !statements.isEmpty)) {
          newlineBuffered = true
          emitImportStatement()
        } else {
          val sortedImports = bucketedImports.removeAll(bucket)
          for (imp: Import <- sortedImports) {
            if (newlineBuffered) {
              statements += makeNewLineElement()
              newlineBuffered = false
            }

            if (bufferedQualifier != imp.qualifier) {
              emitImportStatement()
            }

            bufferedQualifier = imp.qualifier
            bufferedImports.add(imp)
          }
        }
      }

      statements.toSeq
    }

    /**
     * Buckets a map from {qualifier => imports} into a map from {bucket => imports}.
     * Buckets are defined like "import java.util.*" to match everything with that prefix.
     * When a particular import could match multiple buckets, the most specific bucket is used.
     */
    private def bucketImports(importMap: TreeMultimap[String, Import]): TreeMultimap[String, Import] = {
      val reverseLengthOrdering = Ordering.by[(String, Set[Import]), (Int, String)]{
        case (qualifier, imports) => (qualifier.size, qualifier)
      }.reverse
      val buckets = importStyle.lines.filter(_.contains("import"))
      // Map each bucket to _all_ the imports that match.
      val bucketMap = buckets.map {
        bucket => (bucket, getMatchingImports(bucket, importMap).toSet)
      }.toList.sorted(reverseLengthOrdering)

      // Now utilize the bucket ordering to ensure each import goes into only the most specific bucket.
      val uniqueBuckets = TreeMultimap.create[String, Import]()
      for (imp <- importMap.values()) {
        val firstMatchingBucket = bucketMap.find { case (line, bucket) => bucket.contains(imp) }.get // It should exist!
        uniqueBuckets.put(firstMatchingBucket._1, imp)
      }

      assert (uniqueBuckets.values().size() == importMap.values().size())
      uniqueBuckets
    }

    val exactTopLevelImportRegex = "import ([^\\*\\.])+".r
    val exactImportRegex = "import ([^\\*]*[^\\.\\*])\\.([\\w]+)".r
    val wildcardImportRegex = "import ([^\\*]*[^\\.\\*])\\.\\*".r
    val innerWildcardImportRegex = "import ([^\\*]*[^\\.\\*])\\.\\*\\.([^\\*]*[^\\.\\*])".r
    val matchAllRegex = "import \\*".r
    val newLine = "\\s*".r

    /** Returns all imports matching the given bucket description within the imports map. */
    private def getMatchingImports(bucket: String, importMap: TreeMultimap[String, Import]): Seq[Import] =
      bucket match {
        case exactTopLevelImportRegex(selector) =>
          importMap.get("").find(_ == selector).toSeq
        case exactImportRegex(prefix, selector) =>
          importMap.get(prefix).find(_.name == selector).toSeq
        case wildcardImportRegex(prefix) =>
          val matches = importMap.keySet().
            filter(qualifier => qualifier == prefix || qualifier.startsWith(prefix + "."))
          matches.flatMap(qualifier => importMap.get(qualifier)).toSeq
        case innerWildcardImportRegex(prefix, suffix) =>
	        val matches = importMap.entries().map(e => e.getKey + "." + e.getValue.rename.getOrElse(e.getValue.name) -> e.getValue).toMap
		        .filterKeys(k => k.startsWith(prefix + ".") && k.endsWith("." + suffix))
	        matches.values.toSeq
        case matchAllRegex() =>
          importMap.values().toSeq
        case e @ _ => throw new IllegalStateException("Invalid import style: " + e)
      }

    /**
     * Produces an actual AST new line. Note that two newlines seem to be required for this effect.
     */
    private def makeNewLineElement(): PsiElement = {
      ScalaPsiElementFactory.createNewLine(manager, "\n\n")
    }

    /**
     * Produces an actual AST import statement.
     * Avoids using {} on single-imports.
     * Also ensures that wildcards are put after all rename expressions.
     */
    private def makeImportStatement(imports: Seq[Import]): ScImportStmt = {
      assert(imports.size > 0)
      val qualifier = imports.head.qualifier
      val separator = if (qualifier == "") "" else "."
      val selector =
        if (imports.size == 1 && imports(0).rename.isEmpty) {
          imports(0).name
        } else {
          // This will sort imports such that the wildcard is LAST, which is very important for renames.
          val sortedImports = imports.toList.sorted(Ordering.by[Import, String](_.name))
          val importString = sortedImports.map { imp =>
            val renameString = imp.rename.map(" => " + _).getOrElse("")
            imp.name + renameString
          }.mkString(", ")
          "{" + importString + "}"
        }
      ScalaPsiElementFactory.createImportFromText("import " + qualifier + separator + selector, manager)
    }
  }

  def actionPerformed(event: AnActionEvent) {
    withinWritableContext(event.getProject, "organize imports") {
      val (scalaFile, importHolder) = getScalaContext(event)
      if (scalaFile != null) {
        new ScalaImportsOrganizer(scalaFile, importHolder, event).organizeImports()
      }
    }
  }

  private def getScalaContext(e: AnActionEvent): (ScalaFile, ScImportsHolder) = {
    val psiFile: PsiFile = e.getData(LangDataKeys.PSI_FILE)
    val editor: Editor = e.getData(PlatformDataKeys.EDITOR)
    if (psiFile == null || editor == null || !psiFile.isInstanceOf[ScalaFile]) {
      return (null, null)
    }

    val offset: Int = editor.getCaretModel.getOffset
    val elementAt: PsiElement = psiFile.findElementAt(offset)
    val scalaFile = psiFile.asInstanceOf[ScalaFile]
    val importHolder = getImportHolder(elementAt, scalaFile)
    (scalaFile, importHolder)
  }

  private def getImportHolder(ref: PsiElement, file: ScalaFile): ScImportsHolder = {
    PsiTreeUtil.getParentOfType(ref, classOf[ScPackaging]) match {
      case null => file.asInstanceOf[ScImportsHolder]
      case packaging: ScPackaging => packaging
    }
  }

  /** Enables writing to the IntelliJ AST as well as undoing whatever we write. */
  def withinWritableContext(project: Project, name: String)(f: => Unit) {
    val finalRunnable = new Runnable {
      def run() = f
    }
    val commandRunnable = new Runnable {
      def run() = CommandProcessor.getInstance().executeCommand(project, finalRunnable, name, ActionGroup.EMPTY_GROUP)
    }
    ApplicationManager.getApplication.runWriteAction(commandRunnable)
  }
}

object ScalaImportsOrganizerAction {
  private case class Import(qualifier: String, name: String, rename: Option[String], expr: ScImportExpr)
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
      qualifier.equals(o.qualifier) &&
        name.equals(o.name) &&
        rename.equals(o.rename) &&
        expr.equals(o.expr)
    }

    override def hashCode() = {
      (qualifier + name + rename).hashCode // fucking greatest hash function ever
    }
  }
}
