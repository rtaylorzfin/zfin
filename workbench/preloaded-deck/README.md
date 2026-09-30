# Rendered decks

Marp output. **Generated — edit the sources in `reference/`, not these.**

| output | source |
|---|---|
| `review-stacks-deck.html` | `reference/review-stacks-deck.md` |
| `preloaded-dev-stacks-deck.html` | `reference/preloaded-dev-stacks-deck.md` |

Re-render after editing a source:

```bash
npm run deck -- reference/review-stacks-deck.md -o workbench/preloaded-deck/review-stacks-deck.html
```

`marp-cli` is a devDependency, so `npm ci` is enough — nothing global to install.

Note: do **not** pass `--allow-local-files`. It makes marp launch a browser and the render
hangs indefinitely; plain HTML output needs neither.
