#!/bin/sh
set -e

# 1. Detect Linux Distribution Family
DETECTED_FAMILY=""
if [ -f /etc/os-release ]; then
    # Source os-release without modifying current environment variables unexpectedly
    ID=$(grep -E '^ID=' /etc/os-release | head -n 1 | cut -d '=' -f 2 | tr -d '"' | tr -d "'")
    ID_LIKE=$(grep -E '^ID_LIKE=' /etc/os-release | head -n 1 | cut -d '=' -f 2 | tr -d '"' | tr -d "'")
    case "$ID $ID_LIKE" in
        *debian*|*ubuntu*|*mint*|*pop*|*raspbian*|*kali*)
            DETECTED_FAMILY="debian"
            ;;
        *fedora*|*rhel*|*centos*|*rocky*|*almalinux*|*suse*|*amzn*|*ol*|*mageia*)
            DETECTED_FAMILY="rpm"
            ;;
    esac
elif [ -f /etc/debian_version ]; then
    DETECTED_FAMILY="debian"
elif [ -f /etc/redhat-release ] || [ -f /etc/fedora-release ] || [ -f /etc/centos-release ] || [ -f /etc/SuSE-release ]; then
    DETECTED_FAMILY="rpm"
fi

# 2. Select native package format (.deb for Debian/Ubuntu, .rpm for Fedora/RHEL/openSUSE)
IS_RPM_PACKAGE=0
IS_DEB_PACKAGE=0

if [ "$1" = "--rpm" ]; then
    IS_RPM_PACKAGE=1
elif [ "$1" = "--deb" ]; then
    IS_DEB_PACKAGE=1
elif [ "$1" = "--tar" ] || [ "$1" = "--portable" ] || [ "$1" = "--bundle" ]; then
    IS_RPM_PACKAGE=0
    IS_DEB_PACKAGE=0
elif [ "$DETECTED_FAMILY" = "debian" ]; then
    IS_DEB_PACKAGE=1
elif [ "$DETECTED_FAMILY" = "rpm" ]; then
    IS_RPM_PACKAGE=1
elif command -v dpkg >/dev/null 2>&1 && (dpkg -s obsidianscout-server >/dev/null 2>&1 || command -v apt-get >/dev/null 2>&1); then
    IS_DEB_PACKAGE=1
elif command -v rpm >/dev/null 2>&1 && (rpm -q obsidianscout-server >/dev/null 2>&1 || command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1 || command -v zypper >/dev/null 2>&1); then
    IS_RPM_PACKAGE=1
fi

if [ "$IS_RPM_PACKAGE" -eq 1 ] || [ "$IS_DEB_PACKAGE" -eq 1 ]; then
    PKG_TYPE="RPM"
    EXT="rpm"
    if [ "$IS_DEB_PACKAGE" -eq 1 ]; then
        PKG_TYPE="DEB"
        EXT="deb"
    fi
    echo "[ObsidianScout Updater] System native package target detected: $PKG_TYPE (.${EXT})"
    echo "[ObsidianScout Updater] Querying latest $PKG_TYPE release from GitHub..."

    ARCH=$(uname -m)
    ARCH_KEY="linux-x86_64"
    case "$ARCH" in
        "aarch64"|"arm64") ARCH_KEY="linux-arm64" ;;
        "x86_64"|"amd64") ARCH_KEY="linux-x86_64" ;;
        *) ARCH_KEY="fatjar" ;;
    esac

    LATEST_JSON=$(curl -sSL -H "Accept: application/vnd.github.v3+json" "https://api.github.com/repos/steveandjeff999/Obsidianscout-Java/releases/latest" 2>/dev/null || true)
    
    DOWNLOAD_URL=""
    if [ -n "$LATEST_JSON" ]; then
        DOWNLOAD_URL=$(echo "$LATEST_JSON" | grep -o "\"browser_download_url\":\s*\"[^\"]*${ARCH_KEY}\.${EXT}\"" | head -n 1 | cut -d '"' -f 4 || true)
        if [ -z "$DOWNLOAD_URL" ]; then
            DOWNLOAD_URL=$(echo "$LATEST_JSON" | grep -o "\"browser_download_url\":\s*\"[^\"]*\.${EXT}\"" | head -n 1 | cut -d '"' -f 4 || true)
        fi
    fi

    if [ -n "$DOWNLOAD_URL" ]; then
        TARGET_FILE="/tmp/obsidianscout-server-update.$EXT"
        echo "[ObsidianScout Updater] Downloading $DOWNLOAD_URL ..."
        curl -sSL "$DOWNLOAD_URL" -o "$TARGET_FILE"

        echo "[ObsidianScout Updater] Upgrading package via native system package manager..."
        if [ "$IS_RPM_PACKAGE" -eq 1 ]; then
            if command -v dnf >/dev/null 2>&1; then
                sudo dnf upgrade -y "$TARGET_FILE" || sudo rpm -Uvh --replacepkgs "$TARGET_FILE"
            elif command -v yum >/dev/null 2>&1; then
                sudo yum localinstall -y "$TARGET_FILE" || sudo rpm -Uvh --replacepkgs "$TARGET_FILE"
            elif command -v zypper >/dev/null 2>&1; then
                sudo zypper install --allow-unsigned-rpm -y "$TARGET_FILE" || sudo rpm -Uvh --replacepkgs "$TARGET_FILE"
            else
                sudo rpm -Uvh --replacepkgs "$TARGET_FILE"
            fi
        else
            if command -v apt-get >/dev/null 2>&1; then
                sudo apt-get install --reinstall -y "$TARGET_FILE" || sudo dpkg -i "$TARGET_FILE"
            else
                sudo dpkg -i "$TARGET_FILE"
            fi
        fi

        rm -f "$TARGET_FILE"
        echo "[ObsidianScout Updater] Native $PKG_TYPE update completed successfully!"
        exit 0
    else
        echo "[ObsidianScout Updater] Direct $PKG_TYPE asset not found, proceeding with bundle updater..."
    fi
