# Working on ARAK

How changes get from your machine to `main`, and the rules that keep a
public repository and an access-control product safe. The README covers what
ARAK is and how to install the toolchain; read it first.

## The flow

1. **Pick the work.** The roadmap is the milestone table in
   [docs/DESIGN.md](docs/DESIGN.md) (search for `| **M0** |`). Say which
   milestone or slice you are taking before you start, so two people are not in
   the same files. Open a draft pull request early if that helps.
2. **Branch from `main`.** Name it `<you>/<topic>`, for example
   `alex/m35-policy-tests`. Nobody pushes to `main` directly; it is protected.
3. **Build and test locally** (below). A pull request that has not been run is
   not ready for review.
4. **Open a pull request.** CI must pass: backend, frontend, the vendored
   design-system check, and the secret scan. One approval from the other
   developer is required. Add the `integration` label to run the Testcontainers
   suite on the pull request; it always runs after merge.
5. **Merge** once it is approved and green. Keep one change per pull request.
6. **Production is deployed by the maintainer only**, from `main`, after merge.
   Contributors do not need, and are not given, access to the production host,
   its `.env`, or production accounts.

### What goes with every change

- **Tests.** Backend unit tests next to the code (JUnit 5, Mockito, AssertJ);
  anything touching SQL or the database also gets an integration test (`*IT`,
  real PostgreSQL / SQL Server via Testcontainers). Frontend: Jest + React
  Testing Library next to the component.
- **Docs.**
  - [docs/user-guide.md](docs/user-guide.md) when anything a user sees changes.
    It is packed into the jar and NokRak answers "how do I" questions from it,
    so a stale guide means wrong answers in the app.
  - [docs/DESIGN.md](docs/DESIGN.md) for the requirement and the roadmap row.
  - `HANDOFF.md` is the running log of what each round did and why (in Thai).
    Add a section for your change.
- **Commit messages** say what changes for the people using ARAK in the
  subject, and why in the body. Look at `git log` for the tone.

### Database migrations

Flyway migrations live in `backend/dac-service/src/main/resources/db/migration`
as `V<n>__<what>.sql`.

- Never edit a migration that has been merged; add a new one.
- Two branches can pick the same `V<n>`. Before merging, rebase on `main` and
  renumber yours if the number is taken, or the application refuses to start.

## Local setup notes

Beyond the README's getting-started steps:

- **Windows + Git Bash.** MSYS rewrites anything that looks like a path, so
  `APP_WEB_BASE_PATH=/Arak/` turns into a Windows path unless you run
  `export MSYS_NO_PATHCONV=1 MSYS2_ENV_CONV_EXCL=APP_WEB_BASE_PATH` first.
- **JDK 21** without installing anything: `scripts/use-jdk21.sh` (or `.ps1`).
- **The bundle under a path prefix:** build with `VITE_BASE=/Arak/` when the
  backend serves the app at `/Arak/`, or the page loads blank.
- The first start creates a local admin from `IDENTITY_BOOTSTRAP_ADMIN_*` in
  your `.env`. Each developer has their own `.env` and their own keys (LLM
  gateway included); never share one.

### Commands

```bash
# backend (repo root)
./mvnw -pl backend/dac-service -am test                 # unit
./mvnw -pl backend/dac-service -am verify -Pintegration \
  -Dit.test='SomethingIT' -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl backend/dac-service -am package -DskipTests  # the jar

# frontend (frontend/app)
yarn type-check && yarn lint && yarn test && yarn build
```

## Code rules

- **Do not run Prettier** on the frontend. There is no Prettier config;
  running it rewrites whole files and buries the real change. ESLint is the
  only formatter-adjacent check.
- **Keep each file's line endings** as they are (some are CRLF, some LF).
- **Generated code and `frontend/ui-core-components` are not edited** (see the
  README).
- Tailwind classes use the `tw:` prefix.

### Rules the product depends on

These are not style. A change that breaks one is a security bug.

- **The query proxy fails closed.** If SQL cannot be parsed or a table cannot
  be resolved, the query is refused, never passed through. It is read-only.
  Every attempt is audited.
- **Access is decided by policy and grants, not by the caller.** No self-grants;
  a grant needs a reason and an end date in the future; grants are ended, never
  deleted.
- **Data-source credentials are references only** (`vault://`, `fernet:`,
  `azurekeyvault://`, `env:`), never stored in the clear, and never returned by
  an API.
- **The LLM sees metadata only**, never rows. Anything a person may not read is
  filtered out *before* it is written into a prompt; tables out of reach may be
  counted but not named. The LLM suggests; it never runs, saves or sends
  anything itself, and every page works without it.
- Client IP addresses are recorded for audit and never returned in output.

## Secrets and a public repository

The repository has been public. Treat everything you commit as published
forever: removing a secret in a later commit does not take it back.

- **Never commit** `.env`, keys, tokens, passwords, connection strings with
  credentials, or database dumps. `.gitignore` covers the usual files; it does
  not cover a secret pasted into code, a test, a doc or a commit message.
- **Stage by name.** `git add path/to/file`, never `git add -A` or `git add .`:
  there are local files in the working tree (screenshots, exports) that must
  not go in.
- **Tests and docs use made-up values only:** `example.test` / `example.com`
  domains, `192.0.2.x` addresses (RFC 5737), obviously fake keys. No internal
  host names, no real customer schemas, tables or columns, no real people.
- **Scan before you push.** CI runs [gitleaks](https://github.com/gitleaks/gitleaks)
  on every push and pull request and fails on a finding. Run it yourself first:

  ```bash
  gitleaks git --pre-commit --staged --redact    # what you are about to commit
  gitleaks git --log-opts="origin/main..HEAD" --redact   # what you are about to push
  ```

  gitleaks knows common secret formats, not your organisation's host names or
  customer data. Keep your own list of those outside the repository and grep
  your staged diff for it (`git diff --cached | grep -iE '<your list>'`). Do
  not commit that list: it would publish exactly what it protects.
- A finding that is not a secret (a fake token a test needs) goes in
  `.gitleaksignore` with a comment saying why.
- **If a secret gets pushed:** tell the maintainer at once, rotate it first,
  then remove it. The rotation is what matters; history keeps the old value.

## AI assistants

[CLAUDE.md](CLAUDE.md) carries these rules for Claude Code. Whatever assistant
you use, the same rules apply to what it writes, and nothing secret goes into a
prompt or a chat.
