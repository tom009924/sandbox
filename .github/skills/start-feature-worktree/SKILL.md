---
name: start-feature-worktree
description: Start repository changes in a dedicated feature worktree and hand off to the commit, push, PR, and cleanup workflow.
---

# Start Work in a Feature Worktree

Use this skill at the start of any repository task that will change files. Establish the worktree before editing, generating, or formatting project files, then keep all task changes in that worktree through validation and delivery. At completion, follow the `commit-push-pr` skill to create a Conventional Commit, push the feature branch, open a pull request, verify it, and remove the worktree.

## Safety rules

- Never edit, commit, or push task changes from the default-branch worktree.
- Preserve all existing worktree changes. Do not stash, discard, reset, or transfer them implicitly.
- Never reuse or overwrite an existing branch or worktree.
- Keep the feature worktree until its changes have been committed and pushed and the pull request has been created and verified.
- Remove only the task's feature worktree after successful PR verification. Do not delete the feature branch or any other worktree.
- If validation, commit, push, PR creation, or verification fails, preserve the worktree and branch so the task can be resumed.

## Workflow

1. Inspect the repository root, remotes, current branch, worktree status, and existing worktrees. Determine the default branch from `refs/remotes/origin/HEAD`; if it cannot be identified, ask the user rather than assuming its name.
2. Inspect staged and unstaged changes in the current worktree. If the requested changes exist only as uncommitted changes in the default-branch worktree, stop and ask the user how to proceed. Do not transfer them implicitly.
3. Fetch the latest default branch. If fetching fails, stop and report the error; do not create a worktree from stale data.
4. Choose a short, descriptive branch name appropriate to the task. Confirm that the branch name and proposed sibling worktree path do not already exist. If either exists, choose a fresh name or ask the user; never reuse or overwrite them.
5. Create a sibling worktree from the fetched remote default branch:

   ```bash
   git worktree add -b <branch-name> <sibling-worktree-path> origin/<default-branch>
   ```

6. Verify that the worktree is on the new branch, based on the latest fetched default branch, and clean. Report the branch and worktree path, then perform all task edits and validation there.
7. When the task is complete, follow `commit-push-pr` from that worktree. Do not return to the default-branch worktree to finish or deliver the changes.

If there is no remote, the default branch cannot be identified safely, fetching fails, or an isolated worktree cannot be created without disturbing existing work, stop and ask the user how to proceed.
