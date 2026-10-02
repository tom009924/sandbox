---
name: conventional-commit
description: Review changes and create a Git commit using the Conventional Commits format.
---

# Conventional Commit

Use this skill when asked to create a commit or prepare a Conventional Commit message.

## Workflow

1. Inspect `git status` and review both staged and unstaged diffs before deciding what belongs in the commit.
2. Do not include unrelated or pre-existing changes. If the intended files are unclear, ask before staging.
3. Check the relevant tests or validation commands for the changes. Do not claim checks passed unless they ran successfully.
4. Choose a type that describes the change:
   - `feat`: user-facing functionality
   - `fix`: a bug fix
   - `docs`: documentation only
   - `test`: tests only
   - `refactor`: behavior-preserving code change
   - `build`: build system or dependency changes
   - `ci`: continuous integration changes
   - `chore`: maintenance that does not fit another type
5. Use the format:

   ```text
   <type>[optional scope][!]: <imperative summary>

   [Optional body explaining context or motivation]

   [Optional footers, including BREAKING CHANGE: ... when applicable]
   ```

6. Keep the summary concise, specific, and in the repository's usual language. Use a scope only when it adds useful context. Mark breaking changes with `!` and explain them in a `BREAKING CHANGE:` footer.
7. Stage only the intended files, inspect the staged diff, then create the commit. Include repository-required commit trailers.
8. After committing, verify the commit and report its hash, subject, and any validation results.

Do not amend commits, rewrite history, push, or include unrelated files unless explicitly asked.
