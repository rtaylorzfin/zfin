# ZFIN Helper

A small Chrome extension that adds a command palette and cross-environment
navigation to ZFIN websites. It is scoped to `*.zfin.org`,
`zfin.atlassian.net`, and `localhost`, and does nothing on any other domain.

## Features

- **Command palette** — press `Cmd+.` (macOS) or `Ctrl+.` (Linux/Windows) on
  any matching page to open the helper. `Esc` or click outside to close.
- **Go to case** — prompt for a Jira case number and open
  `https://zfin.atlassian.net/browse/ZFIN-<number>`. The destination is
  added to recent pages so you can find it later.
- **Go to gene** — prompt for a gene name and open the current host's
  `/action/quicksearch/prototype?q=!!<name>`. Also logged to recent pages.
- **Recent pages** — every visit to a matching page is recorded
  (URL + title, capped at 100 entries), plus any case/gene destinations
  triggered through the helper. The palette has a filter input with
  `↑`/`↓`/`Enter` keyboard navigation; `Shift+Enter` opens the
  highlighted page in a new tab.
- **Jump between environments** — laid out as compact pills using
  shortened names (`cell`, `trunk`, `test`, `schlapp`, `zfin.org`).
  Click to open in this tab; `Shift+click` for a new tab. Click
  **Edit** to add or delete entries; the list is stored in
  `chrome.storage.local` and persists across browser restarts.

## Install (unpacked, for development)

1. Open `chrome://extensions` in Chrome (or any Chromium-based browser:
   Edge, Brave, Arc, etc.).
2. Toggle **Developer mode** on (top right).
3. Click **Load unpacked** and select this directory:
   `server_apps/WebSiteTools/browser-extension/`.
4. Visit any matching page and press `Cmd+.` to confirm it works.

To update after pulling new code, click the reload icon on the
"ZFIN Helper" card in `chrome://extensions`.

## Using it

1. Open any matching page and press `Cmd+.`.
2. Press `C` or `G` to prompt for a case number or gene name and
   navigate. Press `R` to jump into the recent-pages filter.
3. To jump environments, press the number on the pill (or click it).
   `Shift+click` (or `Shift`+number) opens in a new tab. The current
   path, query string, and hash are preserved.

## Keyboard shortcuts

All shortcuts are also displayed in the panel UI as `kbd` badges next to
the relevant action.

| Key                       | Action                                                        |
| ------------------------- | ------------------------------------------------------------- |
| `Cmd+.` / `Ctrl+.`        | Toggle the helper panel                                       |
| `Esc`                     | Close the panel (or leave env-edit mode)                      |
| `C`                       | Prompt for a case number, then open it in Jira                |
| `G`                       | Prompt for a gene name, then open the quicksearch             |
| `R`                       | Focus the **Recent pages** filter                             |
| `↑` / `↓` (pages filter)  | Move the highlight in the recent-pages list                   |
| `Enter` (pages filter)    | Open the highlighted page                                     |
| `Shift+Enter` (pages)     | Open the highlighted page in a new tab                        |
| `1`–`N`                   | Jump to environment N in the current tab                      |
| `Shift`+`1`–`N`           | Open environment N in a new tab                               |

While typing in any panel input, only `Enter`/`Esc`/`↑`/`↓` are
intercepted so you can type freely.

## Configuration

- **Environment list** — click **Edit** in the *Jump to environment*
  section to add or remove hosts. Stored in `chrome.storage.local`,
  so the list persists across browser restarts. Defaults are seeded
  the first time the extension loads.
- **Recent pages** — persisted in `chrome.storage.local`, capped at 100
  entries. Click **Clear** in the *Recent pages* section to wipe it.
  Case and gene destinations triggered from the helper are added here
  too.
- **Keyboard shortcut binding** — remap `Cmd+.` from
  `chrome://extensions/shortcuts`.

## Files

```
manifest.json    # MV3 manifest, host permissions, command binding
background.js    # Service worker: command routing
content.js       # Injects the palette UI (closed shadow DOM)
icons/           # icon.svg source + 16/32/48/128 PNGs
```

To regenerate the icons from `icons/icon.svg`:

```
cd icons
for s in 16 32 48 128; do rsvg-convert -w $s -h $s icon.svg -o icon-${s}.png; done
```
