package com.simple.videoeditor;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
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
    private EditText etCustomAngle;
    private Button btnRotateLeft, btnRotateRight;
    private Button btnProcess;
    private ProgressBar progressBar;
    private TextView tvProgress;
    
    private int currentRotation = 0;
    
    private ActivityResultLauncher<String> requestPermissionLauncher;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize views
        tvSelectedVideo = findViewById(R.id.tvSelectedVideo);
        etCustomAngle = findViewById(R.id.etCustomAngle);
        btnRotateLeft = findViewById(R.id.btnRotateLeft);
        btnRotateRight = findViewById(R.id.btnRotateRight);
        btnProcess = findViewById(R.id.btnProcess);
        progressBar = findViewById(R.id.progressBar);
        tvProgress = findViewById(R.id.tvProgress);
        
        Button btnSelectVideo = findViewById(R.id.btnSelectVideo);
        
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
        
        // Disable buttons during processing
        btnProcess.setEnabled(false);
        btnRotateLeft.setEnabled(false);
        btnRotateRight.setEnabled(false);
        
        // Show progress
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        tvProgress.setText("Processing: 0%");
        
        // Create output file
        File outputDir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES), "SimpleVideoEditor");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        String outputFileName = "edited_" + System.currentTimeMillis() + ".mp4";
        File outputFile = new File(outputDir, outputFileName);
        
        // Process video
        VideoProcessor processor = new VideoProcessor(videoFilePath, outputFile.getAbsolutePath());
        processor.setRotation(currentRotation);
        processor.setProgressCallback(new VideoProcessor.ProgressCallback() {
            @Override
            public void onProgress(int percent) {
                runOnUiThread(() -> {
                    progressBar.setProgress(percent);
                    tvProgress.setText("Processing: " + percent + "%");
                });
            }
            
            @Override
            public void onComplete(boolean success, String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvProgress.setVisibility(View.GONE);
                    
                    btnProcess.setEnabled(true);
                    btnRotateLeft.setEnabled(true);
                    btnRotateRight.setEnabled(true);
                    
                    if (success) {
                        Toast.makeText(MainActivity.this, 
                            "Video saved to: " + outputFile.getAbsolutePath(), 
                            Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        
        processor.process();
    }
}
