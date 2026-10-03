---
name: feature-worktree-push
description: Implement a requested change in a feature-branch Git worktree, validate it, commit it, and push the feature branch without modifying the default branch.
---

# Feature Worktree and Push

Use this skill when asked to implement a change on a feature branch in a separate Git worktree and push it.

## Safety rules

- Never implement, commit, or push changes directly on the repository's default branch.
- Never reset, amend, rebase, or force-push the default branch or an existing feature branch.
- Preserve pre-existing worktree changes. Do not stage or commit files unrelated to the requested change.
- Push only the new feature branch. Do not merge it, push the default branch, or create a pull request unless explicitly requested.
- Do not change GitHub branch-protection or ruleset settings as part of this workflow. The isolated worktree protects the local default branch; server-side protection is a separate repository setting.

## Workflow

1. Inspect the repository root, remotes, current branch, default branch, and worktree status. Determine the default branch from `refs/remotes/origin/HEAD` when available; otherwise ask the user which branch is the default. Do not assume it is named `main` or `master`.
2. Fetch the remote default branch. If fetching fails, report the error and do not create a branch from stale assumptions.
3. Choose a short, descriptive branch name prefixed with `feature/`, `fix/`, or another fitting change type. Confirm that neither the branch nor the proposed sibling worktree path already exists. If either exists, do not reuse or overwrite it; choose a fresh name or ask the user.
4. Create a separate worktree at a sibling path based on the latest `origin/<default-branch>`:

   ```bash
   git worktree add -b <branch-name> <sibling-worktree-path> origin/<default-branch>
   ```

   Do all implementation and validation in that worktree. Leave the original worktree, its current branch, and any existing changes untouched.
5. Implement the requested change using repository conventions. Update directly related documentation and tests where appropriate.
6. Run the smallest relevant checks, then any broader checks needed. If a check fails, fix failures caused by the change and rerun it. Report environmental blockers accurately; never claim an unrun check passed.
7. Review the complete worktree status and diff. Stage only files for the requested change, inspect the staged diff, and create a Conventional Commit. Include repository-required commit trailers.
8. Push the feature branch without force:

   ```bash
   git push -u origin <branch-name>
   ```

   If authentication, network access, or a remote rejection prevents the push, preserve the local branch and commit, explain the blocker, and provide the exact retry command. Never work around a rejection with a force push.
9. Verify the pushed branch and commit, then report the branch name, worktree path, commit hash and subject, checks run, and push result. Keep the feature worktree available unless the user explicitly asks to remove it.

If the repository has no remote, the default branch cannot be identified, or a safe isolated worktree cannot be created, stop and ask the user rather than making changes on the default branch.
