#!/usr/bin/env bash
# Build debug APK, commit em releases/, push GitHub e cria release com link estável.
# Uso (agent ou dev, após o usuário confirmar "Sim"):
#   ./scripts/ship-apk.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

GITHUB_REPO="${GITHUB_REPO:-henriquesantosneto/Onde-Gallery-Sync}"
APK_NAME="OneGallerySync-debug.apk"
RELEASES_APK="$ROOT/releases/$APK_NAME"

version_name="$(grep 'versionName' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
version_code="$(grep 'versionCode' app/build.gradle.kts | head -1 | sed -E 's/[^0-9]*([0-9]+).*/\1/')"
tag="v${version_name}-debug"

echo "==> Build $version_name ($version_code)"
./gradlew :app:assembleDebug --quiet

mkdir -p releases
cp -f app/build/outputs/apk/debug/app-debug.apk "$RELEASES_APK"

APKSIGNER="${ANDROID_HOME:-$HOME/android-sdk}/build-tools/34.0.0/apksigner"
if [[ -x "$APKSIGNER" ]]; then
  "$APKSIGNER" verify --min-sdk-version 26 "$RELEASES_APK" >/dev/null
fi

cat > releases/LEIA-ME.txt <<EOF
OneGallery Sync — APK $version_name (versionCode $version_code)
=============================================

Download:

  https://github.com/$GITHUB_REPO/releases/download/$tag/$APK_NAME

Hash Azure (Assinatura debug deste build): pH93hJ0fsCAwFF5NxVPK0Snx6Ws=

Se a instalação falhar ("pacote inválido" / "app não instalado"):
  1. Desinstale qualquer OneGallery Sync antigo (Ajustes → Apps).
  2. Baixe só pelo link GitHub Releases acima (não use HTML do repositório).
  3. Permita instalar apps desconhecidos para Chrome ou "Meus arquivos".
  4. Se você compilou o APK no seu PC antes, a assinatura era outra — desinstale primeiro.
EOF

if ! git diff --quiet -- releases/ app/build.gradle.kts 2>/dev/null || [[ -n "$(git status --porcelain releases/)" ]]; then
  git add releases/
  git commit -m "Release APK debug $version_name" || true
fi

branch="$(git branch --show-current)"
echo "==> Push origin ($branch)"
git push -u origin "$branch"

echo "==> Push GitHub (main + $branch)"
if git remote get-url github >/dev/null 2>&1; then
  git push github "HEAD:refs/heads/$branch"
  git push github HEAD:main
else
  git remote add github "https://github.com/$GITHUB_REPO.git"
  git push github "HEAD:refs/heads/$branch"
  git push github HEAD:main
fi

download_url="https://github.com/$GITHUB_REPO/releases/download/$tag/$APK_NAME"

echo "==> GitHub Release $tag"
if gh release view "$tag" --repo "$GITHUB_REPO" >/dev/null 2>&1; then
  gh release upload "$tag" "$RELEASES_APK" --repo "$GITHUB_REPO" --clobber
else
  gh release create "$tag" "$RELEASES_APK" \
    --repo "$GITHUB_REPO" \
    --title "OneGallery Sync $version_name (debug)" \
    --notes "APK debug build $version_code. Instale por cima da versão anterior."
fi

echo ""
echo "DOWNLOAD_URL=$download_url"
echo "$download_url"
