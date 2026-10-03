---
name: feature-worktree-push
description: Implement changes in a feature worktree, validate, commit, push, open a pull request, then remove the worktree.
---

# Feature Worktree, Push, and Pull Request

Use this skill to complete repository changes in a dedicated feature worktree. Start with `start-feature-worktree` before editing, then validate, create a Conventional Commit, push the feature branch, open and verify a pull request, and remove the worktree.

## Safety rules

- Never implement, commit, or push changes directly on the repository's default branch.
- Never reset, amend, rebase, or force-push the default branch or an existing feature branch.
- Preserve pre-existing worktree changes. Do not stage or commit files unrelated to the requested change.
- Push only the new feature branch. Do not merge it or push the default branch.
- Do not change GitHub branch-protection or ruleset settings as part of this workflow. The isolated worktree protects the local default branch; server-side protection is a separate repository setting.
- Do not remove the worktree until the pushed branch and pull request have been verified. Preserve the worktree if any validation or delivery step fails.
- After successful PR verification, remove only the dedicated task worktree; do not delete its local or remote branch.

## Workflow

1. Start with `start-feature-worktree`. If no task worktree exists yet, perform that skill's inspection, fetch, branch/path checks, and worktree creation before editing. Determine the default branch from `refs/remotes/origin/HEAD`; never assume its name.
2. If the requested edits exist only as uncommitted changes in the default-branch worktree, do not transfer them implicitly; ask the user how to proceed. Keep all implementation and validation in the task worktree.
3. Implement the requested change using repository conventions. Update directly related documentation and tests where appropriate.
4. Run the smallest relevant checks, then any broader checks needed. If a check fails, fix failures caused by the change and rerun it. Report environmental blockers accurately; never claim an unrun check passed.
5. Review the complete worktree status and diff. Stage only files for the requested change, inspect the staged diff, and create a Conventional Commit. Include repository-required commit trailers. Verify the resulting commit; do not amend it.
6. Push only the feature branch without force:

   ```bash
   git push -u origin <branch-name>
   ```

   If authentication, network access, or a remote rejection prevents the push, preserve the local branch, commit, and worktree; explain the blocker and provide the exact retry command. Never use force-push to work around a rejection.
7. Check whether a pull request already exists for the branch. If none exists, create one targeting the detected default branch with a concise title and a summary of changes and checks. Do not claim checks passed unless they did. If PR creation fails, preserve the worktree and branch and report an exact retry command.
8. Verify the PR URL, state, head/base branches, and that the worktree is clean and the commit is pushed. Remove only the task worktree:

   ```bash
   cd <original-repository-worktree>
   git worktree remove <sibling-worktree-path>
   ```

   Do not delete the feature branch. If worktree removal fails, report the error and leave it available; never force removal or discard uncommitted changes.
9. Report the branch name, worktree path that was removed, commit hash and subject, checks run, push result, and PR URL.

If the repository has no remote, the default branch cannot be identified, or a safe isolated worktree cannot be created, stop and ask the user rather than making changes on the default branch.
