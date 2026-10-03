---
name: conventional-commit
description: Create a Conventional Commit for intended changes in the active feature worktree.
---

# Conventional Commit

Use this skill to prepare or create the commit step for repository changes. Start with `start-feature-worktree` before editing, and only commit from that task's feature worktree. For the standard end-to-end repository workflow, continue with `commit-push-pr` to push, create and verify a pull request, and remove the worktree.

## Workflow

1. Confirm the current directory is the task's dedicated feature worktree, not the default-branch worktree. If no task worktree exists, use `start-feature-worktree` before editing or committing.
2. Inspect `git status` and review both staged and unstaged diffs before deciding what belongs in the commit.
3. Do not include unrelated or pre-existing changes. If the intended files are unclear, ask before staging.
4. Check the relevant tests or validation commands for the changes. Do not claim checks passed unless they ran successfully.
5. Choose a type that describes the change:
   - `feat`: user-facing functionality
   - `fix`: a bug fix
   - `docs`: documentation only
   - `test`: tests only
   - `refactor`: behavior-preserving code change
   - `build`: build system or dependency changes
   - `ci`: continuous integration changes
   - `chore`: maintenance that does not fit another type
6. Use the format:

   ```text
   <type>[optional scope][!]: <imperative summary>

   [Optional body explaining context or motivation]

   [Optional footers, including BREAKING CHANGE: ... when applicable]
   ```

7. Keep the summary concise, specific, and in the repository's usual language. Use a scope only when it adds useful context. Mark breaking changes with `!` and explain them in a `BREAKING CHANGE:` footer.
8. Stage only the intended files, inspect the staged diff, then create the Conventional Commit. Include repository-required commit trailers.
9. After committing, verify the commit and report its hash, subject, and any validation results. For the end-to-end workflow, continue to `commit-push-pr`; do not leave changes committed but unpushed or skip PR creation and worktree cleanup.

Never amend commits, rewrite history, or include unrelated files. This skill defines commit formatting only; use `commit-push-pr` for the standard push, PR, and worktree-cleanup steps.
