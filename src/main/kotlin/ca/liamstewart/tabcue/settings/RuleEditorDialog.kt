package ca.liamstewart.tabcue.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColorPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.EmptyIcon
import ca.liamstewart.tabcue.model.ColorMath
import ca.liamstewart.tabcue.model.MatchField
import ca.liamstewart.tabcue.model.RuleMatcher
import ca.liamstewart.tabcue.model.StylePalette
import ca.liamstewart.tabcue.model.StyleRule
import ca.liamstewart.tabcue.model.normaliseEmoji
import ca.liamstewart.tabcue.model.TabStyle
import java.awt.Color
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList

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
     * uses. Only meaningful while "Custom…" is selected, so it appears only then.
     */
    private val customColor = ColorPanel().apply { isEnabled = false }
    private val textColorCombo = ComboBox(
        DefaultComboBoxModel(
            (
                listOf(Choice(null, "Default")) +
                    StylePalette.textColors.map { Choice(it.id, it.displayName) } +
                    Choice(CUSTOM_CHOICE_ID, "Custom…")
                )
                .toTypedArray()
        )
    )

    private val customTextColor = ColorPanel().apply { isEnabled = false }
    private val iconCombo = ComboBox(
        DefaultComboBoxModel(
            (listOf(Choice(null, "None")) + StylePalette.icons.map { Choice(it.id, it.displayName) })
                .toTypedArray()
        )
    )

    /** Held so the swatch buttons can be hidden while they have nothing to do. */
    private var customColorCell: Cell<ColorPanel>? = null
    private var customTextColorCell: Cell<ColorPanel>? = null

    private val emojiField = JBTextField(6)
    private val tintCheck = JBCheckBox("Tint the terminal background as well")
    private val enabledCheck = JBCheckBox("Rule enabled", true)

    init {
        title = if (initial == null) "Add Style Rule" else "Edit Style Rule"

        // Named colours mean nothing on their own, so every row carries what it stands for.
        colorCombo.renderer = ChoiceRenderer { id -> StylePalette.color(id)?.let(::swatch) }
        textColorCombo.renderer = ChoiceRenderer { id -> StylePalette.textColor(id)?.let(::swatch) }
        iconCombo.renderer = ChoiceRenderer(StylePalette::icon)

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
            if (ColorMath.isCustom(rule.style.textColorId)) {
                textColorCombo.selectItem(CUSTOM_CHOICE_ID)
                customTextColor.selectedColor = ColorMath.parseCustom(rule.style.textColorId)
            } else {
                textColorCombo.selectItem(rule.style.textColorId)
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

        textColorCombo.addItemListener { updateCustomTextColorEnabled() }
        updateCustomTextColorEnabled()
    }

    private fun updateCustomColorEnabled() {
        val custom = (colorCombo.selectedItem as? Choice)?.id == CUSTOM_CHOICE_ID
        customColor.isEnabled = custom
        // Hidden rather than greyed out. A disabled swatch still shows a colour, and one sitting
        // next to a combo that reads "Teal" is simply wrong.
        customColorCell?.visible(custom)
        // Seeded rather than left blank, so "Custom…" never means "no colour" by accident: an
        // unset ColorPanel would make the rule silently style nothing.
        if (custom && customColor.selectedColor == null) {
            customColor.selectedColor = StylePalette.colors.first().color
        }
    }

    private fun updateCustomTextColorEnabled() {
        val custom = (textColorCombo.selectedItem as? Choice)?.id == CUSTOM_CHOICE_ID
        customTextColor.isEnabled = custom
        customTextColorCell?.visible(custom)
        if (custom && customTextColor.selectedColor == null) {
            customTextColor.selectedColor = StylePalette.textColors.first().color
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
                    "are wildcards, so <code>ssh prod*</code> or <code>*/api/*</code> both work."
            )
        }
        row("Color:") {
            cell(colorCombo)
            customColorCell = cell(customColor)
        }
        row("Text color:") {
            cell(textColorCombo)
            customTextColorCell = cell(customTextColor)
            comment("The only cue that stays visible while the tab is selected.")
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
                "Choose a color, a text color, an icon or an emoji, otherwise this rule has " +
                    "no effect.",
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

    private fun selectedTextColorId(): String? {
        val choice = (textColorCombo.selectedItem as? Choice)?.id ?: return null
        if (choice != CUSTOM_CHOICE_ID) return choice
        return customTextColor.selectedColor?.let { ColorMath.toColorId(it) }
    }

    fun toRule(): StyleRule = StyleRule(
        field = fieldCombo.selectedItem as? MatchField ?: MatchField.TAB_TITLE,
        pattern = patternField.text?.trim().orEmpty(),
        style = TabStyle(
            colorId = selectedColorId(),
            iconId = (iconCombo.selectedItem as? Choice)?.id,
            tintBackground = tintCheck.isSelected,
            emoji = normaliseEmoji(emojiField.text),
            textColorId = selectedTextColorId(),
        ),
        enabled = enabledCheck.isSelected,
    )
}

/**
 * Shows what each row stands for rather than only its name.
 *
 * "Custom…" is left blank on purpose: it has no colour of its own until one is picked, and the
 * swatch button beside the combo is where that happens.
 */
private class ChoiceRenderer(private val iconFor: (String) -> Icon?) : SimpleListCellRenderer<Choice>() {
    override fun customize(
        list: JList<out Choice>,
        value: Choice?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        text = value?.label.orEmpty()
        // Blank rather than absent on the rows with nothing to show, so the names stay in a column.
        icon = value?.id?.takeUnless { it == CUSTOM_CHOICE_ID }?.let(iconFor) ?: EmptyIcon.create(SWATCH_SIZE)
    }
}

private fun swatch(color: Color): Icon = ColorIcon(SWATCH_SIZE, color, true)

/**
 * Sentinel for the "Custom…" combo entry. Not a palette id and not a `#` literal, so it can never
 * collide with a real [ca.liamstewart.tabcue.model.TabStyle.colorId].
 */
private const val CUSTOM_CHOICE_ID = "\u0000custom"

/** Matches the swatches in the tab's own Tab Style menu, so the two read as the same palette. */
internal const val SWATCH_SIZE = 14
