#!/usr/bin/env bash
# Compose env-manifest.json describing OTA-installable environment artifacts.
# Usage: make-env-manifest.sh <version> <out.json> <rootfs-arm64.tar.gz> <rootfs-x86_64.tar.gz>
set -euo pipefail

VERSION="${1:?version}"
OUT="${2:?out path}"
ROOTFS_ARM64="${3:?arm64 rootfs}"
ROOTFS_X64="${4:?x86_64 rootfs}"

sum() { sha256sum "$1" | cut -d' ' -f1; }
size() { stat -c%s "$1"; }

cat > "$OUT" <<EOF
{
  "manifestVersion": 1,
  "appVersion": "$VERSION",
  "notes": "Debian 12 (bookworm) userspace with python3, node, npm, git, build tools. Installs through PRoot — no real root privileges.",
  "rootfs": {
    "version": "bookworm-$(date +%Y%m%d)",
    "arch": "arm64",
    "file": "$(basename "$ROOTFS_ARM64")",
    "sha256": "$(sum "$ROOTFS_ARM64")",
    "size": $(size "$ROOTFS_ARM64")
  },
  "rootfsX86_64": {
    "version": "bookworm-$(date +%Y%m%d)",
    "arch": "x86_64",
    "file": "$(basename "$ROOTFS_X64")",
    "sha256": "$(sum "$ROOTFS_X64")",
    "size": $(size "$ROOTFS_X64")
  }
}
EOF
cat "$OUT"
