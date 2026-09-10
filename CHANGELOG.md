# Changelog

All notable changes to TabCue are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/).

## [Unreleased]

## [1.0.0]

First release.

### Added

- **Per-tab colours and icons** for Terminal tool-window tabs, set from the tab's context menu.
  A colour is drawn both as the tab background and as a small dot, because the IDE's tab painter
  discards a custom tab colour while a tab is selected or hovered, so the dot is what stays
  visible in every state.
- **Any colour, not just the presets.** *Custom…* opens the IDE's own colour picker as a popup,
  with hex entry, RGB fields and the screen eyedropper, and the tab updates live as you drag.
  Custom colours work in rules too.
- The tab background is **derived** from the chosen colour rather than being it: the colour is
  mixed part-way into the tab-strip background, and then pre-compensated for the translucent
  overlay `JBDefaultTabPainter` composites on top. On the dark default that overlay is 60%
  opaque, which is why an uncompensated tab colour looked washed out. The full-strength colour
  goes to the dot and the tint, neither of which sits behind text, so no colour can make a tab
  label unreadable.
- **Emoji tab icons.** Twelve common emoji are items in the tab menu, so choosing one is a single
  click; *Custom…* opens a small popup for anything else, with a button that opens the system
  Emoji & Symbols palette on macOS.
- **Rules** that style tabs automatically by tab title or working directory, first match wins.
  The motivating cases: give each AI agent session its own emoji, and paint anything matching
  `ssh prod` red so you always know which machine you are on.
- **Optional background tint** of the terminal output area itself, not just the tab, with an
  adjustable strength.
- *Clear Style* records an explicit "leave this tab alone" marker, with *Use Rules Again* to undo
  it, so clearing a tab is not immediately repainted by a matching rule.
- *Configure Rules…* in the tab menu, and a settings page under *Tools ▸ TabCue* that is findable
  from Settings search.
- **Starter rules for AI agents.** A project with no rules of its own begins with one rule each
  for Claude Code, Codex, Junie and aider, each a distinct colour *and* a distinct emoji, since
  the case they exist for is three agent tabs open at once. Matching is on the tab title, which the terminal
  keeps in sync with the shell's title, so they fire whether the IDE launched the agent or you
  typed the command. Seeded once and only into an empty rule list, so deleting them sticks.
- **Per-tab text colour.** White, black or custom, from the *Text Color* submenu. This is the
  only cue that survives selection: `JBDefaultTabPainter.getCustomBackground` does not blend a
  custom tab colour when the tab is selected, it discards it and substitutes a theme colour.
- **Auto-assigned colours are now derived from the tab**, meaning its title and working
  directory, rather than handed out as "the first palette entry no other tab is using". A tab therefore keeps
  its colour across restarts and across machines, instead of being reshuffled every time a project
  reopened, which taught you to stop reading the colours. Distinctness still wins on a collision:
  the derivation only chooses where to start looking.

### Polish

- The tab menu is grouped into **Color**, **Icon** and **Emoji** submenus. Flat, it had grown to 38
  items and five separators, around 950px, which scrolls on a 1080p display. Grouping also
  disambiguates the two *Custom…* entries, which read identically side by side, and means the
  platform only has to update the handful of items actually on screen.
- Emoji rows are `[glyph] Name`. Passing the emoji as both the row's icon and its text drew it
  twice side by side, which reads as a rendering fault; the name also makes the menu searchable by
  word rather than by pictogram.
- The custom-emoji popup opens on the next EDT pass rather than synchronously. Opened immediately,
  it was created while the context menu was still dismissing, so the mouse event that closed the
  menu landed outside the new popup and `setCancelOnClickOutside` closed it again at once. The
  symptom was a popup that never appeared.
- Tint strength moved under the switch that gates it, follows its enabled state, and shows its
  value as you drag. A live slider for a feature that is switched off is a dead control.
- New plugin logo: three differently coloured tabs rather than one highlighted tab, since telling
  several terminals apart is the point.
- Asking for a tab's identity no longer performs a reflective terminal lookup and a shell
  working-directory query when the answer is already pinned, which it is for any tab styled by
  hand. It was being asked once per menu item.

### Hardened

- An icon set by another plugin is never destroyed. PhpStorm 2026.2's "AI Agents" terminal feature
  marks the tabs it launches with an agent logo using exactly the two calls this plugin uses,
  `putUserData(SHOW_CONTENT_ICON, true)` and `content.icon = …`. Since every terminal tab is
  restyled whether or not it has a style, clearing unconditionally would wipe that logo the moment
  a tab was touched. Only icons this plugin set are cleared, and whatever was there beforehand is
  restored when a style is removed.
- The tab fill resolves lazily, so it follows a theme switch instead of freezing at whatever theme
  was in force when the tab was styled. It is memoised on the theme values it derives from,
  because `JBColor` calls its supplier on every channel read.
- A style is no longer written to a torn-down tab. The colour picker commits continuously while it
  is open, and once a session is gone the tab's identity falls back to a weak label-based key, so
  the write would have landed under a key shared with every other unresolved tab.
- Colour-picker commits are coalesced. Each one restyles every known tab, and the platform fires
  its colour listener on every mouse-move.
- A rule this build cannot parse now returns to the position it held rather than the end of the
  list. Rules are first-match-wins, so appending changed which style a tab got.
- Emoji are normalised on the way in, so what is stored is the single grapheme that is actually
  drawn rather than an arbitrarily long pasted string.
- XML non-characters are escaped alongside surrogates and control characters; a shell-supplied
  title containing one could previously stop the whole settings file from being written.
- Menu labels built from user input no longer have `_` or `&` eaten as mnemonic markers.

### Notes

- Works in every IntelliJ-based IDE that bundles the Terminal, on platform branch `253` (2025.3)
  or newer, with both the reworked and the classic terminal engine. Verified against IntelliJ
  IDEA, PhpStorm (2025.3 and 2026.2), WebStorm and PyCharm.
- Rules live in `.idea/terminalTabStyle.xml` so they can be shared. Per-tab styles live in
  `workspace.xml`, because their identifiers are derived from working directories and
  shell-reported titles and so contain absolute paths and hostnames that do not belong in a
  committed `.idea/` file.
- Split terminals share one tab colour: a split is several sessions inside a single tab, and tab
  colour is a property of the tab.
