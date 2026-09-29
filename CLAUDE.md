# Instructions for Claude Code in this repository

ARAK is a data access control platform: policies decide who may read which
tables, rows and columns, and ARAK enforces them. [CONTRIBUTING.md](CONTRIBUTING.md)
is the rulebook for everyone; this file repeats the parts an assistant most
often gets wrong. When the two disagree, CONTRIBUTING.md wins.

## Workflow

- Work on a branch (`<developer>/<topic>`) and open a pull request. Never push
  to `main`, never force-push a shared branch, never merge a pull request
  yourself unless the developer asks for that merge.
- Commit or push only when the developer asks.
- Run the checks before saying something works: backend unit tests, and for
  frontend changes `yarn type-check`, `yarn lint`, `yarn test`. Say plainly
  what you ran and what failed.
- Update `docs/user-guide.md` when something a user sees changes (NokRak
  answers from it), the roadmap row in `docs/DESIGN.md`, and add a section to
  `HANDOFF.md` (Thai).
- A new Flyway migration takes the next free `V<n>`; never edit a merged one.

## Never

- **Never run Prettier** in `frontend/app`. There is no config; it rewrites
  whole files.
- **Never `git add -A` or `git add .`**. Stage each file by path. The working
  tree holds local files (screenshots, exports, scratch scripts) that must not
  be committed.
- Never edit generated sources or `frontend/ui-core-components`.
- Never change a file's line endings.
- No production access: do not connect to, deploy to, or read configuration
  from any production host or database. The maintainer deploys.

## Secrets

The repository has been public; treat every commit as published forever.

- Never put a password, token, key, connection string, internal host name,
  internal IP, real customer schema, table or column name, or a real person's
  name into code, tests, docs, commit messages or pull request text.
- Tests and examples use `example.test` / `example.com`, `192.0.2.x`, and
  obviously fake secrets.
- Never print or echo the values in `.env`. Read a secret into a variable if a
  command needs it; show key names only.
- Before every commit, scan what is staged:
  `gitleaks git --pre-commit --staged --redact`. Before every push:
  `gitleaks git --log-opts="origin/main..HEAD" --redact`. A finding stops the
  commit until it is understood; a real secret is rotated, not just removed.

## Product rules that are security rules

Breaking one of these is a security bug, whatever the tests say.

- The query proxy fails closed and is read-only; every attempt is audited.
- No self-grants; grants need a reason and a future end date; grants are ended,
  never deleted.
- Data-source credentials are references (`vault://`, `fernet:`,
  `azurekeyvault://`, `env:`) and are never returned by an API.
- The LLM receives metadata only, never rows, and only what the asking person
  may read, filtered before the prompt is built. It suggests; it never runs,
  saves or sends anything.
- Client IP addresses are never returned in output.
