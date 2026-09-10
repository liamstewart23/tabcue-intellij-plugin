package ca.liamstewart.tabcue.terminal

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Installs the tab watchers once per project.
 *
 * A `postStartupActivity` rather than the `toolWindowInitializer` extension point: the latter looks
 * like the natural seam but `TerminalToolWindowInitializer` is `@ApiStatus.Internal`, and its only
 * registered implementation is the terminal's own bootstrap.
 */
internal class TabStyleStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            TabStyleService.getInstance(project).install()
        }
    }
}
