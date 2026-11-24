# Repository hygiene and commit plan

These are manual commands for the maintainer. Verify the repository root, branch and remote before staging. This workspace contains a `.git/` directory; do not initialize another repository or assume history is empty. No history has been inspected for this guide.

## What belongs in the source repository

| Path | Decision |
|---|---|
| `app/src/main/`, `app/src/debug/`, `app/src/test/`, `app/src/androidTest/` | Track Kotlin, manifests, resources, test code and fixtures after review. Keep opt-in cloud tests off by default. |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `app/build.gradle.kts` | Track shared build configuration. Put machine paths and secrets in local configuration, not these files. |
| `gradle/wrapper/`, `gradlew`, `gradlew.bat` | Track the complete Wrapper, including the JAR. Do not confuse `gradle/` with `.gradle/`. |
| `firebase.json`, `firestore.rules` | Track deployment configuration that refers to the rules file and the versioned rules themselves. Do not stage cloud project aliases, service accounts or `google-services.json`. |
| `tools/`, `setup-android-dev.ps1`, `local.properties.example` | Track reproducible tools and the path template. The legacy bootstrap is not required and modifies user environment variables; the README documents the regular setup. |
| `README.md`, `docs/LOCAL_DEVELOPMENT.md`, `docs/CONTRIBUTING.md`, `.gitignore`, `.gitattributes` | Track the source-checkout documentation and repository policy. |
| `app/schemas/` | If exported in the future, review and track Room schema JSON needed for migration tests. Not a build cache. |
| `build/`, `app/build/`, `.gradle/`, SDK/AVD data, APKs and reports | Keep local. These include large signed benchmark APKs and generated results. Use an approved artifact channel instead if the team needs an APK. |
| `app/google-services.json`, `local.properties`, `.env*`, signing keys, DB files | Keep local; `.env.example` and `local.properties.example` are exceptions for safe templates. Never force-add secrets. |
| `sources/`, internal phase docs, `docs/week-2/`, `docs/evidence/`, `docs/serah-terima/` | Keep local by default. They include coursework PDFs, student identifiers, historical evidence, raw logs and an unconfirmed draft. Publish only reviewed excerpts through a separately approved channel; a private repo is not a secret store. |
| `AGENTS.md`, `.agents/`, `skills-lock.json`, IDE state | Local workflow in this source-only policy. No app dependency on agent/editor files. If the team wants shared agent guidance, publish a reviewed copy that does not depend on private documents. |

`.gitignore` does **not** untrack anything already in the index or remove history. If a real credential was committed or pushed, stop and rotate/revoke it as appropriate; removing the current file is not sufficient. If internal material is already tracked, agree on its disposition with the team before changing the index or rewriting history. Preserve local files and backups. Do not change repository visibility or publish course material without permission.

## Before staging

```powershell
git rev-parse --show-toplevel
git status --short --branch
git log -5 --oneline
git remote -v
git ls-files -ci --exclude-standard
git check-ignore --no-index app/google-services.json local.properties build/ceh-0.2.0-release-benchmark.apk docs/serah-terima/LAPORAN_M2.md
```

`git ls-files -ci --exclude-standard` identifies already-tracked paths that now match ignore rules; inspect the list before proceeding. Keep remote output private if URLs contain credentials. Do not use `git add .`, `git add -A`, `git commit -a` or `git add -f` for this checkout. Do not stage these groups over unrelated staged team changes.

## Branch and pull request workflow

| Branch | Purpose | Merge target |
|---|---|---|
| `main` | Stable, release-ready branch | — |
| `dev` | Integration branch for reviewed changes | `main` through a release PR |
| `feat/*`, `fix/*`, `docs/*`, `chore/*` | Short-lived, single-purpose work based on `dev` | `dev` through a PR |

Protect `main` and `dev` in the hosting settings: require PRs, at least one reviewer where possible, and passing checks that actually exist. Do not force-push or routinely push work directly to either branch. Use squash merge for work PRs and give the squash commit a Conventional Commit title. Use a **merge commit** for `dev` → `main` releases to preserve ancestry between the two long-lived branches; do not squash that release PR.

### Set up `dev` once

The examples assume `origin` is the verified team remote and `main` is the agreed default branch. Do not rename an existing shared default branch without agreement. Start branch setup from a clean working tree and index. If there are already local changes, preserve them in the current checkout or do the setup from a separate clean clone; do not discard them.

```powershell
git fetch origin --prune
git branch -a
```

If `origin/dev` already exists, do **not** create or push a replacement. Use `git switch dev` if you have the local branch; otherwise use `git switch --track origin/dev`. If the team has `origin/main` but neither a local nor remote `dev`, a maintainer can run this once, before making `dev` PR-only (or create `dev` from `main` on the hosting site instead):

```powershell
git switch --no-track -c dev origin/main
if ($LASTEXITCODE -ne 0) { throw 'Could not create dev from origin/main' }
git push -u origin dev
```

If there are **no commits** locally or on the remote, do the source import below on the verified initial branch, then bootstrap `main` and `dev`. If the remote already has a README or any other commit, use the existing-history route instead; do not force-push unrelated history.

### Start one work branch

With a clean working tree and index, use the following, replacing the branch name for the specific work item (e.g. `feat/firebase-profiles`, `fix/api26-map-fallback`, or `docs/local-development`). If a command fails, stop rather than continuing on the wrong branch.

