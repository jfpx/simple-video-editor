package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 片头视频生成器
 * 根据模板动态生成片头视频
 */
public class IntroVideoGenerator {
    private static final String TAG = "IntroVideoGenerator";
    private static final int VIDEO_WIDTH = 1920;
    private static final int VIDEO_HEIGHT = 1080;
    private static final int FPS = 30;
    private static final int BITRATE = 6000000;  // 6 Mbps
    private static final int IFRAME_INTERVAL = 1;  // 1 second between I-frames
    
    public interface ProgressCallback {
        void onProgress(int progress);
    }
    
    /**
     * 根据模板生成片头视频
     * @param template 片头模板
     * @param outputPath 输出视频路径
     * @param callback 进度回调
     * @return 是否成功
     */
    public static boolean generateIntroVideo(IntroTemplate template, String outputPath, ProgressCallback callback) {
        if (template == null) {
            Log.e(TAG, "Template is null");
            return false;
        }
        
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        
        try {
            // 计算帧数
            int durationMs = template.getDurationMs();
            int totalFrames = (int) (durationMs * FPS / 1000.0);
            
            callback.onProgress(5);
            
            // 创建编码器
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
            
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            
            callback.onProgress(10);
            
            // 创建 Muxer
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            
            // 准备画布和画笔
            Bitmap bitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = createPaint(template);
            
            callback.onProgress(15);
            
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int videoTrackIndex = -1;
            boolean muxerStarted = false;
            long frameIndex = 0;
            long presentationTimeUs = 0;
            boolean encodingDone = false;
            
            // 编码循环
            while (!encodingDone) {
                // 进度反馈
                if (frameIndex < totalFrames) {
                    int progress = 15 + (int) (frameIndex * 75 / totalFrames);
                    callback.onProgress(progress);
                }
                
                // 送入输入帧
                if (frameIndex < totalFrames) {
                    int inputBufferIndex = encoder.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        // 渲染当前帧
                        renderFrame(canvas, bitmap, paint, template, frameIndex, totalFrames);
                        
                        // 转换为 YUV420
                        ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                        inputBuffer.clear();
                        convertBitmapToYUV420(bitmap, inputBuffer);
                        
                        presentationTimeUs = frameIndex * 1000000 / FPS;
                        encoder.queueInputBuffer(inputBufferIndex, 0, inputBuffer.position(), presentationTimeUs, 0);
                        frameIndex++;
                    }
                } else {
                    // 送入 EOS
                    int inputBufferIndex = encoder.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                }
                
                // 获取输出
                int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Muxer 配置
                    if (muxerStarted) {
                        throw new RuntimeException("Format changed after muxer start");
                    }
                    MediaFormat newFormat = encoder.getOutputFormat();
                    videoTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                    
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                    
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size != 0) {
                        if (!muxerStarted) {
                            throw new RuntimeException("Muxer not started");
                        }
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                    }
                    
                    encoder.releaseOutputBuffer(outputBufferIndex, false);
                    
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encodingDone = true;
                    }
                }
            }
            
            callback.onProgress(95);
            
            // 清理资源
            bitmap.recycle();
            
            callback.onProgress(100);
            Log.d(TAG, "Intro video generated successfully: " + outputPath);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error generating intro video", e);
            e.printStackTrace();
            return false;
            
        } finally {
            if (encoder != null) {
                try {
                    encoder.stop();
                    encoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing encoder", e);
                }
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing muxer", e);
                }
            }
        }
    }
    
    /**
     * 创建画笔
     */
    private static Paint createPaint(IntroTemplate template) {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(template.getTextSize() * VIDEO_HEIGHT / 100f);  // 将 sp 转为像素
        paint.setColor(template.getTextColor());
        
        // 设置字体风格
        switch (template.getFontStyle()) {
            case "bold":
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                break;
            case "italic":
                paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
                break;
            case "bold_italic":
                paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
                break;
            default:
                paint.setTypeface(Typeface.DEFAULT);
        }
        
        paint.setTextAlign(Paint.Align.CENTER);
        return paint;
    }
    
    /**
     * 渲染单帧
     */
    private static void renderFrame(Canvas canvas, Bitmap bitmap, Paint paint, IntroTemplate template, long frameIndex, int totalFrames) {
        // 清除画布
        canvas.drawColor(template.getBackgroundColor());
        
        // 计算透明度（淡入淡出效果）
        float alpha = 1.0f;
        int fadeFrames = FPS;  // 1秒淡入淡出
        
        if (frameIndex < fadeFrames) {
            // 淡入
            alpha = frameIndex / (float) fadeFrames;
        } else if (frameIndex > totalFrames - fadeFrames) {
            // 淡出
            alpha = (totalFrames - frameIndex) / (float) fadeFrames;
        }
        
        paint.setAlpha((int) (alpha * 255));
        
        // 绘制文字
        String text = template.getText();
        float x = template.getTextX() * VIDEO_WIDTH;
        float y = template.getTextY() * VIDEO_HEIGHT;
        
        // 调整 Y 坐标使文字垂直居中
        Paint.FontMetrics fm = paint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;
        y = y - textHeight / 2 - fm.ascent;
        
        canvas.drawText(text, x, y, paint);
    }
    
    /**
     * 将 Bitmap 转换为 YUV420
     * 这是一个简化实现，实际项目中可能需要更优化的转换
     */
    private static void convertBitmapToYUV420(Bitmap bitmap, ByteBuffer buffer) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        
        byte[] yuv = new byte[width * height * 3 / 2];
        encodeYUV420SP(yuv, argb, width, height);
        
        buffer.put(yuv);
    }
    
    /**
     * RGB 转 YUV420SP
     */
    private static void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;
        
        int yIndex = 0;
        int uvIndex = frameSize;
        
        int R, G, B, Y, U, V;
        int index = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                
                int pixel = argb[index];
                R = Color.red(pixel);
                G = Color.green(pixel);
                B = Color.blue(pixel);
                
                // RGB to YUV
                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;
                
                yuv420sp[yIndex++] = (byte) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));
                
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (byte) ((V < 0) ? 0 : ((V > 255) ? 255 : V));
                    yuv420sp[uvIndex++] = (byte) ((U < 0) ? 0 : ((U > 255) ? 255 : U));
                }
                
                index++;
            }
        }
    }
}
