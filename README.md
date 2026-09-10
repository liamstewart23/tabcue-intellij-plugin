# TabCue: Visual Terminal Tabs

Style terminal tabs with custom colours, background hints, and emojis so you can tell terminals and
AI agents apart at a glance.

It styles each Terminal tool-window tab independently, so a wall of open terminals stays readable.

- **Per-tab icons** — a built-in icon, or any **emoji** (with the system picker) → *Tab Style*.
- **Per-tab colours**, shown both as the tab background and as a colour dot — eight presets plus
  *Custom…*, which opens the IDE's colour picker (hex, RGB, eyedropper) as a popup.
- **Rules** that style tabs automatically by title or working directory. The motivating cases: give
  each AI agent session its own emoji, and paint anything matching `ssh prod` red so you always
  know which box you are on.
- **Optional background tint** of the output area itself, not just the tab.

## Supported IDEs

Every IntelliJ-based IDE that bundles the Terminal — IntelliJ IDEA, PhpStorm, WebStorm, PyCharm,
RubyMine, GoLand, CLion, RustRover, DataGrip, DataSpell, Rider, Aqua — on platform branch `253`
(2025.3) or newer, with no upper bound.

There is nothing product-specific in the plugin: it declares only `com.intellij.modules.platform`
and the bundled `org.jetbrains.plugins.terminal`, and imports nothing outside `com.intellij.*`. The
`phpstorm(...)` line in `build.gradle.kts` is the *compile-time* SDK only and does not narrow where
the plugin runs; `verifyPlugin` therefore checks IDEA, WebStorm and PyCharm alongside both PhpStorm
branches.

Not covered: Android Studio, which uses its own build numbering (`AI-…`) and trails the platform,
so `since-build 253` will not necessarily match a shipping release.

Built against PhpStorm 2025.3 (platform branch `253`), Kotlin, JDK 21.

## Building

There is no system JDK on this machine, so the build points Gradle at PhpStorm's bundled
JetBrains Runtime (a full JDK 21) via `org.gradle.java.home` in `gradle.properties`.

The `gradlew` launcher needs `JAVA_HOME` set *before* it can read `org.gradle.java.home`, so use
the `build.sh` wrapper (which defaults it to the bundled JBR) or `source .env.build` first:

```bash
./build.sh buildPlugin     # → build/distributions/tabcue-1.0.0.zip
./build.sh runIde          # launches a sandbox PhpStorm with the plugin loaded
./build.sh verifyPlugin    # Plugin Verifier — see "API stability" below
./build.sh test            # unit tests for the rule matcher
```

Or, to use `./gradlew` directly:

```bash
export JAVA_HOME=/Applications/PhpStorm.app/Contents/jbr/Contents/Home
./gradlew buildPlugin
```

Install a built ZIP through *Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…*.

Note that PhpStorm itself ships neither the Gradle nor the Plugin DevKit plugin, so this project
cannot be developed from inside PhpStorm. Use the Gradle CLI above, or install IntelliJ IDEA for a
proper debugger and `plugin.xml` completion.

## How it works

Per-tab colouring uses *supported* platform API rather than a hack.
`com.intellij.ui.content.Content` has had `setTabColor(Color)` for years, and the tool-window tab
strip does honour it:

1. `ContentImpl.setTabColor` stores the colour and fires `PROP_TAB_COLOR`.
2. `ToolWindowContentUi`'s property listener turns that into a relayout.
3. `BaseLabel` (the base class of `ContentTabLabel`) caches `content.getTabColor()`.
4. `TabContentLayout` passes it to `JBTabPainter.paintTab(...)`, and `ToolWindowTabPainter` hands it
   to `JBDefaultTabPainter` as the tab background.

`TerminalToolWindowTab.getContent()` bridges the terminal to that API, so nothing needs to traverse
Swing or subclass anything to colour a tab.

**But the colour only survives on an unselected, non-hovered tab**, which is why the icon — not the
colour — is the primary cue. `JBDefaultTabPainter.getCustomBackground(color, selected, active,
hovered)` does this:

- *selected* → returns `theme.underlinedTabBackground`, **discarding the custom colour entirely**;
- *hovered* → alpha-blends `theme.hoverBackground` over it, which erases it when that colour is opaque;
- *unselected, not hovered* → `ColorUtil.alphaBlending(theme.inactiveColoredTabBackground, ours)`.

