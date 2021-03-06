package com.scalaImportsOrganizer;

import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.scala.ScalaFileType;
import org.jetbrains.plugins.scala.highlighter.ScalaEditorHighlighter;

import javax.swing.*;
import java.awt.*;

public class ScalaImportsConfigurationPanel extends CodeStyleAbstractPanel {

    private final JPanel panel;
    private final JLabel description;
    private final JTextArea importStyle;
    private final JPanel checkboxes;
    private final JCheckBox optimizeImports;
    private final JCheckBox unicodeArrow;

    public ScalaImportsConfigurationPanel(@NotNull CodeStyleSettings settings) {
        super(settings);

        ScalaImportsStyleSettings mySettings = settings.getCustomSettings(ScalaImportsStyleSettings.class);

        panel = new JPanel();
        panel.setLayout(new BorderLayout());
        description = new JLabel("Enter groups of imports using *. Separate groups using newlines.");
        description.setPreferredSize(new Dimension(120, 20));
        importStyle = new JTextArea(mySettings.importStyle, 30, 120);
        checkboxes = new JPanel();
        checkboxes.setLayout(new BorderLayout());
        optimizeImports = new JCheckBox("Remove unused imports", mySettings.optimizeImports);
        optimizeImports.setPreferredSize(new Dimension(120, 20));
        unicodeArrow = new JCheckBox("Use Unicode \"⇒\" as arrow character", mySettings.unicodeArrow);
        unicodeArrow.setPreferredSize(new Dimension(120, 20));
        checkboxes.add(optimizeImports, BorderLayout.NORTH);
        checkboxes.add(unicodeArrow, BorderLayout.CENTER);
        panel.add(description, BorderLayout.NORTH);
        panel.add(importStyle, BorderLayout.CENTER);
        panel.add(checkboxes, BorderLayout.SOUTH);
    }

    @Override
    protected int getRightMargin() {
        return 100;
    }

    @Nullable
    @Override
    protected EditorHighlighter createHighlighter(EditorColorsScheme scheme) {
        return new ScalaEditorHighlighter(null, null, scheme);
    }

    @NotNull
    @Override
    protected FileType getFileType() {
        return ScalaFileType.SCALA_FILE_TYPE;
    }

    @Nullable
    @Override
    protected String getPreviewText() {
        return "";
    }

    @Override
    protected String getTabTitle() {
        return "Settings";
    }

    @Override
    public void apply(CodeStyleSettings settings) {
        ScalaImportsStyleSettings mySettings = settings.getCustomSettings(ScalaImportsStyleSettings.class);
        mySettings.importStyle = importStyle.getText();
        mySettings.optimizeImports = optimizeImports.isSelected();
        mySettings.unicodeArrow = unicodeArrow.isSelected();
    }

    @Override
    public boolean isModified(CodeStyleSettings settings) {
        ScalaImportsStyleSettings mySettings = settings.getCustomSettings(ScalaImportsStyleSettings.class);
        return !importStyle.getText().equals(mySettings.importStyle) ||
                optimizeImports.isSelected() != mySettings.optimizeImports ||
                unicodeArrow.isSelected() != mySettings.unicodeArrow;
    }

    @Nullable
    @Override
    public JComponent getPanel() {
        return panel;
    }

    @Override
    protected void resetImpl(CodeStyleSettings settings) {
        ScalaImportsStyleSettings mySettings = settings.getCustomSettings(ScalaImportsStyleSettings.class);
        importStyle.setText(mySettings.importStyle);
        optimizeImports.setSelected(mySettings.optimizeImports);
        unicodeArrow.setSelected(mySettings.unicodeArrow);
    }
}
