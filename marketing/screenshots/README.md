# Store screenshots

Upload order for the Marketplace gallery. The first image is the one shown on the plugin card and
in search results, so it carries the most weight.

| # | File | Shows | Why it is here |
|---|------|-------|----------------|
| 1 | `01-tab-strip.png` | Five terminals in one strip: colours, emoji, a dot on the selected tab, and a tinted output area | The whole pitch in one frame, and the only shot that reads at thumbnail size |
| 2 | `02-tab-style-menu.png` | Right-click ▸ **Tab Style**, with its four submenus | Answers "how do I use this" immediately |
| 3 | `03-tab-colors.png` | The **Tab Color** submenu, eight presets plus *Custom…* | Shows the palette, and that a colour is one click |
| 4 | `04-emoji.png` | The **Emoji** submenu, twelve named rows plus *Custom…* | The feature people actually come for when they run several AI agents |
| 5 | `05-settings.png` | *Settings ▸ Tools ▸ TabCue*: auto colours, tint strength, the rules table | Shows there is real configuration behind the menu |

The plugin description is deliberately image-free. The same `<description>` block is rendered
inside the IDE's own Plugins dialog, where remote images are not reliably loaded, so the pictures
live in the Marketplace gallery and in the repository README instead.

## Captions

The Marketplace gallery has no caption field, so these are for the README, where they do render.

1. Five terminals, five colours. The selected tab keeps its dot when the IDE discards its background.
2. Everything is on the tab's own context menu, under **Tab Style**.
3. Eight presets, or any colour at all from the IDE's own picker.
4. Twelve emoji are one click. Anything else goes through **Custom…**.
5. Rules, auto colours and tint strength live in **Settings ▸ Tools ▸ TabCue**.

## Normalising for the gallery

The Marketplace recommends 1280x800. `normalise.sh` scales each capture down to fit and pads it
onto that canvas with the IDE's own background colour, so a wide, short capture is letterboxed
rather than stretched. Output goes to `store/`.
