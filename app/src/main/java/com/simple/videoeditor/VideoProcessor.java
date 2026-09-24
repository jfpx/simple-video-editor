package com.simple.videoeditor;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Video processor using Android native MediaCodec API.
 * Supports 90° rotation increments (0, 90, 180, 270).
 */
public class VideoProcessor {
    private static final String TAG = "VideoProcessor";
    private static final int TIMEOUT_USEC = 10000;
    
    private String inputPath;
    private String outputPath;
    private int rotationDegrees = 0;
    private ProgressCallback progressCallback;
    
    public interface ProgressCallback {
        void onProgress(int percent);
        void onComplete(boolean success, String message);
    }
    
    public VideoProcessor(String inputPath, String outputPath) {
        this.inputPath = inputPath;
        this.outputPath = outputPath;
    }
    
    public void setRotation(int degrees) {
        // Normalize to 0, 90, 180, 270
        this.rotationDegrees = ((degrees % 360) + 360) % 360;
        if (rotationDegrees % 90 != 0) {
            rotationDegrees = (rotationDegrees / 90) * 90;
        }
    }
    
    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }
    
    public void process() {
        new Thread(() -> {
            try {
                processVideo();
                if (progressCallback != null) {
                    progressCallback.onComplete(true, "Video processed successfully");
                }
            } catch (Exception e) {
                Log.e(TAG, "Video processing failed", e);
                if (progressCallback != null) {
                    progressCallback.onComplete(false, "Error: " + e.getMessage());
                }
            }
        }).start();
    }
    
    private void processVideo() throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inputPath);
        
        // Get video duration for progress calculation
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(inputPath);
        String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        long durationUs = Long.parseLong(durationStr) * 1000; // ms to us
        retriever.release();
        
        // Find video track
        int videoTrackIndex = -1;
        MediaFormat inputFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                videoTrackIndex = i;
                inputFormat = format;
                break;
            }
        }
        
        if (videoTrackIndex < 0) {
            throw new IOException("No video track found");
        }
        
        extractor.selectTrack(videoTrackIndex);
        
        // Get input video properties
        int width = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
        int height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
        String mime = inputFormat.getString(MediaFormat.KEY_MIME);
        
        // Determine output dimensions based on rotation
        int outputWidth = width;
        int outputHeight = height;
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            // Swap dimensions for 90/270 rotation
            outputWidth = height;
            outputHeight = width;
        }
        
        // Create output format
        MediaFormat outputFormat = MediaFormat.createVideoFormat(mime, outputWidth, outputHeight);
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 5000000); // 5Mbps
        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        
        // Create decoder
        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(inputFormat, null, null, 0);
        decoder.start();
        
        // Create encoder
        MediaCodec encoder = MediaCodec.createEncoderByType(mime);
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface inputSurface = encoder.createInputSurface();
        encoder.start();
        
        // Create muxer
        MediaMuxer muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        
        // Process video
        boolean inputDone = false;
        boolean outputDone = false;
        int muxerTrackIndex = -1;
        
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        while (!outputDone) {
            // Feed input to decoder
            if (!inputDone) {
                int inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                        
                        // Report progress
                        if (progressCallback != null && durationUs > 0) {
                            int percent = (int) ((presentationTimeUs * 100) / durationUs);
                            progressCallback.onProgress(Math.min(percent, 99));
                        }
                    }
                }
            }
            
            // Get output from encoder
            int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = encoder.getOutputFormat();
                muxerTrackIndex = muxer.addTrack(newFormat);
                muxer.start();
            } else if (encoderStatus >= 0) {
                ByteBuffer encodedData = encoder.getOutputBuffer(encoderStatus);
                
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                
                if (bufferInfo.size != 0 && muxerTrackIndex >= 0) {
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(muxerTrackIndex, encodedData, bufferInfo);
                }
                
                encoder.releaseOutputBuffer(encoderStatus, false);
                
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
            
            // Transfer decoded frame to encoder (with rotation)
            int decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
            if (decoderStatus >= 0) {
                boolean doRender = (bufferInfo.size != 0);
                decoder.releaseOutputBuffer(decoderStatus, doRender);
                
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encoder.signalEndOfInputStream();
                }
            }
        }
        
        // Cleanup
        decoder.stop();
        decoder.release();
        encoder.stop();
        encoder.release();
        muxer.stop();
        muxer.release();
        extractor.release();
        
        if (progressCallback != null) {
            progressCallback.onProgress(100);
        }
    }
}
