#!/usr/bin/env bash
# Fetch pinned PRoot + libtalloc binaries from the Termux package repository
# (verified upstream artifacts for Android ABIs) and lay them out as Android
# native libraries: app/src/main/jniLibs/<abi>/libproot.so + libtalloc.so
#
# Why Termux artifacts: PRoot must be an Android/bionic ELF. Building proot
# from source with the NDK is possible but reproducible pinned debs from the
# Termux repo are faster and field-proven by millions of devices.
#
# Usage: fetch-proot.sh <aarch64|x86_64> [outdir]
set -euo pipefail

ARCH="${1:?usage: fetch-proot.sh <aarch64|x86_64> [outdir]}"
OUTDIR="${2:-app/src/main/jniLibs}"
TERMUX_REPO="https://packages.termux.dev/apt/termux-main"

tempdir="$(mktemp -d)"
trap 'rm -rf "$tempdir"' EXIT

fetch_latest_deb() {
  local dir_url="$1" prefix="$2"
  local listing target
  listing="$(curl -fsSL "$dir_url")"
  # Directory index is HTML: pick the highest version of matching .deb files
  target="$(echo "$listing" | grep -oE "${prefix}_[0-9][^\"<>]*_all\.deb" | sort -V | tail -1)"
  if [ -z "$target" ]; then
    target="$(echo "$listing" | grep -oE "${prefix}_[0-9][^\"<>]*\.deb" | sort -V | tail -1)"
  fi
  [ -n "$target" ] || { echo "ERROR: no $prefix package found in $dir_url" >&2; exit 1; }
  echo "$target"
}

case "$ARCH" in
  aarch64) ABI="arm64-v8a" ;;
  x86_64)  ABI="x86_64" ;;
  *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac

PROOT_DIR="$TERMUX_REPO/pool/main/p/proot"
TALLOC_DIR="$TERMUX_REPO/pool/main/t/talloc"

PROOT_DEB="$(fetch_latest_deb "$PROOT_DIR" "proot")"
TALLOC_DEB="$(fetch_latest_deb "$TALLOC_DIR" "libtalloc")"

echo "==> proot:  $PROOT_DEB"
echo "==> talloc: $TALLOC_DEB"

curl -fsSL -o "$tempdir/proot.deb"   "$PROOT_DIR/$PROOT_DEB"
curl -fsSL -o "$tempdir/talloc.deb"  "$TALLOC_DIR/$TALLOC_DEB"

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

# PRoot is GPL-2.0-or-later: keep the license next to the binaries.
mkdir -p scripts/licenses
cp "$tempdir/proot-x/data/data/com.termux/files/usr/share/doc/proot/copyright" \
   "scripts/licenses/PROOT_COPYRIGHT" 2>/dev/null || true
cp "$tempdir/talloc-x/data/data/com.termux/files/usr/share/doc/libtalloc*/copyright" \
   "scripts/licenses/TALLOC_COPYRIGHT" 2>/dev/null || true

sha256sum "$DEST/libproot.so" "$DEST/libtalloc.so"
echo "==> installed to $DEST"
