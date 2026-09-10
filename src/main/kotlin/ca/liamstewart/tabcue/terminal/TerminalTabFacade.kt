package ca.liamstewart.tabcue.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.project.Project
import com.intellij.terminal.TerminalTitle
import com.intellij.ui.content.Content
import ca.liamstewart.tabcue.util.guarded
import ca.liamstewart.tabcue.util.quietly
import java.awt.Component
import java.awt.Container
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * The single choke point for every call into the reworked ("Gen2") terminal API.
 *
 * Why this file is entirely reflective: `com.intellij.terminal.frontend.toolwindow.*` is annotated
 * `@ApiStatus.Experimental` *and* `@ApiStatus.NonExtendable` on 253, and has already drifted —
 * `TerminalToolWindowTabsManagerKt.findTabByContent(manager, content)` exists on 253 but was
 * removed in 262 in favour of the extension `Content.getTerminalTab()`. A static reference would
 * mean two problems: Plugin Verifier flags it (its scan is static, so `try`/`catch NoSuchMethodError`
 * does not help), and a future rename turns into a `NoClassDefFoundError` that breaks the whole
 * plugin rather than one feature.
 *
 * So: no compile-time dependency on the experimental API at all. Every lookup is cached, every
 * failure degrades to `null`/`false`, and the caller falls back to the engine-agnostic
 * `Content`-only path. [TerminalTitle] and [com.intellij.terminal.ui.TerminalWidget] are the
 * exception — those live in the platform, are unannotated, and are safe to bind directly.
 */
object TerminalTabFacade {

    private val LOG = logger<TerminalTabFacade>()

    private const val TABS_MANAGER = "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"
    private const val TABS_MANAGER_KT = "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManagerKt"
    private const val TABS_LISTENER = "com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener"
    private const val CLASSIC_MANAGER = "org.jetbrains.plugins.terminal.TerminalToolWindowManager"

    private val loader = TerminalTabFacade::class.java.classLoader

    private fun classOrNull(fqn: String): Class<*>? =
        quietly { Class.forName(fqn, false, loader) }

    private val tabsManagerClass by lazy { classOrNull(TABS_MANAGER) }
    private val tabsManagerKtClass by lazy { classOrNull(TABS_MANAGER_KT) }
    private val tabsListenerClass by lazy { classOrNull(TABS_LISTENER) }
    private val classicManagerClass by lazy { classOrNull(CLASSIC_MANAGER) }

    private fun gen2Manager(project: Project): Any? = quietly {
        val managerClass = tabsManagerClass ?: return@quietly null
        cachedMethod("getInstance") {
            managerClass.getMethod("getInstance", Project::class.java)
        }?.invoke(null, project)
    }

