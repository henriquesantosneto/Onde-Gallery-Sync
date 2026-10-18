#!/usr/bin/env bash
# Uso (no seu PC, com GitHub CLI autenticado):
#   gh auth login
#   ./scripts/publish-to-github.sh henriquekako OneGallerySync
set -euo pipefail
OWNER="${1:?owner GitHub, ex: henriquekako}"
REPO="${2:?nome do repo, ex: OneGallerySync}"

if ! gh auth status -h github.com >/dev/null 2>&1; then
  echo "Faça login: gh auth login"
  exit 1
fi

if ! gh repo view "$OWNER/$REPO" >/dev/null 2>&1; then
  gh repo create "$OWNER/$REPO" --public --description "OneGallery Sync — galeria Android + OneDrive" --source=. --remote=github --push
else
  git remote remove github 2>/dev/null || true
  git remote add github "https://github.com/$OWNER/$REPO.git"
  git push -u github main
fi

echo ""
echo "Download do APK (repo público):"
echo "https://github.com/$OWNER/$REPO/raw/main/releases/OneGallerySync-debug.apk"
