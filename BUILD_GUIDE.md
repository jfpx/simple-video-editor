# Build Guide for Simple Video Editor

## Prerequisites

1. **Android Studio** (Arctic Fox or newer)
   - Download from: https://developer.android.com/studio
   
2. **Android SDK**
   - API 34 (Android 14) - for compilation
   - API 23 (Android 6.0) - minimum supported version

3. **Java JDK 8 or newer**

## Step-by-Step Build Instructions

### Option 1: Build with Android Studio (Recommended)

1. **Open Project**
   ```
   File → Open → Select "simple-video-editor" folder
   ```

2. **Sync Gradle**
   - Android Studio will automatically sync Gradle dependencies
   - Wait for FFmpeg-Kit library to download (~100MB)

3. **Build APK**
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```

4. **Find APK**
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Option 2: Build with Command Line

```bash
cd simple-video-editor

# Windows
gradlew.bat assembleDebug

# Linux/Mac
./gradlew assembleDebug

# APK location
app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

### Gradle Sync Failed

**Problem**: Dependencies cannot be downloaded

**Solution**:
```bash
# Clean and rebuild
gradlew clean
gradlew assembleDebug
```

### FFmpeg-Kit Download Slow

**Problem**: Maven repository slow in your region

**Solution**: Add mirror repository in `build.gradle`:
```gradle
repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }  // Fallback
}
```

### Out of Memory

**Problem**: Gradle build fails with "Out of memory"

**Solution**: Increase heap size in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

### SDK Not Found

**Problem**: "SDK location not found"

**Solution**: Create `local.properties`:
```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

## Release Build (Signed APK)

### 1. Generate Keystore

```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
```

### 2. Configure Signing in `app/build.gradle`

```gradle
android {
    signingConfigs {
        release {
            storeFile file("../my-release-key.jks")
            storePassword "your_store_password"
            keyAlias "my-key-alias"
            keyPassword "your_key_password"
        }
    }
    
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

### 3. Build Release APK

```bash
gradlew assembleRelease

# APK location
app/build/outputs/apk/release/app-release.apk
```

## Testing on Device

### Install APK

```bash
# Via ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Or transfer APK to device and install manually
```

### Test Video Processing

1. Record a short test video (30 seconds)
2. Select video in app
3. Try rotation: 90° right
4. Try crop: 20px from all edges
5. Process and verify output in `Movies/SimpleVideoEditor/`

## Performance Notes

- **20-minute 1080p video**: ~5-10 minutes processing time (depends on device)
- **Rotation only**: Fastest (no transcoding needed)
- **Crop + Rotation**: Moderate (requires transcoding)
- **Custom angle rotation**: Slowest (complex transform)

## Build Variants

```bash
# Debug (for development)
gradlew assembleDebug

# Release (for distribution)
gradlew assembleRelease

# List all tasks
gradlew tasks
```

## Dependencies Used

| Library | Version | Purpose |
|---------|---------|---------|
| FFmpeg-Kit | 6.0-2 | Video processing engine |
| AppCompat | 1.6.1 | Android compatibility |
| Material | 1.9.0 | UI components |
| ConstraintLayout | 2.1.4 | Layout management |

## Next Steps

After successful build:
1. Test on multiple Android versions (6.0, 10, 13)
2. Test with various video formats (MP4, MOV, AVI)
3. Monitor memory usage with 4K videos
4. Optimize FFmpeg preset if needed (`ultrafast` → `medium`)
