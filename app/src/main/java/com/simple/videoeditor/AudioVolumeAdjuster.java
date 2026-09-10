package com.simple.videoeditor;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Audio volume adjustment utility
 * Supports 0.5x - 3.0x volume gain
 */
public class AudioVolumeAdjuster {
    
    public interface ProgressCallback {
        void onProgress(int progress);
        void onComplete(String outputPath);
        void onError(String error);
    }
    
    /**
     * Adjust audio volume
     * @param inputPath Source video file
     * @param outputPath Output video file
     * @param volumeGain Volume multiplier (0.5 = half volume, 2.0 = double volume)
     * @param callback Progress callback
     */
    public static void adjustVolume(String inputPath, String outputPath, 
                                   float volumeGain, ProgressCallback callback) {
        
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;
        MediaCodec audioDecoder = null;
        MediaCodec audioEncoder = null;
        
        try {
            // Setup extractors
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(inputPath);
            
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(inputPath);
            
            // Find tracks
            int videoTrackIndex = -1;
            int audioTrackIndex = -1;
            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;
            
            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                MediaFormat format = videoExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    videoFormat = format;
                } else if (mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioFormat = format;
                }
            }
            
            if (videoTrackIndex == -1) {
                callback.onError("No video track found");
                return;
            }
            
            // Create muxer
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            
            // Add video track and copy video samples (no modification)
            videoExtractor.selectTrack(videoTrackIndex);
            int muxerVideoTrack = muxer.addTrack(videoFormat);
            
