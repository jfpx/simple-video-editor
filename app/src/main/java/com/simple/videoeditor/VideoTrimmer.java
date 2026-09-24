package com.simple.videoeditor;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Video trimming utility - extract time segment without re-encoding
 * Ultra-fast processing (10x real-time) by copying frames directly
 */
public class VideoTrimmer {
    
    public interface ProgressCallback {
        void onProgress(int progress);
        void onComplete(String outputPath);
        void onError(String error);
    }
    
    /**
     * Trim video to specified time range
     * @param inputPath Source video file
     * @param outputPath Output video file
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @param callback Progress callback
     */
    public static void trimVideo(String inputPath, String outputPath, 
                                 long startMs, long endMs, 
                                 ProgressCallback callback) {
        
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);
            
            // Find video and audio tracks
            int videoTrackIndex = -1;
            int audioTrackIndex = -1;
            int videoTrackCount = 0;
            int audioTrackCount = 0;
            
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    videoTrackCount = i;
                } else if (mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioTrackCount = i;
                }
            }
            
            if (videoTrackIndex == -1) {
                callback.onError("No video track found");
                return;
            }
            
            // Create muxer
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            
            // Add video track
            MediaFormat videoFormat = extractor.getTrackFormat(videoTrackIndex);
            int muxerVideoTrack = muxer.addTrack(videoFormat);
            
            // Add audio track if exists
            int muxerAudioTrack = -1;
            if (audioTrackIndex != -1) {
                MediaFormat audioFormat = extractor.getTrackFormat(audioTrackIndex);
                muxerAudioTrack = muxer.addTrack(audioFormat);
            }
            
            muxer.start();
            
            // Convert start/end time to microseconds
            long startUs = startMs * 1000;
            long endUs = endMs * 1000;
            long durationUs = endUs - startUs;
            
            // Process video track
            extractor.selectTrack(videoTrackIndex);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            
            long lastProgressUpdate = 0;
            int progress = 0;
            
            while (true) {
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;
                
                long sampleTime = extractor.getSampleTime();
                if (sampleTime > endUs) break;
                
                if (sampleTime >= startUs) {
                    bufferInfo.offset = 0;
                    bufferInfo.size = sampleSize;
                    bufferInfo.flags = extractor.getSampleFlags();
                    // Adjust timestamp to start from 0
                    bufferInfo.presentationTimeUs = sampleTime - startUs;
                    
                    muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo);
                    
                    // Update progress (0-70% for video)
                    long currentUs = sampleTime - startUs;
                    int newProgress = (int) ((currentUs * 70) / durationUs);
                    if (newProgress > progress) {
                        progress = newProgress;
                        callback.onProgress(progress);
                    }
                }
                
                extractor.advance();
            }
            
            // Process audio track if exists
            if (audioTrackIndex != -1) {
                extractor.unselectTrack(videoTrackIndex);
                extractor.selectTrack(audioTrackIndex);
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                
                while (true) {
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;
                    
                    long sampleTime = extractor.getSampleTime();
                    if (sampleTime > endUs) break;
                    
                    if (sampleTime >= startUs) {
                        bufferInfo.offset = 0;
                        bufferInfo.size = sampleSize;
                        bufferInfo.flags = extractor.getSampleFlags();
                        bufferInfo.presentationTimeUs = sampleTime - startUs;
                        
                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo);
                        
                        // Update progress (70-100% for audio)
                        long currentUs = sampleTime - startUs;
                        int newProgress = 70 + (int) ((currentUs * 30) / durationUs);
                        if (newProgress > progress) {
                            progress = newProgress;
                            callback.onProgress(progress);
                        }
                    }
                    
                    extractor.advance();
                }
            } else {
                // No audio, jump to 100%
                callback.onProgress(100);
            }
            
            muxer.stop();
            callback.onComplete(outputPath);
            
        } catch (Exception e) {
            callback.onError("Trim failed: " + e.getMessage());
        } finally {
            if (extractor != null) {
                extractor.release();
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {}
            }
        }
    }
}
