#!/usr/bin/env bash
# Envia main para o GitHub do Henrique (requer GITHUB_TOKEN no ambiente).
set -euo pipefail
REPO="${1:-https://github.com/henriquesantosneto/Onde-Gallery-Sync.git}"
if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  echo "Defina GITHUB_TOKEN (fine-grained PAT com Contents: write neste repo)."
  exit 1
fi
git remote remove github 2>/dev/null || true
git remote add github "https://github.com/henriquesantosneto/Onde-Gallery-Sync.git"
git push "https://x-access-token:${GITHUB_TOKEN}@github.com/henriquesantosneto/Onde-Gallery-Sync.git" HEAD:main
echo ""
echo "APK:"
echo "https://github.com/henriquesantosneto/Onde-Gallery-Sync/raw/main/releases/OneGallerySync-debug.apk"
