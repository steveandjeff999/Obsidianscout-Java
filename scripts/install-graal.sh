#!/bin/sh
set -e

echo "========================================================================="
echo " [ObsidianScout] Installing GraalVM JDK 21 (Native Image Support) "
echo "========================================================================="

UNAME_S=$(uname -s | tr '[:upper:]' '[:lower:]')
UNAME_M=$(uname -m)

case "$UNAME_S" in
    linux*)  OS="linux" ;;
    darwin*) OS="macos" ;;
    *)       echo "Unsupported OS: $UNAME_S"; exit 1 ;;
esac

case "$UNAME_M" in
    x86_64|amd64)  ARCH="x64" ;;
    aarch64|arm64) ARCH="aarch64" ;;
    *)             echo "Unsupported architecture: $UNAME_M"; exit 1 ;;
esac

INSTALL_DIR="$HOME/.graalvm/graalvm-jdk-21"
URL="https://download.oracle.com/graalvm/21/latest/graalvm-jdk-21_${OS}-${ARCH}_bin.tar.gz"

if [ -f "$INSTALL_DIR/bin/native-image" ]; then
    echo "[GraalVM] GraalVM JDK 21 is already installed at $INSTALL_DIR"
else
    echo "[1/3] Downloading GraalVM JDK 21 for ${OS}-${ARCH}..."
    TMP_TAR=$(mktemp)
    curl -sSL "$URL" -o "$TMP_TAR"

    echo "[2/3] Extracting to $INSTALL_DIR..."
    mkdir -p "$HOME/.graalvm"
    TMP_DIR=$(mktemp -d)
    tar -xzf "$TMP_TAR" -C "$TMP_DIR"
    EXTRACTED_SUBDIR=$(find "$TMP_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    rm -rf "$INSTALL_DIR"
    mv "$EXTRACTED_SUBDIR" "$INSTALL_DIR"
    rm -f "$TMP_TAR"
    rm -rf "$TMP_DIR"
    echo "[GraalVM] Extraction complete!"
fi

echo "[3/3] Setting GRAALVM_HOME and PATH in shell configuration..."
export GRAALVM_HOME="$INSTALL_DIR"
export PATH="$INSTALL_DIR/bin:$PATH"

for PROFILE in "$HOME/.bashrc" "$HOME/.zshrc" "$HOME/.profile"; do
    if [ -f "$PROFILE" ] && ! grep -q "GRAALVM_HOME" "$PROFILE"; then
        echo "" >> "$PROFILE"
        echo "# GraalVM JDK 21" >> "$PROFILE"
        echo "export GRAALVM_HOME=\"$INSTALL_DIR\"" >> "$PROFILE"
        echo "export PATH=\"\$GRAALVM_HOME/bin:\$PATH\"" >> "$PROFILE"
        echo "Added GRAALVM_HOME to $PROFILE"
    fi
done

echo "========================================================================="
echo " GRAALVM INSTALLATION COMPLETE!"
echo " GRAALVM_HOME: $INSTALL_DIR"
echo "========================================================================="
"$INSTALL_DIR/bin/native-image" --version
