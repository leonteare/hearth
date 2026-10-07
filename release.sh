#!/usr/bin/env bash
# Publish a new version of Hearth to GitHub Releases. Both phones pick it up from Settings / the Home banner.
#
#   ./release.sh "What changed"            # 1.2.3 -> 1.2.4
#   ./release.sh "What changed" minor      # 1.2.3 -> 1.3.0
#   ./release.sh "What changed" major      # 1.2.3 -> 2.0.0
set -euo pipefail
cd "$(dirname "$0")"

notes="${1:?Usage: ./release.sh \"What changed\" [patch|minor|major]}"
bump="${2:-patch}"

if [ -n "$(git status --porcelain)" ]; then
    echo "Commit or stash your changes first." >&2
    exit 1
fi

code=$(grep '^versionCode=' version.properties | cut -d= -f2)
name=$(grep '^versionName=' version.properties | cut -d= -f2)
IFS=. read -r major minor patch <<< "$name"
case "$bump" in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
    *) echo "Bump must be patch, minor or major" >&2; exit 1 ;;
esac
new="$major.$minor.$patch"
code=$((code + 1))
printf 'versionCode=%s\nversionName=%s\n' "$code" "$new" > version.properties
echo "Releasing Hearth $new (build $code)"

export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
./gradlew --console=plain -q testDebugUnitTest assembleRelease

mkdir -p dist
apk="dist/Hearth-$new.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"

git add version.properties
git commit -q -m "Release $new"
git tag "v$new"
git push -q origin HEAD "v$new"
gh release create "v$new" "$apk" --title "Hearth $new" --notes "$notes"
echo "Done: Hearth $new is live."
