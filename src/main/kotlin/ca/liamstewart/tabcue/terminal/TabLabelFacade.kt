package ca.liamstewart.tabcue.terminal

import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.content.Content
import ca.liamstewart.tabcue.util.quietly
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.lang.ref.WeakReference
import javax.swing.SwingUtilities

/**
 * Sets the text colour of a single tool-window tab label.
 *
 * `Content` has no foreground API, so this reaches the label itself.
 * `BaseLabel.setActiveFg`/`setPassiveFg` are public, and `paintComponent` reads the fields they
 * write rather than the `getActiveFg`/`getPassiveFg` getters that `ContentTabLabel` overrides with
 * theme colours, so painting honours what we set. Same in the 253 and 262 bytecode.
 *
 * Reflective because the class is in an `impl` package: the Plugin Verifier flags a static
 * reference to one, and a rename should cost this feature rather than the whole plugin.
 */
internal object TabLabelFacade {

    private const val TAB_LABEL_CLASS = "com.intellij.openapi.wm.impl.content.ContentTabLabel"

    /** Bounds the search in case the fallback below ever has to walk a whole IDE frame. */
    private const val MAX_VISITED = 4000

    private val LABEL = Key.create<WeakReference<Component>>("TabCue.tabLabel")

    /**
     * Failed searches, so a tab that has no label of this type stops being searched for.
     *
     * A collapsed tool window renders a `ContentComboLabel` instead, and nothing here will ever
     * match it. Without a cap that tab would walk the decorator's whole component tree on every
     * shell prompt. Cleared by [forget] whenever something invalidates the applied style.
     */
    private val MISSES = Key.create<Int>("TabCue.labelMisses")

    private const val MAX_MISSES = 8

    private val loader = TabLabelFacade::class.java.classLoader

    private val labelClass: Class<*>? by lazy {
        quietly { Class.forName(TAB_LABEL_CLASS, false, loader) }
    }

    private val getContent by lazy { quietly { labelClass?.getMethod("getContent") } }
    private val setActiveFg by lazy { quietly { labelClass?.getMethod("setActiveFg", Color::class.java) } }
    private val setPassiveFg by lazy { quietly { labelClass?.getMethod("setPassiveFg", Color::class.java) } }

    private val unavailable: Boolean
        get() = labelClass == null || getContent == null || setActiveFg == null || setPassiveFg == null

    /**
     * Paints [content]'s tab label in [color], or hands it back to the theme when null.
     *
     * Returns false when the label cannot be found, which is normal for a tab whose tool window
     * has never been shown. The caller retries rather than caching that.
     */
    fun applyTextColor(content: Content, color: Color?): Boolean {
        if (unavailable) return false

        val misses = content.getUserData(MISSES) ?: 0
        // Reported as settled, not failed: the caller should stop asking rather than keep retrying.
        if (misses >= MAX_MISSES) return true

        val label = labelFor(content)
        if (label == null) {
            content.putUserData(MISSES, misses + 1)
            return false
        }
        content.putUserData(MISSES, null)

        // JBColor.foreground() is what BaseLabel.updateUI assigns, so clearing restores the
        // platform's own value rather than an approximation of it.
        val target: Color = color ?: JBColor.foreground()

        // paintComponent copies whichever field applies into the JLabel foreground, which is the
        // only way to see whether our value is still in place. Skipping the write when it is keeps
        // this callable on every restyle without repainting the tab strip each time.
        if (label.foreground == target) return true

        return quietly {
            setActiveFg?.invoke(label, target)
            setPassiveFg?.invoke(label, target)
            label.repaint()
            true
        } ?: false
    }

    /** Drops what we remember about a tab, so the next call searches again. */
    fun forget(content: Content) {
        content.putUserData(LABEL, null)
        content.putUserData(MISSES, null)
    }

    private fun labelFor(content: Content): Component? =
        cached(content) ?: search(content)?.also { content.putUserData(LABEL, WeakReference(it)) }

    private fun cached(content: Content): Component? {
        val label = content.getUserData(LABEL)?.get() ?: return null
        // Labels are recycled, so a stale reference could otherwise recolour somebody else's tab.
        val ours = quietly { getContent?.invoke(label) } === content
        return label.takeIf { ours && it.isDisplayable }
    }

    private fun search(content: Content): Component? {
        val root = searchRoot(content) ?: return null
        val labelClass = labelClass ?: return null
        var visited = 0

        fun find(component: Component): Component? {
            if (visited++ > MAX_VISITED) return null
            if (labelClass.isInstance(component) && quietly { getContent?.invoke(component) } === content) {
                return component
            }
            if (component is Container) {
                for (child in component.components) find(child)?.let { return it }
            }
            return null
        }
        return find(root)
    }

    /**
     * The labels are siblings of the tab content rather than children, so the search cannot start
     * at `content.component`. The tool window's decorator is the smallest container holding both.
     */
    private fun searchRoot(content: Content): Component? {
        val start = quietly { content.component } ?: return null
        var current: Component? = start
        while (current != null) {
            if (current.javaClass.name.endsWith("InternalDecorator")) return current
            current = current.parent
        }
        return quietly { SwingUtilities.getWindowAncestor(start) }
    }
}
