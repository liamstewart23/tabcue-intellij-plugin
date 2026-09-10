package ca.liamstewart.tabcue.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import ca.liamstewart.tabcue.model.ColorMath
import ca.liamstewart.tabcue.model.MatchField
import ca.liamstewart.tabcue.model.RuleMatcher
import ca.liamstewart.tabcue.model.StylePalette
import ca.liamstewart.tabcue.model.StyleRule
import ca.liamstewart.tabcue.model.normaliseEmoji
import ca.liamstewart.tabcue.model.TabStyle
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JLabel

/** A nullable id paired with a label, so "None" can sit in a combo box alongside real choices. */
private class Choice(val id: String?, val label: String) {
    override fun toString(): String = label
}

internal class RuleEditorDialog(
    project: Project?,
    private val initial: StyleRule?,
) : DialogWrapper(project, false) {

    private val fieldCombo = ComboBox(DefaultComboBoxModel(MatchField.entries.toTypedArray()))
    private val fieldHint = JLabel()
    private val patternField = JBTextField(30)
    private val colorCombo = ComboBox(
        DefaultComboBoxModel(
            (
                listOf(Choice(null, "None")) +
                    StylePalette.colors.map { Choice(it.id, it.displayName) } +
                    Choice(CUSTOM_CHOICE_ID, "Custom…")
                )
                .toTypedArray()
        )
    )

    /**
     * The platform's own swatch button: clicking it opens the same colour chooser the tab menu
     * uses. Only meaningful while "Custom…" is selected, so it follows the combo's enablement.
     */
    private val customColor = ColorPanel().apply { isEnabled = false }
    private val iconCombo = ComboBox(
        DefaultComboBoxModel(
            (listOf(Choice(null, "None")) + StylePalette.icons.map { Choice(it.id, it.displayName) })
                .toTypedArray()
        )
    )
    private val emojiField = JBTextField(6)
    private val tintCheck = JBCheckBox("Tint the terminal background as well")
    private val enabledCheck = JBCheckBox("Rule enabled", true)

    init {
        title = if (initial == null) "Add Style Rule" else "Edit Style Rule"
        init()

        initial?.let { rule ->
            fieldCombo.selectedItem = rule.field
            patternField.text = rule.pattern
            if (ColorMath.isCustom(rule.style.colorId)) {
                colorCombo.selectItem(CUSTOM_CHOICE_ID)
                customColor.selectedColor = ColorMath.parseCustom(rule.style.colorId)
            } else {
                colorCombo.selectItem(rule.style.colorId)
            }
            iconCombo.selectItem(rule.style.iconId)
            emojiField.text = rule.style.emoji.orEmpty()
            tintCheck.isSelected = rule.style.tintBackground
            enabledCheck.isSelected = rule.enabled
        }

        // The hint has to follow the selection. Reading it once while building the panel would pin
        // it to whichever field happened to be selected first and then never update.
        fieldCombo.addItemListener { updateFieldHint() }
        updateFieldHint()

        colorCombo.addItemListener { updateCustomColorEnabled() }
        updateCustomColorEnabled()
    }

    private fun updateCustomColorEnabled() {
        val custom = (colorCombo.selectedItem as? Choice)?.id == CUSTOM_CHOICE_ID
        customColor.isEnabled = custom
        // Seeded rather than left blank, so "Custom…" never means "no colour" by accident: an
        // unset ColorPanel would make the rule silently style nothing.
        if (custom && customColor.selectedColor == null) {
            customColor.selectedColor = StylePalette.colors.first().color
        }
    }

    private fun updateFieldHint() {
        fieldHint.text = (fieldCombo.selectedItem as? MatchField)?.hint.orEmpty()
    }

    private fun ComboBox<Choice>.selectItem(id: String?) {
        for (i in 0 until itemCount) {
            if (getItemAt(i).id == id) {
                selectedIndex = i
                return
            }
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Match on:") { cell(fieldCombo) }
        row("") { cell(fieldHint) }
        row("Pattern:") { cell(patternField) }
        row("") {
            comment(
                "Case-insensitive, matched as a substring. <code>*</code> and <code>?</code> " +
                    "are wildcards &mdash; e.g. <code>ssh prod*</code> or <code>*/api/*</code>."
            )
        }
        row("Color:") {
            cell(colorCombo)
            cell(customColor)
        }
        row("Icon:") { cell(iconCombo) }
        row("Emoji:") {
            cell(emojiField)
            comment("Takes precedence over the icon. ⌃⌘Space opens the system palette.")
        }
        row("") { cell(tintCheck) }
        row("") { cell(enabledCheck) }
    }

    override fun doValidate(): ValidationInfo? {
        val pattern = patternField.text?.trim().orEmpty()
        if (pattern.isEmpty()) {
            return ValidationInfo("Enter a pattern to match.", patternField)
        }
        if (pattern.length > RuleMatcher.MAX_PATTERN_LENGTH) {
            // An absurdly long pattern is far more likely a paste accident than intent.
            return ValidationInfo(
                "Keep the pattern under ${RuleMatcher.MAX_PATTERN_LENGTH} characters.",
                patternField,
            )
        }
        if (toRule().style.isEmpty) {
            // Otherwise the rule silently matches and then does nothing, which reads as a bug.
            return ValidationInfo(
                "Choose a color, an icon or an emoji, otherwise this rule has no effect.",
                colorCombo,
            )
        }
        return null
    }

    override fun getPreferredFocusedComponent(): JComponent = patternField

    /**
     * "Custom…" resolves to a `#RRGGBB` literal; every other entry is a palette id or null. A
     * custom selection with no colour chosen resolves to null, so `doValidate` catches it as an
     * empty style rather than persisting an unresolvable id.
     */
    private fun selectedColorId(): String? {
        val choice = (colorCombo.selectedItem as? Choice)?.id ?: return null
        if (choice != CUSTOM_CHOICE_ID) return choice
        return customColor.selectedColor?.let { ColorMath.toColorId(it) }
    }

    fun toRule(): StyleRule = StyleRule(
        field = fieldCombo.selectedItem as? MatchField ?: MatchField.TAB_TITLE,
        pattern = patternField.text?.trim().orEmpty(),
        style = TabStyle(
            colorId = selectedColorId(),
            iconId = (iconCombo.selectedItem as? Choice)?.id,
            tintBackground = tintCheck.isSelected,
            emoji = normaliseEmoji(emojiField.text),
        ),
        enabled = enabledCheck.isSelected,
    )
}

/**
 * Sentinel for the "Custom…" combo entry. Not a palette id and not a `#` literal, so it can never
 * collide with a real [ca.liamstewart.tabcue.model.TabStyle.colorId].
 */
private const val CUSTOM_CHOICE_ID = "\u0000custom"
