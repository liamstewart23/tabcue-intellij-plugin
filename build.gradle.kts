import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "ca.liamstewart"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compiled against the 253 baseline, which is also the `since-build` floor. Targeting the
        // newer 262 would make the platform plugin infer a JVM 25 bytecode target, which the
        // Kotlin compiler cannot emit. verifyPlugin below checks both branches instead.
        phpstorm("2025.3.6.1")

        // Supplies both org.jetbrains.plugins.terminal.* and the reworked
        // com.intellij.terminal.frontend.* API (they ship in the same terminal.jar on 253).
        bundledPlugin("org.jetbrains.plugins.terminal")

        pluginVerifier()

        // Lets the applier be tested against real ContentImpl instances, headlessly.
        testFramework(TestFrameworkType.Platform)
    }
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib")

    // The rule matcher is pure Kotlin, so it is testable without booting an IDE.
    testImplementation("junit:junit:4.13.2")
}

// Java 21 is set explicitly rather than via `jvmToolchain(21)`. The only JDK on this machine is
// PhpStorm's bundled JBR, which `org.gradle.java.home` already selects as the build JVM; asking
// for a *toolchain* additionally requires Gradle's auto-detection to find a JDK 21 on disk, and it
// does not look inside .app bundles — so a fresh daemon failed to configure at all.
kotlin {
    // Provisioned by Gradle rather than taken from whatever JDK happens to be installed, so an
    // IDE runtime upgrade cannot change what this build produces.
    jvmToolchain(21)
    compilerOptions {
        // Pinned to what branch 253 bundles. Left unpinned, the compiler can emit metadata newer
        // than the IDE's runtime stdlib understands, while `untilBuild = null` claims we work on
        // every future build.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            // No upper bound: the plugin degrades gracefully rather than hard-failing when
            // the experimental terminal API drifts (see TerminalTabFacade).
            untilBuild = provider { null }
        }
        // Taken from CHANGELOG.md so the release notes and the Marketplace entry cannot disagree.
        changeNotes = provider { changeNotesForCurrentVersion() }
    }

    pluginVerification {
        ides {
            // Both ends of the supported range. 262 moved the reworked-terminal API into a
            // content-module jar and replaced findTabByContent with Content.getTerminalTab, so
            // verifying only one branch would miss exactly the drift this plugin is built for.
            create(IntelliJPlatformType.PhpStorm, "2025.3.6.1")
            create(IntelliJPlatformType.PhpStorm, "2026.2.2")

            // Nothing here is PhpStorm-specific: the plugin uses only the platform and the
            // bundled terminal plugin, so it should run on every IntelliJ-based IDE. IDEA is the
            // reference platform build, and WebStorm and PyCharm cover other products so a
            // product-specific regression cannot pass unnoticed.
            // (IntellijIdeaCommunity is not resolvable from 2025.3 on: the Community and Ultimate
            // distributions were unified into `IntellijIdea`.)
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
            create(IntelliJPlatformType.WebStorm, "2025.3")
            create(IntelliJPlatformType.PyCharm, "2025.3")
        }
        // The plugin's own defaults do NOT fail on internal/override-only/non-extendable usages,
        // but the Marketplace validator rejects on all three. Without these, local verification
        // can pass and the upload still be refused.
        //
        // EXPERIMENTAL_API_USAGES is deliberately absent: it is non-blocking for the Marketplace,
        // and the two guarded usages here are a considered trade-off, not an oversight.
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
            FailureLevel.NON_EXTENDABLE_API_USAGES,
            FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            FailureLevel.INVALID_PLUGIN,
        )
    }

    // Marketplace uploads must be signed. Values come from the environment so no key material is
    // ever committed; see RELEASING.md for how to generate them.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A pre-release version publishes to a side channel users must opt into, so a 0.x build
        // cannot land on everyone by accident.
        channels = provider {
            listOf(if (version.toString().contains('-')) "eap" else "default")
        }
    }
}

/**
 * The body of the top-most released section of CHANGELOG.md, converted to the HTML the plugin
 * descriptor expects.
 */
fun changeNotesForCurrentVersion(): String {
    val changelog = file("CHANGELOG.md")
    if (!changelog.exists()) return ""
    val lines = changelog.readLines()
    val start = lines.indexOfFirst { it.startsWith("## [") && !it.startsWith("## [Unreleased") }
    if (start == -1) return ""
    val body = lines.drop(start + 1).takeWhile { !it.startsWith("## [") }

    // Items are buffered because a changelog entry wraps across lines: emitting </li> eagerly put
    // every continuation line outside its own list item.
    val html = StringBuilder()
    var inList = false
    var item: StringBuilder? = null

    fun flushItem() {
        item?.let { html.append("<li>").append(inlineMarkdownToHtml(it.toString())).append("</li>") }
        item = null
    }

    fun closeList() {
        flushItem()
        if (inList) {
            html.append("</ul>")
            inList = false
        }
    }

    body.forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("### ") -> {
                closeList()
                html.append("<h4>").append(line.removePrefix("### ")).append("</h4>")
            }
            line.startsWith("- ") -> {
                flushItem()
                if (!inList) {
                    html.append("<ul>")
                    inList = true
                }
                item = StringBuilder(line.removePrefix("- "))
            }
            line.isEmpty() -> flushItem()
            item != null -> item!!.append(' ').append(line)
        }
    }
    closeList()
    return html.toString()
}

fun inlineMarkdownToHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace(Regex("`([^`]+)`"), "<code>$1</code>")
    .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
    .replace(Regex("\\*([^*]+)\\*"), "<i>$1</i>")
    .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")

tasks {
    // Indexes the settings page so its labels are findable from Settings search and Search
    // Everywhere. Costs a headless IDE run at build time, which is worth it for discoverability.
    buildSearchableOptions {
        enabled = true
    }
}