There is no per-tab hook into any of that. `BaseLabel`, by contrast, paints the icon regardless of
tab-background state, so an icon (a chosen one, an emoji, or a dot in the tab's colour) is the only
cue that holds in every state. Hence *"Also show the tab colour as a dot icon"*, on by default.

**That last blend is not a pass-through, which is worth spelling out because it is easy to get
wrong.** `alphaBlending(a, b)` composites `a` *over* `b` using **a's** alpha, so the theme paints an
overlay on top of the colour. On branch 253 `inactiveColoredTabBackground` defaults to black at 7%
for light themes and `#3C3F41` at **60%** for dark ones — a dark theme therefore keeps only 40% of
whatever colour is set, which is why an uncompensated tab colour looks washed out.

`ColorMath` deals with both halves of that:

- The value handed to `setTabColor` is **pre-compensated** for the overlay, by inverting the blend
  per channel and clamping to the gamut, so the *rendered* fill lands where intended whatever
  overlay the current theme defines. Above 85% overlay opacity it gives up and passes the target
  through, since every channel would clip.
- The fill is **derived** from the accent, not equal to it: the tab-strip background mixed half-way
  toward the accent. Because it stays close to the surrounding chrome, the label — whose foreground
  `Content` exposes no API to set — keeps its contrast for *any* accent, which is what makes a free
  colour picker safe. The full-strength accent goes to the dot and the tint instead, neither of
  which sits behind text.
- `tabFill` returns a **lazy** `JBColor`. Both inputs are theme-dependent, so a computed `Color`
  would freeze the fill at the theme in force when the tab was styled; resolving through a supplier
  keeps the automatic theme adaptation that a palette `JBColor` used to give for free.

### Layout

| Path | Role |
| --- | --- |
| `model/` | `TabStyle`, `StylePalette`, `ColorMath` (the fill derivation), `DotIcon`/`EmojiIcon`, and `StyleRule` + `RuleMatcher` (pure, unit-tested) |
| `settings/` | `PersistentStateComponent` (`.idea/terminalTabStyle.xml`), the Configurable, the rule dialog |
| `terminal/TerminalTabFacade.kt` | **Every** call into the experimental terminal API, isolated |
| `terminal/TabStyleApplier.kt` | Writes a style onto a `Content` |
| `terminal/TabStyleService.kt` | Watches tabs, resolves which style each should get |
| `actions/` | The *Tab Style* submenu on `ToolWindowContextMenu` |

### Cost per event

`TabStyleService` re-resolves a tab's style on **every terminal title change**, which for a shell
that reports its title per prompt is many times a minute. `TabStyleApplier` therefore compares the
full render input — style, dot setting, tint setting — against what it last applied and returns
early when nothing changed. Without that guard each title change would walk the tab's whole Swing
tree hunting for the output editor and re-fire property changes that trigger a relayout. A test
asserts an unchanged re-apply fires no property-change events at all.

Auto-assigned colours are pinned per tab in user data for the same reason: recomputing "first
colour no other tab is using" on every title change made a tab's colour drift as its neighbours
came and went.

### Two listener tiers

New tabs are detected twice over, on purpose:

- `TerminalTabsManagerListener.tabAdded` — the reworked ("Gen2") terminal's own event, which fires
  with the tab fully constructed. Comparable plugins instead poll with a `Timer(500)` and match tabs
  by display name; both are races this avoids.
- `ContentManagerListener.contentAdded` on the Terminal tool window — engine-agnostic. Covers the
  classic JediTerm engine, tabs restored when a project reopens, and the case where the experimental
  Gen2 event stops firing after an API change.

Contents are marked with user data so a tab seen by both tiers is only wired up once.

### API stability

`com.intellij.terminal.frontend.toolwindow.*` is annotated `@ApiStatus.Experimental` *and*
`@ApiStatus.NonExtendable`, and it has already drifted: `findTabByContent(manager, content)` exists
on 253 but was removed in 262 in favour of the extension `Content.getTerminalTab()`.

So `TerminalTabFacade` reaches it **entirely by reflection**, including a `java.lang.reflect.Proxy`
to implement the listener interface. This buys two things a `try`/`catch` cannot: Plugin Verifier
stays quiet (its scan is static, so it would flag a direct reference regardless of any catch block),
and a future rename degrades one feature to a no-op instead of failing the whole plugin with a
`NoClassDefFoundError`. Both the 253 and 262 lookups are attempted, newest first, and cached.

`Content`, `TerminalTitle` and `com.intellij.terminal.ui.TerminalWidget` are unannotated platform
API and are bound directly.

## Known limitations

These are properties of the platform, not things left unfinished:

- **A lone tab is fine, despite appearances.** `TabContentLayout.isToDrawTabs()` does hide the tab
  strip when only one tab is open *and* `content.getToolwindowTitle()` is blank — but `ContentImpl`
  falls back to the display name, which the terminal always sets, so that branch cannot trigger.
  An earlier version of this plugin forced a `toolwindowTitle` to "fix" this; it was dead code
  (and stopped the tool window header tracking renames), so it was removed. A unit test pins the
  fallback so the assumption is not quietly re-broken.
- **Tab colour is invisible on the selected tab and while hovering.** See above — the platform's
  painter replaces or overpaints it, with no per-tab override. The colour dot and the background
  tint are the two cues that work in every state.
- **Tab text colour is not settable.** `Content` exposes no foreground API. Rather than restricting
  the palette to keep the label legible, the tab fill is derived from the tab-strip background (see
  above), so contrast holds for any accent and *Custom…* can offer the IDE's full colour picker.
- **There is no IDE emoji picker.** Emoji are drawn by this plugin (`EmojiIcon`, via Apple Color
  Emoji on macOS). The picker offered in the emoji dialog is the *system* Emoji & Symbols palette,
  which the IDE exposes as the `EmojiAndSymbols` action — macOS only. Elsewhere, paste an emoji in.
- **Split terminals share one colour.** A split is several `TerminalView`s inside a single
  `Content`, and the colour belongs to the `Content`.
- **The background tint is unsupported and opt-in per tab.** The reworked terminal renders into
  `EditorImpl` instances that `TerminalView` does not expose, so the tint finds them by walking the
  Swing tree and calling the supported `EditorEx.setBackgroundColor`. Turn it on for a tab from its
  *Tab Style* menu; the settings checkbox is a kill switch, not the opt-in. It fails silently
  (logging at INFO), leaving colours and icons intact.
- **Per-session ANSI palettes are not possible.** `BlockTerminalColorPalette` is constructed
  privately inside `MutableTerminalOutputModelImpl` and reads the global scheme regardless; and
  `TerminalColorScheme` is Kotlin-`internal` with mutators that throw. Terminal ANSI colours stay
  global, under *Settings ▸ Editor ▸ Color Scheme ▸ Console Colors*.
- **Renaming is already solved by the IDE.** `userDefinedTitle` outranks the shell's OSC 0/2 title
  in `TerminalTitle.buildTitle()`, so the bundled *Rename Session* action already sticks. This
  plugin deliberately does not add a competing rename.

### Text is escaped before it reaches the settings XML

Two classes of character do not survive that XML, and both arrive through user-visible fields.
Characters above the BMP — most emoji — are surrogate pairs in a Java `String`, and a per-`char`
validity check strips each half as a lone surrogate, so the value comes back as an **empty string**
with no error. Control characters are illegal in XML 1.0 outright, and a shell can emit one inside
an OSC title that then ends up in a style key.

`encodeXmlSafe` escapes exactly those to `\uXXXX` (doubling literal backslashes) and leaves
everything else alone, so paths and glob patterns stay readable in the file. It is applied to
emoji, rule patterns **and** identity keys — the last two matter because renaming a tab to
`🚀 api` would otherwise produce a key that never matches again after a restart, silently losing
the style.

### Glob matching does not use a regex

`*` → `.*` produces the textbook catastrophic-backtracking shape, and matching runs on the EDT
against a shell-reported title that a remote host can influence. `RuleMatcher` uses the standard
two-pointer glob scan instead: worst case O(pattern × text), no exponential cliff, and no pattern
cache to grow. A test with 20 nested wildcards runs under a timeout that a regex would blow.

## Privacy

Storage is split deliberately:

- **`.idea/terminalTabStyle.xml`** (usually committed) holds only *rules* and the three settings
  checkboxes. You wrote those patterns on purpose — though note a rule like `ssh prod-db-01` does
  put that hostname in the repository.
- **`.idea/workspace.xml`** (usually not committed) holds the per-tab overrides. Their keys are
  derived from working directories and shell-reported titles, so they contain absolute paths and
  internal hostnames that nobody typed deliberately — exactly what you would not want to find in
  a shared file.

Nothing leaves the machine: no network calls, no commands run, and only tab titles and working
directories are read. Shell titles are deliberately kept out of `idea.log`, since they can carry
paths and hostnames.

## Style identity

A hand-picked style has to be remembered across restarts, but 253's `TerminalToolWindowTab` exposes
only `view` and `content` — there is no stable per-tab id. Keys are therefore derived once, on first
use, and pinned in the content's user data so they survive the shell changing directory:

1. `name:<userDefinedTitle>` if the tab has been renamed — the most stable identity, since the user
   chose it and nothing else rewrites it.
2. `cwd:<working directory>` otherwise, which reads naturally: the terminal you open in a given
   project keeps its colour.
3. `tab:<tabName>` as a last resort.

Notably this is *not* keyed on `displayName`, which the terminal continuously rewrites from shell
titles — the bug in both comparable plugins.
