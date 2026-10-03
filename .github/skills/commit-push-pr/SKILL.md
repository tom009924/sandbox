---
name: commit-push-pr
description: Commit requested changes on a feature branch, push it, and open a pull request without modifying the default branch.
---

# Commit, Push, and Open a Pull Request

Use this skill when explicitly asked to commit changes, push the feature branch, and create a pull request.

## Safety rules

- Never commit or push changes directly to the default branch.
- Never reset, amend, rebase, or force-push the default branch or an existing feature branch.
- Preserve pre-existing worktree changes. Stage and commit only files in scope for the user's request.
- Do not merge the pull request or change branch-protection/ruleset settings.
- Do not include secrets, credentials, or unrelated files in commits or pull-request text.
- If the requested changes exist only as uncommitted changes in the default-branch worktree, do not discard, stash, or transfer them implicitly. Ask the user how to proceed.

## Workflow

1. Inspect the repository root, remotes, current branch, default branch, status, and staged/unstaged diffs. Determine the default branch from `refs/remotes/origin/HEAD`; if it cannot be identified, ask the user rather than assuming a branch name.
2. Fetch the default branch. If fetching fails, stop before creating a branch or worktree and report the error.
3. Confirm the requested changes are available in the intended worktree and that there are no conflicting pre-existing changes. If a clean feature worktree is needed, ensure the proposed branch name and sibling path do not already exist, then create it from the fetched `origin/<default-branch>`. Never overwrite or reuse an existing branch/worktree.
4. Implement or prepare only the requested changes in the feature worktree. Run relevant validation and report environmental failures accurately.
5. Review the complete status and diff. Stage only intended files, inspect the staged diff, and create a Conventional Commit. Include any repository-required commit trailers. Verify the resulting commit; do not amend it.
6. Push only the feature branch, without force:

   ```bash
   git push -u origin <branch-name>
   ```

   If the push fails, preserve the local commit and report the exact retry command. Do not use force-push to work around a rejection.
7. Before opening a pull request, check whether one already exists for the branch. If it does, report its URL and do not create a duplicate.
8. Create a pull request targeting the detected default branch. Use the repository's available authenticated GitHub CLI or approved GitHub integration. Provide a concise title and a body summarizing the change and listing the checks run. Do not claim checks passed unless they did. If pull-request creation is unavailable or authentication fails, preserve the pushed branch and provide the blocker and a retry command, such as:

   ```bash
   gh pr create --base <default-branch> --head <branch-name> --title "<title>" --body "<summary and checks>"
   ```

9. Verify the pull-request URL and head/base branches. Keep the feature worktree and branch available unless the user explicitly asks to remove them.

If there is no remote, the default branch cannot be identified safely, or the requested changes cannot be carried into a safe feature worktree without disturbing existing work, stop and ask the user.
