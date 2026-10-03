---
name: commit-push-pr
description: Commit changes from a feature worktree, push the branch, open a pull request, then remove the worktree.
---

# Commit, Push, and Open a Pull Request

Use this skill to finish repository changes made in a dedicated feature worktree. Create a Conventional Commit, push the feature branch, open and verify a pull request, and remove the worktree only after successful PR verification.

## Safety rules

- Never commit or push changes directly to the default branch.
- Never reset, amend, rebase, or force-push the default branch or an existing feature branch.
- Preserve pre-existing worktree changes. Stage and commit only files in scope for the user's request.
- Do not merge the pull request or change branch-protection/ruleset settings.
- Do not include secrets, credentials, or unrelated files in commits or pull-request text.
- If the requested changes exist only as uncommitted changes in the default-branch worktree, do not discard, stash, or transfer them implicitly. Ask the user how to proceed.
- Never remove the worktree before the pushed branch and pull request have both been verified. Preserve it if any delivery step fails.
- Remove only the dedicated task worktree after successful PR verification; do not delete its local or remote branch.

## Workflow

1. Inspect the repository root, remotes, current branch, default branch, status, and staged/unstaged diffs. Determine the default branch from `refs/remotes/origin/HEAD`; if it cannot be identified, ask the user rather than assuming a branch name.
2. Fetch the default branch. If fetching fails, stop before creating a branch or worktree and report the error.
3. Start with the dedicated feature worktree created by `start-feature-worktree`. If it has not been created, follow that skill before editing. Confirm the requested changes are in this worktree and that it contains no conflicting pre-existing changes. If the requested edits exist only in the default-branch worktree, stop and ask the user how to proceed instead of transferring them.
4. Implement or prepare only the requested changes in the feature worktree. Run relevant validation and report environmental failures accurately.
5. Review the complete status and diff. Stage only intended files, inspect the staged diff, and create a Conventional Commit. Include any repository-required commit trailers. Verify the resulting commit; do not amend it.
6. Push only the feature branch, without force:

   ```bash
   git push -u origin <branch-name>
   ```

   If the push fails, preserve the local commit and report the exact retry command. Do not use force-push to work around a rejection.
7. Before opening a pull request, check whether one already exists for the branch. If it does, verify its URL and branch direction and do not create a duplicate.
8. Create a pull request targeting the detected default branch. Use the repository's available authenticated GitHub CLI or approved GitHub integration. Provide a concise title and a body summarizing the change and listing the checks run. Do not claim checks passed unless they did. If pull-request creation is unavailable or authentication fails, preserve the pushed branch and worktree and provide the blocker and a retry command, such as:

   ```bash
   gh pr create --base <default-branch> --head <branch-name> --title "<title>" --body "<summary and checks>"
   ```

9. Verify the pull-request URL, state, head/base branches, and that the worktree is clean and its commit is pushed. Then remove only the dedicated worktree:

   ```bash
   cd <original-repository-worktree>
   git worktree remove <sibling-worktree-path>
   ```

   Do not remove the feature branch. If worktree removal fails, report the error and leave it available; never force removal or discard uncommitted changes.

If there is no remote, the default branch cannot be identified safely, or the requested changes cannot be handled in a safe feature worktree without disturbing existing work, stop and ask the user.
