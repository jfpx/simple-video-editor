package com.simple.videoeditor;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Replace video's original audio with background music
 */
public class AudioReplacer {
    private static final String TAG = "AudioReplacer";
    
    public interface ProgressCallback {
        void onProgress(int progress, String message);
    }
    
    /**
     * Replace video audio with background music
     * 
     * @param videoPath Input video path
     * @param musicPath Background music path
     * @param outputPath Output video path
     * @param callback Progress callback
     * @return true if successful
     */
    public static boolean replaceAudio(
            String videoPath, 
            String musicPath, 
            String outputPath,
            ProgressCallback callback) {
        
        MediaExtractor videoExtractor = null;
        MediaExtractor musicExtractor = null;
        MediaMuxer muxer = null;
        
        try {
            callback.onProgress(0, "Initializing...");
            
            // Setup video extractor
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(videoPath);
            
            // Setup music extractor
            musicExtractor = new MediaExtractor();
            musicExtractor.setDataSource(musicPath);
            
            // Find video track
            int videoTrackIndex = -1;
            MediaFormat videoFormat = null;
            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                MediaFormat format = videoExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    videoFormat = format;
                    break;
                }
            }
            
            if (videoTrackIndex == -1) {
                callback.onProgress(-1, "No video track found");
                return false;
            }
            
            // Find audio track in music
            int musicTrackIndex = -1;
            MediaFormat musicFormat = null;
            for (int i = 0; i < musicExtractor.getTrackCount(); i++) {
                MediaFormat format = musicExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("audio/")) {
                    musicTrackIndex = i;
                    musicFormat = format;
                    break;
                }
            }
            
            if (musicTrackIndex == -1) {
                callback.onProgress(-1, "No audio track in music file");
                return false;
            }
            
            // Get video duration
            long videoDuration = videoFormat.getLong(MediaFormat.KEY_DURATION);
            
            callback.onProgress(10, "Setting up muxer...");
            
            // Setup muxer
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            
            // Add video track
            int muxerVideoTrack = muxer.addTrack(videoFormat);
            
            // Add audio track
            int muxerAudioTrack = muxer.addTrack(musicFormat);
            
            // Start muxing
            muxer.start();
            
            callback.onProgress(20, "Copying video track...");
            
            // Copy video track
            videoExtractor.selectTrack(videoTrackIndex);
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            
            long lastProgressTime = 0;
            while (true) {
                int sampleSize = videoExtractor.readSampleData(buffer, 0);
                if (sampleSize < 0) {
                    break;
                }
                
                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = videoExtractor.getSampleTime();
                bufferInfo.flags = videoExtractor.getSampleFlags();
                
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo);
                
                // Update progress
                long currentTime = videoExtractor.getSampleTime();
                if (currentTime - lastProgressTime > 500000) { // Every 0.5 second
                    int progress = 20 + (int) (30 * currentTime / videoDuration);
                    callback.onProgress(progress, "Copying video: " + (currentTime / 1000000) + "s");
                    lastProgressTime = currentTime;
                }
                
                videoExtractor.advance();
            }
            
            callback.onProgress(50, "Copying audio track...");
            
            // Copy audio track (loop if needed)
            musicExtractor.selectTrack(musicTrackIndex);
            long currentAudioTime = 0;
            long musicDuration = musicFormat.containsKey(MediaFormat.KEY_DURATION) 
                ? musicFormat.getLong(MediaFormat.KEY_DURATION) : Long.MAX_VALUE;
            
            lastProgressTime = 0;
            while (currentAudioTime < videoDuration) {
                int sampleSize = musicExtractor.readSampleData(buffer, 0);
                if (sampleSize < 0) {
                    // Music ended, loop back
                    musicExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    continue;
                }
                
                long originalTime = musicExtractor.getSampleTime();
                
                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = currentAudioTime;
                bufferInfo.flags = musicExtractor.getSampleFlags();
                
                // Stop if we've reached video duration
                if (currentAudioTime >= videoDuration) {
                    break;
                }
                
                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo);
                
                // Update progress
                if (currentAudioTime - lastProgressTime > 500000) {
                    int progress = 50 + (int) (50 * currentAudioTime / videoDuration);
                    callback.onProgress(progress, "Copying audio: " + (currentAudioTime / 1000000) + "s");
                    lastProgressTime = currentAudioTime;
                }
                
                musicExtractor.advance();
                
                // Calculate next audio timestamp
                long nextTime = musicExtractor.getSampleTime();
                if (nextTime < originalTime) {
                    // Looped back
                    currentAudioTime += (musicDuration - originalTime);
                } else {
                    currentAudioTime += (nextTime - originalTime);
                }
            }
            
            callback.onProgress(100, "Audio replacement complete!");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error replacing audio", e);
            callback.onProgress(-1, "Error: " + e.getMessage());
            return false;
            
        } finally {
            if (videoExtractor != null) {
                videoExtractor.release();
            }
            if (musicExtractor != null) {
                musicExtractor.release();
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping muxer", e);
                }
            }
        }
    }
}
