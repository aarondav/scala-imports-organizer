package com.scalaImportsOrganizer

import java.lang.String

import com.intellij.application.options.{CodeStyleAbstractConfigurable, CodeStyleAbstractPanel, TabbedLanguageCodeStylePanel}
import com.intellij.openapi.options.Configurable
import com.intellij.psi.codeStyle._
import org.jetbrains.plugins.scala.ScalaFileType

class ImportsTabbedCodeStylePanel(currentSettings: CodeStyleSettings, settings: CodeStyleSettings)
  extends TabbedLanguageCodeStylePanel(ScalaFileType.SCALA_LANGUAGE, currentSettings, settings) {
  protected override def initTabs(settings: CodeStyleSettings) {
    addTab(new ScalaImportsConfigurationPanel(settings))
  }
}

class ScalaImportsCodeStyleSettingsProvider extends CodeStyleSettingsProvider {
  override def getConfigurableDisplayName: String = "Scala Imports Organizer"

  def createSettingsPage(settings: CodeStyleSettings, originalSettings: CodeStyleSettings): Configurable = {
    new CodeStyleAbstractConfigurable(settings, originalSettings, "Scala Imports Organizer") {
      override protected def createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel = {
        new ImportsTabbedCodeStylePanel(getCurrentSettings, settings)
      }

      override def getHelpTopic: String = null
    }
  }

  override def getPriority: DisplayPriority = DisplayPriority.COMMON_SETTINGS

  override def getLanguage = ScalaFileType.SCALA_LANGUAGE

  override def createCustomSettings(settings: CodeStyleSettings) = {
    new ScalaImportsStyleSettings(settings)
  }
}
