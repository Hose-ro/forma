#!/bin/sh
set -eu

root=$(git rev-parse --show-toplevel)
# Git shares its default hooks directory across linked worktrees.
hooks_dir=$(git rev-parse --path-format=absolute --git-common-dir)/hooks

if configured_hooks=$(git config --get-all core.hooksPath); then
  local_hooks=$(git config --local --no-includes --get-all core.hooksPath || true)
  if [ "$configured_hooks" != '.githooks' ] || [ "$local_hooks" != "$configured_hooks" ]; then
    printf 'Revisa la configuración de core.hooksPath antes de instalar (git config --show-origin --get-all core.hooksPath).\n' >&2
    exit 1
  fi
fi

mkdir -p "$hooks_dir"

for hook in pre-commit pre-push _gitleaks.sh; do
  destination="$hooks_dir/$hook"
  if [ -L "$destination" ] || { [ -e "$destination" ] &&
      { [ ! -f "$destination" ] || ! cmp -s "$root/.githooks/$hook" "$destination"; }; }; then
    printf 'Se conserva el hook existente en %s. Revísalo antes de volver a instalar.\n' "$destination" >&2
    exit 1
  fi
done

install -m 0644 "$root/.githooks/_gitleaks.sh" "$hooks_dir/_gitleaks.sh"
install -m 0755 "$root/.githooks/pre-commit" "$root/.githooks/pre-push" "$hooks_dir/"

if [ "${local_hooks:-}" = '.githooks' ]; then
  git config --local --unset-all core.hooksPath
fi
if git config --get core.hooksPath >/dev/null; then
  printf 'Git sigue usando una ruta personalizada de hooks en este worktree.\n' >&2
  exit 1
fi
printf 'Hooks instalados en %s\n' "$hooks_dir"