fi

HAS_LOCAL_NATIVE=""
for native_bin in ./obsidianscout-server-native*; do
    if [ -x "$native_bin" ] && [ -f "$native_bin" ]; then
        HAS_LOCAL_NATIVE="$native_bin"
        break
    fi
done

if [ -n "$HAS_LOCAL_NATIVE" ]; then
    echo "[ObsidianScout Native] Running native update utility: $HAS_LOCAL_NATIVE --update"
    exec "$HAS_LOCAL_NATIVE" --update "$@"
fi

GRAAL_JAVA="$HOME/.graalvm/graalvm-jdk-21/bin/java"
if [ ! -x "$GRAAL_JAVA" ] && [ -n "$GRAALVM_HOME" ] && [ -x "$GRAALVM_HOME/bin/java" ]; then
    GRAAL_JAVA="$GRAALVM_HOME/bin/java"
fi

if [ ! -x "$GRAAL_JAVA" ]; then
    echo "[ObsidianScout] GraalVM JDK 21 not detected. Auto-installing GraalVM for maximum performance..."
    if [ -x ./install-graal.sh ]; then
        ./install-graal.sh
    elif [ -x ./scripts/install-graal.sh ]; then
        ./scripts/install-graal.sh
    fi
    if [ -x "$HOME/.graalvm/graalvm-jdk-21/bin/java" ]; then
        GRAAL_JAVA="$HOME/.graalvm/graalvm-jdk-21/bin/java"
    fi
fi

if [ -x "$GRAAL_JAVA" ]; then
    JAVA_EXEC="$GRAAL_JAVA"
else
    JAVA_EXEC="java"
fi

# Clear any previous update state
rm -f .update_result

# Run the interactive Java update utility
"$JAVA_EXEC" -cp obsidianscout-server.jar com.obsidianscout.utils.UpdateHelperKt "$@"

# If the helper completed successfully and wrote the path of the new files
if [ -f .update_result ]; then
    SRC_ROOT=$(cat .update_result)
    rm -f .update_result
    
    if [ -d "$SRC_ROOT" ]; then
        echo "Finalizing update (copying new files)..."
        
        # Create backup of current files
        mkdir -p .backup
        if [ -f obsidianscout-server.jar ]; then
            cp obsidianscout-server.jar .backup/
        fi
        for native_bin in obsidianscout-server-native*; do
            if [ -f "$native_bin" ]; then
                cp "$native_bin" .backup/
            fi
        done
        for script in run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat install-graal.sh install-graal.bat install-graal.ps1; do
            if [ -f "$script" ]; then
                cp "$script" .backup/
            fi
        done

        # Copy JAR if present
        if [ -f "$SRC_ROOT/obsidianscout-server.jar" ]; then
            cp "$SRC_ROOT/obsidianscout-server.jar" ./
        fi

        # Copy Native Executables if present (or remove old native binaries if incoming release is JAR-only)
        HAS_NATIVE=$(find "$SRC_ROOT" -maxdepth 1 -name "obsidianscout-server-native*" | head -n 1)
        if [ -z "$HAS_NATIVE" ]; then
            rm -f obsidianscout-server-native*
        else
            for native_bin in "$SRC_ROOT"/obsidianscout-server-native*; do
                if [ -f "$native_bin" ]; then
                    cp "$native_bin" ./
                    chmod +x "./$(basename "$native_bin")"
                fi
            done
        fi
        for lib in "$SRC_ROOT"/*.so; do
            if [ -f "$lib" ]; then
                cp "$lib" ./
            fi
        done
        
        # Copy scripts
        for script in run.sh run.bat reset-superadmin.sh reset-superadmin.bat update.sh update.bat install-graal.sh install-graal.bat install-graal.ps1; do
            if [ -f "$SRC_ROOT/$script" ]; then
                cp "$SRC_ROOT/$script" ./
                if [ "${script##*.}" = "sh" ]; then
                    chmod +x "./$script"
                fi
            fi
        done
        
        # Clean up temp folder (parent of SRC_ROOT since it was extracted inside temp directory)
        TEMP_DIR=$(dirname "$SRC_ROOT")
        rm -rf "$TEMP_DIR"
        rm -rf .update_tmp 2>/dev/null || true
        
        echo "Update completed successfully!"
    else
        echo "Error: Extracted update files not found at $SRC_ROOT"
        echo "Press enter to exit..."
        read dummy
        exit 1
    fi
fi

echo "Press enter to exit..."
read -r dummy || true

