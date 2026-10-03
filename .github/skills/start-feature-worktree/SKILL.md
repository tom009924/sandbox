---
name: start-feature-worktree
description: Create an isolated feature branch worktree before editing files for a repository task.
---

# Start Work in a Feature Worktree

Use this skill at the start of any repository task that will change files. Establish the worktree before editing, generating, or formatting project files, then keep all task changes in that worktree through validation and delivery.

## Safety rules

- Never edit, commit, or push task changes from the default-branch worktree.
- Preserve all existing worktree changes. Do not stash, discard, reset, or transfer them implicitly.
- Never reuse or overwrite an existing branch or worktree.
- Do not push, commit, or create a pull request unless the user requests those actions or a subsequent workflow requires them.

## Workflow

1. Inspect the repository root, remotes, current branch, worktree status, and existing worktrees. Determine the default branch from `refs/remotes/origin/HEAD`; if it cannot be identified, ask the user rather than assuming its name.
2. Inspect staged and unstaged changes in the current worktree. If the requested changes exist only as uncommitted changes in the default-branch worktree, stop and ask the user how to proceed. Do not transfer them implicitly.
3. Fetch the latest default branch. If fetching fails, stop and report the error; do not create a worktree from stale data.
4. Choose a short, descriptive branch name appropriate to the task. Confirm that the branch name and proposed sibling worktree path do not already exist. If either exists, choose a fresh name or ask the user; never reuse or overwrite them.
5. Create a sibling worktree from the fetched remote default branch:

   ```bash
   git worktree add -b <branch-name> <sibling-worktree-path> origin/<default-branch>
   ```

6. Verify that the worktree is on the new branch, based on the latest fetched default branch, and clean. Report the branch and worktree path, then perform all task edits, validation, and requested delivery steps there.

If there is no remote, the default branch cannot be identified safely, fetching fails, or an isolated worktree cannot be created without disturbing existing work, stop and ask the user how to proceed.
