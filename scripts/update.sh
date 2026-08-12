#!/bin/sh
set -e

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
"$JAVA_EXEC" -cp obsidianscout-server.jar com.obsidianscout.utils.UpdateHelperKt

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

