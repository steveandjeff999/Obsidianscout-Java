#!/usr/bin/env bash
# Linux half of scripts/release-local.ps1 - runs inside WSL as root.
# Mirrors the Linux jobs of .github/workflows/release.yml: each target is built from a
# fresh clone of the release tag inside a GraalVM JDK 25 container (arm64 via QEMU),
# then packaged as .tar.gz / .zip + .rpm with the same names CI uses.
#
# Usage: release-linux.sh <repo-path> <tag> <version> <out-dir> [targets]
#   targets: comma list of linux-x86_64,linux-arm64,fatjar (default: all three)
set -euo pipefail

REPO="$1"; TAG="$2"; VER="$3"; OUT="$4"
TARGETS="${5:-linux-x86_64,linux-arm64,fatjar}"

WORK="/var/tmp/obsidianscout-release/$VER"
DIST="$WORK/dist"
IMAGE_ORACLE="container-registry.oracle.com/graalvm/native-image:25"
IMAGE_COMMUNITY="ghcr.io/graalvm/native-image-community:25"

want() { [[ ",$TARGETS," == *",$1,"* ]]; }
log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

install_deps() {
    local missing=()
    if command -v dnf >/dev/null 2>&1; then
        local pkgs=(podman rpm-build zip git)
        want linux-arm64 && pkgs+=(qemu-user-static-aarch64)
        for p in "${pkgs[@]}"; do rpm -q "$p" >/dev/null 2>&1 || missing+=("$p"); done
        if ((${#missing[@]})); then
            log "Installing build dependencies: ${missing[*]}"
            dnf install -y "${missing[@]}"
        fi
    elif command -v apt-get >/dev/null 2>&1; then
        local pkgs=(podman rpm zip git)
        want linux-arm64 && pkgs+=(qemu-user-static binfmt-support)
        for p in "${pkgs[@]}"; do dpkg -s "$p" >/dev/null 2>&1 || missing+=("$p"); done
        if ((${#missing[@]})); then
            log "Installing build dependencies: ${missing[*]}"
            apt-get update
            DEBIAN_FRONTEND=noninteractive apt-get install -y "${missing[@]}"
        fi
    else
        die "Unsupported WSL distro (need dnf or apt-get)."
    fi
}

# Register only the aarch64 handler. Restarting systemd-binfmt would unregister
# WSLInterop as well, breaking Windows .exe launching from this distro.
setup_binfmt() {
    [[ -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ]] && return 0
    log "Registering QEMU aarch64 emulation (binfmt_misc)"
    local conf
    conf=$(ls /usr/lib/binfmt.d/qemu-aarch64-static.conf /usr/lib/binfmt.d/qemu-aarch64.conf 2>/dev/null | head -n 1 || true)
    if [[ -n "$conf" ]]; then
        /usr/lib/systemd/systemd-binfmt "$conf"
    elif command -v update-binfmts >/dev/null 2>&1; then
        update-binfmts --enable qemu-aarch64
    fi
    [[ -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ]] || die "Could not register QEMU aarch64 binfmt handler."
}

# Builds (once) a GraalVM 25 image with findutils added - gradlew needs xargs,
# which the slim Oracle Linux base images do not ship. Sets $BUILDER.
ensure_builder() {
    local platform=$1 arch=$2
    BUILDER="localhost/obsidianscout-builder:25-$arch"
    podman image exists "$BUILDER" && return 0

    log "Preparing GraalVM 25 build image for $platform"
    local base=$IMAGE_ORACLE
    if ! podman pull --platform "$platform" "$base"; then
        echo "Oracle GraalVM image unavailable, falling back to GraalVM Community."
        base=$IMAGE_COMMUNITY
        podman pull --platform "$platform" "$base"
    fi
    local ctx
    ctx=$(mktemp -d)
    printf 'FROM %s\nRUN microdnf install -y findutils && microdnf clean all\nENTRYPOINT []\nCMD ["bash"]\n' "$base" > "$ctx/Containerfile"
    podman build --platform "$platform" --network host -t "$BUILDER" "$ctx"
    rm -rf "$ctx"
}

# gradle_build <name> <podman-platform> <arch> <gradle args...>
# Fresh LF checkout of the tag per target (like actions/checkout), separate Gradle
# home per arch so the build cache can never hand an x86 binary to the arm build.
gradle_build() {
    local name=$1 platform=$2 arch=$3; shift 3
    SRC="$WORK/$name/src"
    rm -rf "$WORK/$name"
    mkdir -p "$WORK/$name"
    git -c safe.directory='*' clone --quiet --depth 1 --branch "$TAG" "file://$REPO" "$SRC"
    ensure_builder "$platform" "$arch"
    podman run --rm --platform "$platform" --network host \
        -v "$SRC:/work" -v "obsidianscout-gradle-$arch:/root/.gradle" -w /work \
        "$BUILDER" bash -c "
            export JAVA_HOME=\${JAVA_HOME:-\$(dirname \$(dirname \$(readlink -f \$(command -v java))))}
            export GRAALVM_HOME=\${GRAALVM_HOME:-\$JAVA_HOME}
            chmod +x gradlew && ./gradlew $* --no-daemon --console=plain"
}

package_tar() { # <bundle-dir> <arch-key>
    local name="obsidianscout-v${VER}-$2.tar.gz"
    echo "Compressing $1 to $name..."
    tar -czf "$DIST/$name" -C "$1" .
}

package_zip() { # <bundle-dir> <arch-key>
    local name="obsidianscout-v${VER}-$2.zip"
    echo "Zipping $1 to $name..."
    rm -f "$DIST/$name"
    (cd "$1" && zip -qr -X "$DIST/$name" .)
}

package_rpm() { # <bundle-dir> <arch-key>  - identical spec to release.yml
    local BUNDLE_DIR=$1 ARCH_KEY=$2
    local RPM_VER="${VER//-/_}"
    RPM_VER="${RPM_VER:-0.1.0}"

    local RPM_ARCH="x86_64"
    if [ "$ARCH_KEY" = "linux-arm64" ]; then
        RPM_ARCH="aarch64"
    elif [ "$ARCH_KEY" = "fatjar" ]; then
        RPM_ARCH="noarch"
    fi

    local RPM_ROOT="$WORK/rpm-$ARCH_KEY"
    rm -rf "$RPM_ROOT"
    mkdir -p "$RPM_ROOT/BUILD" "$RPM_ROOT/RPMS" "$RPM_ROOT/SOURCES" "$RPM_ROOT/SPECS" "$RPM_ROOT/SRPMS"

    cat << EOF > "$RPM_ROOT/SPECS/obsidianscout-server.spec"
%global _enable_debug_package 0
%define debug_package %{nil}
%define _build_id_links none
%define _binary_payload w19.zstdio

Name:           obsidianscout-server
Version:        ${RPM_VER}
Release:        1
Summary:        ObsidianScout High-Performance Scouting Server
License:        Proprietary
BuildArch:      ${RPM_ARCH}
AutoReqProv:    no

%description
ObsidianScout High-Performance Scouting Server

%prep

%build

%install
mkdir -p %{buildroot}/opt/obsidianscout-server
mkdir -p %{buildroot}/usr/bin
mkdir -p %{buildroot}/usr/lib/systemd/system

cp -r "${BUNDLE_DIR}"/* %{buildroot}/opt/obsidianscout-server/
chmod +x %{buildroot}/opt/obsidianscout-server/*.sh 2>/dev/null || true
chmod +x %{buildroot}/opt/obsidianscout-server/obsidianscout-server-native* 2>/dev/null || true

cat << 'SH_EOF' > %{buildroot}/usr/bin/obsidianscout-server
#!/bin/sh
cd /opt/obsidianscout-server && exec ./run.sh "\$@"
SH_EOF
chmod +x %{buildroot}/usr/bin/obsidianscout-server

cat << 'SVC_EOF' > %{buildroot}/usr/lib/systemd/system/obsidianscout-server.service
[Unit]
Description=ObsidianScout Scouting Server
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/obsidianscout-server
ExecStart=/opt/obsidianscout-server/run.sh
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
SVC_EOF

%files
/opt/obsidianscout-server
/usr/bin/obsidianscout-server
/usr/lib/systemd/system/obsidianscout-server.service

EOF

    rpmbuild --define "_topdir $RPM_ROOT" --target "${RPM_ARCH}" -bb "$RPM_ROOT/SPECS/obsidianscout-server.spec"
    local RPM_FILE
    RPM_FILE=$(ls "$RPM_ROOT"/RPMS/*/*.rpm 2>/dev/null | head -n 1 || true)
    [[ -n "$RPM_FILE" && -f "$RPM_FILE" ]] || die "rpmbuild produced no RPM for $ARCH_KEY"
    cp "$RPM_FILE" "$DIST/obsidianscout-v${VER}-${ARCH_KEY}.rpm"
    echo "Successfully generated RPM: obsidianscout-v${VER}-${ARCH_KEY}.rpm"
}

publish() { # copy finished assets for one target to the Windows output folder
    mkdir -p "$OUT"
    cp -f "$DIST"/obsidianscout-v"${VER}"-"$1".* "$OUT"/
}

require_dir() { [[ -d "$1" ]] || die "Bundle directory not found: $1"; }

# ---------------------------------------------------------------------------

[[ -d "$REPO/.git" ]] || die "Not a git repository: $REPO"
mkdir -p "$DIST"
install_deps
want linux-arm64 && setup_binfmt

if want linux-x86_64; then
    log "[linux-x86_64] Native build"
    gradle_build linux-x86_64 linux/amd64 x86_64 publishnative -PwithNativeImage -PskipBump
    BUNDLE="$SRC/build/bundle-native/obsidianscout-v${VER}-x86_64"
    require_dir "$BUNDLE"
    package_tar "$BUNDLE" linux-x86_64
    package_rpm "$BUNDLE" linux-x86_64
    publish linux-x86_64
fi

if want fatjar; then
    log "[fatjar] Cross-platform fat-JAR build"
    gradle_build fatjar linux/amd64 x86_64 publish -PskipBump
    BUNDLE="$SRC/build/bundle"
    require_dir "$BUNDLE"
    package_zip "$BUNDLE" fatjar
    package_rpm "$BUNDLE" fatjar
    publish fatjar
fi

if want linux-arm64; then
    log "[linux-arm64] Native build under QEMU emulation - this is slow (can take 1-3 hours)"
    gradle_build linux-arm64 linux/arm64 arm64 publishnative -PwithNativeImage -PskipBump
    BUNDLE="$SRC/build/bundle-native/obsidianscout-v${VER}-arm64"
    require_dir "$BUNDLE"
    package_tar "$BUNDLE" linux-arm64
    package_rpm "$BUNDLE" linux-arm64
    publish linux-arm64
fi

log "Linux targets complete"
