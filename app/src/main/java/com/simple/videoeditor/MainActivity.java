package com.simple.videoeditor;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    private Button btnSelectVideo, btnRotateLeft, btnRotateRight, btnProcess;
    private EditText etCustomAngle, etCropTop, etCropBottom, etCropLeft, etCropRight;
    private TextView tvSelectedVideo, tvProgress;
    private ProgressBar progressBar;
    
    private Uri selectedVideoUri;
    private String selectedVideoPath;
    private double rotationAngle = 0;
    
    private ActivityResultLauncher<Intent> videoPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        setupVideoPickerLauncher();
        setupClickListeners();
        checkPermissions();
    }
    
    private void initViews() {
        btnSelectVideo = findViewById(R.id.btnSelectVideo);
        btnRotateLeft = findViewById(R.id.btnRotateLeft);
        btnRotateRight = findViewById(R.id.btnRotateRight);
        btnProcess = findViewById(R.id.btnProcess);
        
        etCustomAngle = findViewById(R.id.etCustomAngle);
        etCropTop = findViewById(R.id.etCropTop);
        etCropBottom = findViewById(R.id.etCropBottom);
        etCropLeft = findViewById(R.id.etCropLeft);
        etCropRight = findViewById(R.id.etCropRight);
        
        tvSelectedVideo = findViewById(R.id.tvSelectedVideo);
        tvProgress = findViewById(R.id.tvProgress);
        progressBar = findViewById(R.id.progressBar);
    }
    
    private void setupVideoPickerLauncher() {
        videoPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    selectedVideoUri = result.getData().getData();
                    selectedVideoPath = getPathFromUri(selectedVideoUri);
                    if (selectedVideoPath != null) {
                        tvSelectedVideo.setText(new File(selectedVideoPath).getName());
                    } else {
                        tvSelectedVideo.setText("Error: Could not get video path");
                    }
                }
            }
        );
    }
    
    private void setupClickListeners() {
        btnSelectVideo.setOnClickListener(v -> selectVideo());
        
        btnRotateLeft.setOnClickListener(v -> {
            rotationAngle -= 90;
            updateAngleDisplay();
        });
        
        btnRotateRight.setOnClickListener(v -> {
            rotationAngle += 90;
            updateAngleDisplay();
        });
        
        btnProcess.setOnClickListener(v -> processVideo());
    }
    
    private void updateAngleDisplay() {
        etCustomAngle.setText(String.valueOf(rotationAngle));
    }
    
    private void selectVideo() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        videoPickerLauncher.launch(intent);
    }
    
    private void processVideo() {
        if (selectedVideoPath == null) {
            Toast.makeText(this, R.string.no_video_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Get rotation angle
        String angleStr = etCustomAngle.getText().toString().trim();
        if (!angleStr.isEmpty()) {
            rotationAngle = Double.parseDouble(angleStr);
        }
        
        // Get crop values
        int cropTop = getIntValue(etCropTop);
        int cropBottom = getIntValue(etCropBottom);
        int cropLeft = getIntValue(etCropLeft);
        int cropRight = getIntValue(etCropRight);
        
        // Show progress
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        tvProgress.setText(R.string.processing);
        btnProcess.setEnabled(false);
        
        // Build FFmpeg filter
        String filter = buildFFmpegFilter(rotationAngle, cropTop, cropBottom, cropLeft, cropRight);
        
        // Generate output path
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File outputDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SimpleVideoEditor");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        String outputPath = new File(outputDir, "edited_" + timestamp + ".mp4").getAbsolutePath();
        
        // Execute FFmpeg command
        String command = String.format("-i \"%s\" -vf \"%s\" -c:v libx264 -preset ultrafast -c:a copy \"%s\"",
                selectedVideoPath, filter, outputPath);
        
        FFmpegKit.executeAsync(command, session -> {
            ReturnCode returnCode = session.getReturnCode();
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                tvProgress.setVisibility(View.GONE);
                btnProcess.setEnabled(true);
                
                if (ReturnCode.isSuccess(returnCode)) {
                    Toast.makeText(MainActivity.this, R.string.success + "\n" + outputPath, Toast.LENGTH_LONG).show();
                    scanMediaFile(outputPath);
                } else {
                    String error = session.getFailStackTrace();
                    Toast.makeText(MainActivity.this, R.string.error + ": " + error, Toast.LENGTH_LONG).show();
                }
            });
        }, log -> {
            // Log callback
        }, statistics -> {
            // Update progress based on statistics
            int progress = (int) (statistics.getTime() / 1000.0); // rough estimate
            runOnUiThread(() -> {
                tvProgress.setText(getString(R.string.processing) + " " + progress + "s");
            });
        });
    }
    
    private String buildFFmpegFilter(double angle, int top, int bottom, int left, int right) {
        StringBuilder filter = new StringBuilder();
        
        // Add rotation if needed
        if (angle != 0) {
            double radians = angle * Math.PI / 180.0;
            filter.append("rotate=").append(radians).append(":fillcolor=black");
        }
        
        // Add crop if needed
        if (top > 0 || bottom > 0 || left > 0 || right > 0) {
            if (filter.length() > 0) {
                filter.append(",");
            }
            filter.append("crop=iw-").append(left + right)
                  .append(":ih-").append(top + bottom)
                  .append(":").append(left)
                  .append(":").append(top);
        }
        
        return filter.toString();
    }
    
    private int getIntValue(EditText editText) {
        String text = editText.getText().toString().trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    private String getPathFromUri(Uri uri) {
        String[] projection = {MediaStore.Video.Media.DATA};
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
                return cursor.getString(columnIndex);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    private void scanMediaFile(String path) {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        File file = new File(path);
        Uri contentUri = Uri.fromFile(file);
        mediaScanIntent.setData(contentUri);
        sendBroadcast(mediaScanIntent);
    }
    
    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses READ_MEDIA_VIDEO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.READ_MEDIA_VIDEO}, 
                    PERMISSION_REQUEST_CODE);
            }
        } else {
            // Older versions use READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, 
                                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 
                    PERMISSION_REQUEST_CODE);
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
            }
        }
    }
}
