# Vendored: OpenMetadata UI Core Components

Source: https://github.com/open-metadata/OpenMetadata
Path:   `openmetadata-ui-core-components/src/main/resources/ui`
Tag:    `2.0.1-release`
Pulled: 2026-09-15
License: Apache-2.0 (see LICENSE)

## Why vendored instead of an npm dependency

`@openmetadata/ui-core-components` is **not published to npm** (registry returns 404).
It is consumed inside the OpenMetadata monorepo via a `link:` protocol dependency.
Vendoring the Apache-2.0 source is the only way to get byte-identical design tokens
and components, which is the stated requirement (UI must match OpenMetadata).

## What was excluded

- `src/stories/**` — Storybook demo files, pulls in `@storybook/*` devDependencies.
  Re-add from the same tag if we want the component gallery.

## Upgrade procedure

`scripts/sync-om-design-system.sh <tag>` re-pulls the same file set.
Review the diff — **do not hand-edit files in this directory.**
Local overrides belong in `frontend/app/src/theme/`.
