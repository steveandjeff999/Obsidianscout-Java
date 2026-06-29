#!/bin/sh
set -e

# Clear any previous update state
rm -f .update_result

# Run the interactive Java update utility
java -cp obsidianscout-server.jar com.obsidianscout.utils.UpdateHelperKt

# If the helper completed successfully and wrote the path of the new files
if [ -f .update_result ]; then
    SRC_ROOT=$(cat .update_result)
    rm -f .update_result
    
    if [ -d "$SRC_ROOT" ]; then
        echo "Finalizing update (copying new files)..."
        
        # Copy JAR
        cp "$SRC_ROOT/obsidianscout-server.jar" ./
        
        # Copy scripts
        for script in run.sh run.bat reset-superadmin.sh reset-superadmin.bat update.sh update.bat; do
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
        
        echo "Update completed successfully!"
    else
        echo "Error: Extracted update files not found at $SRC_ROOT"
        echo "Press enter to exit..."
        read dummy
        exit 1
    fi
fi

echo "Press enter to exit..."
read dummy
