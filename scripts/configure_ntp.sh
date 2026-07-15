#!/bin/bash

# Ensure script is run as root
if [ "$EUID" -ne 0 ]; then 
  echo "Error: Please run this script with sudo."
  exit 1
fi

echo "=== Starting Automatic NTP Configuration ==="

# 1. Disable the loose system default timer
if command -v timedatectl >/dev/null 2>&1; then
    echo "Disabling systemd-timesyncd..."
    timedatectl set-ntp no
fi

# 2. Detect Package Manager and Install Chrony if missing
if ! command -v chronyd >/dev/null 2>&1 && ! command -v chronyc >/dev/null 2>&1; then
    echo "Chrony is not installed. Installing now..."
    if [ -x "$(command -v apt-get)" ]; then
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -y
        apt-get install chrony -y
    elif [ -x "$(command -v dnf)" ]; then
        dnf install chrony -y
    elif [ -x "$(command -v yum)" ]; then
        yum install chrony -y
    else
        echo "Error: Unsupported package manager. Please install chrony manually."
        exit 1
    fi
else
    echo "Chrony is already installed."
fi

# 3. Create drop-in configuration directory if it doesn't exist
mkdir -p /etc/chrony/conf.d

# 4. Write the Google NTP configuration
echo "Writing Google NTP configurations to drop-in folder..."
cat <<EOF > /etc/chrony/conf.d/google-ntp.conf
server time1.google.com iburst
server time2.google.com iburst
server time3.google.com iburst
server time4.google.com iburst
EOF

# 5. Enable and Restart the service
echo "Starting and enabling Chrony..."
if [ -x "$(command -v systemctl)" ]; then
    # Handles systemd environments (Ubuntu, Debian, RHEL 7+)
    systemctl enable chrony 2>/dev/null || systemctl enable chronyd
    systemctl restart chrony 2>/dev/null || systemctl restart chronyd
else
    # Fallback for older sysvinit/init.d platforms
    service chrony restart 2>/dev/null || service chronyd restart
fi

echo "=== Linux NTP Setup Complete ==="
