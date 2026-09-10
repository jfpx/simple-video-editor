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
    private Button btnSelectMusic;
    private TextView tvSelectedMusic;
    private Button btnSelectIntro;  // Keep old intro video feature
    private TextView tvSelectedIntro;  // Keep old intro video feature
    private Button btnRotateLeft, btnRotateRight;
    private Button btnProcess;
    private ProgressBar progressBar;
    private TextView tvProgress;
    
    // Trimming controls
    private CheckBox cbEnableTrim;
    private View layoutTrimControls;
    private EditText etTrimStart, etTrimEnd;
    
    // Volume controls
    private CheckBox cbEnableVolume;
    private View layoutVolumeControls;
    private Spinner spinnerVolume;
    
    // Intro template controls
    private CheckBox cbEnableIntro;
    private View layoutIntroControls;
    private Spinner spinnerIntroTemplate;
    private EditText etIntroText;
    private Button btnSaveTemplate;
    private Button btnManageTemplates;
    private TextView tvTemplatePreview;
    
    private IntroTemplateManager templateManager;
    private IntroTemplate currentTemplate;
    
    private int currentRotation = 0;
    private int originalWidth = 0;
    private int originalHeight = 0;
    
    private ActivityResultLauncher<String> requestPermissionLauncher;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize template manager
        templateManager = new IntroTemplateManager(this);
        
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
        btnSelectIntro = findViewById(R.id.btnSelectIntro);  // Old intro video button
        tvSelectedIntro = findViewById(R.id.tvSelectedIntro);  // Old intro video text
        btnSelectMusic = findViewById(R.id.btnSelectMusic);
        tvSelectedMusic = findViewById(R.id.tvSelectedMusic);
        btnRotateLeft = findViewById(R.id.btnRotateLeft);
        btnRotateRight = findViewById(R.id.btnRotateRight);
        btnProcess = findViewById(R.id.btnProcess);
        progressBar = findViewById(R.id.progressBar);
        tvProgress = findViewById(R.id.tvProgress);
        
        // Trimming controls
        cbEnableTrim = findViewById(R.id.cbEnableTrim);
        layoutTrimControls = findViewById(R.id.layoutTrimControls);
        etTrimStart = findViewById(R.id.etTrimStart);
        etTrimEnd = findViewById(R.id.etTrimEnd);
        
        // Volume controls
        cbEnableVolume = findViewById(R.id.cbEnableVolume);
        layoutVolumeControls = findViewById(R.id.layoutVolumeControls);
        spinnerVolume = findViewById(R.id.spinnerVolume);
        
        // Intro template controls
        cbEnableIntro = findViewById(R.id.cbEnableIntro);
        layoutIntroControls = findViewById(R.id.layoutIntroControls);
        spinnerIntroTemplate = findViewById(R.id.spinnerIntroTemplate);
        etIntroText = findViewById(R.id.etIntroText);
        btnSaveTemplate = findViewById(R.id.btnSaveTemplate);
        btnManageTemplates = findViewById(R.id.btnManageTemplates);
        tvTemplatePreview = findViewById(R.id.tvTemplatePreview);
        
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
        
        // Setup volume spinner
        ArrayAdapter<CharSequence> volumeAdapter = ArrayAdapter.createFromResource(this,
                R.array.volume_options, android.R.layout.simple_spinner_item);
        volumeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerVolume.setAdapter(volumeAdapter);
        spinnerVolume.setSelection(2); // Default to 100% (Original)
        
        // Setup intro template spinner
        setupIntroTemplateSpinner();
        
        // Setup trimming checkbox listener
        cbEnableTrim.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layoutTrimControls.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
        
        // Setup volume checkbox listener
        cbEnableVolume.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layoutVolumeControls.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
        
        // Setup intro template checkbox listener
        cbEnableIntro.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layoutIntroControls.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isChecked && currentTemplate != null) {
                updateTemplatePreview();
            }
        });
        
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
        
        // Select intro video button (old feature)
        btnSelectIntro.setOnClickListener(v -> openIntroPicker());
        
        // Select background music button
        btnSelectMusic.setOnClickListener(v -> openMusicPicker());
        
        // Intro text change listener (update preview)
        etIntroText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (cbEnableIntro.isChecked()) {
                    updateTemplatePreview();
                }
            }
            
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
        
        // Fast mode checkbox listener
        cbFastMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Fast mode: disable resolution, speed, overlay, intro, and music
                spinnerResolution.setEnabled(false);
                spinnerSpeed.setEnabled(false);
                etOverlayText.setEnabled(false);
                cbEnableIntro.setEnabled(false);
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
                cbEnableIntro.setEnabled(true);
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
    
    private float getSelectedVolume() {
        int position = spinnerVolume.getSelectedItemPosition();
        switch (position) {
            case 0: return 0.5f;   // 50%
            case 1: return 0.75f;  // 75%
            case 2: return 1.0f;   // 100%
            case 3: return 1.25f;  // 125%
            case 4: return 1.5f;   // 150%
            case 5: return 2.0f;   // 200%
            case 6: return 3.0f;   // 300%
            default: return 1.0f;
        }
    }
    
    private void processVideo() {
        if (videoFilePath == null) {
            Toast.makeText(this, "Please select a video first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Validate trim times if trimming is enabled
        if (cbEnableTrim.isChecked()) {
            try {
                float startTime = Float.parseFloat(etTrimStart.getText().toString().trim());
                float endTime = Float.parseFloat(etTrimEnd.getText().toString().trim());
                if (startTime < 0 || endTime <= startTime) {
                    Toast.makeText(this, "Invalid trim times (end must be > start)", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid trim time format", Toast.LENGTH_SHORT).show();
                return;
            }
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
                    // Step: Trim video if enabled (do this before main processing to reduce data)
                    if (cbEnableTrim.isChecked()) {
                        runOnUiThread(() -> tvProgress.setText("Trimming video..."));
                        
                        float trimStart = Float.parseFloat(etTrimStart.getText().toString().trim());
                        float trimEnd = Float.parseFloat(etTrimEnd.getText().toString().trim());
                        
                        File tempTrimmed = new File(getCacheDir(), "temp_trimmed.mp4");
                        String tempTrimmedPath = tempTrimmed.getAbsolutePath();
                        
                        boolean[] trimSuccess = {false};
                        VideoTrimmer.trimVideo(
                            processingInput,
                            tempTrimmedPath,
                            (long)(trimStart * 1000),
                            (long)(trimEnd * 1000),
                            new VideoTrimmer.ProgressCallback() {
                                @Override
                                public void onProgress(int progress) {
                                    runOnUiThread(() -> {
                                        int baseProgress = (introFilePath != null) ? 50 : 0;
                                        int trimProgress = baseProgress + (progress * 15 / 100); // 15% for trimming
                                        progressBar.setProgress(trimProgress);
                                        tvProgress.setText("Trimming: " + progress + "%");
                                    });
                                }
                                
                                @Override
                                public void onComplete(String output) {
                                    trimSuccess[0] = true;
                                }
                                
                                @Override
                                public void onError(String error) {
                                    runOnUiThread(() -> {
                                        Toast.makeText(MainActivity.this, "Trim failed: " + error, Toast.LENGTH_LONG).show();
                                    });
                                }
                            }
                        );
                        
                        // Wait for trim to complete (synchronous)
                        try {
                            Thread.sleep(100);
                            while (!trimSuccess[0]) {
                                Thread.sleep(100);
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        
                        if (trimSuccess[0]) {
                            processingInput = tempTrimmedPath;
                        } else {
                            success = false;
                            shouldContinue = false;
                        }
                    }
                }
                
                if (shouldContinue) {
                    // Determine final output path
                    String videoProcessOutput = outputPath;
                    
                    // If we have background music OR volume adjustment, we need a temporary processed video
                    if (musicFilePath != null || cbEnableVolume.isChecked()) {
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
                    
                    // Step 2: Replace audio if background music OR adjust volume
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
                    } else if (success && cbEnableVolume.isChecked()) {
                        // Volume adjustment (without music replacement)
                        runOnUiThread(() -> tvProgress.setText("Adjusting volume..."));
                        
                        float volumeGain = getSelectedVolume();
                        
                        boolean[] volumeSuccess = {false};
                        AudioVolumeAdjuster.adjustVolume(
                            finalVideoOutput,
                            outputPath,
                            volumeGain,
                            new AudioVolumeAdjuster.ProgressCallback() {
                                @Override
                                public void onProgress(int progress) {
                                    runOnUiThread(() -> {
                                        int baseProgress = (introFilePath != null) ? 50 : 0;
                                        int videoProgress = 70 * (100 - baseProgress) / 100;
                                        int audioProgress = progress * 30 / 100 * (100 - baseProgress) / 100;
                                        int displayProgress = baseProgress + videoProgress + audioProgress;
                                        progressBar.setProgress(displayProgress);
                                        tvProgress.setText("Volume: " + progress + "%");
                                    });
                                }
                                
                                @Override
                                public void onComplete(String output) {
                                    volumeSuccess[0] = true;
                                }
                                
                                @Override
                                public void onError(String error) {
                                    runOnUiThread(() -> {
                                        Toast.makeText(MainActivity.this, 
                                            "Volume adjustment failed: " + error, 
                                            Toast.LENGTH_LONG).show();
                                    });
                                }
                            }
                        );
                        
                        // Wait for volume adjustment to complete
                        try {
                            Thread.sleep(100);
                            while (!volumeSuccess[0]) {
                                Thread.sleep(100);
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        
                        // Clean up temporary processed video
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
    
    /**
     * 设置片头模板 Spinner
     */
    private void setupIntroTemplateSpinner() {
        String[] templateNames = templateManager.getTemplateNames();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, 
            android.R.layout.simple_spinner_item, templateNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerIntroTemplate.setAdapter(adapter);
        
        // 加载最后使用的模板
        currentTemplate = templateManager.getLastUsedTemplate();
        if (currentTemplate != null) {
            etIntroText.setText(currentTemplate.getText());
            updateTemplatePreview();
            
            // 选中对应的模板
            for (int i = 0; i < templateNames.length; i++) {
                if (templateNames[i].equals(currentTemplate.getName())) {
                    spinnerIntroTemplate.setSelection(i);
                    break;
                }
            }
        }
        
        // 模板选择监听
        spinnerIntroTemplate.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                String templateName = (String) parent.getItemAtPosition(position);
                currentTemplate = templateManager.getTemplate(templateName);
                if (currentTemplate != null) {
                    etIntroText.setText(currentTemplate.getText());
                    templateManager.setLastUsedTemplate(templateName);
                    updateTemplatePreview();
                }
            }
            
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        
        // 保存模板按钮
        btnSaveTemplate.setOnClickListener(v -> showSaveTemplateDialog());
        
        // 管理模板按钮
        btnManageTemplates.setOnClickListener(v -> showManageTemplatesDialog());
    }
    
    /**
     * 更新模板预览
     */
    private void updateTemplatePreview() {
        if (currentTemplate == null) return;
        
        // 应用模板样式到预览
        tvTemplatePreview.setBackgroundColor(currentTemplate.getBackgroundColor());
        tvTemplatePreview.setTextColor(currentTemplate.getTextColor());
        tvTemplatePreview.setTextSize(currentTemplate.getTextSize() / 2f); // 缩放显示
        
        // 设置字体风格
        switch (currentTemplate.getFontStyle()) {
            case "bold":
                tvTemplatePreview.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                break;
            case "italic":
                tvTemplatePreview.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC));
                break;
            case "bold_italic":
                tvTemplatePreview.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC));
                break;
            default:
                tvTemplatePreview.setTypeface(android.graphics.Typeface.DEFAULT);
        }
        
        String text = etIntroText.getText().toString().trim();
        if (text.isEmpty()) {
            text = "Preview";
        }
        tvTemplatePreview.setText(text);
    }
    
    /**
     * 显示保存模板对话框
     */
    private void showSaveTemplateDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(R.string.save_as_template);
        
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.enter_template_name);
        if (currentTemplate != null) {
            input.setText(currentTemplate.getName());
        }
        builder.setView(input);
        
        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Template name required", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 创建新模板或更新现有模板
            IntroTemplate newTemplate = currentTemplate != null ? currentTemplate.copy() : new IntroTemplate();
            newTemplate.setName(name);
            newTemplate.setText(etIntroText.getText().toString().trim());
            
            if (templateManager.saveTemplate(newTemplate)) {
                Toast.makeText(this, R.string.template_saved, Toast.LENGTH_SHORT).show();
                setupIntroTemplateSpinner(); // 刷新列表
            } else {
                Toast.makeText(this, "Failed to save template", Toast.LENGTH_SHORT).show();
            }
        });
        
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    /**
     * 显示管理模板对话框
     */
    private void showManageTemplatesDialog() {
        String[] templateNames = templateManager.getTemplateNames();
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(R.string.manage_templates);
        builder.setItems(templateNames, (dialog, which) -> {
            String selectedName = templateNames[which];
            
            // 显示删除确认
            new android.app.AlertDialog.Builder(this)
                .setTitle("Delete Template")
                .setMessage(getString(R.string.confirm_delete_template, selectedName))
                .setPositiveButton("Delete", (d, w) -> {
                    if (templateManager.deleteTemplate(selectedName)) {
                        Toast.makeText(this, R.string.template_deleted, Toast.LENGTH_SHORT).show();
                        setupIntroTemplateSpinner(); // 刷新列表
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
        builder.setNegativeButton("Close", null);
        builder.show();
    }
}
