#!/usr/bin/env bash
# Build a Debian 12 (bookworm) userspace tarball for CrabCode Mobile.
# Runs inside GitHub Actions on a runner of the matching architecture
# (arm64 runner for aarch64, x86_64 runner for x86_64) — the rootfs must
# match the device CPU because PRoot executes the binaries natively.
#
# Usage: build-rootfs.sh <aarch64|x86_64> <output.tar.gz>
set -euo pipefail

ARCH="${1:?usage: build-rootfs.sh <aarch64|x86_64> <output.tar.gz>}"
OUT="${2:?usage: build-rootfs.sh <aarch64|x86_64> <output.tar.gz>}"

NAME="crab-rootfs-$(date +%Y%m%d)"
CONTAINER="crab-rootfs-$NAME-$$"

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "==> preparing Debian bookworm container for $ARCH"
docker pull --platform "linux/${ARCH}" debian:bookworm

# Install the toolchain users get out of the box and strip the weight in ONE
# docker run — the container stops when the script ends, and `docker export`
# works on stopped containers (docker exec does not, hence the merge).
docker run --platform "linux/${ARCH}" --name "$CONTAINER" debian:bookworm \
  bash -euc '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y --no-install-recommends \
      bash bash-completion ca-certificates curl wget git openssh-client \
      python3 python3-pip python3-venv \
      nodejs npm \
      unzip zip jq less vim nano sudo procps file \
      build-essential pkg-config
    apt-get clean
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*
    # crab user experience inside PRoot
    useradd -m -s /bin/bash crab 2>/dev/null || true
    echo "crab:x:1000:1000:Crab:/home/crab:/bin/bash" >> /etc/passwd || true
    mkdir -p /workspace
    # strip documentation/locale weight (~40% smaller tarball)
    rm -rf /usr/share/doc/* /usr/share/man/* /usr/share/locale/* \
           /usr/share/groff/* /usr/share/info/* /usr/share/zoneinfo/* \
           /usr/share/common-licenses/* /var/log/*
    ldconfig 2>/dev/null || true
  '

echo "==> exporting filesystem"
mkdir -p "$(dirname "$OUT")"
docker export "$CONTAINER" | gzip -6 > "$OUT"

sha256sum "$OUT"
ls -lh "$OUT"
echo "==> rootfs ready: $OUT"