    /**
     * Subscribes to Gen2 tab creation. Returns false when the API is unavailable, in which case the
     * caller must rely on [com.intellij.ui.content.ContentManagerListener] alone.
     *
     * The listener interface is implemented with a [Proxy] rather than a Kotlin `object :` so that
     * nothing here references the experimental type at compile time.
     */
    fun installTabAddedListener(
        project: Project,
        parent: Disposable,
        onTabAdded: (Content) -> Unit,
    ): Boolean {
        val managerClass = tabsManagerClass ?: return false
        val listenerClass = tabsListenerClass ?: return false
        val manager = gen2Manager(project) ?: return false

        return guarded(LOG, "Reworked terminal tab listener unavailable") {
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "tabAdded" -> {
                        val tab = args?.firstOrNull()
                        contentOf(tab)?.let { content ->
                            guarded(LOG, "Failed to style a newly added terminal tab") { onTabAdded(content) }
                        }
                        null
                    }
                    // These must answer for the *proxy*, not for this object. Answering for the
                    // handler made `proxy.equals(proxy)` false and gave every proxy the same
                    // hashCode, so any equality-based removal — a HashSet, List.remove, a
                    // Disposer hook — silently failed to unregister the listener. That pins the
                    // plugin classloader and the IDE reports "plugin was not unloaded".
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "TabCue.TabsManagerListenerProxy"
                    // A type-appropriate default, not null: if this experimental interface ever
                    // gains a primitive-returning method, returning null would throw an NPE
                    // inside the terminal's own listener dispatch and could break tab creation
                    // rather than just disabling our feature.
                    else -> defaultValueFor(method.returnType)
                }
            }
            val proxy = Proxy.newProxyInstance(loader, arrayOf(listenerClass), handler)
            managerClass
                .getMethod("addListener", Disposable::class.java, listenerClass)
                .invoke(manager, parent, proxy)
            true
        } ?: false
    }

    private fun defaultValueFor(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    private fun contentOf(tab: Any?): Content? = quietly {
        val tabClass = tab?.javaClass ?: return@quietly null
        methodOn(tabClass, "getContent")?.invoke(tab) as? Content
    }

    /**
     * A [java.lang.reflect.Method] cache keyed by class and name.
     *
     * `Class.getMethod` copies the class's declared-method array on every call, and these are hit
     * several times per terminal title change — which for a shell reporting its title per prompt is
     * many times a minute.
     */
    private val instanceMethods =
        java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Method>>()

    private fun methodOn(owner: Class<*>, name: String): java.lang.reflect.Method? =
        instanceMethods.computeIfAbsent(owner.name + "#" + name) {
            java.util.Optional.ofNullable(quietly { owner.getMethod(name) })
        }.orElse(null)

    /**
     * Resolves the Gen2 `TerminalToolWindowTab` for a content, across the 253/262 API split.
     *
     * 262+ exposes it as an extension on `Content`; 253 as an extension on the manager. Both are
     * attempted, newest first, and the resolved [java.lang.reflect.Method] is cached because this
     * runs on every tab event.
     */
    private fun gen2Tab(project: Project, content: Content): Any? {
        val kt = tabsManagerKtClass ?: return null

        // 262+: fun Content.getTerminalTab(): TerminalToolWindowTab?
        val byContent = cachedMethod("getTerminalTab") {
            kt.getMethod("getTerminalTab", Content::class.java)
        }
        if (byContent != null) {
            return quietly { byContent.invoke(null, content) }
        }

        // 253: fun TerminalToolWindowTabsManager.findTabByContent(content: Content): ...?
        val managerClass = tabsManagerClass ?: return null
        val byManager = cachedMethod("findTabByContent") {
            kt.getMethod("findTabByContent", managerClass, Content::class.java)
        } ?: return null
        val manager = gen2Manager(project) ?: return null
        return quietly { byManager.invoke(null, manager, content) }
    }

    private val methodCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Method>>()

    private fun cachedMethod(key: String, lookup: () -> java.lang.reflect.Method): java.lang.reflect.Method? =
        methodCache.computeIfAbsent(key) {
            java.util.Optional.ofNullable(quietly(lookup))
        }.orElse(null)

    private fun gen2View(project: Project, content: Content): Any? = quietly {
        val tab = gen2Tab(project, content) ?: return@quietly null
        methodOn(tab.javaClass, "getView")?.invoke(tab)
    }

    /** The classic (JediTerm) engine's widget for a content, or null when Gen2 is in use. */
    private fun classicWidget(content: Content): com.intellij.terminal.ui.TerminalWidget? = quietly {
        classicManagerClass
            ?.getMethod("findWidgetByContent", Content::class.java)
            ?.invoke(null, content) as? com.intellij.terminal.ui.TerminalWidget
    }

    /**
     * Title and working directory together, so a caller needing both resolves the tab once.
     *
     * [title] is also the only correct channel for *naming* a tab: the terminal drives
     * `Content.displayName` from it (`TerminalTitleUtils.updateTabNameOnTitleChange`), so writing
     * the content's display name alone is overwritten the moment the shell emits an OSC 0/2 title.
     */
    data class TerminalTabInfo(val title: TerminalTitle?, val currentDirectory: String?)

    /**
     * Resolves the tab once and reads both values off it.
     *
     * Callers wanting title *and* directory previously took two independent paths through
     * [terminalTitle] and [currentDirectory], each re-resolving the Gen2 tab. That happens on every
     * terminal title change, which for a shell reporting its title per prompt is many times a
     * minute.
     */
    fun describe(project: Project, content: Content): TerminalTabInfo {
        gen2View(project, content)?.let { view ->
            val title = quietly { methodOn(view.javaClass, "getTitle")?.invoke(view) as? TerminalTitle }
            val cwd = quietly { methodOn(view.javaClass, "getCurrentDirectory")?.invoke(view) as? String }
            if (title != null || cwd != null) {
                return TerminalTabInfo(title, cwd?.takeIf { it.isNotBlank() })
            }
        }
        val widget = classicWidget(content)
        return TerminalTabInfo(
            title = widget?.terminalTitle,
            currentDirectory = quietly { widget?.getCurrentDirectory()?.takeIf { it.isNotBlank() } },
        )
    }

    /**
     * Every editor rendering terminal output inside this tab.
     *
     * The reworked terminal draws into `EditorImpl` instances, but `TerminalView` does not expose
     * them, so we walk the Swing tree instead of casting to the internal `TerminalViewImpl`.
     * Traversal is the more durable of the two options — it survives class renames — but it is
     * still unsupported, hence the tint being opt-in and best-effort. Must run on the EDT.
     *
     * A split tab yields more than one editor; all of them get tinted.
     */
    fun outputEditors(content: Content): List<EditorEx> {
        val root = quietly { content.component } ?: return emptyList()
        val found = mutableListOf<EditorEx>()
        collectEditors(root, found)
        return found
    }

    private fun collectEditors(component: Component, into: MutableList<EditorEx>) {
        if (component is EditorComponentImpl) {
            into += component.editor
            return
        }
        if (component is Container) {
            for (child in component.components) {
                collectEditors(child, into)
            }
        }
    }
}
