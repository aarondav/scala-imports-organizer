import com.intellij.openapi.project.Project;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;

public class ScalaImportsStyleSettings extends CustomCodeStyleSettings {

    public static ScalaImportsStyleSettings getInstance(Project project) {
        return CodeStyleSettingsManager.getSettings(project).getCustomSettings(ScalaImportsStyleSettings.class);
    }

    public ScalaImportsStyleSettings(CodeStyleSettings container) {
        super("ScalaImportsStyleSettings", container);
    }

    public String importStyle =
            "import java.*\n" +
            "\n" +
            "import scala.*\n" +
            "\n" +
            "import *\n";

    public boolean optimizeImports = true;

    public boolean renamesOnNewLine = true;
}