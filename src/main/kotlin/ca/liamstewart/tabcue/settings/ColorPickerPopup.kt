package ca.liamstewart.tabcue.settings

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColorChooserService
import com.intellij.ui.JBColor
import com.intellij.ui.picker.ColorListener
import com.intellij.ui.picker.ColorPickerPopupCloseListener
import com.intellij.util.Alarm
import java.awt.Color

/**
 * The platform colour picker, opened as a popup anchored to the click.
 *
 * The platform's own rather than anything hand-built: it already has the hex field, RGB spinners
 * and screen eyedropper, and it matches every other colour control in the IDE. Plain public API,
 * so unlike `TerminalTabFacade` none of this needs reflection.
 *
 * Opacity is switched off, because alpha would be silently dropped: `JBDefaultTabPainter` blends
 * using the overlay's alpha and ignores ours, and the tint has its own strength slider.
 */
internal object ColorPickerPopup {

    /**
     * Commits are coalesced, and the final value is committed again when the popup closes.
     *
     * [ColorListener] fires on every mouse-move inside the saturation field, and each commit
     * restyles every known tab, resolving each one's identity through a reflective lookup. Per
     * event that put the whole fan-out on the EDT dozens of times a second while the user was
     * still dragging. Debouncing keeps the preview live for a fraction of the work, and the commit
     * on close catches a movement still pending when the popup is dismissed.
     *
     * There is no cancel: dismissing keeps the last colour, like the platform's own colour popups.
     */
    fun show(event: AnActionEvent, project: Project, current: Color?, onPicked: (Color) -> Unit) {
        // Same anchoring the emoji popup uses, via the factory rather than the mouse position, so
        // it also lands sensibly when the menu was opened from the keyboard.
        val point = JBPopupFactory.getInstance().guessBestPopupLocation(event.dataContext)

        // Parented to the project so an alarm cannot outlive it if the close callback never runs.
        val lifetime = Disposer.newDisposable("TabCue.colorPicker")
        Disposer.register(project, lifetime)
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, lifetime)
        var latest: Color? = null

        fun commitPending() {
            alarm.cancelAllRequests()
            latest?.let { onPicked(it) }
            latest = null
        }

        // Explicit objects rather than lambdas: a SAM-converted listener whose body called back
        // into a same-named helper has bitten this project twice with silent infinite recursion.
        val listener = object : ColorListener {
            override fun colorChanged(color: Color?, source: Any?) {
                if (color == null) return
                latest = color
                alarm.cancelAllRequests()
                alarm.addRequest({ commitPending() }, COMMIT_DELAY_MS)
            }
        }
        val closeListener = object : ColorPickerPopupCloseListener {
            override fun onPopupClosed() {
                commitPending()
                Disposer.dispose(lifetime)
            }
        }

        // showPopup, not showColorPickerPopup: the latter is deprecated with an explicit
        // ReplaceWith pointing here, because it does not work under remote development.
        ColorChooserService.getInstance().showPopup(
            project,
            current ?: JBColor.GRAY,
            listener,
            point,
            /* showAlpha = */ false,
            /* showAlphaAsPercent = */ false,
            closeListener,
        )
    }

    /** Long enough to collapse a drag into a few commits, short enough to still read as live. */
    private const val COMMIT_DELAY_MS = 80
}
