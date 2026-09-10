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
    private static final int REQUEST_CODE_INTRO = 1001;
    private static final int REQUEST_CODE_MUSIC = 1002;
    
    private Uri selectedVideoUri;
    private String videoFilePath;
    
    private Uri selectedIntroUri;
    private String introFilePath;
    
    private Uri selectedMusicUri;
    private String musicFilePath;
    
    private TextView tvSelectedVideo;
    private CheckBox cbFastMode;
    private TextView tvModeHint;
    private EditText etCustomAngle;
    private Spinner spinnerResolution;
    private Spinner spinnerSpeed;
    private EditText etOverlayText;
    private TextView tvResolutionLabel;
    private TextView tvSpeedLabel;
    private TextView tvOverlayLabel;
    private Button btnSelectIntro;
    private TextView tvSelectedIntro;
    private Button btnSelectMusic;
    private TextView tvSelectedMusic;
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
        spinnerSpeed = findViewById(R.id.spinnerSpeed);
        etOverlayText = findViewById(R.id.etOverlayText);
        tvResolutionLabel = findViewById(R.id.tvResolutionLabel);
        tvSpeedLabel = findViewById(R.id.tvSpeedLabel);
        tvOverlayLabel = findViewById(R.id.tvOverlayLabel);
        btnSelectIntro = findViewById(R.id.btnSelectIntro);
        tvSelectedIntro = findViewById(R.id.tvSelectedIntro);
        btnSelectMusic = findViewById(R.id.btnSelectMusic);
        tvSelectedMusic = findViewById(R.id.tvSelectedMusic);
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
        
        // Setup speed spinner
        ArrayAdapter<CharSequence> speedAdapter = ArrayAdapter.createFromResource(this,
                R.array.speed_options, android.R.layout.simple_spinner_item);
        speedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSpeed.setAdapter(speedAdapter);
        spinnerSpeed.setSelection(2); // Default to 1.0x (Normal)
        
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
        
        // Select intro video button
        btnSelectIntro.setOnClickListener(v -> openIntroPicker());
        
        // Select background music button
        btnSelectMusic.setOnClickListener(v -> openMusicPicker());
        
        // Fast mode checkbox listener
        cbFastMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Fast mode: disable resolution, speed, overlay, intro, and music
                spinnerResolution.setEnabled(false);
                spinnerSpeed.setEnabled(false);
                etOverlayText.setEnabled(false);
                btnSelectIntro.setEnabled(false);
                btnSelectMusic.setEnabled(false);
                tvResolutionLabel.setEnabled(false);
                tvSpeedLabel.setEnabled(false);
                tvOverlayLabel.setEnabled(false);
                tvModeHint.setText(R.string.fast_mode_hint);
                tvModeHint.setBackgroundColor(0xFFFFF3E0); // Light orange
            } else {
                // Full mode: enable all options
                spinnerResolution.setEnabled(true);
                spinnerSpeed.setEnabled(true);
                etOverlayText.setEnabled(true);
                btnSelectIntro.setEnabled(true);
                btnSelectMusic.setEnabled(true);
                tvResolutionLabel.setEnabled(true);
                tvSpeedLabel.setEnabled(true);
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
    
    private void openIntroPicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("video/*");
        startActivityForResult(intent, REQUEST_CODE_INTRO);
    }
    
    private void openMusicPicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("audio/*");
        startActivityForResult(intent, REQUEST_CODE_MUSIC);
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
        } else if (requestCode == REQUEST_CODE_INTRO && resultCode == RESULT_OK && data != null) {
            selectedIntroUri = data.getData();
            if (selectedIntroUri != null) {
                // Get intro video name
                String introName = getFileName(selectedIntroUri);
                tvSelectedIntro.setText("Selected: " + introName);
                
                // Copy to cache for processing
                copyIntroToCache();
            }
        } else if (requestCode == REQUEST_CODE_MUSIC && resultCode == RESULT_OK && data != null) {
            selectedMusicUri = data.getData();
            if (selectedMusicUri != null) {
                // Get music file name
                String musicName = getFileName(selectedMusicUri);
                tvSelectedMusic.setText(getString(R.string.music_selected, musicName));
                
                // Copy to cache for processing
                copyMusicToCache();
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
    
    private void copyIntroToCache() {
        new Thread(() -> {
            try {
                File cacheFile = new File(getCacheDir(), "intro_video.mp4");
                
                InputStream inputStream = getContentResolver().openInputStream(selectedIntroUri);
                FileOutputStream outputStream = new FileOutputStream(cacheFile);
                
                byte[] buffer = new byte[8192];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
                
                inputStream.close();
                outputStream.close();
                
                introFilePath = cacheFile.getAbsolutePath();
                
                runOnUiThread(() -> {
                    Toast.makeText(this, "Intro video ready", Toast.LENGTH_SHORT).show();
                });
                
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error loading intro: " + e.getMessage(), 
                        Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    private void copyMusicToCache() {
        new Thread(() -> {
            try {
                // Determine file extension from URI
                String fileName = getFileName(selectedMusicUri);
                String extension = ".mp3";
                if (fileName != null && fileName.contains(".")) {
                    extension = fileName.substring(fileName.lastIndexOf("."));
                }
                
                File cacheFile = new File(getCacheDir(), "background_music" + extension);
                
                InputStream inputStream = getContentResolver().openInputStream(selectedMusicUri);
                FileOutputStream outputStream = new FileOutputStream(cacheFile);
                
                byte[] buffer = new byte[8192];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
                
                inputStream.close();
                outputStream.close();
                
                musicFilePath = cacheFile.getAbsolutePath();
                
                runOnUiThread(() -> {
                    Toast.makeText(this, "Background music ready", Toast.LENGTH_SHORT).show();
                });
                
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error loading music: " + e.getMessage(), 
                        Toast.LENGTH_LONG).show();
                    // Reset music selection on error
                    musicFilePath = null;
                    tvSelectedMusic.setText(R.string.no_music_selected);
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
    
    private float getSelectedSpeed() {
        int position = spinnerSpeed.getSelectedItemPosition();
        switch (position) {
            case 0: return 0.5f;
            case 1: return 0.75f;
            case 2: return 1.0f;
            case 3: return 1.25f;
            case 4: return 1.5f;
            case 5: return 2.0f;
            default: return 1.0f;
        }
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
            boolean success = false;  // Initialize to avoid compilation error
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
                // Full mode: rotation + scaling + overlay + speed + intro
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
                
                // Get playback speed
                float speed = getSelectedSpeed();
                
                final int finalWidth = targetWidth;
                final int finalHeight = targetHeight;
                final float finalSpeed = speed;
                
                // Check if we need to add intro first
                String processingInput = videoFilePath;
                boolean shouldContinue = true;
                
                if (introFilePath != null) {
                    runOnUiThread(() -> tvProgress.setText("Adding intro video..."));
                    
                    // Create temp file for merged video
                    File tempMerged = new File(getCacheDir(), "temp_merged.mp4");
                    String tempMergedPath = tempMerged.getAbsolutePath();
                    
                    // Merge intro + main video
                    boolean mergeSuccess = VideoMerger.addIntro(
                        introFilePath,
                        videoFilePath,
                        tempMergedPath,
                        (progress, message) -> runOnUiThread(() -> {
                            if (progress >= 0) {
                                progressBar.setProgress(progress / 2); // First 50% for merging
                                tvProgress.setText("Merging intro: " + message);
                            }
                        })
                    );
                    
                    if (!mergeSuccess) {
                        success = false;
                        shouldContinue = false;
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Failed to add intro", Toast.LENGTH_LONG).show();
                        });
                    } else {
                        processingInput = tempMergedPath;
                    }
                }
                
                if (shouldContinue) {
                    // Determine final output path
                    String videoProcessOutput = outputPath;
                    
                    // If we have background music, we need a temporary processed video
                    if (musicFilePath != null) {
                        File tempProcessed = new File(getCacheDir(), "temp_processed_" + System.currentTimeMillis() + ".mp4");
                        videoProcessOutput = tempProcessed.getAbsolutePath();
                    }
                    
                    final String finalVideoOutput = videoProcessOutput;
                    
                    runOnUiThread(() -> tvProgress.setText(
                        String.format("Full Mode: %dx%d → %dx%d (%.1fx speed)", 
                            originalWidth, originalHeight, finalWidth, finalHeight, finalSpeed)
                    ));
                    
                    // Step 1: Process video (rotation/scaling/overlay/speed)
                    success = VideoProcessorOptimized.processVideoOptimized(
                        processingInput,
                        finalVideoOutput,
                        currentRotation,
                        targetWidth,
                        targetHeight,
                        overlayText,
                        speed,
                        (progress, message) -> runOnUiThread(() -> {
                            if (progress >= 0) {
                                // Calculate progress based on whether we have music
                                int displayProgress;
                                if (musicFilePath != null) {
                                    // 0-70% for video processing, 70-100% for audio replacement
                                    int baseProgress = (introFilePath != null) ? 50 : 0;
                                    displayProgress = baseProgress + (progress * 7 / 10 * (100 - baseProgress) / 100);
                                } else {
                                    // Normal progress calculation
                                    displayProgress = (introFilePath != null) ? (50 + progress / 2) : progress;
                                }
                                progressBar.setProgress(displayProgress);
                                tvProgress.setText(message);
                            } else {
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                            }
                        })
                    );
                    
                    // Step 2: Replace audio if background music is selected
                    if (success && musicFilePath != null) {
                        runOnUiThread(() -> tvProgress.setText("Replacing audio with background music..."));
                        
                        success = AudioReplacer.replaceAudio(
                            finalVideoOutput,
                            musicFilePath,
                            outputPath,
                            (progress, message) -> runOnUiThread(() -> {
                                if (progress >= 0) {
                                    // Map audio progress to 70-100%
                                    int baseProgress = (introFilePath != null) ? 50 : 0;
                                    int videoProgress = 70 * (100 - baseProgress) / 100;
                                    int audioProgress = progress * 30 / 100 * (100 - baseProgress) / 100;
                                    int displayProgress = baseProgress + videoProgress + audioProgress;
                                    progressBar.setProgress(displayProgress);
                                    tvProgress.setText(message);
                                } else {
                                    // Audio replacement failed, but video is processed
                                    // Keep the video without audio replacement
                                    Toast.makeText(MainActivity.this, 
                                        "Audio replacement failed, keeping original audio", 
                                        Toast.LENGTH_LONG).show();
                                }
                            })
                        );
                        
                        // Clean up temporary processed video
                        if (!success) {
                            // If audio replacement failed, copy temp video to output
                            File tempFile = new File(finalVideoOutput);
                            File outputFileObj = new File(outputPath);
                            try {
                                java.nio.file.Files.copy(
                                    tempFile.toPath(), 
                                    outputFileObj.toPath(), 
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                                );
                                success = true; // Still consider it success since video is processed
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        
                        // Delete temp file
                        new File(finalVideoOutput).delete();
                    }
                }
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
