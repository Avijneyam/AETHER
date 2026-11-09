# AETHER's website

A static page with no build step and no dependencies. It fetches nothing from anyone else's servers, and the Content-Security-Policy in the root `vercel.json` holds it to that.

- `index.html`, `styles.css`, `app.js`: the page. The statues are drawn live in WebGL, with the same halftone and dot pairing as the app.
- `assets/statues/`: the figures, small, in greyscale. The browser reads their brightness to place the dots. Credits are in `third_party/STATUE_PHOTOS.md`.
- `assets/screens/`: real screenshots of the app.
- `assets/fonts/`: the app's three typefaces as WOFF2, under the SIL Open Font License (`assets/fonts/licenses/`).
- `assets/media/headphones.json`: the app's AutoEq catalogue, for the equaliser bench.

`node site/dev.mjs` serves it at http://localhost:4173 and reloads on every saved change. Vercel serves `site/` from `main`, as set in `vercel.json`. See `docs/releasing.md`.
