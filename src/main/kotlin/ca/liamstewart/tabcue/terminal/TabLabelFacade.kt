package ca.liamstewart.tabcue.terminal

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.JBColor
import com.intellij.ui.content.Content
import ca.liamstewart.tabcue.util.quietly
import java.awt.Color
import java.awt.Component
import java.awt.Container
import javax.swing.SwingUtilities

/**
 * Sets the *text* colour of a single tool-window tab label.
 *
 * `Content` has no foreground API — that is why the tab fill is derived rather than used raw, so
 * the theme's own label colour always stays legible. This is the deliberate escape hatch from that
 * design, for the one case derivation cannot reach: the platform discards a tab's colour entirely
 * while that tab is selected, so the label is the only thing left to colour.
 *
 * ## Why this works, and why it is reflective
 *
 * The label is `com.intellij.openapi.wm.impl.content.ContentTabLabel`, in an `impl` package. Its
 * base class `BaseLabel` declares public `setActiveFg`/`setPassiveFg` and, crucially,
 * `paintComponent` reads the *fields* those setters write rather than the `getActiveFg`/
 * `getPassiveFg` getters — which matters, because `ContentTabLabel` overrides both getters to
 * return theme colours and ignore the fields. Painting therefore honours what we set; the getters
 * would not have. Verified identical in the 253 and 262 bytecode.
 *
 * Reflection rather than a direct reference for the same reason as [TerminalTabFacade]: an `impl`
 * class is not API, the Plugin Verifier flags a static reference to one, and a rename should cost
 * this one feature rather than loading the plugin at all.
 *
 * ## What resets it
 *
 * `BaseLabel.updateUI()` — a look-and-feel change — reassigns both fields to `JBColor.foreground()`.
 * Nothing else in the platform writes them. [TabStyleService] already invalidates and restyles
 * every tab on a theme change, which re-applies this along with everything else.
 */
internal object TabLabelFacade {

    private val LOG = logger<TabLabelFacade>()

    private const val TAB_LABEL_CLASS = "com.intellij.openapi.wm.impl.content.ContentTabLabel"

    /**
     * Bounds the search. The decorator holding one tool window is a few hundred components at
     * most; without a cap, a fallback to the whole IDE frame on some future layout could walk
     * tens of thousands on every restyle.
     */
    private const val MAX_VISITED = 4000

    private val loader = TabLabelFacade::class.java.classLoader

    private val tabLabelClass: Class<*>? by lazy {
        quietly { Class.forName(TAB_LABEL_CLASS, false, loader) }
    }

    private val getContentMethod by lazy {
        quietly { tabLabelClass?.getMethod("getContent") }
    }

    private val setActiveFgMethod by lazy {
        quietly { tabLabelClass?.getMethod("setActiveFg", Color::class.java) }
    }

    private val setPassiveFgMethod by lazy {
        quietly { tabLabelClass?.getMethod("setPassiveFg", Color::class.java) }
    }

    /** True once we have established the label class is not where we expect it. */
    private val unavailable: Boolean
        get() = tabLabelClass == null ||
            getContentMethod == null ||
            setActiveFgMethod == null ||
            setPassiveFgMethod == null

    /**
     * Paints [content]'s tab label in [color], or restores the theme default when it is null.
     *
     * Returns false when the label could not be found, which is the normal case for a tab whose
     * tool window has never been shown — the label does not exist until then. The caller treats
     * that as "not settled yet" and retries, rather than caching a miss.
     */
    fun applyTextColor(content: Content, color: Color?): Boolean {
        if (unavailable) return false

        val label = findLabelFor(content) ?: return false
        // JBColor.foreground() is exactly what BaseLabel.updateUI() assigns, so clearing restores
        // the platform's own value rather than an approximation of it.
        val target: Color = color ?: JBColor.foreground()
        return quietly {
            setActiveFgMethod?.invoke(label, target)
            setPassiveFgMethod?.invoke(label, target)
            // The fields are only read while painting, and nothing above fires an event.
            label.repaint()
            true
        } ?: false
    }

    private fun findLabelFor(content: Content): Component? {
        val root = searchRootFor(content) ?: return null
        val labelClass = tabLabelClass ?: return null
        val getContent = getContentMethod ?: return null

        var visited = 0
        fun search(component: Component): Component? {
            if (visited++ > MAX_VISITED) return null
            if (labelClass.isInstance(component)) {
                val owner = quietly { getContent.invoke(component) }
                if (owner === content) return component
            }
            if (component is Container) {
                for (child in component.components) {
                    search(child)?.let { return it }
                }
            }
            return null
        }
        return search(root)
    }

    /**
     * The smallest container that holds both the tab strip and the content panel.
     *
     * The labels are siblings of the content, not children of it, so the search cannot start at
     * `content.component`. Walking up to the tool window's `InternalDecorator` keeps the sweep to
     * one tool window; if that class is ever renamed the window ancestor still finds the label,
     * just over a wider tree, which is what [MAX_VISITED] is there for.
     */
    private fun searchRootFor(content: Content): Component? {
        val start = quietly { content.component } ?: return null
        var current: Component? = start
        while (current != null) {
            if (current.javaClass.name.endsWith("InternalDecorator")) return current
            current = current.parent
        }
        return quietly { SwingUtilities.getWindowAncestor(start) }
            ?.also { LOG.debug("No InternalDecorator above the tab; searching the window instead") }
    }
}