            // Setup audio if exists
            int muxerAudioTrack = -1;
            if (audioTrackIndex != -1 && audioFormat != null) {
                // Create audio decoder
                String audioMime = audioFormat.getString(MediaFormat.KEY_MIME);
                audioDecoder = MediaCodec.createDecoderByType(audioMime);
                audioDecoder.configure(audioFormat, null, null, 0);
                audioDecoder.start();
                
                // Create audio encoder
                int sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                int bitrate = audioFormat.containsKey(MediaFormat.KEY_BIT_RATE) ? 
                             audioFormat.getInteger(MediaFormat.KEY_BIT_RATE) : 128000;
                
                MediaFormat encoderFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount);
                encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, 2); // AAC-LC = 2
                encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                
                audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
                audioEncoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                audioEncoder.start();
                
                muxerAudioTrack = muxer.addTrack(encoderFormat);
            } else {
                // No audio track, just add video
                muxer.addTrack(videoFormat);
            }
            
            muxer.start();
            
            // Copy video track directly (0-50% progress)
            ByteBuffer videoBuffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo videoBufferInfo = new MediaCodec.BufferInfo();
            
            long videoDuration = videoFormat.getLong(MediaFormat.KEY_DURATION);
            int progress = 0;
            
            while (true) {
                int sampleSize = videoExtractor.readSampleData(videoBuffer, 0);
                if (sampleSize < 0) break;
                
                videoBufferInfo.offset = 0;
                videoBufferInfo.size = sampleSize;
                videoBufferInfo.flags = videoExtractor.getSampleFlags();
                videoBufferInfo.presentationTimeUs = videoExtractor.getSampleTime();
                
                muxer.writeSampleData(muxerVideoTrack, videoBuffer, videoBufferInfo);
                
                // Update progress (0-50%)
                int newProgress = (int) ((videoBufferInfo.presentationTimeUs * 50) / videoDuration);
                if (newProgress > progress) {
                    progress = newProgress;
                    callback.onProgress(progress);
                }
                
                videoExtractor.advance();
            }
            
            // Process audio with volume adjustment (50-100% progress)
            if (audioTrackIndex != -1 && audioDecoder != null && audioEncoder != null) {
                audioExtractor.selectTrack(audioTrackIndex);
                
                boolean inputDone = false;
                boolean outputDone = false;
                
                MediaCodec.BufferInfo decodeBufferInfo = new MediaCodec.BufferInfo();
                MediaCodec.BufferInfo encodeBufferInfo = new MediaCodec.BufferInfo();
                
                long audioDuration = audioFormat.getLong(MediaFormat.KEY_DURATION);
                
                while (!outputDone) {
                    // Feed input to decoder
                    if (!inputDone) {
                        int inputIndex = audioDecoder.dequeueInputBuffer(10000);
                        if (inputIndex >= 0) {
                            ByteBuffer inputBuffer = audioDecoder.getInputBuffer(inputIndex);
                            int sampleSize = audioExtractor.readSampleData(inputBuffer, 0);
                            
                            if (sampleSize < 0) {
                                audioDecoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long presentationTimeUs = audioExtractor.getSampleTime();
                                audioDecoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                                audioExtractor.advance();
                            }
                        }
                    }
                    
                    // Get decoded output and apply volume
                    int outputIndex = audioDecoder.dequeueOutputBuffer(decodeBufferInfo, 10000);
                    if (outputIndex >= 0) {
                        ByteBuffer decodedBuffer = audioDecoder.getOutputBuffer(outputIndex);
                        
                        if (decodedBuffer != null && decodeBufferInfo.size > 0) {
                            // Apply volume gain to PCM samples
                            byte[] pcmData = new byte[decodeBufferInfo.size];
                            decodedBuffer.get(pcmData);
                            applyVolumeGain(pcmData, volumeGain);
                            
                            // Feed to encoder
                            int encoderInputIndex = audioEncoder.dequeueInputBuffer(10000);
                            if (encoderInputIndex >= 0) {
                                ByteBuffer encoderInputBuffer = audioEncoder.getInputBuffer(encoderInputIndex);
                                encoderInputBuffer.clear();
                                encoderInputBuffer.put(pcmData);
                                
                                int flags = decodeBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                                audioEncoder.queueInputBuffer(encoderInputIndex, 0, pcmData.length, 
                                                             decodeBufferInfo.presentationTimeUs, flags);
                            }
                        }
                        
                        audioDecoder.releaseOutputBuffer(outputIndex, false);
                        
                        if ((decodeBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true;
                        }
                    }
                    
                    // Get encoded output and write to muxer
                    int encoderOutputIndex = audioEncoder.dequeueOutputBuffer(encodeBufferInfo, 10000);
                    if (encoderOutputIndex >= 0) {
                        ByteBuffer encodedBuffer = audioEncoder.getOutputBuffer(encoderOutputIndex);
                        
                        if (encodedBuffer != null && encodeBufferInfo.size > 0 && 
                            (encodeBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            muxer.writeSampleData(muxerAudioTrack, encodedBuffer, encodeBufferInfo);
                            
                            // Update progress (50-100%)
                            int newProgress = 50 + (int) ((encodeBufferInfo.presentationTimeUs * 50) / audioDuration);
                            if (newProgress > progress) {
                                progress = Math.min(newProgress, 100);
                                callback.onProgress(progress);
                            }
                        }
                        
                        audioEncoder.releaseOutputBuffer(encoderOutputIndex, false);
                    }
                }
            } else {
                callback.onProgress(100);
            }
            
            muxer.stop();
            callback.onComplete(outputPath);
            
        } catch (Exception e) {
            callback.onError("Volume adjustment failed: " + e.getMessage());
        } finally {
            if (videoExtractor != null) videoExtractor.release();
            if (audioExtractor != null) audioExtractor.release();
            if (audioDecoder != null) {
                audioDecoder.stop();
                audioDecoder.release();
            }
            if (audioEncoder != null) {
                audioEncoder.stop();
                audioEncoder.release();
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {}
            }
        }
    }
    
    /**
     * Apply volume gain to PCM samples (16-bit signed)
     */
    private static void applyVolumeGain(byte[] pcmData, float gain) {
        ShortBuffer shortBuffer = ByteBuffer.wrap(pcmData)
                                           .order(ByteOrder.LITTLE_ENDIAN)
                                           .asShortBuffer();
        
        for (int i = 0; i < shortBuffer.limit(); i++) {
            short sample = shortBuffer.get(i);
            int adjusted = (int) (sample * gain);
            
            // Clamp to prevent clipping
            adjusted = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, adjusted));
            shortBuffer.put(i, (short) adjusted);
        }
    }
}
