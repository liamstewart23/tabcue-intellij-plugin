package ca.liamstewart.tabcue.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import ca.liamstewart.tabcue.model.EmojiIcon
import ca.liamstewart.tabcue.model.normaliseEmoji
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Asks for a custom emoji in a popup rather than a modal dialog.
 *
 * A modal `DialogWrapper` is far too heavy for typing one character: it dims the IDE and has to be
 * dismissed with a button. This is a small popup anchored where the click happened, committing on
 * Enter and dismissing on Escape or a click elsewhere. The common emoji are separate menu items,
 * so most of the time this is not needed at all.
 */
internal object EmojiPopup {

    /** Registered in the platform's own PlatformActions.xml; macOS only. */
    private const val EMOJI_ACTION_ID = "EmojiAndSymbols"

    fun show(event: AnActionEvent, initial: String?, onChosen: (String?) -> Unit) {
        val field = JBTextField(initial.orEmpty(), 10)
        val preview = JBLabel()

        fun refresh() {
            val text = field.text?.trim()
            preview.icon = if (text.isNullOrEmpty()) null else EmojiIcon(text, size = 18)
        }
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
        refresh()

        val row = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(6)
            add(field, BorderLayout.CENTER)
            add(preview, BorderLayout.EAST)
        }

        val emojiAction = ActionManager.getInstance().getAction(EMOJI_ACTION_ID)
        val content = JPanel(BorderLayout()).apply {
            add(row, BorderLayout.CENTER)
            if (emojiAction != null) {
                // Opens the system Emoji & Symbols palette, which inserts into the focused field.
                add(
                    JButton("System Picker…").apply {
                        addActionListener { _: ActionEvent ->
                            field.requestFocusInWindow()
                            val context = DataManager.getInstance().getDataContext(field)
                            ActionUtil.performAction(
                                emojiAction,
                                AnActionEvent.createEvent(
                                    emojiAction,
                                    context,
                                    emojiAction.templatePresentation.clone(),
                                    ActionPlaces.UNKNOWN,
                                    ActionUiKind.NONE,
                                    null,
                                ),
                            )
                        }
                    },
                    BorderLayout.SOUTH,
                )
            }
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, field)
            .setTitle("Tab Emoji")
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(false)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .createPopup()

        // A JTextField already fires an action on Enter, so no key binding is needed: Enter
        // commits, and Escape or a click elsewhere cancels via the popup itself.
        field.addActionListener {
            onChosen(normaliseEmoji(field.text))
            popup.closeOk(null)
        }

        // Anchored to a point captured *now*, while the menu's data context is still alive. Once
        // the action returns, the context belongs to a dismissed popup and `showInBestPositionFor`
        // has nothing useful left to aim at.
        val point = JBPopupFactory.getInstance().guessBestPopupLocation(event.dataContext)

        // Shown on the next EDT pass rather than immediately. This action runs while the context
        // menu is still dismissing, and the very mouse event that closes the menu is then
        // delivered outside the new popup — which, with setCancelOnClickOutside, closed it again
        // the instant it opened. The symptom is a popup that never appears at all.
        ApplicationManager.getApplication().invokeLater(
            { if (!popup.isDisposed) popup.show(point) },
            ModalityState.any(),
        )
    }
}
