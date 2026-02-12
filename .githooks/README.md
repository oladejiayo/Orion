# Orion Platform — Git Hooks

## What Are These?

These are **Git hooks** — scripts that run automatically at specific points in the Git workflow. They enforce code quality standards before code reaches the repository.

| Hook | When It Runs | What It Does |
|------|-------------|-------------|
| `pre-commit` | Before every `git commit` | Runs Spotless formatting check; rejects unformatted code |
| `commit-msg` | After writing commit message | Validates Conventional Commits format; rejects bad messages |

## Setup

Git doesn't use custom hook directories by default. Run this **once** after cloning:

```bash
git config core.hooksPath .githooks
```

Or on Windows PowerShell:

```powershell
git config core.hooksPath .githooks
```

This tells Git to use `.githooks/` instead of the default `.git/hooks/`.

## Conventional Commits Format

All commit messages must follow this format:

```
type(scope): subject

optional body

optional footer
```

### Allowed Types

| Type | When to Use |
|------|-----------|
| `feat` | A new feature |
| `fix` | A bug fix |
| `docs` | Documentation changes only |
| `style` | Code style (formatting, whitespace) — no logic change |
| `refactor` | Code restructuring — no new feature, no bug fix |
| `perf` | Performance improvement |
| `test` | Adding or updating tests |
| `chore` | Build process, dependencies, tooling |
| `revert` | Reverting a previous commit |
| `ci` | CI/CD pipeline changes |
| `build` | Build system or dependency changes |

### Examples

```
feat(rfq): add quote expiry timeout
fix(security): validate tenant ID before database query
docs: update architecture overview diagram
test(event-model): add serialization round-trip tests
chore(deps): bump Spring Boot to 3.4.4
ci: add JaCoCo coverage threshold to PR workflow
build: add maven-enforcer-plugin to parent POM
```

## Fixing Formatting Issues

If the pre-commit hook rejects your commit:

```bash
# Auto-fix all formatting
./mvnw spotless:apply

# Stage the fixes
git add -u

# Try your commit again
git commit -m "feat(rfq): your message"
```

## Skipping Hooks (Emergency Only)

In rare cases where you need to bypass hooks:

```bash
git commit --no-verify -m "fix: emergency hotfix"
```

⚠️ **Use sparingly** — CI will still enforce these checks.
