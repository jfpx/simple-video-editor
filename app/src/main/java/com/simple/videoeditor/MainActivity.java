package com.simple.videoeditor;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    
    private static final int VIDEO_PICK_CODE = 1000;
    
    private Uri selectedVideoUri;
    private String videoFilePath;
    
    private TextView tvSelectedVideo;
    private CheckBox cbFastMode;
    private TextView tvModeHint;
    private EditText etCustomAngle;
    private Spinner spinnerResolution;
    private EditText etOverlayText;
    private TextView tvResolutionLabel;
    private TextView tvOverlayLabel;
    private Button btnRotateLeft, btnRotateRight;
    private Button btnProcess;
    private ProgressBar progressBar;
    private TextView tvProgress;
    
    private int currentRotation = 0;
    private int originalWidth = 0;
    private int originalHeight = 0;
    
    private ActivityResultLauncher<String> requestPermissionLauncher;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize views
        tvSelectedVideo = findViewById(R.id.tvSelectedVideo);
        cbFastMode = findViewById(R.id.cbFastMode);
        tvModeHint = findViewById(R.id.tvModeHint);
        etCustomAngle = findViewById(R.id.etCustomAngle);
        spinnerResolution = findViewById(R.id.spinnerResolution);
        etOverlayText = findViewById(R.id.etOverlayText);
        tvResolutionLabel = findViewById(R.id.tvResolutionLabel);
        tvOverlayLabel = findViewById(R.id.tvOverlayLabel);
        btnRotateLeft = findViewById(R.id.btnRotateLeft);
        btnRotateRight = findViewById(R.id.btnRotateRight);
        btnProcess = findViewById(R.id.btnProcess);
        progressBar = findViewById(R.id.progressBar);
        tvProgress = findViewById(R.id.tvProgress);
        
        Button btnSelectVideo = findViewById(R.id.btnSelectVideo);
        
        // Setup resolution spinner
        String[] resolutions = {
            getString(R.string.resolution_original),
            getString(R.string.resolution_1080p),
            getString(R.string.resolution_720p),
            getString(R.string.resolution_480p)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, resolutions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerResolution.setAdapter(adapter);
        
        // Setup permission launcher
        requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    openVideoPicker();
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                }
            }
        );
        
        // Select video button
        btnSelectVideo.setOnClickListener(v -> checkPermissionAndPickVideo());
        
        // Fast mode checkbox listener
        cbFastMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Fast mode: disable resolution and overlay
                spinnerResolution.setEnabled(false);
                etOverlayText.setEnabled(false);
                tvResolutionLabel.setEnabled(false);
                tvOverlayLabel.setEnabled(false);
                tvModeHint.setText(R.string.fast_mode_hint);
                tvModeHint.setBackgroundColor(0xFFFFF3E0); // Light orange
            } else {
                // Full mode: enable all options
                spinnerResolution.setEnabled(true);
                etOverlayText.setEnabled(true);
                tvResolutionLabel.setEnabled(true);
                tvOverlayLabel.setEnabled(true);
                tvModeHint.setText(R.string.full_mode_hint);
                tvModeHint.setBackgroundColor(0xFFE3F2FD); // Light blue
            }
        });
        
        // Trigger initial state
        cbFastMode.setChecked(true);
        
        // Rotation buttons
        btnRotateLeft.setOnClickListener(v -> {
            currentRotation = (currentRotation - 90 + 360) % 360;
            updateRotationDisplay();
        });
        
        btnRotateRight.setOnClickListener(v -> {
            currentRotation = (currentRotation + 90) % 360;
            updateRotationDisplay();
        });
        
        // Process button
        btnProcess.setOnClickListener(v -> processVideo());
        
        updateRotationDisplay();
    }
    
    private void checkPermissionAndPickVideo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: Use READ_MEDIA_VIDEO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
                    == PackageManager.PERMISSION_GRANTED) {
                openVideoPicker();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO);
            }
        } else {
            // Android 6-12: Use READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED) {
                openVideoPicker();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }
    
    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("video/*");
        startActivityForResult(intent, VIDEO_PICK_CODE);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == VIDEO_PICK_CODE && resultCode == RESULT_OK && data != null) {
            selectedVideoUri = data.getData();
            if (selectedVideoUri != null) {
                // Get video name
                String videoName = getFileName(selectedVideoUri);
                tvSelectedVideo.setText("Selected: " + videoName);
                
                // Copy to cache for processing
                copyVideoToCache();
            }
        }
    }
    
    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }
    
    private void copyVideoToCache() {
        new Thread(() -> {
            try {
                File cacheFile = new File(getCacheDir(), "input_video.mp4");
                
                InputStream inputStream = getContentResolver().openInputStream(selectedVideoUri);
                FileOutputStream outputStream = new FileOutputStream(cacheFile);
                
                byte[] buffer = new byte[8192];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
                
                inputStream.close();
                outputStream.close();
                
                videoFilePath = cacheFile.getAbsolutePath();
                
                // Extract video metadata
                extractVideoMetadata();
                
                runOnUiThread(() -> {
                    btnProcess.setEnabled(true);
                    Toast.makeText(this, "Video ready for processing", Toast.LENGTH_SHORT).show();
                });
                
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error loading video: " + e.getMessage(), 
                        Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    private void extractVideoMetadata() {
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFilePath);
            
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            
            if (widthStr != null && heightStr != null) {
                originalWidth = Integer.parseInt(widthStr);
                originalHeight = Integer.parseInt(heightStr);
                
                runOnUiThread(() -> {
                    String info = String.format(" (Original: %dx%d)", originalWidth, originalHeight);
                    tvSelectedVideo.setText(tvSelectedVideo.getText() + info);
                });
            }
            
            retriever.release();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void updateRotationDisplay() {
        String rotationText = "Current rotation: " + currentRotation + "°";
        if (currentRotation == 90) {
            rotationText += " (Portrait → Landscape)";
        } else if (currentRotation == 270) {
            rotationText += " (Landscape → Portrait)";
        } else if (currentRotation == 180) {
            rotationText += " (Upside down)";
        }
        Toast.makeText(this, rotationText, Toast.LENGTH_SHORT).show();
    }
    
    private void processVideo() {
        if (videoFilePath == null) {
            Toast.makeText(this, "Please select a video first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Get custom angle if specified
        String angleStr = etCustomAngle.getText().toString().trim();
        if (!angleStr.isEmpty()) {
            try {
                currentRotation = Integer.parseInt(angleStr);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid angle", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        // Disable buttons during processing
        btnProcess.setEnabled(false);
        btnRotateLeft.setEnabled(false);
        btnRotateRight.setEnabled(false);
        cbFastMode.setEnabled(false);
        
        // Show progress
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        
        // Create output file
        File outputDir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES), "SimpleVideoEditor");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        String outputFileName = "edited_" + System.currentTimeMillis() + ".mp4";
        File outputFile = new File(outputDir, outputFileName);
        String outputPath = outputFile.getAbsolutePath();
        
        boolean isFastMode = cbFastMode.isChecked();
        
        new Thread(() -> {
            boolean success;
            long startTime = System.currentTimeMillis();
            
            if (isFastMode) {
                // Fast mode: only rotation
                runOnUiThread(() -> tvProgress.setText("Fast Mode: Starting..."));
                
                success = VideoProcessorOptimized.fastRotate(
                    videoFilePath,
                    outputPath,
                    currentRotation,
                    (progress, message) -> runOnUiThread(() -> {
                        if (progress >= 0) {
                            progressBar.setProgress(progress);
                            tvProgress.setText(message);
                        } else {
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    })
                );
            } else {
                // Full mode: rotation + scaling + overlay
                int selectedResIndex = spinnerResolution.getSelectedItemPosition();
                int targetWidth = originalWidth;
                int targetHeight = originalHeight;
                
                // Calculate target resolution
                switch (selectedResIndex) {
                    case 1: // 1080p
                        if (originalWidth > originalHeight) {
                            targetWidth = 1920;
                            targetHeight = 1080;
                        } else {
                            targetWidth = 1080;
                            targetHeight = 1920;
                        }
                        break;
                    case 2: // 720p
                        if (originalWidth > originalHeight) {
                            targetWidth = 1280;
                            targetHeight = 720;
                        } else {
                            targetWidth = 720;
                            targetHeight = 1280;
                        }
                        break;
                    case 3: // 480p
                        if (originalWidth > originalHeight) {
                            targetWidth = 854;
                            targetHeight = 480;
                        } else {
                            targetWidth = 480;
                            targetHeight = 854;
                        }
                        break;
                    default: // Original
                        break;
                }
                
                String overlayText = etOverlayText.getText().toString().trim();
                if (overlayText.isEmpty()) {
                    overlayText = null;
                }
                
                final int finalWidth = targetWidth;
                final int finalHeight = targetHeight;
                runOnUiThread(() -> tvProgress.setText(
                    String.format("Full Mode: %dx%d → %dx%d", originalWidth, originalHeight, finalWidth, finalHeight)
                ));
                
                success = VideoProcessorOptimized.processVideoOptimized(
                    videoFilePath,
                    outputPath,
                    currentRotation,
                    targetWidth,
                    targetHeight,
                    overlayText,
                    (progress, message) -> runOnUiThread(() -> {
                        if (progress >= 0) {
                            progressBar.setProgress(progress);
                            tvProgress.setText(message);
                        } else {
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    })
                );
            }
            
            long elapsedTime = System.currentTimeMillis() - startTime;
            String timeStr = String.format("%.1f seconds", elapsedTime / 1000.0);
            
            boolean finalSuccess = success;
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                tvProgress.setVisibility(View.GONE);
                
                btnProcess.setEnabled(true);
                btnRotateLeft.setEnabled(true);
                btnRotateRight.setEnabled(true);
                cbFastMode.setEnabled(true);
                
                if (finalSuccess) {
                    Toast.makeText(MainActivity.this, 
                        "✓ Video saved in " + timeStr + "\n" + outputFile.getAbsolutePath(), 
                        Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "Processing failed", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
}
