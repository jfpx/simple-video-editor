# Simple Video Editor

A simple Android app for rotating and cropping videos before uploading to YouTube.

## Features

- **Rotate Video**: 90°, 180°, 270° rotation or custom angle adjustment (for tilted videos)
- **Crop Edges**: Remove unwanted edges from top, bottom, left, right
- **Fast Processing**: Uses FFmpeg for efficient video processing
- **Offline**: All processing done locally on your device
- **Free & Open Source**: No ads, no subscriptions, no cloud upload

## Use Case

Perfect for:
- Correcting video orientation before YouTube upload
- Adjusting tilted videos (slight angle corrections)
- Removing black bars or unwanted edges
- Quick pre-processing for 20-minute recordings

## Requirements

- Android 6.0 (API 23) or higher
- Storage permission for reading/writing videos

## How to Use

1. **Select Video**: Tap "Select Video" to choose a video from your gallery
2. **Rotate**: 
   - Use quick buttons for 90° left/right rotation
   - Or enter custom angle (e.g., 3 degrees to correct tilt)
3. **Crop** (Optional): Enter pixels to remove from each edge
4. **Process**: Tap "Process Video" to apply changes
5. **Result**: Processed video saved to `Movies/SimpleVideoEditor/`

## Technical Details

- Built with Java and FFmpeg-Kit 6.0-2
- Video codec: H.264 (libx264) with ultrafast preset
- Audio: Direct stream copy (no re-encoding)
- Output format: MP4

## Building from Source

```bash
# Clone the repository
git clone https://github.com/jfpx/simple-video-editor.git

# Open in Android Studio and build
./gradlew assembleDebug

# APK will be in: app/build/outputs/apk/debug/
```

## License

MIT License - Free to use, modify, and distribute

## Privacy

This app does not:
- Upload your videos to any server
- Collect any personal data
- Require internet connection
- Show ads or track usage

All video processing is done locally on your device.

---

# 简易视频编辑器

一个简单的 Android 应用，用于在上传 YouTube 前旋转和裁剪视频。

## 功能

- **旋转视频**：90°、180°、270° 旋转或自定义角度调整（用于校正倾斜视频）
- **裁剪边缘**：移除上下左右不需要的边缘
- **快速处理**：使用 FFmpeg 高效处理视频
- **离线处理**：所有处理在设备本地完成
- **免费开源**：无广告、无订阅、不上传云端

## 使用场景

适合：
- 上传 YouTube 前校正视频方向
- 调整倾斜的视频（轻微角度校正）
- 移除黑边或不需要的边缘
- 20 分钟录制视频的快速预处理

## 系统要求

- Android 6.0 (API 23) 或更高版本
- 存储权限用于读取/写入视频

## 使用方法

1. **选择视频**：点击"选择视频"从相册选择视频
2. **旋转**：
   - 使用快速按钮进行 90° 左/右旋转
   - 或输入自定义角度（例如 3 度来校正水平）
3. **裁剪**（可选）：输入每个边缘要移除的像素数
4. **处理**：点击"处理视频"应用更改
5. **结果**：处理后的视频保存到 `Movies/SimpleVideoEditor/`

## 技术细节

- 使用 Java 和 FFmpeg-Kit 6.0-2 构建
- 视频编码：H.264 (libx264) ultrafast 预设
- 音频：直接流复制（不重新编码）
- 输出格式：MP4

## 从源码构建

```bash
# 克隆仓库
git clone https://github.com/jfpx/simple-video-editor.git

# 在 Android Studio 中打开并构建
./gradlew assembleDebug

# APK 位于: app/build/outputs/apk/debug/
```

## 许可证

MIT 许可证 - 可自由使用、修改和分发

## 隐私

本应用不会：
- 上传你的视频到任何服务器
- 收集任何个人数据
- 需要互联网连接
- 显示广告或追踪使用情况

所有视频处理在你的设备本地完成。
