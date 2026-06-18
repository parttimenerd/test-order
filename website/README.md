# Run the docs site locally

```sh
cd website
npm install
npm start          # opens http://localhost:3000
```

`npm run build` produces a static site in `build/`. The CI workflow at
`../.github/workflows/docs.yml` builds and deploys to GitHub Pages on every
push to `main`.

The markdown source lives at `../docs/`, not in this directory — Docusaurus
reads it via `path: '../docs'` in `docusaurus.config.js`. Edits to docs land
on `/docs/<NAME>` on the published site and stay readable on GitHub blob view
unchanged.

The landing page (`/`) is a standalone marketing page in `src/pages/index.jsx`
with the slogan "Stop running tests that will never fail." It pulls
screenshots from `static/img/screenshots/` (populated at build time by
`scripts/capture-screenshots.mjs`) and an asciinema cast from
`static/casts/`.
