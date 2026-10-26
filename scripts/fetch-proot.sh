#!/usr/bin/env bash
# Fetch pinned PRoot + libtalloc binaries from the Termux package repository
# (verified upstream artifacts for Android ABIs) and lay them out as Android
# native libraries: app/src/main/jniLibs/<abi>/libproot.so + libtalloc.so
#
# Why Termux artifacts: PRoot must be an Android/bionic ELF. Building proot
# from source with the NDK is possible but reproducible pinned debs from the
# Termux repo are faster and field-proven by millions of devices.
#
# Exact .deb locations are resolved from the architecture-specific Debian
# Packages index (dists/stable/main/binary-<arch>/Packages) — the pool layout
# changed over time (t/talloc -> libt/libtalloc), so directory scraping is
# fragile while the index is the authoritative mapping.
#
# Usage: fetch-proot.sh <aarch64|x86_64> [outdir]
set -euo pipefail

ARCH="${1:?usage: fetch-proot.sh <aarch64|x86_64> [outdir]}"
OUTDIR="${2:-app/src/main/jniLibs}"
TERMUX_REPO="https://packages.termux.dev/apt/termux-main"

tempdir="$(mktemp -d)"
trap 'rm -rf "$tempdir"' EXIT

case "$ARCH" in
  aarch64) ABI="arm64-v8a" ;;
  x86_64)  ABI="x86_64" ;;
  *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac

INDEX_URL="$TERMUX_REPO/dists/stable/main/binary-$ARCH/Packages"
echo "==> reading package index: $INDEX_URL"
INDEX="$(curl -fsSL "$INDEX_URL")"

# Print "<version> <filename>" for the newest stanza of a package.
deb_path() {
  local pkg="$1"
  echo "$INDEX" | awk -v pkg="$pkg" '
    BEGIN { RS=""; FS="\n" }
    {
      ispkg = 0; ver = ""; file = ""
      for (i = 1; i <= NF; i++) {
        if ($i == "Package: " pkg) ispkg = 1
        if ($i ~ /^Version: /)  ver  = substr($i, 10)
        if ($i ~ /^Filename: /) file = substr($i, 11)
      }
      if (ispkg && file != "") print ver, file
    }' | sort -V | tail -1 | cut -d' ' -f2
}

PROOT_DEB_PATH="$(deb_path proot)"
TALLOC_DEB_PATH="$(deb_path libtalloc)"
[ -n "$PROOT_DEB_PATH" ]  || { echo "ERROR: proot not found in $INDEX_URL" >&2; exit 1; }
[ -n "$TALLOC_DEB_PATH" ] || { echo "ERROR: libtalloc not found in $INDEX_URL" >&2; exit 1; }
echo "==> proot:  $PROOT_DEB_PATH"
echo "==> talloc: $TALLOC_DEB_PATH"

curl -fsSL -o "$tempdir/proot.deb"  "$TERMUX_REPO/$PROOT_DEB_PATH"
curl -fsSL -o "$tempdir/talloc.deb" "$TERMUX_REPO/$TALLOC_DEB_PATH"

dpkg-deb -x "$tempdir/proot.deb"  "$tempdir/proot-x"
dpkg-deb -x "$tempdir/talloc.deb" "$tempdir/talloc-x"

PROOT_BIN="$tempdir/proot-x/data/data/com.termux/files/usr/bin/proot"
TALLOC_LIB="$tempdir/talloc-x/data/data/com.termux/files/usr/lib/libtalloc.so.2"

[ -f "$PROOT_BIN" ]  || { echo "proot binary not found in deb" >&2; exit 1; }
[ -f "$TALLOC_LIB" ] || { echo "libtalloc.so.2 not found in deb" >&2; exit 1; }

DEST="$OUTDIR/$ABI"
mkdir -p "$DEST"
cp "$PROOT_BIN"  "$DEST/libproot.so"
cp "$TALLOC_LIB" "$DEST/libtalloc.so"

# License notices for the bundled binaries are COMMITTED in scripts/licenses/
# (PROOT_COPYRIGHT, TALLOC_COPYRIGHT, GPL-2.0.txt, GPL-3.0.txt, LGPL-3.0.txt).
# Note: the usr/share/doc/<pkg>/copyright entries inside the Termux .debs are
# dangling symlinks into the separate termux-licenses package, so copying them
# at build time is unreliable — hence the committed copies.

sha256sum "$DEST/libproot.so" "$DEST/libtalloc.so"
echo "==> installed to $DEST"
