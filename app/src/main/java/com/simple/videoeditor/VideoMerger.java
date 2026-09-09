package com.simple.videoeditor;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频拼接工具
 * 支持多个视频拼接成一个视频
 */
public class VideoMerger {
    private static final String TAG = "VideoMerger";
    private static final int TIMEOUT_USEC = 10000;
    
    public interface ProgressCallback {
        void onProgress(int progress, String message);
    }
    
    /**
     * 拼接多个视频
     * @param inputPaths 输入视频路径列表
     * @param outputPath 输出视频路径
     * @param callback 进度回调
     * @return 是否成功
     */
    public static boolean mergeVideos(List<String> inputPaths, String outputPath, ProgressCallback callback) {
        if (inputPaths == null || inputPaths.size() < 2) {
            Log.e(TAG, "Need at least 2 videos to merge");
            return false;
        }
        
        MediaMuxer muxer = null;
        
        try {
            callback.onProgress(5, "准备拼接 " + inputPaths.size() + " 个视频...");
            
            // 创建 Muxer
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            
            int videoTrackIndex = -1;
            int audioTrackIndex = -1;
            boolean muxerStarted = false;
            
            long videoPresentationOffset = 0;
            long audioPresentationOffset = 0;
            
            // 遍历每个输入视频
            for (int fileIndex = 0; fileIndex < inputPaths.size(); fileIndex++) {
                String inputPath = inputPaths.get(fileIndex);
                callback.onProgress(10 + (fileIndex * 80 / inputPaths.size()), 
                        "处理视频 " + (fileIndex + 1) + "/" + inputPaths.size());
                
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(inputPath);
                
                int videoTrack = -1;
                int audioTrack = -1;
                MediaFormat videoFormat = null;
                MediaFormat audioFormat = null;
                
                // 找到视频和音频轨道
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    
                    if (mime.startsWith("video/") && videoTrack == -1) {
                        videoTrack = i;
                        videoFormat = format;
                    } else if (mime.startsWith("audio/") && audioTrack == -1) {
                        audioTrack = i;
                        audioFormat = format;
                    }
                }
                
                // 第一个视频时，添加轨道并启动 Muxer
                if (!muxerStarted) {
                    if (videoFormat != null) {
                        videoTrackIndex = muxer.addTrack(videoFormat);
                    }
                    if (audioFormat != null) {
                        audioTrackIndex = muxer.addTrack(audioFormat);
                    }
                    muxer.start();
                    muxerStarted = true;
                }
                
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
                
                // 复制视频轨道
                if (videoTrack >= 0 && videoTrackIndex >= 0) {
                    extractor.selectTrack(videoTrack);
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    
                    long lastPresentationTime = 0;
                    
                    while (true) {
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) break;
                        
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = extractor.getSampleTime() + videoPresentationOffset;
                        info.flags = extractor.getSampleFlags();
                        
                        lastPresentationTime = info.presentationTimeUs;
                        
                        muxer.writeSampleData(videoTrackIndex, buffer, info);
                        extractor.advance();
                    }
                    
                    // 更新下一个视频的时间偏移
                    videoPresentationOffset = lastPresentationTime + 33333; // ~1 frame at 30fps
                    
                    extractor.unselectTrack(videoTrack);
                }
                
                // 复制音频轨道
                if (audioTrack >= 0 && audioTrackIndex >= 0) {
                    extractor.selectTrack(audioTrack);
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    
                    long lastPresentationTime = 0;
                    
                    while (true) {
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) break;
                        
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = extractor.getSampleTime() + audioPresentationOffset;
                        info.flags = extractor.getSampleFlags();
                        
                        lastPresentationTime = info.presentationTimeUs;
                        
                        muxer.writeSampleData(audioTrackIndex, buffer, info);
                        extractor.advance();
                    }
                    
                    // 更新下一个视频的音频时间偏移
                    audioPresentationOffset = lastPresentationTime + 20000; // ~1 audio sample
                }
                
                extractor.release();
            }
            
            callback.onProgress(95, "完成拼接...");
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Video merge failed", e);
            callback.onProgress(-1, "拼接失败: " + e.getMessage());
            return false;
        } finally {
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception ignored) {}
            }
        }
    }
    
    /**
     * 添加片头视频
     * @param introPath 片头视频路径
     * @param mainPath 主视频路径
     * @param outputPath 输出路径
     * @param callback 进度回调
     * @return 是否成功
     */
    public static boolean addIntro(String introPath, String mainPath, String outputPath, ProgressCallback callback) {
        List<String> paths = new ArrayList<>();
        paths.add(introPath);
        paths.add(mainPath);
        return mergeVideos(paths, outputPath, callback);
    }
}
