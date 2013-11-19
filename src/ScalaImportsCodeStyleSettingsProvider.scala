import com.intellij.application.options.{TabbedLanguageCodeStylePanel, CodeStyleAbstractPanel, CodeStyleAbstractConfigurable, SmartIndentOptionsEditor}
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationBundle
import com.intellij.openapi.options.Configurable
import com.intellij.psi.codeStyle._
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider.SettingsType
import java.lang.String
import org.jetbrains.plugins.scala.lang.formatting.settings.{TypeAnnotationsPanel, MultiLineStringCodeStylePanel, ScalaTabbedCodeStylePanel, ScalaCodeStyleSettings}
import org.jetbrains.plugins.scala.{ScalaBundle, ScalaFileType}
import scala.collection.mutable.ArrayBuffer

class ImportsTabbedCodeStylePanel(currentSettings: CodeStyleSettings, settings: CodeStyleSettings)
  extends TabbedLanguageCodeStylePanel(ScalaFileType.SCALA_LANGUAGE, currentSettings, settings) {
  protected override def initTabs(settings: CodeStyleSettings) {
//    super.initTabs(settings)
//    addTab(new MultiLineStringCodeStylePanel(settings))
    addTab(new ImportsPanel(settings))
  }
}

class ScalaImportsCodeStyleSettingsProvider extends CodeStyleSettingsProvider {
//  override def getLanguage: Language = ScalaFileType.SCALA_LANGUAGE
//  override def getCodeSample(settingsType: SettingsType): String = "Hello!"
override def getConfigurableDisplayName: String = "Scala Imports Organizer"

  def createSettingsPage(settings: CodeStyleSettings, originalSettings: CodeStyleSettings): Configurable = {
    new CodeStyleAbstractConfigurable(settings, originalSettings, "Scala") {
      protected def createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel = {
        new ImportsTabbedCodeStylePanel(getCurrentSettings, settings)
      }

      def getHelpTopic: String = {
        null
      }
    }
  }

  override def getPriority: DisplayPriority = DisplayPriority.COMMON_SETTINGS

  override def getLanguage = ScalaFileType.SCALA_LANGUAGE

  override def createCustomSettings(settings: CodeStyleSettings) = {
    new ScalaImportsStyleSettings(settings)
  }
}