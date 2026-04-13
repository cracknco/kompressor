# Kompressor documentation

This folder contains the Mintlify-powered documentation site for Kompressor.

## Local preview

```bash
npm i -g mint
cd docs
mint dev
```

The site is served at `http://localhost:3000` with hot reload.

## Structure

- `docs.json` — navigation, theme, branding
- `introduction.mdx`, `installation.mdx`, `quickstart.mdx` — get-started pages
- `concepts/` — cross-cutting concepts (cancellation, error handling, the `Kompressor` interface)
- `guides/` — one walkthrough per compressor plus the probe / compatibility flow
- `reference/` — per-package API reference

Every page is a plain MDX file with a `title` / `description` frontmatter. Update `docs.json` whenever you add a new page to the navigation.
