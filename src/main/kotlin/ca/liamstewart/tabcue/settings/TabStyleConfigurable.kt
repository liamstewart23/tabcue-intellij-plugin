package ca.liamstewart.tabcue.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import ca.liamstewart.tabcue.model.StylePalette
import ca.liamstewart.tabcue.model.StyleRule
import javax.swing.JComponent
import javax.swing.JSlider

/** Settings ▸ Tools ▸ TabCue. */
internal class TabStyleConfigurable(private val project: Project) : SearchableConfigurable {

    private val autoAssign = JBCheckBox("Give each terminal tab its own color automatically")
    private val showColorAsDot = JBCheckBox("Also show the tab color as a dot icon")
    private val allowTint = JBCheckBox("Allow tinting the terminal background (uses internal APIs)")
    private val tintStrengthValue = JBLabel()

    private val tintStrength = JSlider(TINT_STRENGTH_MIN, TINT_STRENGTH_MAX, TINT_STRENGTH_DEFAULT).apply {
        majorTickSpacing = 15
        paintTicks = true
        paintLabels = true
        // The tick labels give the scale; this gives the actual value, so dragging is not guesswork.
        addChangeListener { tintStrengthValue.text = "$value%" }
    }

    init {
        // Plain Swing rather than a DSL predicate: `selected` is defined on the DSL's `Cell`, not
        // on a checkbox held as a field, and a hand-rolled ComponentPredicate would be more
        // machinery than one listener.
        allowTint.addItemListener { syncTintControls() }
    }

    /** A live strength slider for a feature that is switched off is a dead control. */
    private fun syncTintControls() {
        tintStrength.isEnabled = allowTint.isSelected
        tintStrengthValue.isEnabled = allowTint.isSelected
    }

