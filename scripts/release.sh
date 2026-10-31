#!/usr/bin/env bash
# Cuts a release: scripts/release.sh 1.1.0
#
# Moves the "Unreleased" notes in CHANGELOG.md under the new version, bumps version.properties,
# commits, tags v1.1.0 and pushes. GitHub Actions then builds the signed APK and publishes it
# as a GitHub Release with those notes.
set -euo pipefail

version="${1:-}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Usage: scripts/release.sh MAJOR.MINOR.PATCH   (current: $(cut -d= -f2 "$(dirname "$0")/../version.properties"))" >&2
    exit 1
fi
cd "$(dirname "$0")/.."

if [[ -n "$(git status --porcelain)" ]]; then
    echo "You have uncommitted changes. Commit them first." >&2
    exit 1
fi
if git rev-parse -q --verify "refs/tags/v$version" >/dev/null; then
    echo "v$version already exists." >&2
    exit 1
fi
notes="$(awk '/^## \[Unreleased\]/{f=1;next} /^## \[/{f=0} f' CHANGELOG.md | sed '/^[[:space:]]*$/d')"
if [[ -z "$notes" ]]; then
    echo "Add what changed under '## [Unreleased]' in CHANGELOG.md first." >&2
    exit 1
fi

sed -i "s/^versionName=.*/versionName=$version/" version.properties
sed -i "0,/^## \[Unreleased\]/s//## [Unreleased]\n\n## [$version] - $(date +%F)/" CHANGELOG.md
git commit -am "Release $version"
git tag -a "v$version" -m "Leatherbound Listening $version"
git push origin HEAD "v$version"
echo "Pushed v$version. GitHub is building the APK: $(gh repo view --json url -q .url 2>/dev/null)/actions"
