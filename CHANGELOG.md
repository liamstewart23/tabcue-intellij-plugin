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
  discards a custom tab colour while a tab is selected or hovered — the dot stays visible in
  every state.
- **Any colour, not just the presets.** *Custom…* opens the IDE's own colour picker as a popup,
  with hex entry, RGB fields and the screen eyedropper, and the tab updates live as you drag.
  Custom colours work in rules too.
- The tab background is **derived** from the chosen colour rather than being it: the colour is
  mixed part-way into the tab-strip background, and then pre-compensated for the translucent
  overlay `JBDefaultTabPainter` composites on top — on the dark default that overlay is 60%
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

### Polish

- The tab menu is grouped into **Color**, **Icon** and **Emoji** submenus. Flat, it had grown to 38
  items and five separators — around 950px, which scrolls on a 1080p display. Grouping also
  disambiguates the two *Custom…* entries, which read identically side by side, and means the
  platform only has to update the handful of items actually on screen.
- Tint strength moved under the switch that gates it, follows its enabled state, and shows its
  value as you drag. A live slider for a feature that is switched off is a dead control.
- New plugin logo: three differently coloured tabs rather than one highlighted tab, since telling
  several terminals apart is the point.
- Asking for a tab's identity no longer performs a reflective terminal lookup and a shell
  working-directory query when the answer is already pinned — which it is for any tab styled by
  hand, and it was being asked once per menu item.

### Hardened

- The tab fill resolves lazily, so it follows a theme switch instead of freezing at whatever theme
  was in force when the tab was styled — and is memoised on the theme values it derives from,
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