    private val tableModel = ListTableModel<StyleRule>(
        EnabledColumn, FieldColumn, PatternColumn, StyleColumn,
    )
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        emptyText.text = "No rules. Tabs are styled only by hand."
    }

    /**
     * A [SearchableConfigurable] with a stable id, so Settings search and Search Everywhere can
     * find this page and its checkbox labels. As a plain `Configurable` with searchable options
     * disabled, typing "terminal tab color" found nothing at all.
     */
    override fun getId(): String = ID

    override fun getDisplayName(): String = "TabCue"

    override fun createComponent(): JComponent {
        val rulesPanel = ToolbarDecorator.createDecorator(table)
            .setAddAction { editRule(null) }
            .setEditAction { editRule(table.selectedRow.takeIf { it >= 0 }) }
            .setRemoveAction {
                table.selectedRow.takeIf { it >= 0 }?.let { row ->
                    tableModel.removeRow(table.convertRowIndexToModel(row))
                }
            }
            .setMoveUpAction { moveSelected(-1) }
            .setMoveDownAction { moveSelected(1) }
            .createPanel()

        return panel {
            group("Tabs") {
                row {
                    cell(autoAssign)
                        .comment(
                            "The color comes from the tab's own name and directory, so a tab " +
                                "keeps the same one after a restart. Rules and colors you set by " +
                                "hand always win."
                        )
                }
                row {
                    cell(showColorAsDot)
                        .comment(
                            "Strongly recommended. The IDE's tab painter discards a custom tab " +
                                "color on the <b>selected</b> tab and paints its hover color " +
                                "over it on <b>hover</b>, so a color alone is invisible in those " +
                                "states. The icon is painted regardless, so a dot keeps the color " +
                                "readable at all times."
                        )
                }
            }
            // The strength control belongs *under* the switch that gates it, and follows its
            // state: a live slider for a feature that is switched off is a dead control.
            group("Terminal background") {
                row {
                    cell(allowTint)
                        .comment(
                            "A kill switch, not an opt-in. Turn tinting on per tab from the " +
                                "tab's <b>Tab Style</b> menu. Recoloring the output area relies on " +
                                "internal IDE structure, so clear this if an IDE update breaks it. " +
                                "Tab colors and icons are unaffected either way."
                        )
                }
                indent {
                    row("Tint strength:") {
                        cell(tintStrength)
                        cell(tintStrengthValue)
                    }
                    row("") {
                        comment(
                            "How far the terminal background is blended toward the tab color. " +
                                "Above roughly 70% the terminal's own colors stop being legible, " +
                                "so that is the cap."
                        )
                    }
                }
            }
            group("Rules") {
                row {
                    comment(
                        "The first matching rule wins. A style set by hand on a tab always " +
                            "overrides these."
                    )
                }
                row {
                    cell(rulesPanel).align(com.intellij.ui.dsl.builder.AlignX.FILL)
                }.resizableRow()
            }
        }
    }

    private fun editRule(modelRowOrNull: Int?) {
        val existing = modelRowOrNull?.let { tableModel.getItem(table.convertRowIndexToModel(it)) }
        val dialog = RuleEditorDialog(project, existing)
        if (!dialog.showAndGet()) return
        val rule = dialog.toRule()
        if (existing == null) {
            tableModel.addRow(rule)
        } else {
            tableModel.setItem(table.convertRowIndexToModel(modelRowOrNull), rule)
        }
    }

    private fun moveSelected(delta: Int) {
        val viewRow = table.selectedRow.takeIf { it >= 0 } ?: return
        val row = table.convertRowIndexToModel(viewRow)
        val target = row + delta
        if (target !in 0 until tableModel.rowCount) return
        val items = tableModel.items.toMutableList()
        val moved = items.removeAt(row)
        items.add(target, moved)
        tableModel.items = items
        table.setRowSelectionInterval(target, target)
    }

    override fun isModified(): Boolean {
        val settings = TabStyleSettings.getInstance(project)
        return autoAssign.isSelected != settings.autoAssignColors ||
            allowTint.isSelected != settings.allowBackgroundTint ||
            tintStrength.value != settings.tintStrength ||
            showColorAsDot.isSelected != settings.showColorAsDot ||
            tableModel.items != settings.rules()
    }

    override fun apply() {
        val settings = TabStyleSettings.getInstance(project)
        settings.autoAssignColors = autoAssign.isSelected
        settings.allowBackgroundTint = allowTint.isSelected
        settings.tintStrength = tintStrength.value
        settings.showColorAsDot = showColorAsDot.isSelected
        settings.retainUnknownRules()
        settings.setRules(tableModel.items.toList())
        // Push the new configuration onto every tab that is already open. The service subscribes
        // to this, so no direct restyle call is needed (it would just run the pass twice).
        settings.notifyChanged()
    }

    override fun reset() {
        val settings = TabStyleSettings.getInstance(project)
        autoAssign.isSelected = settings.autoAssignColors
        allowTint.isSelected = settings.allowBackgroundTint
        tintStrength.value = settings.tintStrength
        // Seeded explicitly: JSlider.setValue fires no change event when the value is unchanged, so
        // the readout would start blank whenever the stored strength equals the slider's initial one.
        tintStrengthValue.text = "${settings.tintStrength}%"
        syncTintControls()
        showColorAsDot.isSelected = settings.showColorAsDot
        tableModel.items = settings.rules().toMutableList()
    }
}

private object EnabledColumn : ColumnInfo<StyleRule, String>("Enabled") {
    override fun valueOf(item: StyleRule): String = if (item.enabled) "Yes" else "No"
    // Must be the widest value actually rendered, or the column clips.
    override fun getMaxStringValue(): String = "Enabled"
}

private object FieldColumn : ColumnInfo<StyleRule, String>("Match on") {
    override fun valueOf(item: StyleRule): String = item.field.displayName
}

private object PatternColumn : ColumnInfo<StyleRule, String>("Pattern") {
    override fun valueOf(item: StyleRule): String = item.pattern
}

private object StyleColumn : ColumnInfo<StyleRule, String>("Style") {
    override fun valueOf(item: StyleRule): String {
        val parts = buildList {
            item.style.emoji?.takeIf { it.isNotBlank() }?.let { add(it) }
            StylePalette.colorName(item.style.colorId)?.let { add(it) }
            // Labelled, so it cannot be mistaken for the tab colour beside it. Without this a
            // rule that only sets a text colour read as "None" while working perfectly well.
            StylePalette.textColorName(item.style.textColorId)?.let { add("$it text") }
            item.style.iconId?.let { id ->
                StylePalette.icons.firstOrNull { it.id == id }?.displayName?.let { add("$it icon") }
            }
            if (item.style.tintBackground) add("tinted")
        }
        return if (parts.isEmpty()) "None" else parts.joinToString(", ")
    }
}

private const val ID = "ca.liamstewart.tabcue.settings"
