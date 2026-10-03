#!/usr/bin/env bash
set -e

# デフォルトブランチを自動判定
default_branch=""
if git rev-parse --verify --quiet refs/remotes/origin/HEAD >/dev/null; then
  default_branch="$(git symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##')"
fi

if [ -z "$default_branch" ]; then
  default_branch="$(git remote show origin 2>/dev/null | sed -n 's/.*HEAD branch: //p' || true)"
fi

# remote の HEAD が取れない場合は main を想定
if [ -z "$default_branch" ]; then
  default_branch="main"
fi

echo "Default branch: $default_branch"

# pre-push では stdin から push 予定の refs を受け取る
while read -r local_ref local_sha remote_ref remote_sha; do
  # 空行は無視
  [ -n "$remote_ref" ] || continue

  # 例: refs/heads/main
  if [[ "$remote_ref" == refs/heads/* ]]; then
    target_branch="${remote_ref#refs/heads/}"

    if [ "$target_branch" = "$default_branch" ]; then
      echo
      echo "ERROR: '$target_branch' はデフォルトブランチです。"
      echo "プッシュを中止しました。別ブランチで作業してください。"
      echo
      exit 1
    fi
  fi
done

exit 0
