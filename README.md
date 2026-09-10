# TabCue: Visual Terminal Tabs

Style terminal tabs with custom colours, background hints, and emojis so you can tell terminals and
AI agents apart at a glance.

I kept losing track of which terminal was which — `npm run dev`, a couple of Claude sessions, and
an `ssh` into something I'd rather not typo in. They all look identical. So each Terminal tab can
now have its own colour, icon or emoji.

## What it does

- **Colours** — eight presets, or *Custom…* for the IDE's full colour picker (hex, RGB,
  eyedropper). Shown as the tab background *and* a small dot.
- **Icons and emoji** — a built-in icon or any emoji, picked from the tab menu.
- **Rules** — style tabs automatically by title or working directory. Give each agent session its
  own emoji, or paint anything matching `ssh prod` red.
- **Background tint** — optionally tint the terminal output area too, not just the tab.

Right-click any Terminal tab → **Tab Style**. Settings live under *Tools ▸ TabCue*.

## Install

Search for **TabCue** in *Settings ▸ Plugins*. Or build it yourself (see below) and use
*Install Plugin from Disk* on the zip.

Works in any IntelliJ-based IDE that bundles the Terminal — IDEA, PhpStorm, WebStorm, PyCharm,
GoLand, RubyMine and the rest — on 2025.3 or newer, with both the new and classic terminal engines.

## Good to know

A few things that look like bugs but are the platform:

- **The tab colour disappears on the selected tab and on hover.** The IDE's tab painter overpaints
  it and there's no per-tab override. That's why there's also a colour dot — it survives every
  state, and so does the background tint.
- **Split terminals share one colour.** A split is several sessions inside one tab, and the colour
  belongs to the tab.
- **Emoji picking is macOS-only.** The button opens the system Emoji & Symbols palette. Elsewhere,
  paste one in — it still works.
- **Terminal ANSI colours stay global.** Only the tab and the background are per-tab; the palette
  itself is `Settings ▸ Editor ▸ Color Scheme ▸ Console Colors`.

Rules are stored in `.idea/terminalTabStyle.xml` so you can commit and share them. Per-tab styles
go in `workspace.xml` instead — their keys contain working directories and shell-reported titles,
which don't belong in a shared file. Nothing leaves your machine.

## Building

```bash
./build.sh buildPlugin     # → build/distributions/tabcue-1.0.0.zip
./build.sh runIde          # sandbox IDE with the plugin loaded
./build.sh check           # tests
```

The build provisions its own JDK 21, so there's nothing to install first.

Compiled against the 2025.3 SDK (platform branch `253`) — that's the baseline, not a ceiling.
`since-build` is `253` with no upper bound. Compiling against the oldest supported branch is
deliberate: it's what makes the compiler reject anything missing from 2025.3, rather than finding
out later.

1.0.0 passes the JetBrains Plugin Verifier against all five, with 89 tests green and no compiler
warnings:

| IDE | Build |
| --- | --- |
| PhpStorm 2026.2.2 | `PS-262.10315.130` |
| PhpStorm 2025.3.6.1 | `PS-253.33813.65` |
| IntelliJ IDEA 2025.3 | `IU-253.28294.334` |
| WebStorm 2025.3 | `WS-253.28294.332` |
| PyCharm 2025.3 | `PY-253.28294.336` |

```bash
./build.sh verifyPlugin
```

## Licence

MIT — see [LICENSE](LICENSE).