```powershell
git switch dev
if ($LASTEXITCODE -ne 0) { throw 'Could not switch to dev' }
git pull --ff-only origin dev
if ($LASTEXITCODE -ne 0) { throw 'Could not fast-forward dev' }
git switch -c fix/dicoding-schedule-offsets
```

If the relevant changes already exist in your working tree, inspect them before switching. Base the work branch on `dev`; if a clean switch is not possible, use a separate clean checkout based on `dev` and move only the intended changes into it.

## Suggested commits for a source import

Use these groups only if the corresponding content is actually new. For an existing remote, run them on a work branch such as `chore/source-import` based on `dev`; for a genuinely empty repo, run them on the initial branch only. The app build, Wrapper, source and tests belong together for the initial import; source files share integration points across features. Do not recreate historical commits from a snapshot.

**Run the examples one line at a time.** Stop if staging or `git diff --cached --check` fails. Inspect `git diff --cached --name-status` and `git diff --cached` before committing; skip empty commits and stop if ignored paths or unrelated staged changes appear.

```powershell
git add -- .gitignore .gitattributes
git diff --cached --check
git diff --cached
git commit -m "chore(repo): define source tracking rules"
```

```powershell
git add -- gradlew gradlew.bat gradle/wrapper build.gradle.kts settings.gradle.kts gradle.properties app/build.gradle.kts app/src firebase.json firestore.rules
git diff --cached --check
git diff --cached
git commit -m "feat(app): import TemuTech"
```

```powershell
git add -- setup-android-dev.ps1 local.properties.example
git diff --cached --check
git diff --cached
git commit -m "chore(dev): add local Android setup tools"
```

```powershell
git add -- tools/check-firestore-rules.mjs tools/verify-ceh-cloud-live.mjs tools/measure-ceh-startup.mjs
git diff --cached --check
git diff --cached
git commit -m "test(tools): add cloud and startup checks"
```

```powershell
git add -- README.md docs/LOCAL_DEVELOPMENT.md docs/CONTRIBUTING.md
git diff --cached --check
git diff --cached
git commit -m "docs(dev): document Android setup and shutdown"
```

### Publish the first baseline only on an empty remote

After reviewing the baseline and confirming the remote really has no branches, keep `main` if already named `main`; otherwise run `git branch -m main` after the first commits. Then publish the initial branches once as the maintainer:

```powershell
git push -u origin main
if ($LASTEXITCODE -ne 0) { throw 'Could not publish main' }
git switch -c dev main
if ($LASTEXITCODE -ne 0) { throw 'Could not create dev from main' }
git push -u origin dev
```

Set `main` as the default branch and enable the PR protections above after the bootstrap. Feature work starts from `dev` after that.

## Feature commits on an existing history

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): `type(scope): imperative summary`. For example, use `feat(map): ...` for a new map feature and `fix(map): ...` for a defect. `git(feat)` is not the standard form. Compare with the actual branch first. Use only the matching example on its own work branch, not in addition to the same changes in the import above. Apply the same staging and diff checks described for the import. Include matching tests and any necessary shared Gradle, manifest, resources and caller changes in the **same** commit; stage only the relevant hunks in shared files. If a path is new, use `git add -- <reviewed-new-file>` for it, since `git add -p` does not stage untracked files by default.

```powershell
# fix/dicoding-schedule-offsets — mapper and its tests
git add -p -- app/src/main/java/id/ac/ceh/data/Dicoding.kt app/src/test/java/id/ac/ceh/data/DicodingTest.kt app/src/androidTest/java/id/ac/ceh/data/RepositoryTest.kt
git diff --cached --check
git diff --cached
git commit -m "fix(dicoding): honor explicit schedule offsets"

# fix/api26-map-fallback — fallback and user-facing strings; add tests if present
git add -p -- app/src/main/java/id/ac/ceh/MapFragment.kt app/src/main/res/values/map_strings.xml
git diff --cached --check
git diff --cached
git commit -m "fix(map): use a location list on API 26"

# feat/firebase-profiles — shared app and Firebase configuration; stage only cloud changes
git add -p -- app/src app/build.gradle.kts build.gradle.kts firebase.json firestore.rules
git diff --cached --check
git diff --cached
git commit -m "feat(auth): add private Firebase profiles"

# feat/local-event-crud — UI and Room; stage only the matching change
git add -p -- app/src
git diff --cached --check
git diff --cached
git commit -m "feat(events): manage locally owned events"

# feat/offline-saved-events — stage only corresponding UI, data and tests
git add -p -- app/src
git diff --cached --check
git diff --cached
git commit -m "feat(saved): keep bookmarks and RSVPs offline"
```

## Push the work branch and open a PR

A successful build in a dirty working tree does not prove the staged feature is self-contained. Validate the committed changes in a clean checkout or rely on required CI before merging. Review the branch diff, then push the **work branch**, not `dev` or `main`:

```powershell
git status --short --branch
git log --oneline origin/dev..HEAD
git diff --stat origin/dev...HEAD
# Example: replace this name with your verified work branch.
git push -u origin fix/dicoding-schedule-offsets
```

On the hosting site, open the PR with **base `dev`** and **compare your work branch**. Summarize the change, tests run, and known limitations. After review, squash merge using the Conventional Commit title. For a release, open a separate PR with **base `main`** and **compare `dev`**; use a merge commit.
