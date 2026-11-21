#!/usr/bin/env bash
# Signs a published release's checksums.txt so servers can verify updates (optional; unsigned releases still install).
#
# One-time setup:
#   java -cp build/libs/obsidianscout-server.jar com.obsidianscout.utils.ReleaseSigner keygen ~/.obsidianscout-release.key
# Keep the key offline and backed up. Paste the printed public key into ReleaseSignature.PINNED_PUBLIC_KEY.
#
# Per release:  scripts/sign-release.sh v0.7.4.9 [key-file] [server-jar]
set -euo pipefail
tag="${1:?usage: sign-release.sh <tag> [key-file] [server-jar]}"
key="${2:-$HOME/.obsidianscout-release.key}"
jar="${3:-$(dirname "$0")/../build/libs/obsidianscout-server.jar}"
[ -f "$key" ] || { echo "Signing key not found at $key" >&2; exit 1; }
[ -f "$jar" ] || { echo "Server jar not found at $jar; run ./gradlew shadowJar" >&2; exit 1; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
gh release download "$tag" --pattern checksums.txt --dir "$work"
java -cp "$jar" com.obsidianscout.utils.ReleaseSigner sign "$key" "$work/checksums.txt"
gh release upload "$tag" "$work/checksums.txt.sig" --clobber
echo "Signed release $tag."
