#!/bin/bash
# Auto Build Script for Simple Video Editor
# This script downloads Android SDK and builds the APK

set -e

echo "=== Simple Video Editor Auto Build Script ==="

# Check Java version
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}')
echo "Java version: $JAVA_VERSION"

# Set Android SDK Home
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# Download Android SDK if not exists
if [ ! -d "$ANDROID_HOME" ]; then
    echo "Downloading Android SDK Command Line Tools..."
    mkdir -p $ANDROID_HOME/cmdline-tools
    cd $ANDROID_HOME/cmdline-tools
    
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q commandlinetools-linux-11076708_latest.zip
    mv cmdline-tools latest
    rm commandlinetools-linux-11076708_latest.zip
    
    echo "Installing Android SDK components..."
    yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
    $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
fi

# Create local.properties
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Build APK
echo "Building APK..."
chmod +x gradlew
./gradlew clean assembleDebug

# Show result
echo ""
echo "=== Build Complete ==="
ls -lh app/build/outputs/apk/debug/app-debug.apk
echo ""
echo "APK location: app/build/outputs/apk/debug/app-debug.apk"
