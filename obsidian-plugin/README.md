# Nibline for Obsidian

Opens `.excalidraw` notes from the Nibline tablet app in Obsidian (desktop and mobile), using the Excalidraw editor.

- **PDF annotations:** pages of an annotated PDF show under your ink. The PDF is read from the same folder as the note, so keep the note and its PDF together, as the app does.
- **Editing:** edits are saved back into the same file. The app's own data (pens, page layout, PDF page pointers) is kept. The rendered PDF pages are never written into the note.
- **Changes from outside:** if the file changes on disk while it's open (e.g. after `git pull`), the view reloads it.

## Editing in the tablet app (Android)

Tapping a `.excalidraw` drawing in Obsidian opens it in the Nibline app. The app edits the vault file in place, so your changes go straight back into the vault. Press back to return to Obsidian, which then shows the updated drawing. "Edit in Nibline app" is also in each drawing's file menu and in the viewer's header.

**First time per vault:** the app asks for access to your vault folder. Pick the vault folder (or any folder containing it) and tap "Use this folder", then "Allow". The setting "Tapping a drawing opens the Nibline app" switches tapping back to the viewer.

## Install

1. Download `nibline-obsidian.zip` from the CI artifacts (or build it, see below).
2. Unzip it into `<your vault>/.obsidian/plugins/inkcalidraw/`. That folder should then contain `main.js`, `manifest.json` and `styles.css`.
3. In Obsidian, go to Settings → Community plugins, then enable **Nibline**.

**Opening drawings:** with the Excalidraw plugin disabled, `.excalidraw` files and the Excalidraw plugin's own `.excalidraw.md` drawings open in Nibline automatically. Support for `.excalidraw.md` is basic: drawing, text and images work; nothing beyond that. You can also open a drawing from the pen icon in the ribbon, the command "Open a drawing…", or **Open in Nibline** in any drawing's file menu. "Open as Markdown" in the view's menu shows a `.excalidraw.md` note's text.

**Excalidraw plugin conflict:** if the Excalidraw plugin is also installed, it keeps the `.excalidraw` extension. In that case, right-click a note and choose **Open in Nibline**, or use the command "Open current .excalidraw file in Nibline". The setting "Open .excalidraw files with Nibline" turns off claiming the extension.

## Build and test

```sh
npm install
npm run build      # outputs dist/main.js, dist/styles.css, dist/manifest.json
npm test           # headless-Chromium test against a real PDF-annotation note
```
