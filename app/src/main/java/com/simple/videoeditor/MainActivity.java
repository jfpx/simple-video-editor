package com.simple.videoeditor;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@UnstableApi
public class MainActivity extends AppCompatActivity {
    private static final int VIDEO_PICK_CODE = 1000;
    private static final int REQUEST_CODE_INTRO = 1001;
    private static final int REQUEST_CODE_MUSIC = 1002;
    private static final int REQUEST_CODE_WATERMARK = 1003;
    private static final int REQUEST_CODE_APPEND = 1004;
    private static final int REQUEST_CODE_OUTPUT_TREE = 1005;
    private static final float[] SPEEDS = {.5f, .75f, 1f, 1.25f, 1.5f, 2f};
    private static final float[] VOLUMES = {0f, .5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f};
    private static final int[] HEIGHTS = {0, 1080, 720, 480};

    private LoadedVideo selectedMainVideo;
    private LoadedVideo selectedIntroVideo;
    private LoadedVideo selectedMusic;
    private final ArrayList<LoadedVideo> appendedVideos = new ArrayList<>();
    private String watermarkFilePath;
    private PngWatermark watermarkImage;
    private CheckBox cbEnableWatermark;
    private CheckBox cbEnableMerge;
    private EditText etWatermarkWidth, etWatermarkX, etWatermarkY;
    private Bitmap currentThumbnail, previewBitmap;
    private PublishedVideo lastVideo;
    private final ExecutorService publicationWorker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean publicationCancelled =
            new java.util.concurrent.atomic.AtomicBoolean();
    private boolean publishing;
    private int previewGeneration;
    private Runnable previewRequest;
    private Future<?> previewTask;
    private boolean destroyed, loading, exporting, geometryValid;
    private boolean cleanupSelectionsWhenExportStops;
    private int loadGeneration;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService loader = Executors.newSingleThreadExecutor();
    private Future<?> loadTask;
    private Media3ExportEngine exportEngine;

    private TextView tvSelectedVideo, tvErrorDetails, tvSuccessTime, tvOutputPath;
    private TextView tvDebugInfo, tvProgress, tvGeometryStatus, tvTemplatePreview;
    private TextView tvMergeSemantics, tvMergeSummary;
    private ImageView ivVideoThumbnail;
    private View svErrorContainer, layoutSuccessContainer, svDebugContainer;
    private Button btnSelectVideo, btnProcess, btnCancelExport, btnSelfTest;
    private Button btnSelectExtras, btnClearExtras;
    private CheckBox cbCompatibilityEncoding, cbEnableTrim, cbEnableVolume, cbEnableIntro;
    private EditText etCustomAngle, etTrimStart, etTrimEnd, etOverlayText;
    private EditText etCropLeft, etCropTop, etCropRight, etCropBottom, etIntroText;
    private Spinner spinnerResolution, spinnerSpeed, spinnerVolume, spinnerIntroTemplate;
    private Button btnSaveTemplate, btnManageTemplates;
    private ProgressBar progressBar;
    private LinearLayout layoutMergeList;
    private IntroTemplateManager templateManager;
    private IntroTemplate currentTemplate;
    private TitleAppearanceControls titleAppearanceControls;
    private TitleBackgroundControls titleBackgroundControls;
    private WholeEditPresets wholePresets;
    private OfflineMusicControls offlineMusic;
    private boolean applyingPreset;
    private ColorAdjustmentControls colorControls;
    private boolean updatingTitleControls;
    private CheckBox cbEnableBorder;
    private Spinner spinnerBorderColor, spinnerBorderWidth, spinnerBorderScope;
    private EditorPanels editorPanels;
    private boolean restoringUi = true, savedSelections, restoringAssets;
    private int pendingPickerCode, pendingPickerResult;
    private Intent pendingPickerData;
    private boolean restoreResultVisible;
    private int selectedTemplatePosition = -1;
    private static final String[] TITLE_STYLES = {"legacy", "fade", "slide", "typewriter", "scale", "lower-third", "dissolve"};
    private static final String[] TITLE_FONTS = {"sans-serif", "serif", "monospace"};
    private static final String[] TITLE_WEIGHTS = {"normal", "bold", "italic", "bold_italic"};
    private static final String[] TITLE_ALIGNS = {"left", "center", "right"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiLocales.initialize(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        exportEngine = new Media3ExportEngine(this);
        templateManager = new IntroTemplateManager(this);
        wholePresets = new WholeEditPresets(this);
        tvSelectedVideo = findViewById(R.id.tvSelectedVideo);
        ivVideoThumbnail = findViewById(R.id.ivVideoThumbnail);
        cbEnableMerge = findViewById(R.id.cbEnableMerge);
        tvMergeSemantics = findViewById(R.id.tvMergeSemantics);
        tvMergeSummary = findViewById(R.id.tvMergeSummary);
        btnSelectExtras = findViewById(R.id.btnSelectExtras);
        btnClearExtras = findViewById(R.id.btnClearExtras);
        layoutMergeList = findViewById(R.id.layoutMergeList);
        svErrorContainer = findViewById(R.id.svErrorContainer);
        tvErrorDetails = findViewById(R.id.tvErrorDetails);
        layoutSuccessContainer = findViewById(R.id.layoutSuccessContainer);
        tvSuccessTime = findViewById(R.id.tvSuccessTime);
        tvOutputPath = findViewById(R.id.tvOutputPath);
        svDebugContainer = findViewById(R.id.svDebugContainer);
        tvDebugInfo = findViewById(R.id.tvDebugInfo);
        tvProgress = findViewById(R.id.tvProgress);
        tvGeometryStatus = findViewById(R.id.tvGeometryStatus);
        cbCompatibilityEncoding = findViewById(R.id.cbCompatibilityEncoding);
        cbEnableTrim = findViewById(R.id.cbEnableTrim);
        cbEnableVolume = findViewById(R.id.cbEnableVolume);
        cbEnableIntro = findViewById(R.id.cbEnableIntro);
        etCustomAngle = findViewById(R.id.etCustomAngle);
        etTrimStart = findViewById(R.id.etTrimStart);
        etTrimEnd = findViewById(R.id.etTrimEnd);
        etOverlayText = findViewById(R.id.etOverlayText);
        etCropLeft = findViewById(R.id.etCropLeft);
        etCropTop = findViewById(R.id.etCropTop);
        etCropRight = findViewById(R.id.etCropRight);
        etCropBottom = findViewById(R.id.etCropBottom);
        spinnerResolution = findViewById(R.id.spinnerResolution);
        spinnerSpeed = findViewById(R.id.spinnerSpeed);
        spinnerVolume = findViewById(R.id.spinnerVolume);
        spinnerIntroTemplate = findViewById(R.id.spinnerIntroTemplate);
        etIntroText = findViewById(R.id.etIntroText);
        btnSaveTemplate = findViewById(R.id.btnSaveTemplate);
        btnManageTemplates = findViewById(R.id.btnManageTemplates);
        tvTemplatePreview = findViewById(R.id.tvTemplatePreview);
        btnSelectVideo = findViewById(R.id.btnSelectVideo);
        btnProcess = findViewById(R.id.btnProcess);
        btnCancelExport = findViewById(R.id.btnCancelExport);
        btnSelfTest = findViewById(R.id.btnSelfTest);
        progressBar = findViewById(R.id.progressBar);
        cbEnableWatermark = findViewById(R.id.cbEnableWatermark);
        etWatermarkWidth = findViewById(R.id.etWatermarkWidth);
        etWatermarkX = findViewById(R.id.etWatermarkX);
        etWatermarkY = findViewById(R.id.etWatermarkY);
        editorPanels = new EditorPanels(this, this::updateSpatialPreview);
        colorControls = new ColorAdjustmentControls(this, this::updateSpatialPreview);
        cbEnableBorder = findViewById(R.id.cbEnableBorder);
        spinnerBorderColor = findViewById(R.id.spinnerBorderColor);
        spinnerBorderWidth = findViewById(R.id.spinnerBorderWidth);
        spinnerBorderScope = findViewById(R.id.spinnerBorderScope);
        setOptions(spinnerBorderColor, getResources().getStringArray(R.array.editor_border_colors));
        setOptions(spinnerBorderWidth, getResources().getStringArray(R.array.editor_border_widths));
        setOptions(spinnerBorderScope, getResources().getStringArray(R.array.editor_border_scopes));
        spinnerBorderWidth.setSelection(1);
        cbEnableBorder.setOnCheckedChangeListener((button, checked) -> updateSpatialPreview());
        for (Spinner spinner : new Spinner[]{spinnerBorderColor, spinnerBorderWidth, spinnerBorderScope}) {
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    updateSpatialPreview();
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }
        findViewById(R.id.btnLanguage).setOnClickListener(v -> {
            if (!loading && !exporting && !publishing) UiLocales.switchLanguage(this);
        });
        findViewById(R.id.btnErrorDetails).setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this).setTitle(R.string.editor_error_details)
                        .setMessage(((TextView) findViewById(R.id.tvRawError)).getText())
                        .setPositiveButton(android.R.string.ok, null).show());
        findViewById(R.id.btnOutputLocation).setOnClickListener(v -> {
            if (lastVideo != null) new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.editor_output_location).setMessage(lastVideo.info())
                    .setPositiveButton(android.R.string.ok, null).show();
        });
        findViewById(R.id.btnSelectWatermark).setOnClickListener(v ->
                openDocument("image/png", REQUEST_CODE_WATERMARK));
        findViewById(R.id.btnClearWatermark).setOnClickListener(v -> {
            if (loading || exporting) return;
            deleteCacheFile(watermarkFilePath);
            watermarkFilePath = null;
            watermarkImage = null;
            cbEnableWatermark.setChecked(false);
            ((TextView) findViewById(R.id.tvWatermark)).setText(R.string.editor_png_cleared);
            updateSpatialPreview();
        });
        cbEnableWatermark.setOnCheckedChangeListener((button, checked) -> updateSpatialPreview());
        for (EditText field : new EditText[]{etWatermarkWidth, etWatermarkX, etWatermarkY}) {
            field.addTextChangedListener(watcher(this::updateSpatialPreview));
        }

        setOptions(spinnerResolution, getResources().getStringArray(R.array.editor_resolutions));
        setOptions(spinnerSpeed, getResources().getStringArray(R.array.editor_speeds));
        spinnerSpeed.setSelection(2);
        setOptions(spinnerVolume, getResources().getStringArray(R.array.editor_volumes));
        spinnerVolume.setSelection(3);
        spinnerResolution.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) { updateSpatialPreview(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        spinnerSpeed.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) { updateSpatialPreview(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        cbEnableVolume.setOnCheckedChangeListener((button, checked) -> {
            findViewById(R.id.layoutVolumeControls).setVisibility(checked ? View.VISIBLE : View.GONE);
            updateSpatialPreview();
        });
        cbCompatibilityEncoding.setChecked(VideoEncodingSettings.compatibilityEnabled(this));
        findViewById(R.id.btnSelectIntro).setOnClickListener(v ->
                openDocument("video/*", REQUEST_CODE_INTRO));
        findViewById(R.id.btnSelectMusic).setOnClickListener(v ->
                openDocument("audio/*", REQUEST_CODE_MUSIC));
        btnSelectExtras.setOnClickListener(v -> openExtraVideos());
        btnClearExtras.setOnClickListener(v -> clearAppendedVideos());
        findViewById(R.id.btnSelectIntro).setOnLongClickListener(v -> clearAuxiliary(REQUEST_CODE_INTRO));
        findViewById(R.id.btnSelectMusic).setOnLongClickListener(v -> clearAuxiliary(REQUEST_CODE_MUSIC));
        cbEnableMerge.setOnCheckedChangeListener((button, checked) -> {
            updateMergeUi();
            updateWorkControls();
        });
        updateAuxiliaryLabels();
        cbEnableIntro.setEnabled(true);
        cbEnableIntro.setText(R.string.editor_prepend_title);
        // The legacy template warning has no ID and immediately follows the checkbox.
        ViewGroup introSection = (ViewGroup) cbEnableIntro.getParent();
        View templateHint = introSection.getChildAt(introSection.indexOfChild(cbEnableIntro) + 1);
        if (templateHint instanceof TextView) {
            ((TextView) templateHint).setText(R.string.editor_title_hint);
        }
        findViewById(R.id.layoutIntroControls).setVisibility(View.VISIBLE);
        titleBackgroundControls = new TitleBackgroundControls(this, this::titleGeometry, () -> {
            if (restoringUi || applyingPreset || currentTemplate == null) return;
            titleBackgroundControls.put(currentTemplate);
            updateWorkControls();
            updateTemplatePreview();
        });
        setupTitleControls();
        setupIntroTemplateSpinner();
        setupWholePresets();
        offlineMusic = new OfflineMusicControls(this, () -> {
            if (!restoringUi && !applyingPreset) {
                updateAuxiliaryLabels();
                updateSpatialPreview();
            }
        });
        etIntroText.addTextChangedListener(watcher(this::updateTemplatePreview));
        for (EditText field : new EditText[]{etCropLeft, etCropTop, etCropRight,
                etCropBottom, etCustomAngle, etTrimStart, etTrimEnd}) {
            field.addTextChangedListener(watcher(this::updateSpatialPreview));
        }
        cbEnableTrim.setOnCheckedChangeListener((button, checked) -> {
            findViewById(R.id.layoutTrimControls).setVisibility(checked ? View.VISIBLE : View.GONE);
            updateSpatialPreview();
        });
        cbEnableIntro.setOnCheckedChangeListener((button, checked) -> {
            if (!checked && !restoringUi) clearTitleEditFocus();
            updateWorkControls();
            updateSpatialPreview();
        });
        findViewById(R.id.btnRotateLeft).setOnClickListener(v -> rotateBy(-90));
        findViewById(R.id.btnRotateRight).setOnClickListener(v -> rotateBy(90));
        btnSelectVideo.setOnClickListener(v -> openDocument("video/*", VIDEO_PICK_CODE));
        btnProcess.setOnClickListener(v -> startExport());
        btnCancelExport.setOnClickListener(v -> {
            publicationCancelled.set(true);
            if (loading && loadTask != null) {
                ++loadGeneration;
                loadTask.cancel(true);
                // A blocked provider/native call may ignore interruption. Do not queue behind it.
                loader.shutdownNow();
                loader = Executors.newSingleThreadExecutor();
                ++previewGeneration;
                if (previewTask != null) previewTask.cancel(true);
                restoringAssets = false;
                pendingPickerData = null;
                finishLoading();
                showError(getString(R.string.editor_source_cancelled), new InterruptedIOException("Import cancelled; retry is available"));
                updateSpatialPreview();
            }
            if (!publishing) exportEngine.cancel();
        });
        btnSelfTest.setOnClickListener(v -> startActivity(new Intent(this, SelfTestActivity.class)));
        findViewById(R.id.btnOpenOutputFolder).setOnClickListener(v -> openOutput(false));
        findViewById(R.id.btnShareVideo).setOnClickListener(v -> openOutput(true));
        findViewById(R.id.btnPublicationDraft).setOnClickListener(v -> {
            PublishedVideo video = lastVideo;
            if (video != null && video.uri != null)
                startActivity(new Intent(this, PublicationDraftActivity.class)
                        .putExtra(PublicationDraftActivity.EXPORT_URI, video.uri.toString()));
        });
        findViewById(R.id.btnPublicationDrafts).setOnClickListener(v ->
                startActivity(new Intent(this, PublicationDraftActivity.class)));
        findViewById(R.id.btnCopyVideoInfo).setOnClickListener(v -> {
            if (lastVideo == null) return;
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText(getString(R.string.editor_output_file), lastVideo.info()));
            Toast.makeText(this, R.string.editor_location_copied, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnVideoDirectory).setVisibility(Build.VERSION.SDK_INT < 29 ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnVideoDirectory).setOnClickListener(v -> chooseVideoDirectory());
        publicationWorker.execute(() -> {
            try {
                PublishedVideo restored = PublishedVideo.recover(getApplicationContext());
                main.post(() -> {
                    if (!destroyed && !exporting && lastVideo == null && restored != null) {
                        lastVideo = restored;
                        if (savedInstanceState == null || restoreResultVisible) showVideo(restored);
                    }
                });
            } catch (IOException | RuntimeException error) {
                main.post(() -> { if (!destroyed) showError(getString(R.string.editor_output_recovery_failed), error); });
            }
        });
        updateMergeUi();
        updateSpatialPreview();
        updateWorkControls();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("cropRemovalVersion", 1);
        state.putBundle("editorUi", editorPanels.save());
        if (currentTemplate != null) state.putString("editorTitle", currentTemplate.toJson());
        state.putBundle("editorMain", saveMedia(selectedMainVideo));
        state.putBundle("editorIntro", saveMedia(selectedIntroVideo));
        state.putBundle("editorMusic", saveMedia(selectedMusic));
        state.putBundle("editorLibraryMusic", offlineMusic.save());
        ArrayList<Bundle> clips = new ArrayList<>();
        for (LoadedVideo video : appendedVideos) clips.add(saveMedia(video));
        state.putParcelableArrayList("editorClips", clips);
        state.putString("editorPng", watermarkFilePath);
        state.putString("editorRawError", ((TextView) findViewById(R.id.tvRawError)).getText().toString());
        state.putString("editorDebug", tvDebugInfo.getText().toString());
        state.putBoolean("editorErrorVisible", svErrorContainer.getVisibility() == View.VISIBLE);
        state.putBoolean("editorResultVisible", layoutSuccessContainer.getVisibility() == View.VISIBLE);
        if (lastVideo != null) {
            Bundle output = new Bundle();
            output.putString("uri", lastVideo.uri == null ? null : lastVideo.uri.toString());
            output.putString("name", lastVideo.name);
            output.putString("location", lastVideo.location);
            output.putString("status", lastVideo.status);
            output.putString("private", lastVideo.privateFile == null ? null : lastVideo.privateFile.getAbsolutePath());
            state.putBundle("editorOutput", output);
        }
        savedSelections = true;
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onPostCreate(Bundle state) {
        super.onPostCreate(state);
        if (state != null) {
            restoreResultVisible = state.getBoolean("editorResultVisible");
            Bundle output = state.getBundle("editorOutput");
            if (output != null) {
                String uri = output.getString("uri"), path = output.getString("private");
                lastVideo = new PublishedVideo(uri == null ? null : Uri.parse(uri), output.getString("name"),
                        output.getString("location"), output.getString("status"), path == null ? null : new File(path));
            }
            if (state.getBoolean("editorErrorVisible")) {
                tvErrorDetails.setText(R.string.editor_previous_error);
                svErrorContainer.setVisibility(View.VISIBLE);
            }
            selectedMainVideo = restoreMedia(state.getBundle("editorMain"));
            selectedIntroVideo = restoreMedia(state.getBundle("editorIntro"));
            selectedMusic = restoreMedia(state.getBundle("editorMusic"));
            ArrayList<Bundle> clips = state.getParcelableArrayList("editorClips");
            if (clips != null) for (Bundle clip : clips) {
                LoadedVideo restored = restoreMedia(clip);
                if (restored != null) appendedVideos.add(restored);
            }
            watermarkFilePath = state.getString("editorPng");
            String title = state.getString("editorTitle");
            if (title != null) {
                try { currentTemplate = IntroTemplate.fromDraftJson(title); }
                catch (org.json.JSONException | IllegalArgumentException error) {
                    currentTemplate = null;
                    showError(getString(R.string.title_preset_load_failed), error);
                }
            }
            editorPanels.restore(state.getBundle("editorUi"));
            titleAppearanceControls.sync();
            offlineMusic.restore(state.getBundle("editorLibraryMusic"));
            ((TextView) findViewById(R.id.tvRawError)).setText(state.getString("editorRawError", ""));
            findViewById(R.id.tvRawError).setVisibility(
                    state.getString("editorRawError", "").isEmpty() ? View.GONE : View.VISIBLE);
            tvDebugInfo.setText(state.getString("editorDebug", ""));
            svDebugContainer.setVisibility(tvDebugInfo.length() == 0 ? View.GONE : View.VISIBLE);
            restoreSelectionThumbnails();
            if (restoreResultVisible && lastVideo != null) showVideo(lastVideo);
        }
        if (state != null && state.getInt("cropRemovalVersion", 0) == 0) {
            for (EditText edge : new EditText[]{etCropRight, etCropBottom}) {
                try {
                    edge.setText(new BigDecimal("100").subtract(
                            new BigDecimal(edge.getText().toString())).stripTrailingZeros().toPlainString());
                } catch (NumberFormatException ignored) {
                    // Leave invalid legacy values visible for explicit correction.
                }
            }
        }
        // Revalidate restored view text; encoding mode comes from the persisted preference.
        cbCompatibilityEncoding.setChecked(VideoEncodingSettings.compatibilityEnabled(this));
        cbCompatibilityEncoding.setOnCheckedChangeListener((button, checked) ->
                VideoEncodingSettings.setCompatibilityEnabled(this, checked));
        findViewById(R.id.layoutTrimControls).setVisibility(
                cbEnableTrim.isChecked() ? View.VISIBLE : View.GONE);
        findViewById(R.id.layoutVolumeControls).setVisibility(
                cbEnableVolume.isChecked() ? View.VISIBLE : View.GONE);
        updateAuxiliaryLabels();
        updateMergeUi();
        selectedTemplatePosition = spinnerIntroTemplate.getSelectedItemPosition();
        findViewById(R.id.editorRoot).post(() -> {
            restoringUi = false;
            updateWorkControls();
            updateSpatialPreview();
        });
    }

    private Bundle saveMedia(LoadedVideo video) {
        if (video == null) return null;
        Bundle state = new Bundle();
        state.putBundle("source", video.source.save());
        state.putString("name", video.name);
        state.putLong("duration", video.duration);
        state.putInt("width", video.width);
        state.putInt("height", video.height);
        state.putBoolean("audio", video.hasAudio);
        state.putLong("bytes", video.sizeBytes);
        return state;
    }

    private LoadedVideo restoreMedia(Bundle state) {
        if (state == null) return null;
        try {
            VideoSource source;
            if (state.getBundle("source") != null) source = VideoSource.restore(this, state.getBundle("source"));
            else if (state.getString("path") != null)
                source = VideoSource.owned(this, new File(state.getString("path")), state.getString("name"));
            else throw new IOException("Saved source missing");
            return new LoadedVideo(source, state.getLong("duration"), null,
                    state.getInt("width"), state.getInt("height"), state.getBoolean("audio"));
        } catch (IOException | RuntimeException error) {
            showError(getString(R.string.editor_source_reselect), error);
            return null;
        }
    }

    private void restoreSelectionThumbnails() {
        if (selectedMainVideo == null && selectedIntroVideo == null && appendedVideos.isEmpty()
                && watermarkFilePath == null) return;
        loading = true;
        restoringAssets = true;
        tvProgress.setText(R.string.editor_restoring);
        updateWorkControls();
        ArrayList<LoadedVideo> videos = new ArrayList<>();
        if (selectedMainVideo != null) videos.add(selectedMainVideo);
        if (selectedIntroVideo != null) videos.add(selectedIntroVideo);
        videos.addAll(appendedVideos);
        String pngPath = watermarkFilePath;
        loadTask = loader.submit(() -> {
            PngWatermark png = null;
            Exception failure = null;
            try {
                for (LoadedVideo video : videos) {
                    if (Thread.currentThread().isInterrupted()) return;
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try {
                        video.source.checkCurrent(this);
                        VideoSource.setDataSource(this, retriever, video.source.uri);
                        video.thumbnail = SelectedFramePreview.decode(retriever, 0, new Size(video.width, video.height));
                    } finally { retriever.release(); }
                }
                if (pngPath != null) {
                    File pngFile = new File(pngPath);
                    if (pngFile.length() > PngWatermark.MAX_BYTES) throw new IOException("PNG exceeds 8 MiB");
                    try (InputStream input = new java.io.FileInputStream(pngFile);
                         java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                        byte[] buffer = new byte[16384];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (bytes.size() + count > PngWatermark.MAX_BYTES) throw new IOException("PNG exceeds 8 MiB");
                            bytes.write(buffer, 0, count);
                        }
                        png = PngWatermark.fromBytes(bytes.toByteArray());
                    }
                }
            } catch (IOException | RuntimeException error) { failure = error; }
            PngWatermark ready = png;
            Exception error = failure;
            main.post(() -> {
                if (destroyed) return;
                watermarkImage = ready;
                currentThumbnail = selectedMainVideo == null ? null : selectedMainVideo.thumbnail;
                if (selectedMainVideo != null) tvSelectedVideo.setText(describeClip(selectedMainVideo));
                restoringAssets = false;
                finishLoading();
                if (selectedMainVideo != null) showSourceAccess(selectedMainVideo);
                updateAuxiliaryLabels();
                updateSpatialPreview();
                editorPanels.restoreFocusAndScroll();
                if (restoreResultVisible && lastVideo != null) showVideo(lastVideo);
                if (error != null) showError(getString(R.string.editor_source_reselect), error);
                if (pendingPickerData != null) {
                    Intent data = pendingPickerData;
                    pendingPickerData = null;
                    onActivityResult(pendingPickerCode, pendingPickerResult, data);
                }
            });
        });
    }

    private void clearTitleEditFocus() {
        View focused = findViewById(R.id.layoutIntroControls).findFocus();
        if (focused instanceof EditText) {
            EditText text = (EditText) focused;
            int start = text.getSelectionStart(), end = text.getSelectionEnd();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(focused.getWindowToken(), 0);
            findViewById(R.id.editorRoot).requestFocus();
            text.setSelection(start, end);
        }
        editorPanels.clearTitleFocus();
    }

    private static TextWatcher watcher(Runnable action) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                action.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
    }

    private void setOptions(Spinner spinner, String[] options) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void openDocument(String type, int code) {
        if (loading || exporting) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, code);
        } catch (RuntimeException error) {
            showError(getString(R.string.editor_picker_failed), error);
        }
    }

    private void openExtraVideos() {
        if (loading || exporting || !cbEnableMerge.isChecked()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_CODE_APPEND);
        } catch (RuntimeException error) {
            showError(getString(R.string.editor_append_picker_failed), error);
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == TitleBackgroundControls.SAVE_IMAGE) {
            if (result == RESULT_OK && data != null && data.getData() != null)
                titleBackgroundControls.save(data.getData());
            return;
        }
        if (restoringAssets && result == RESULT_OK && data != null) {
            pendingPickerCode = code;
            pendingPickerResult = result;
            pendingPickerData = data;
            return;
        }
        if (code == REQUEST_CODE_OUTPUT_TREE) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            Uri tree = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            publicationWorker.execute(() -> {
                try {
                    if (flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                        throw new IOException("Output directory requires read and write grants");
                    }
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    PublishedVideo.saveTree(getApplicationContext(), tree);
                    main.post(() -> { if (!destroyed) Toast.makeText(this,
                            R.string.editor_directory_saved, Toast.LENGTH_LONG).show(); });
                } catch (IOException | RuntimeException error) {
                    main.post(() -> { if (!destroyed) showError(getString(R.string.editor_directory_save_failed), error); });
                }
            });
            return;
        }
        if (destroyed || result != RESULT_OK || data == null) return;
        if (code != VIDEO_PICK_CODE && code != REQUEST_CODE_INTRO && code != REQUEST_CODE_MUSIC
                && code != REQUEST_CODE_WATERMARK && code != REQUEST_CODE_APPEND) return;
        if (loading || exporting) {
            showError(getString(R.string.editor_selection_busy), new IllegalStateException("Wait for the active work to finish"));
            return;
        }
        if (code != REQUEST_CODE_MUSIC && code != REQUEST_CODE_WATERMARK) {
            for (Uri document : collectDocumentUris(data))
                VideoSource.retainReadGrant(this, document, data.getFlags());
        }
        if (code == REQUEST_CODE_APPEND) {
            loadAppendedVideos(data);
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            showError(getString(R.string.editor_selection_empty), new IllegalArgumentException("No document was returned"));
            return;
        }
        loadMedia(uri, code);
    }

    private void loadMedia(Uri uri, int code) {
        loadMedia(uri, code, false);
    }

    private void loadMedia(Uri uri, int code, boolean copyConfirmed) {
        loading = true;
        int generation = ++loadGeneration;
        clearError();
        layoutSuccessContainer.setVisibility(View.GONE);
        tvProgress.setText(R.string.editor_loading_media);
        updateWorkControls();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        Context context = getApplicationContext();
        Handler handler = main;
        long started = SystemClock.elapsedRealtime();
        boolean boundedVideo = code != REQUEST_CODE_MUSIC
                && (cbEnableMerge.isChecked() || !appendedVideos.isEmpty());
        File target = new File(getCacheDir(), "input_" + UUID.randomUUID()
                + (code == REQUEST_CODE_WATERMARK ? ".png" : code == REQUEST_CODE_MUSIC ? ".audio" : ".mp4"));
        loadTask = loader.submit(() -> {
            try {
                if (code == REQUEST_CODE_WATERMARK) {
                    PngWatermark image = PngWatermark.copyDocument(context, uri, target);
                    handler.post(() -> {
                        MainActivity activity = owner.get();
                        if (activity == null || activity.destroyed || generation != activity.loadGeneration) {
                            deleteCacheFile(target.getAbsolutePath());
                        } else {
                            deleteCacheFile(activity.watermarkFilePath);
                            activity.watermarkFilePath = target.getAbsolutePath();
                            activity.watermarkImage = image;
                            ((TextView) activity.findViewById(R.id.tvWatermark)).setText(
                                    activity.getString(R.string.editor_png_ready, image.width, image.height));
                            activity.finishLoading();
                            activity.updateSpatialPreview();
                        }
                    });
                    return;
                }
                LoadedVideo loaded = readMedia(context, uri, target, code != REQUEST_CODE_MUSIC,
                        started, boundedVideo ? EditConfig.MAX_VIDEO_BYTES : 0L,
                        boundedVideo ? EditConfig.MAX_VIDEO_DIMENSION : 0, message -> handler.post(() -> {
                            MainActivity activity = owner.get();
                            if (activity != null && !activity.destroyed && generation == activity.loadGeneration)
                                activity.tvProgress.setText(message);
                         }), copyConfirmed);
                handler.post(() -> {
                    MainActivity activity = owner.get();
                    if (activity == null || activity.destroyed || generation != activity.loadGeneration) {
                        loaded.dispose();
                    } else {
                        if (code == VIDEO_PICK_CODE) activity.acceptVideo(uri, loaded);
                        else activity.acceptAuxiliary(code, loaded);
                    }
                });
            } catch (IOException | RuntimeException error) {
                if (target.exists() && !target.delete()) {
                    error.addSuppressed(new IOException("Cannot remove incomplete cache copy: " + target));
                }
                handler.post(() -> {
                    MainActivity activity = owner.get();
                    if (activity != null && !activity.destroyed && generation == activity.loadGeneration) {
                        activity.finishLoading();
                        if (error instanceof VideoSource.NeedsLocalCopyException && !copyConfirmed) {
                            activity.confirmSourceCopy((VideoSource.NeedsLocalCopyException) error,
                                    () -> activity.loadMedia(uri, code, true));
                        } else activity.showError(activity.getString(R.string.editor_source_reselect), error);
                    }
                });
            }
        });
    }

    private void loadAppendedVideos(Intent data) {
        loadAppendedVideos(data, new java.util.HashSet<>());
    }

    private void loadAppendedVideos(Intent data, java.util.Set<Uri> copyConfirmed) {
        ArrayList<Uri> uris = collectDocumentUris(data);
        if (uris.isEmpty()) {
            showError(getString(R.string.editor_append_selection_required),
                    new IllegalArgumentException("Select one to five readable video documents"));
            return;
        }
        if (uris.size() > EditConfig.MAX_APPENDED_VIDEO_COUNT) {
            showError(getString(R.string.editor_append_selection_required),
                    new IllegalArgumentException("Select at most 5 appended clips"));
            return;
        }
        loading = true;
        int generation = ++loadGeneration;
        clearError();
        layoutSuccessContainer.setVisibility(View.GONE);
        tvProgress.setText(R.string.editor_loading_clips);
        updateWorkControls();
        WeakReference<MainActivity> owner = new WeakReference<>(this);
        Context context = getApplicationContext();
        Handler handler = main;
        long started = SystemClock.elapsedRealtime();
        long availableBytes = EditConfig.MAX_AGGREGATE_VIDEO_BYTES
                 - aggregateSelectedVideoBytes(selectedMainVideo, selectedIntroVideo, Collections.emptyList());
        loadTask = loader.submit(() -> {
            ArrayList<LoadedVideo> loaded = new ArrayList<>();
            try {
                if (aggregateSelectedVideoBytes(selectedMainVideo, selectedIntroVideo, Collections.emptyList()) < 0)
                    throw new IOException("Merge requires known bounded source sizes");
                long remainingBytes = availableBytes;
                for (int i = 0; i < uris.size(); i++) {
                    if (remainingBytes <= 0) {
                        throw new IOException("Copied video snapshots exceed the 256 MiB aggregate limit");
                    }
                    Uri uri = uris.get(i);
                    File target = new File(getCacheDir(), "append_" + UUID.randomUUID() + ".mp4");
                    loaded.add(readMedia(context, uri, target, true, started,
                            Math.min(EditConfig.MAX_VIDEO_BYTES, remainingBytes), EditConfig.MAX_VIDEO_DIMENSION,
                            message -> handler.post(() -> {
                                MainActivity activity = owner.get();
                                if (activity != null && !activity.destroyed && generation == activity.loadGeneration)
                                    activity.tvProgress.setText(message);
                            }), copyConfirmed.contains(uri)));
                    remainingBytes -= loaded.get(loaded.size() - 1).sizeBytes;
                }
                handler.post(() -> {
                    MainActivity activity = owner.get();
                    if (activity == null || activity.destroyed || generation != activity.loadGeneration) {
                        for (LoadedVideo clip : loaded) clip.dispose();
                    } else {
                        activity.acceptAppendedVideos(loaded);
                    }
                });
            } catch (IOException | RuntimeException error) {
                for (LoadedVideo clip : loaded) clip.dispose();
                handler.post(() -> {
                    MainActivity activity = owner.get();
                    if (activity != null && !activity.destroyed && generation == activity.loadGeneration) {
                        activity.finishLoading();
                        if (error instanceof VideoSource.NeedsLocalCopyException) {
                            VideoSource.NeedsLocalCopyException needed = (VideoSource.NeedsLocalCopyException) error;
                            activity.confirmSourceCopy(needed, () -> {
                                copyConfirmed.add(needed.uri);
                                activity.loadAppendedVideos(data, copyConfirmed);
                            });
                        } else activity.showError(activity.getString(R.string.editor_source_reselect), error);
                    }
                });
            }
        });
    }

    private static ArrayList<Uri> collectDocumentUris(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                ClipData.Item item = clipData.getItemAt(i);
                if (item != null && item.getUri() != null) {
                    uris.add(item.getUri());
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private interface LoadProgress { void accept(String message); }

    private void confirmSourceCopy(VideoSource.NeedsLocalCopyException error, Runnable confirmed) {
        String reason = getString(error.reason == VideoSource.Reason.NON_SEEKABLE
                ? R.string.editor_source_not_seekable : R.string.editor_source_virtual);
        new AlertDialog.Builder(this).setTitle(R.string.editor_source_copy_title)
                .setMessage(getString(R.string.editor_source_copy_message, reason, sourceSize(error.sizeBytes),
                        sourceSize(getCacheDir().getUsableSpace())))
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        tvProgress.setText(R.string.editor_source_copy_declined))
                .setPositiveButton(R.string.editor_source_copy_confirm, (dialog, which) -> {
                    if (!destroyed && !loading && !exporting) confirmed.run();
                }).show();
    }

    private String sourceSize(long size) {
        return size < 0 ? getString(R.string.editor_source_size_unknown) : formatBytes(size);
    }

    private static LoadedVideo readMedia(Context context, Uri uri, File target, boolean video,
                                         long started, long maxBytes, int maxDimension)
            throws IOException {
        return readMedia(context, uri, target, video, started, maxBytes, maxDimension,
                message -> android.util.Log.i("MediaImport", message), false);
    }

    private static LoadedVideo readMedia(Context context, Uri uri, File target, boolean video,
                                         long started, long maxBytes, int maxDimension,
                                         LoadProgress progress, boolean copyConfirmed) throws IOException {
        MediaCopy.checkCancelled();
        Bitmap frame = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        VideoSource source = null;
        try {
            long[] last = {0};
            source = !video || copyConfirmed ? VideoSource.stage(context, uri, target, maxBytes, bytes -> {
                long now = SystemClock.elapsedRealtime();
                if (now - last[0] >= 250) {
                    last[0] = now;
                    progress.accept(context.getString(R.string.editor_source_copy_progress,
                            bytes, (now - started) / 1000));
                }
            }) : VideoSource.document(context, uri);
            if (maxBytes > 0 && (source.sizeBytes < 0 || source.sizeBytes > maxBytes))
                throw new IOException("Merge requires a known size within the snapshot byte limit");
            MediaCopy.checkCancelled();
            progress.accept(context.getString(R.string.editor_source_probing));
            VideoSource.setDataSource(context, retriever, source.uri);
            String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationText == null) throw new IOException("Video has no duration metadata");
            long duration = Long.parseLong(durationText);
            if (duration <= 0 || duration > Long.MAX_VALUE / 1000) {
                throw new IOException("Invalid source duration: " + durationText);
            }
            boolean hasAudio = "yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            if (!video) {
                if (!hasAudio) {
                    throw new IOException("Selected music has no audio track");
                }
                MediaCopy.checkCancelled();
                return new LoadedVideo(source, duration, null, 0, 0, true);
            }
            int width = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rotationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation = rotationText == null ? 0 : Integer.parseInt(rotationText);
            Size displaySize = Media3ExportEngine.displaySize(width, height, rotation,
                    readPixelAspectRatio(context, source.uri));
            width = displaySize.getWidth();
            height = displaySize.getHeight();
            if (maxDimension > 0 && (width > maxDimension || height > maxDimension)) {
                throw new IOException("Selected video exceeds the 4096 px display-size limit");
            }
            frame = SelectedFramePreview.decode(retriever, 0, displaySize);
            if (frame == null) throw new IOException("Decoder returned no video thumbnail");
            int maxSide = Math.max(frame.getWidth(), frame.getHeight());
            if (maxSide > 640) {
                Bitmap scaled = Bitmap.createScaledBitmap(frame,
                        Math.max(1, frame.getWidth() * 640 / maxSide),
                        Math.max(1, frame.getHeight() * 640 / maxSide), true);
                if (scaled != frame) frame.recycle();
                frame = scaled;
            }
            MediaCopy.checkCancelled();
            // Retriever frames already honor source display rotation. Do not rotate metadata twice.
            LoadedVideo loaded = new LoadedVideo(source, duration, frame, width, height, hasAudio);
            retriever.release();
            retriever = null;
            frame = null;
            return loaded;
        } catch (IOException | RuntimeException error) {
            if (source != null) source.dispose();
            if (error instanceof VideoSource.NeedsLocalCopyException && maxBytes > 0
                    && ((VideoSource.NeedsLocalCopyException) error).sizeBytes > maxBytes)
                throw new IOException("Source exceeds the snapshot byte limit; copying cannot fix the merge limit", error);
            throw error;
        } finally {
            if (frame != null) frame.recycle();
            if (retriever != null) retriever.release();
        }
    }

    private static float readPixelAspectRatio(Context context, Uri source) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            VideoSource.setDataSource(context, extractor, source);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    if (format.containsKey("sar-width") && format.containsKey("sar-height")) {
                        int width = format.getInteger("sar-width");
                        int height = format.getInteger("sar-height");
                        if (width <= 0 || height <= 0) throw new IOException("Invalid pixel aspect ratio");
                        return (float) width / height;
                    }
                    return 1f;
                }
            }
            throw new IOException("Selected intro/source has no video track");
        } finally {
            extractor.release();
        }
    }

    private static final class LoadedVideo {
        final VideoSource source;
        final String name;
        final long duration;
        Bitmap thumbnail;
        final int width, height;
        final boolean hasAudio;
        final long sizeBytes;

        LoadedVideo(VideoSource source, long duration, Bitmap thumbnail, int width, int height,
                    boolean hasAudio) {
            this.source = source;
            this.name = source.name;
            this.duration = duration;
            this.thumbnail = thumbnail;
            this.width = width;
            this.height = height;
            this.hasAudio = hasAudio;
            this.sizeBytes = source.sizeBytes;
        }

        void dispose() {
            // A displayed thumbnail may still be referenced by RenderThread.
            thumbnail = null;
            source.dispose();
        }

        EditConfig.ImportedVideo asImportedVideo() {
            return new EditConfig.ImportedVideo(source.uri, duration, width, height, hasAudio, sizeBytes, source);
        }
    }

    private void acceptVideo(Uri uri, LoadedVideo loaded) {
        try {
            validateSelectedVideoTotal(loaded, selectedIntroVideo, appendedVideos, !appendedVideos.isEmpty());
        } catch (RuntimeException error) {
            loaded.dispose();
            finishLoading();
            showError(getString(R.string.editor_video_rejected), error);
            return;
        }
        releaseThumbnail();
        deleteCachedInput();
        selectedMainVideo = loaded;
        currentThumbnail = loaded.thumbnail;
        tvSelectedVideo.setText(getString(R.string.editor_selected_video, loaded.name,
                currentThumbnail.getWidth(), currentThumbnail.getHeight(),
                BigDecimal.valueOf(loaded.duration, 3).toPlainString(),
                getString(loaded.hasAudio ? R.string.editor_audio_present : R.string.editor_silent)));
        etTrimStart.setText("0");
        etTrimEnd.setText(BigDecimal.valueOf(loaded.duration, 3).toPlainString());
        finishLoading();
        showSourceAccess(loaded);
        updateAuxiliaryLabels();
        updateMergeUi();
        updateSpatialPreview();
    }

    private void finishLoading() {
        loading = false;
        updateWorkControls();
        updateTemplatePreview();
    }

    private void acceptAuxiliary(int code, LoadedVideo loaded) {
        if (code == REQUEST_CODE_INTRO) {
            try {
                validateSelectedVideoTotal(selectedMainVideo, loaded, appendedVideos, !appendedVideos.isEmpty());
            } catch (RuntimeException error) {
                loaded.dispose();
                finishLoading();
                showError(getString(R.string.editor_intro_rejected), error);
                return;
            }
            disposeLoadedVideo(selectedIntroVideo);
            selectedIntroVideo = loaded;
        } else {
            disposeLoadedVideo(selectedMusic);
            selectedMusic = loaded;
            offlineMusic.useImportedReplacement();
        }
        finishLoading();
        showSourceAccess(loaded);
        updateAuxiliaryLabels();
        updateMergeUi();
        Toast.makeText(this, getString(R.string.editor_media_ready, loaded.name),
                Toast.LENGTH_LONG).show();
    }

    private boolean clearAuxiliary(int code) {
        if (loading || exporting) return true;
        if (code == REQUEST_CODE_INTRO) {
            disposeLoadedVideo(selectedIntroVideo);
            selectedIntroVideo = null;
        } else {
            disposeLoadedVideo(selectedMusic);
            selectedMusic = null;
        }
        updateAuxiliaryLabels();
        updateMergeUi();
        return true;
    }

    private void updateAuxiliaryLabels() {
        ((TextView) findViewById(R.id.tvWatermark)).setText(watermarkImage == null
                ? getString(R.string.editor_watermark_hint)
                : getString(R.string.editor_png_ready, watermarkImage.width, watermarkImage.height));
        ((TextView) findViewById(R.id.tvSelectedIntro)).setText(
                getString(R.string.editor_intro_status,
                        getString(selectedIntroVideo == null ? R.string.editor_intro_none : R.string.editor_intro_ready))
                        + (selectedIntroVideo == null ? "" : "\n" + sourceAccess(selectedIntroVideo)));
        ((TextView) findViewById(R.id.tvSelectedMusic)).setText(
                offlineMusic != null && offlineMusic.isEnabled()
                        ? getString(R.string.bgm_import_inactive) : getString(R.string.editor_music_status,
                        getString(selectedMusic == null ? R.string.editor_music_original : R.string.editor_music_ready)));
    }

    private void acceptAppendedVideos(ArrayList<LoadedVideo> loaded) {
        try {
            validateSelectedVideoTotal(selectedMainVideo, selectedIntroVideo, loaded, true);
        } catch (RuntimeException error) {
            for (LoadedVideo clip : loaded) clip.dispose();
            finishLoading();
            showError(getString(R.string.editor_append_rejected), error);
            return;
        }
        clearLoadedVideos(appendedVideos);
        appendedVideos.clear();
        appendedVideos.addAll(loaded);
        finishLoading();
        for (LoadedVideo video : loaded) showSourceAccess(video);
        updateMergeUi();
        Toast.makeText(this, getString(R.string.editor_clips_ready, loaded.size()), Toast.LENGTH_LONG).show();
    }

    private void clearAppendedVideos() {
        if (loading || exporting) return;
        clearLoadedVideos(appendedVideos);
        appendedVideos.clear();
        updateMergeUi();
    }

    private void moveAppendedVideo(int index, int delta) {
        if (loading || exporting) return;
        int target = index + delta;
        if (index < 0 || index >= appendedVideos.size() || target < 0 || target >= appendedVideos.size()) {
            return;
        }
        Collections.swap(appendedVideos, index, target);
        updateMergeUi();
    }

    private void removeAppendedVideo(int index) {
        if (loading || exporting || index < 0 || index >= appendedVideos.size()) return;
        appendedVideos.remove(index).dispose();
        updateMergeUi();
    }

    private void updateMergeUi() {
        boolean mergeEnabled = cbEnableMerge.isChecked();
        long aggregateBytes = aggregateSelectedVideoBytes(selectedMainVideo, selectedIntroVideo,
                appendedVideos);
        long mergedDurationMs = estimateMergedDurationMs(mergeEnabled);
        tvMergeSemantics.setText(getString(R.string.editor_merge_status,
                getString(mergeEnabled ? R.string.editor_enabled : R.string.editor_disabled)));
        tvMergeSummary.setText(getString(R.string.editor_merge_totals, appendedVideos.size(),
                EditConfig.MAX_APPENDED_VIDEO_COUNT, formatBytes(aggregateBytes),
                BigDecimal.valueOf(mergedDurationMs, 3).toPlainString())
                + (mergedDurationMs > EditConfig.MAX_MERGED_DURATION_MS
                ? getString(R.string.editor_merge_too_long)
                : "")
                + (mergeEnabled && appendedVideos.isEmpty()
                ? getString(R.string.editor_merge_needs_clips)
                : "")
                + (mergeEnabled ? "" : getString(R.string.editor_merge_disabled_summary)));
        layoutMergeList.removeAllViews();
        layoutMergeList.addView(createMergeRow(getString(R.string.editor_main_anchored), selectedMainVideo, false,
                false, false, -1));
        if (appendedVideos.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(mergeEnabled
                    ? R.string.editor_merge_needs_clips
                    : R.string.editor_merge_empty);
            empty.setPadding(dp(8), dp(4), dp(8), dp(8));
            layoutMergeList.addView(empty);
        } else {
            for (int i = 0; i < appendedVideos.size(); i++) {
                layoutMergeList.addView(createMergeRow(getString(R.string.editor_appended_label, i + 2), appendedVideos.get(i),
                        true, i > 0, i + 1 < appendedVideos.size(), i));
            }
        }
        updateWorkControls();
    }

    private View createMergeRow(String label, LoadedVideo video, boolean removable,
                                boolean canMoveUp, boolean canMoveDown, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setBackgroundColor(0xFFF7F7F7);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        ImageView image = new ImageView(this);
        image.setLayoutParams(imageParams);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xFF000000);
        if (video != null && video.thumbnail != null) image.setImageBitmap(video.thumbnail);
        image.setContentDescription(getString(R.string.editor_clip_thumbnail, label));
        LinearLayout textAndButtons = new LinearLayout(this);
        textAndButtons.setOrientation(LinearLayout.VERTICAL);
        textAndButtons.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        textAndButtons.setPadding(dp(8), 0, 0, 0);
        TextView text = new TextView(this);
        text.setText(label + ": " + describeClip(video));
        textAndButtons.addView(text);
        if (removable) {
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            Button up = new Button(this);
            up.setText(R.string.editor_up);
            up.setTag("merge:up:" + index);
            up.setContentDescription(getString(R.string.editor_move_up, index + 1));
            up.setEnabled(!loading && !exporting && cbEnableMerge.isChecked() && canMoveUp);
            up.setOnClickListener(v -> moveAppendedVideo(index, -1));
            Button down = new Button(this);
            down.setText(R.string.editor_down);
            down.setTag("merge:down:" + index);
            down.setContentDescription(getString(R.string.editor_move_down, index + 1));
            down.setEnabled(!loading && !exporting && cbEnableMerge.isChecked() && canMoveDown);
            down.setOnClickListener(v -> moveAppendedVideo(index, 1));
            Button remove = new Button(this);
            remove.setText(R.string.editor_remove);
            remove.setTag("merge:remove:" + index);
            remove.setContentDescription(getString(R.string.editor_remove_clip, index + 1));
            remove.setEnabled(!loading && !exporting && cbEnableMerge.isChecked());
            remove.setOnClickListener(v -> removeAppendedVideo(index));
            for (Button button : new Button[]{up, down, remove}) {
                button.setMinWidth(0);
                button.setMinimumWidth(0);
                button.setTextSize(12);
                button.setPadding(dp(2), 0, dp(2), 0);
                buttons.addView(button, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            textAndButtons.addView(buttons);
        }
        row.addView(image);
        row.addView(textAndButtons);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);
        return row;
    }

    private String describeClip(LoadedVideo video) {
        if (video == null) {
            return getString(R.string.editor_not_selected);
        }
        return getString(R.string.editor_clip_description, video.name, video.width, video.height,
                BigDecimal.valueOf(video.duration, 3).toPlainString(),
                getString(video.hasAudio ? R.string.editor_audio_present : R.string.editor_silent),
                formatBytes(video.sizeBytes)) + "\n" + sourceAccess(video);
    }

    private long estimateMergedDurationMs(boolean includeAppendedVideos) {
        long mainDuration = selectedMainVideo == null ? 0 : selectedMainVideo.duration;
        try {
            if (selectedMainVideo != null) {
                long start = 0;
                long end = selectedMainVideo.duration;
                if (cbEnableTrim.isChecked()) {
                    BigDecimal startValue = new BigDecimal(etTrimStart.getText().toString().trim());
                    BigDecimal endValue = new BigDecimal(etTrimEnd.getText().toString().trim());
                    start = startValue.movePointRight(3).setScale(0, RoundingMode.CEILING).longValueExact();
                    end = endValue.movePointRight(3).setScale(0, RoundingMode.FLOOR).longValueExact();
                }
                mainDuration = Math.max(0L, Math.round((end - start)
                        / (double) SPEEDS[spinnerSpeed.getSelectedItemPosition()]));
            }
        } catch (RuntimeException ignored) {
            // Keep the last known full-duration estimate while the user is editing fields.
        }
        long total = mainDuration;
        if (cbEnableIntro.isChecked() && currentTemplate != null) total += currentTemplate.getDurationMs();
        if (selectedIntroVideo != null) total += selectedIntroVideo.duration;
        if (includeAppendedVideos) for (LoadedVideo clip : appendedVideos) total += clip.duration;
        return total;
    }

    private static long aggregateSelectedVideoBytes(LoadedVideo mainVideo, LoadedVideo introVideo,
                                                    List<LoadedVideo> extras) {
        long total = 0;
        if (mainVideo != null && mainVideo.sizeBytes < 0 || introVideo != null && introVideo.sizeBytes < 0) return -1;
        for (LoadedVideo clip : extras) if (clip.sizeBytes < 0) return -1;
        if (mainVideo != null) total += mainVideo.sizeBytes;
        if (introVideo != null) total += introVideo.sizeBytes;
        for (LoadedVideo clip : extras) total += clip.sizeBytes;
        return total;
    }

    private static void validateSelectedVideoTotal(LoadedVideo mainVideo, LoadedVideo introVideo,
                                                   List<LoadedVideo> extras, boolean mergeRestricted) {
        if (extras.size() > EditConfig.MAX_APPENDED_VIDEO_COUNT) {
            throw new IllegalArgumentException("At most 5 appended clips are supported");
        }
        if (!mergeRestricted) {
            return;
        }
        if (aggregateSelectedVideoBytes(mainVideo, introVideo, extras) < 0)
            throw new IllegalArgumentException("Merge requires known bounded source sizes");
        if (mainVideo != null) {
            if (mainVideo.sizeBytes > EditConfig.MAX_VIDEO_BYTES) {
                throw new IllegalArgumentException("Main clip exceeds the 128 MiB snapshot limit for merge");
            }
            if (mainVideo.width > EditConfig.MAX_VIDEO_DIMENSION
                    || mainVideo.height > EditConfig.MAX_VIDEO_DIMENSION) {
                throw new IllegalArgumentException("Main clip exceeds the 4096 px display-size limit for merge");
            }
        }
        if (introVideo != null && introVideo.duration > EditConfig.MAX_MERGED_DURATION_MS) {
            throw new IllegalArgumentException("Imported intro exceeds the 120 second limit");
        }
        if (introVideo != null && introVideo.sizeBytes > EditConfig.MAX_VIDEO_BYTES) {
            throw new IllegalArgumentException("Imported intro exceeds the 128 MiB snapshot limit");
        }
        if (introVideo != null && (introVideo.width > EditConfig.MAX_VIDEO_DIMENSION
                || introVideo.height > EditConfig.MAX_VIDEO_DIMENSION)) {
            throw new IllegalArgumentException("Imported intro exceeds the 4096 px display-size limit");
        }
        for (LoadedVideo clip : extras) {
            if (clip.duration > EditConfig.MAX_MERGED_DURATION_MS) {
                throw new IllegalArgumentException("An appended clip exceeds the 120 second limit");
            }
            if (clip.sizeBytes > EditConfig.MAX_VIDEO_BYTES) {
                throw new IllegalArgumentException("An appended clip exceeds the 128 MiB snapshot limit");
            }
            if (clip.width > EditConfig.MAX_VIDEO_DIMENSION || clip.height > EditConfig.MAX_VIDEO_DIMENSION) {
                throw new IllegalArgumentException("An appended clip exceeds the 4096 px display-size limit");
            }
        }
        if (aggregateSelectedVideoBytes(mainVideo, introVideo, extras) > EditConfig.MAX_AGGREGATE_VIDEO_BYTES) {
            throw new IllegalArgumentException("Copied video snapshots exceed the 256 MiB aggregate limit");
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1024d / 1024d);
    }

    private void showSourceAccess(LoadedVideo video) {
        String access = sourceAccess(video);
        tvProgress.setText(access);
        if (video.source.ownedFile == null && "content".equals(video.source.uri.getScheme())
                && !video.source.persistentRead)
            new AlertDialog.Builder(this).setMessage(access).setPositiveButton(android.R.string.ok, null).show();
        else Toast.makeText(this, access, Toast.LENGTH_LONG).show();
        if (selectedMainVideo != null) tvSelectedVideo.setOnClickListener(view ->
                new AlertDialog.Builder(this).setMessage(describeClip(selectedMainVideo))
                        .setPositiveButton(android.R.string.ok, null).show());
    }

    private String sourceAccess(LoadedVideo video) {
        return getString(video.source.ownedFile != null ? R.string.editor_source_copied
                : video.source.persistentRead || !"content".equals(video.source.uri.getScheme())
                ? R.string.editor_source_direct : R.string.editor_source_temporary);
    }

    private int readRotation() {
        String value = etCustomAngle.getText().toString().trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Enter a clockwise angle (0 for no rotation)");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Clockwise angle must be a signed 32-bit integer", error);
        }
    }

    private void rotateBy(int delta) {
        try {
            int normalized = ((readRotation() % 360) + 360) % 360;
            etCustomAngle.setText(Integer.toString((normalized + delta + 360) % 360));
        } catch (IllegalArgumentException error) {
            showError(getString(R.string.editor_rotation_invalid), error);
        }
    }

    private float[] readCrop() {
        return CropRemoval.retained(etCropLeft.getText().toString(), etCropTop.getText().toString(),
                etCropRight.getText().toString(), etCropBottom.getText().toString());
    }

    private void updateSpatialPreview() {
        if (applyingPreset || restoringUi) return;
        int generation = ++previewGeneration;
        if (previewRequest != null) main.removeCallbacks(previewRequest);
        if (previewTask != null) previewTask.cancel(true);
        if (destroyed) return;
        try {
            readCrop();
            readRotation();
            readWatermark();
            VideoBorder border = readBorder();
            border.validateTitle(cbEnableIntro.isChecked() && currentTemplate != null);
            geometryValid = true;
            if (selectedMainVideo == null) {
                tvGeometryStatus.setText(R.string.editor_preview_select_video);
            } else {
                float[] crop = readCrop();
                long[] trim = readTrim();
                EditConfig.Watermark watermark = readWatermark();
                EditConfig config = new EditConfig.Builder(selectedMainVideo.source.uri, selectedMainVideo.duration)
                        .inputSource(selectedMainVideo.source)
                        .sourceSize(selectedMainVideo.width, selectedMainVideo.height)
                        .trim(trim[0], trim[1]).crop(crop[0], crop[1], crop[2], crop[3])
                        .rotation(readRotation()).outputHeight(HEIGHTS[spinnerResolution.getSelectedItemPosition()])
                        .border(border.appliesTo(false) ? border : VideoBorder.OFF)
                        .colorAdjustment(colorControls.snapshot())
                        .watermark(watermark == null ? null : watermark.image,
                                watermark == null ? .2f : watermark.widthFraction,
                                watermark == null ? .75f : watermark.x, watermark == null ? .75f : watermark.y).build();
                Size output = Media3ExportEngine.editedCanvas(config);
                if (config.watermark != null) PngWatermark.rectangle(output.getWidth(), output.getHeight(), config.watermark);
                String label = getString(R.string.editor_preview_frame,
                        config.startMs / 1000d, config.rotationDegrees, output.getWidth(), output.getHeight(),
                        (config.watermark == null ? "" : getString(R.string.editor_preview_png))
                                + (config.border.appliesTo(false) ? getString(R.string.editor_preview_border) : ""));
                tvGeometryStatus.setText(R.string.editor_preview_updating);
                previewRequest = () -> {
                    if (destroyed || generation != previewGeneration || loader.isShutdown()) return;
                    previewTask = loader.submit(() -> {
                        try {
                            Bitmap rendered = SelectedFramePreview.render(getApplicationContext(), config);
                            main.post(() -> {
                                if (destroyed || generation != previewGeneration) {
                                    rendered.recycle();
                                    return;
                                }
                                // Displayed bitmaps are GC-owned: RenderThread can still reference an old frame.
                                previewBitmap = rendered;
                                ivVideoThumbnail.setImageBitmap(rendered);
                                ivVideoThumbnail.setVisibility(View.VISIBLE);
                                ivVideoThumbnail.setContentDescription(label);
                                tvGeometryStatus.setText(label);
                            });
                        } catch (IOException | RuntimeException error) {
                            main.post(() -> {
                                if (!destroyed && generation == previewGeneration) {
                                    tvGeometryStatus.setText(R.string.editor_preview_unavailable);
                                    recordRawError(getString(R.string.editor_preview_unavailable), error);
                                }
                            });
                        }
                    });
                };
                main.postDelayed(previewRequest, 200);
            }
        } catch (IllegalArgumentException error) {
            geometryValid = false;
            tvGeometryStatus.setText(getString(R.string.editor_invalid_edit, localizedValidation(error)));
            recordRawError(getString(R.string.editor_invalid_edit, localizedValidation(error)), error);
        }
        updateMergeUi();
        updateWorkControls();
        if (currentTemplate != null) updateTemplatePreview();
    }

    private EditConfig snapshotConfig() {
        if (selectedMainVideo == null) {
            throw new IllegalArgumentException("Select a video and wait for loading to finish");
        }
        boolean mergeRequested = cbEnableMerge.isChecked();
        if (mergeRequested && appendedVideos.isEmpty()) {
            throw new IllegalArgumentException("Merge is enabled but no appended clips are selected");
        }
        if (cbEnableIntro.isChecked() && currentTemplate == null) {
            throw new IllegalArgumentException("Select a template before enabling its intro title");
        }
        float[] crop = readCrop();
        long[] trim = readTrim();
        ArrayList<EditConfig.ImportedVideo> extras = new ArrayList<>();
        if (mergeRequested) {
            for (LoadedVideo clip : appendedVideos) extras.add(clip.asImportedVideo());
        }
        return new EditConfig.Builder(selectedMainVideo.source.uri, selectedMainVideo.duration)
                .inputSource(selectedMainVideo.source)
                .mainSourceMetadata(selectedMainVideo.hasAudio, selectedMainVideo.sizeBytes)
                .sourceSize(selectedMainVideo.width, selectedMainVideo.height)
                .mergeMode(mergeRequested)
                .introVideo(selectedIntroVideo == null ? null : selectedIntroVideo.asImportedVideo())
                .introTemplate(cbEnableIntro.isChecked() ? currentTemplate : null,
                        etIntroText.getText().toString())
                .titleBackground(cbEnableIntro.isChecked() && currentTemplate.hasSourceFrameBackground()
                        ? titleBackgroundControls.snapshot(titleGeometry()) : null)
                .appendVideos(extras)
                .replacementMusic(selectedMusic == null || offlineMusic.isEnabled() ? null : selectedMusic.source.uri)
                .backgroundMusic(offlineMusic.snapshot())
                .trim(trim[0], trim[1]).crop(crop[0], crop[1], crop[2], crop[3])
                .rotation(readRotation()).outputHeight(HEIGHTS[spinnerResolution.getSelectedItemPosition()])
                .speed(SPEEDS[spinnerSpeed.getSelectedItemPosition()])
                .volume(cbEnableVolume.isChecked() ? VOLUMES[spinnerVolume.getSelectedItemPosition()] : 1f)
                .watermark(cbEnableWatermark.isChecked() ? watermarkImage : null,
                        cbEnableWatermark.isChecked() ? readWatermark().widthFraction : .2f,
                        cbEnableWatermark.isChecked() ? readWatermark().x : .75f,
                        cbEnableWatermark.isChecked() ? readWatermark().y : .75f)
                .colorAdjustment(colorControls.snapshot())
                .border(readBorder()).overlayText(etOverlayText.getText().toString()).build();
    }

    private long[] readTrim() {
        long start = 0, end = selectedMainVideo.duration;
        if (cbEnableTrim.isChecked()) {
            try {
                BigDecimal startValue = new BigDecimal(etTrimStart.getText().toString().trim());
                BigDecimal endValue = new BigDecimal(etTrimEnd.getText().toString().trim());
                BigDecimal duration = BigDecimal.valueOf(selectedMainVideo.duration, 3);
                if (startValue.signum() < 0 || startValue.compareTo(endValue) >= 0
                        || endValue.compareTo(duration) > 0) {
                    throw new IllegalArgumentException("Trim requires 0 ≤ start < end ≤ "
                            + duration.toPlainString() + " seconds");
                }
                start = startValue.movePointRight(3).setScale(0, RoundingMode.CEILING).longValueExact();
                end = endValue.movePointRight(3).setScale(0, RoundingMode.FLOOR).longValueExact();
                if (start >= end) throw new IllegalArgumentException("Trim must retain at least one millisecond");
            } catch (NumberFormatException | ArithmeticException error) {
                throw new IllegalArgumentException("Trim times must be finite decimal seconds", error);
            }
        }
        return new long[]{start, end};
    }

    private VideoBorder readBorder() {
        return new VideoBorder(cbEnableBorder.isChecked(), spinnerBorderColor.getSelectedItemPosition(),
                spinnerBorderWidth.getSelectedItemPosition() + 1,
                spinnerBorderScope.getSelectedItemPosition() == 0 ? VideoBorder.Scope.TITLE : VideoBorder.Scope.WHOLE);
    }

    private EditConfig.Watermark readWatermark() {
        if (!cbEnableWatermark.isChecked()) return null;
        if (watermarkImage == null) throw new IllegalArgumentException("Select a PNG before enabling watermark");
        try {
            return new EditConfig.Builder(Uri.EMPTY, 1).watermark(watermarkImage,
                    Float.parseFloat(etWatermarkWidth.getText().toString()) / 100f,
                    Float.parseFloat(etWatermarkX.getText().toString()) / 100f,
                    Float.parseFloat(etWatermarkY.getText().toString()) / 100f).build().watermark;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("PNG width/X/Y must be finite percentages", error);
        }
    }

    private void startExport() {
        if (loading || exporting || destroyed || titleBackgroundControls.isSaving()) return;
        if (Build.VERSION.SDK_INT < 29 && PublishedVideo.prefs(this).getString("tree", null) == null) {
            chooseVideoDirectory();
            return;
        }
        clearError();
        layoutSuccessContainer.setVisibility(View.GONE);
        final EditConfig config;
        final File output;
        try {
            // Snapshot every widget on the UI thread before starting asynchronous Media3 work.
            config = snapshotConfig();
            File directory = new File(getFilesDir(), "exports");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create export directory: " + directory);
            }
            output = new File(directory, "edited_" + UUID.randomUUID() + ".mp4");
        } catch (IOException | RuntimeException error) {
            showError(getString(R.string.editor_export_failed), error);
            return;
        }
        exporting = true;
        publicationCancelled.set(false);
        cleanupSelectionsWhenExportStops = false;
        tvDebugInfo.setText(config + "\nDestination: " + output
                + "\nImmutable snapshot; changes to controls apply only to the next export."
                + "\nNo elapsed/stall time limit. Replacement music overrides original audio and its gain."
                + "\nMerge semantics: title → intro → edited main → appended clips; appended count="
                + config.appendedVideos.size() + ".");
        svDebugContainer.setVisibility(View.VISIBLE);
        tvProgress.setText(R.string.editor_export_starting);
        progressBar.setProgress(0);
        updateWorkControls();
        long started = SystemClock.elapsedRealtime();
        exportEngine.export(config, output, new Media3ExportEngine.Listener() {
            @Override public void onElapsed(long elapsedMs, String pass) {
                if (!destroyed) tvProgress.setText(getString(R.string.editor_export_progress, progressBar.getProgress())
                        + " · " + elapsedMs / 1000 + " s · " + pass);
            }

            @Override public void onDiagnostic(String message) {
                // Native callbacks can arrive off-main. Bound each update and the visible tail.
                String bounded = message.substring(0, Math.min(2000, message.length()));
                main.post(() -> {
                    if (destroyed) return;
                    String previous = tvDebugInfo.getText().toString();
                    if (previous.length() > 12000) previous = previous.substring(previous.length() - 12000);
                    tvDebugInfo.setText(previous + "\n" + bounded);
                });
            }
            @Override public void onProgress(int percent) {
                if (destroyed) return;
                progressBar.setProgress(percent);
                tvProgress.setText(getString(R.string.editor_export_progress, percent));
            }

            @Override public void onCompleted(File file) {
                publishing = true;
                if (!destroyed) {
                    tvProgress.setText(R.string.editor_publishing);
                    updateWorkControls();
                }
                publicationWorker.execute(() -> {
                    PublishedVideo result;
                    Exception failure = null;
                    try {
                        result = PublishedVideo.publish(getApplicationContext(), file, publicationCancelled);
                    } catch (IOException | RuntimeException error) {
                        failure = error;
                        result = new PublishedVideo(null, file.getName(), "Private export (not public)",
                                "Publication failed / cancelled; private MP4 retained", file);
                    }
                    if (result.uri != null) {
                        try { PublicationExportSnapshot.record(getApplicationContext(), result, config); }
                        catch (Exception snapshotError) { failure = snapshotError; }
                    }
                    PublishedVideo finished = result;
                    Exception error = failure;
                    main.post(() -> {
                        lastVideo = finished;
                        publishing = false;
                        exporting = false;
                        if (cleanupSelectionsWhenExportStops) releaseAllSelections();
                        if (destroyed) return;
                        updateMergeUi();
                        updateWorkControls();
                        showVideo(finished);
                        if (error != null) showError(getString(R.string.editor_publication_failed), error);
                    });
                });
                if (destroyed) publicationWorker.shutdown();
            }

            @Override public void onError(Exception error) {
                exporting = false;
                if (destroyed) publicationWorker.shutdown();
                if (cleanupSelectionsWhenExportStops) releaseAllSelections();
                if (destroyed) return;
                updateMergeUi();
                updateWorkControls();
                showError(getString(error instanceof VideoSource.SourceUnavailableException
                        ? R.string.editor_source_reselect : R.string.editor_export_incomplete), error);
            }
        });
    }

    private void chooseVideoDirectory() {
        if (loading || exporting || Build.VERSION.SDK_INT >= 29) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_CODE_OUTPUT_TREE); }
        catch (RuntimeException error) { showError(getString(R.string.editor_directory_picker_failed), error); }
    }

    private void showVideo(PublishedVideo video) {
        lastVideo = video;
        tvSuccessTime.setText(video.uri == null ? R.string.editor_output_private : R.string.editor_output_published);
        tvOutputPath.setText(video.name);
        findViewById(R.id.btnPublicationDraft).setEnabled(video.uri != null);
        layoutSuccessContainer.setVisibility(loading || exporting || publishing ? View.GONE : View.VISIBLE);
    }

    private void openOutput(boolean share) {
        PublishedVideo video = lastVideo;
        publicationWorker.execute(() -> {
            try {
                Intent intent = OutputSharing.prepareVideo(this, video, share);
                main.post(() -> {
                    if (destroyed) return;
                    try { OutputSharing.launchVideo(this, intent); }
                    catch (RuntimeException error) { showError(getString(share
                            ? R.string.editor_share_failed : R.string.editor_open_failed), error); }
                });
            } catch (IOException | RuntimeException error) {
                main.post(() -> { if (!destroyed) showError(getString(R.string.editor_output_read_failed), error); });
            }
        });
    }

    private void updateWorkControls() {
        boolean busy = loading || exporting || publishing
                || (titleBackgroundControls != null && titleBackgroundControls.isSaving());
        findViewById(R.id.btnLanguage).setEnabled(!busy);
        findViewById(R.id.btnPublicationDrafts).setEnabled(!busy);
        for (int id : new int[]{R.id.btnSaveWholePreset, R.id.btnApplyWholePreset, R.id.btnDeleteWholePreset,
                R.id.etWholePresetName, R.id.spinnerWholePreset, R.id.titlePreviewTime})
            editorPanels.setEnabled(findViewById(id), !busy);
        cbCompatibilityEncoding.setEnabled(!busy);
        btnSelectVideo.setEnabled(!busy && !loader.isShutdown());
        findViewById(R.id.btnSelectIntro).setEnabled(!busy && !loader.isShutdown());
        findViewById(R.id.btnSelectMusic).setEnabled(!busy && !loader.isShutdown());
        cbEnableMerge.setEnabled(!busy);
        btnSelectExtras.setEnabled(!busy && !loader.isShutdown() && cbEnableMerge.isChecked());
        btnClearExtras.setEnabled(!busy && !appendedVideos.isEmpty());
        findViewById(R.id.btnSelectWatermark).setEnabled(!busy && !loader.isShutdown());
        findViewById(R.id.btnClearWatermark).setEnabled(!busy);
        cbEnableWatermark.setEnabled(!busy);
        cbEnableBorder.setEnabled(!busy);
        colorControls.setBusy(busy);
        for (Spinner spinner : new Spinner[]{spinnerBorderColor, spinnerBorderWidth, spinnerBorderScope}) {
            spinner.setEnabled(!busy && cbEnableBorder.isChecked());
        }
        editorPanels.setEnabled(etWatermarkWidth, !busy && cbEnableWatermark.isChecked());
        editorPanels.setEnabled(etWatermarkX, !busy && cbEnableWatermark.isChecked());
        editorPanels.setEnabled(etWatermarkY, !busy && cbEnableWatermark.isChecked());
        cbEnableTrim.setEnabled(!busy);
        editorPanels.setEnabled(etTrimStart, !busy && cbEnableTrim.isChecked());
        editorPanels.setEnabled(etTrimEnd, !busy && cbEnableTrim.isChecked());
        cbEnableVolume.setEnabled(!busy);
        if (offlineMusic != null) offlineMusic.setBusy(busy);
        spinnerVolume.setEnabled(!busy && cbEnableVolume.isChecked());
        cbEnableIntro.setEnabled(!busy);
        boolean titleEnabled = !busy && cbEnableIntro.isChecked();
        if (titleBackgroundControls != null) titleBackgroundControls.setBusy(busy, cbEnableIntro.isChecked());
        for (int id : new int[]{R.id.spinnerIntroTemplate, R.id.etIntroText, R.id.spinnerTitleStyle,
                R.id.spinnerTitleDuration, R.id.spinnerTitleFont, R.id.spinnerTitleWeight,
                R.id.spinnerTitleAlign, R.id.spinnerTitlePalette, R.id.etTitleSize,
                R.id.etTitleDurationMs, R.id.spinnerTitleLayout, R.id.btnTitleTextColor,
                R.id.btnTitleBackgroundColor, R.id.btnTitleGradientColor, R.id.cbTitleGradient})
            editorPanels.setEnabled(findViewById(id), titleEnabled);
        btnSaveTemplate.setEnabled(!busy);
        btnManageTemplates.setEnabled(!busy);
        editorPanels.setEnabled(etCustomAngle, !busy);
        editorPanels.setEnabled(etOverlayText, !busy);
        editorPanels.setEnabled(etCropLeft, !busy);
        editorPanels.setEnabled(etCropTop, !busy);
        editorPanels.setEnabled(etCropRight, !busy);
        editorPanels.setEnabled(etCropBottom, !busy);
        spinnerResolution.setEnabled(!busy);
        spinnerSpeed.setEnabled(!busy);
        findViewById(R.id.btnRotateLeft).setEnabled(!busy);
        findViewById(R.id.btnRotateRight).setEnabled(!busy);
        btnSelfTest.setEnabled(!busy);
        btnProcess.setEnabled(!busy && selectedMainVideo != null && geometryValid
                && !(cbEnableMerge.isChecked() && appendedVideos.isEmpty()));
        btnProcess.setVisibility(busy ? View.GONE : View.VISIBLE);
        for (int id : new int[]{R.id.btnOpenOutputFolder, R.id.btnShareVideo, R.id.btnCopyVideoInfo,
                R.id.btnOutputLocation, R.id.btnVideoDirectory}) findViewById(id).setEnabled(!busy);
        btnCancelExport.setEnabled(exporting || loading);
        btnCancelExport.setVisibility(exporting || loading ? View.VISIBLE : View.GONE);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        progressBar.setIndeterminate(loading || publishing);
        tvProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshMergeRowButtons(layoutMergeList);
    }

    private void refreshMergeRowButtons(View view) {
        if (view instanceof Button) {
            Object tagObject = view.getTag();
            if (tagObject instanceof String) {
                String tag = (String) tagObject;
                boolean enabled = !loading && !exporting && cbEnableMerge.isChecked();
                if (tag.startsWith("merge:up:")) {
                    int index = Integer.parseInt(tag.substring("merge:up:".length()));
                    view.setEnabled(enabled && index > 0);
                } else if (tag.startsWith("merge:down:")) {
                    int index = Integer.parseInt(tag.substring("merge:down:".length()));
                    view.setEnabled(enabled && index + 1 < appendedVideos.size());
                } else if (tag.startsWith("merge:remove:")) {
                    view.setEnabled(enabled);
                }
            }
            return;
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            refreshMergeRowButtons(group.getChildAt(i));
        }
    }

    private void setupIntroTemplateSpinner() {
        btnSaveTemplate.setOnClickListener(v -> showSaveTemplateDialog());
        btnManageTemplates.setOnClickListener(v -> showManageTemplatesDialog());
        try {
        String[] names = templateManager.getTemplateNames();
        setOptions(spinnerIntroTemplate, names);
        currentTemplate = templateManager.getLastUsedTemplate();
        if (currentTemplate == null && names.length > 0) {
            currentTemplate = templateManager.getTemplate(names[0]);
            templateManager.setLastUsedTemplate(names[0]);
        }
        if (currentTemplate != null) {
            syncTitleControls();
            etIntroText.setText(currentTemplate.getText());
            updateTemplatePreview();
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(currentTemplate.getName())) {
                    spinnerIntroTemplate.setSelection(i);
                    break;
                }
            }
        }
        spinnerIntroTemplate.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                String name = (String) parent.getItemAtPosition(position);
                if (restoringUi || position == selectedTemplatePosition) return;
                selectedTemplatePosition = position;
                try {
                currentTemplate = templateManager.getTemplate(name);
                if (currentTemplate != null) {
                    syncTitleControls();
                    etIntroText.setText(currentTemplate.getText());
                    templateManager.setLastUsedTemplate(name);
                    updateTemplatePreview();
                }
                } catch (RuntimeException error) {
                    showError(getString(R.string.title_preset_load_failed), error);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        } catch (RuntimeException error) {
            showError(getString(R.string.title_preset_load_failed), error);
        }
    }

    private void setupWholePresets() {
        refreshWholePresets();
        findViewById(R.id.btnSaveWholePreset).setOnClickListener(v -> saveWholePreset());
        findViewById(R.id.btnApplyWholePreset).setOnClickListener(v -> applyWholePreset());
        findViewById(R.id.btnDeleteWholePreset).setOnClickListener(v -> {
            if (loading || exporting) return;
            String name = selectedWholePreset();
            if (name == null) return;
            new android.app.AlertDialog.Builder(this).setTitle(R.string.editor_delete_preset_confirm)
                    .setMessage(name).setPositiveButton(R.string.editor_delete, (dialog, which) -> {
                        try { wholePresets.delete(name); refreshWholePresets(); }
                        catch (Exception error) { showError(getString(R.string.editor_preset_delete_failed), error); }
                    }).setNegativeButton(R.string.editor_cancel, null).show();
        });
    }

    private String selectedWholePreset() {
        Object item = ((Spinner) findViewById(R.id.spinnerWholePreset)).getSelectedItem();
        return item == null ? null : item.toString();
    }

    private void refreshWholePresets() {
        refreshWholePresets(selectedWholePreset());
    }

    private void refreshWholePresets(String selected) {
        try {
            Spinner spinner = findViewById(R.id.spinnerWholePreset);
            String[] names = wholePresets.names();
            setOptions(spinner, names);
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(selected)) { spinner.setSelection(i); break; }
            }
        }
        catch (Exception error) { showError(getString(R.string.editor_presets_read_failed), error); }
    }

    private org.json.JSONObject wholePresetSnapshot() throws Exception {
        EditConfig config = snapshotConfig();
        org.json.JSONObject recipe = new org.json.JSONObject();
        recipe.put("headMs", config.startMs).put("tailMs", config.sourceDurationMs - config.endMs);
        recipe.put("crop", new org.json.JSONArray(CropRemoval.removed(
                config.cropLeft, config.cropTop, config.cropRight, config.cropBottom)));
        recipe.put("rotation", config.rotationDegrees).put("resolution", spinnerResolution.getSelectedItemPosition());
        recipe.put("speed", spinnerSpeed.getSelectedItemPosition()).put("volume", spinnerVolume.getSelectedItemPosition());
        recipe.put("trimEnabled", cbEnableTrim.isChecked()).put("volumeEnabled", cbEnableVolume.isChecked());
        recipe.put("overlay", config.overlayText).put("titleEnabled", cbEnableIntro.isChecked());
        IntroTemplate title = currentTemplate.copy();
        title.setText(etIntroText.getText().toString());
        new EditConfig.Builder(Uri.EMPTY, 1).sourceSize(320, 240).introTemplate(title, title.getText()).build();
        recipe.put("title", new org.json.JSONObject(title.toJson()));
        recipe.put("pngEnabled", cbEnableWatermark.isChecked());
        recipe.put("pngWidth", Float.parseFloat(etWatermarkWidth.getText().toString()) / 100);
        recipe.put("pngX", Float.parseFloat(etWatermarkX.getText().toString()) / 100);
        recipe.put("pngY", Float.parseFloat(etWatermarkY.getText().toString()) / 100);
        recipe.put("border", readBorder().toJson());
        recipe.put("colorAdjustment", colorControls.snapshot().toJson());
        config.backgroundMusic.putRecipe(recipe);
        return recipe;
    }

    private void saveWholePreset() {
        if (selectedIntroVideo != null) {
            new AlertDialog.Builder(this).setTitle(R.string.editor_source_copy_title)
                    .setMessage(getString(R.string.editor_source_preset_copy,
                            sourceSize(selectedIntroVideo.sizeBytes), sourceSize(getFilesDir().getUsableSpace())))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.editor_source_copy_confirm, (dialog, which) -> saveWholePresetConfirmed()).show();
        } else saveWholePresetConfirmed();
    }

    private void saveWholePresetConfirmed() {
        if (loading || exporting || loader.isShutdown()) return;
        try {
            org.json.JSONObject recipe = wholePresetSnapshot();
            String name = ((EditText) findViewById(R.id.etWholePresetName)).getText().toString();
            VideoSource intro = selectedIntroVideo == null ? null : selectedIntroVideo.source;
            File music = selectedMusic == null ? null : selectedMusic.source.ownedFile;
            File png = watermarkFilePath == null ? null : new File(watermarkFilePath);
            loading = true;
            int generation = ++loadGeneration;
            updateWorkControls();
            loadTask = loader.submit(() -> {
                try {
                    wholePresets.saveWithVideoSource(name, recipe, intro, music, png,
                            bytes -> main.post(() -> {
                                if (!destroyed && generation == loadGeneration)
                                    tvProgress.setText(getString(R.string.editor_source_preset_progress, bytes));
                            }));
                    main.post(() -> {
                        if (destroyed || generation != loadGeneration) return;
                        finishLoading(); refreshWholePresets(name.trim());
                        tvProgress.setText(getString(R.string.editor_preset_saved, name));
                    });
                } catch (Exception error) {
                    main.post(() -> { if (!destroyed && generation == loadGeneration) {
                        finishLoading(); showError(getString(R.string.editor_preset_save_failed), error);
                    } });
                }
            });
        } catch (Exception error) { showError(getString(R.string.editor_preset_save_failed), error); }
    }

    private void applyWholePreset() {
        if (loading || exporting || selectedMainVideo == null || loader.isShutdown()) return;
        String name = selectedWholePreset();
        if (name == null) return;
        loading = true;
        int generation = ++loadGeneration;
        updateWorkControls();
        loadTask = loader.submit(() -> {
            LoadedVideo intro = null, music = null;
            File png = null;
            try {
                org.json.JSONObject recipe = wholePresets.load(name);
                File introAsset = wholePresets.asset(recipe, "introAsset");
                File musicAsset = wholePresets.asset(recipe, "musicAsset");
                File pngAsset = wholePresets.asset(recipe, "pngAsset");
                long started = SystemClock.elapsedRealtime();
                if (introAsset != null) intro = readMedia(this, Uri.fromFile(introAsset),
                        new File(getCacheDir(), "preset-intro-" + UUID.randomUUID() + ".mp4"), true,
                        started, EditConfig.MAX_VIDEO_BYTES, EditConfig.MAX_VIDEO_DIMENSION,
                        message -> {}, true);
                if (musicAsset != null) music = readMedia(this, Uri.fromFile(musicAsset),
                        new File(getCacheDir(), "preset-music-" + UUID.randomUUID()), false,
                        started, EditConfig.MAX_VIDEO_BYTES, EditConfig.MAX_VIDEO_DIMENSION);
                PngWatermark image = null;
                if (pngAsset != null) {
                    png = new File(getCacheDir(), "preset-png-" + UUID.randomUUID() + ".png");
                    image = PngWatermark.copyDocument(this, Uri.fromFile(pngAsset), png);
                }
                LoadedVideo readyIntro = intro, readyMusic = music;
                File readyPng = png;
                PngWatermark readyImage = image;
                main.post(() -> {
                    try {
                        if (destroyed || generation != loadGeneration) throw new IOException("Preset application cancelled");
                        applyWholePresetSnapshot(recipe, readyIntro, readyMusic, readyPng, readyImage);
                        ((EditText) findViewById(R.id.etWholePresetName)).setText(name);
                        tvProgress.setText(getString(R.string.editor_preset_applied, name));
                    } catch (Exception error) {
                        if (readyIntro != null) readyIntro.dispose();
                        if (readyMusic != null) readyMusic.dispose();
                        if (readyPng != null) readyPng.delete();
                        if (!destroyed && generation == loadGeneration) showError(getString(R.string.editor_preset_apply_failed), error);
                    } finally { if (!destroyed && generation == loadGeneration) finishLoading(); }
                });
            } catch (Exception error) {
                if (intro != null) intro.dispose();
                if (music != null) music.dispose();
                if (png != null) png.delete();
                main.post(() -> { if (!destroyed && generation == loadGeneration) { finishLoading(); showError(getString(R.string.editor_preset_apply_failed), error); } });
            }
        });
    }

    private void applyWholePresetSnapshot(org.json.JSONObject recipe, LoadedVideo intro, LoadedVideo music,
                                          File png, PngWatermark image) throws Exception {
        long[] trim = WholeEditPresets.trim(recipe, selectedMainVideo.duration);
        float[] crop = WholeEditPresets.crop(recipe);
        int resolution = recipe.getInt("resolution"), speed = recipe.getInt("speed"), volume = recipe.getInt("volume");
        if (resolution < 0 || resolution >= HEIGHTS.length || speed < 0 || speed >= SPEEDS.length
                || volume < 0 || volume >= VOLUMES.length) throw new IOException("Invalid preset option index");
        IntroTemplate title = IntroTemplate.fromJsonStrict(recipe.getJSONObject("title"));
        if (title.hasSourceFrameBackground())
            TitleBackground.parseTime(title.getSourceFrameSeconds(), selectedMainVideo.duration);
        boolean titleEnabled = recipe.getBoolean("titleEnabled"), pngEnabled = recipe.getBoolean("pngEnabled");
        boolean trimEnabled = recipe.getBoolean("trimEnabled"), volumeEnabled = recipe.getBoolean("volumeEnabled");
        VideoBorder border = VideoBorder.fromRecipe(recipe);
        ColorAdjustment color = ColorAdjustment.fromRecipe(recipe);
        BackgroundMusic bgm = BackgroundMusic.fromRecipe(recipe);
        OfflineMusicCatalog.load(this).validateSelection(bgm);
        int rotation = recipe.getInt("rotation");
        String overlay = recipe.getString("overlay");
        float width = (float) recipe.getDouble("pngWidth"), x = (float) recipe.getDouble("pngX"),
                y = (float) recipe.getDouble("pngY");
        if (pngEnabled && image == null) throw new IOException("Enabled watermark asset missing");
        ArrayList<EditConfig.ImportedVideo> extras = new ArrayList<>();
        if (cbEnableMerge.isChecked()) for (LoadedVideo clip : appendedVideos) extras.add(clip.asImportedVideo());
        new EditConfig.Builder(Uri.EMPTY, 1).sourceSize(320, 240).introTemplate(title, title.getText()).build();
        EditConfig validated = new EditConfig.Builder(selectedMainVideo.source.uri, selectedMainVideo.duration)
                .inputSource(selectedMainVideo.source)
                .mainSourceMetadata(selectedMainVideo.hasAudio, selectedMainVideo.sizeBytes)
                .sourceSize(selectedMainVideo.width, selectedMainVideo.height).trim(trim[0], trim[1])
                .crop(crop[0], crop[1], crop[2], crop[3]).rotation(rotation).outputHeight(HEIGHTS[resolution])
                .speed(SPEEDS[speed]).volume(volumeEnabled ? VOLUMES[volume] : 1)
                .overlayText(overlay).introTemplate(titleEnabled ? title : null, title.getText())
                .introVideo(intro == null ? null : intro.asImportedVideo())
                .replacementMusic(music == null || bgm.enabled ? null : music.source.uri)
                .backgroundMusic(bgm)
                .watermark(pngEnabled ? image : null, width, x, y)
                .border(border)
                .colorAdjustment(color)
                .mergeMode(cbEnableMerge.isChecked()).appendVideos(extras).build();
        Size size = Media3ExportEngine.editedCanvas(validated);
        if (validated.introTitle != null) TitleRenderer.validate(size.getWidth(), size.getHeight(), validated.introTitle);
        if (validated.watermark != null) PngWatermark.rectangle(size.getWidth(), size.getHeight(), validated.watermark);
        // Nothing above mutates the current edit. Asset snapshots and all settings swap together.
        applyingPreset = true;
        LoadedVideo previousIntro = selectedIntroVideo, previousMusic = selectedMusic;
        String previousPng = watermarkFilePath;
        try {
            selectedIntroVideo = intro; selectedMusic = music;
            offlineMusic.apply(bgm);
            watermarkFilePath = png == null ? null : png.getAbsolutePath(); watermarkImage = image;
            currentTemplate = title;
            etIntroText.setText(title.getText()); cbEnableIntro.setChecked(titleEnabled);
            etTrimStart.setText(BigDecimal.valueOf(trim[0], 3).toPlainString());
            etTrimEnd.setText(BigDecimal.valueOf(trim[1], 3).toPlainString()); cbEnableTrim.setChecked(trimEnabled);
            EditText[] fields = {etCropLeft, etCropTop, etCropRight, etCropBottom};
            for (int i = 0; i < 4; i++) fields[i].setText(recipe.getJSONArray("crop").getString(i));
            etCustomAngle.setText(Integer.toString(rotation)); spinnerResolution.setSelection(resolution);
            spinnerSpeed.setSelection(speed); spinnerVolume.setSelection(volume); cbEnableVolume.setChecked(volumeEnabled);
            etOverlayText.setText(overlay); etWatermarkWidth.setText(Float.toString(width * 100));
            etWatermarkX.setText(Float.toString(x * 100)); etWatermarkY.setText(Float.toString(y * 100));
            cbEnableWatermark.setChecked(pngEnabled);
            spinnerBorderColor.setSelection(border.colorIndex);
            spinnerBorderWidth.setSelection(border.percent - 1);
            spinnerBorderScope.setSelection(border.scope == VideoBorder.Scope.TITLE ? 0 : 1);
            cbEnableBorder.setChecked(border.enabled);
            colorControls.apply(color);
        } finally { applyingPreset = false; }
        if (previousIntro != null) previousIntro.dispose();
        if (previousMusic != null) previousMusic.dispose();
        deleteCacheFile(previousPng);
        syncTitleControls();
        updateAuxiliaryLabels(); updateTemplatePreview(); updateSpatialPreview();
    }

    private void setupTitleControls() {
        titleAppearanceControls = new TitleAppearanceControls(this, () -> currentTemplate, this::updateTemplatePreview);
        int[] ids = {R.id.spinnerTitleStyle, R.id.spinnerTitleDuration, R.id.spinnerTitleFont,
                R.id.spinnerTitleWeight, R.id.spinnerTitleAlign, R.id.spinnerTitlePalette};
        String[][] options = {getResources().getStringArray(R.array.editor_title_styles),
                getResources().getStringArray(R.array.editor_title_durations),
                getResources().getStringArray(R.array.editor_title_fonts),
                getResources().getStringArray(R.array.editor_title_weights),
                getResources().getStringArray(R.array.editor_title_aligns),
                getResources().getStringArray(R.array.editor_title_palettes)};
        for (int i = 0; i < ids.length; i++) {
            final int id = ids[i];
            Spinner spinner = findViewById(id);
            setOptions(spinner, options[i]);
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long row) {
                    if (restoringUi || applyingPreset || updatingTitleControls || currentTemplate == null) return;
                    if (position != spinner.getSelectedItemPosition()
                            || Integer.valueOf(position).equals(spinner.getTag())) return;
                    spinner.setTag(position);
                    if (id == R.id.spinnerTitleStyle) {
                        currentTemplate.setLayout(currentTemplate.getLayout());
                        currentTemplate.setAnimation(TITLE_STYLES[position]);
                    } else if (id == R.id.spinnerTitleDuration && position > 0) {
                        currentTemplate.setDurationMs(position == 1 ? 3000 : 5000);
                        ((EditText) findViewById(R.id.etTitleDurationMs)).setText(Integer.toString(currentTemplate.getDurationMs()));
                    }
                    else if (id == R.id.spinnerTitleFont) currentTemplate.setFontFamily(TITLE_FONTS[position]);
                    else if (id == R.id.spinnerTitleWeight) currentTemplate.setFontStyle(TITLE_WEIGHTS[position]);
                    else if (id == R.id.spinnerTitleAlign) currentTemplate.setAlignment(TITLE_ALIGNS[position]);
                    else if (id == R.id.spinnerTitlePalette && position > 0) {
                        int[] starts = {0, 0xFF102438, 0xFF44172C, 0xFFFFF2DD, 0xFF000000};
                        int[] ends = {0, 0xFF356B83, 0xFFB16C36, 0xFFFFF2DD, 0xFF000000};
                        currentTemplate.setBackgroundColor(starts[position]);
                        if (starts[position] == ends[position]) currentTemplate.clearGradient();
                        else currentTemplate.setGradientColor(ends[position]);
                        titleAppearanceControls.sync();
                    }
                    updateTemplatePreview();
                }
            });
        }
        ((EditText) findViewById(R.id.etTitleSize)).addTextChangedListener(watcher(() -> {
            if (!restoringUi && !updatingTitleControls && currentTemplate != null) {
                try { currentTemplate.setTextSize(Integer.parseInt(((EditText) findViewById(R.id.etTitleSize)).getText().toString())); }
                catch (NumberFormatException error) { currentTemplate.setTextSize(0); }
                ((EditText) findViewById(R.id.etTitleSize)).setError(
                        currentTemplate.getTextSize() >= 1 && currentTemplate.getTextSize() <= 160
                                ? null : getString(R.string.editor_title_size));
                updateTemplatePreview();
            }
        }));
        ((EditText) findViewById(R.id.etTitleDurationMs)).addTextChangedListener(watcher(() -> {
            if (restoringUi || applyingPreset || updatingTitleControls || currentTemplate == null) return;
            try { currentTemplate.setDurationMs(Integer.parseInt(
                    ((EditText) findViewById(R.id.etTitleDurationMs)).getText().toString())); }
            catch (NumberFormatException error) { currentTemplate.setDurationMs(0); }
            ((EditText) findViewById(R.id.etTitleDurationMs)).setError(
                    currentTemplate.getDurationMs() > 0 ? null : getString(R.string.title_duration_ms));
            setTitleSelection(R.id.spinnerTitleDuration, currentTemplate.getDurationMs() == 3000
                    ? 1 : currentTemplate.getDurationMs() == 5000 ? 2 : 0);
            updateTemplatePreview();
        }));
        ((android.widget.SeekBar) findViewById(R.id.titlePreviewTime)).setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int value, boolean fromUser) { updateTemplatePreview(); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
        });
    }

    private void syncTitleControls() {
        if (currentTemplate == null) return;
        if (titleBackgroundControls != null) titleBackgroundControls.apply(currentTemplate);
        updatingTitleControls = true;
        setTitleSelection(R.id.spinnerTitleStyle, java.util.Arrays.asList(TITLE_STYLES).indexOf(currentTemplate.getAnimation()));
        setTitleSelection(R.id.spinnerTitleFont, java.util.Arrays.asList(TITLE_FONTS).indexOf(currentTemplate.getFontFamily()));
        setTitleSelection(R.id.spinnerTitleWeight, java.util.Arrays.asList(TITLE_WEIGHTS).indexOf(currentTemplate.getFontStyle()));
        setTitleSelection(R.id.spinnerTitleAlign, java.util.Arrays.asList(TITLE_ALIGNS).indexOf(currentTemplate.getAlignment()));
        setTitleSelection(R.id.spinnerTitleDuration, currentTemplate.getDurationMs() == 3000 ? 1 : currentTemplate.getDurationMs() == 5000 ? 2 : 0);
        setTitleSelection(R.id.spinnerTitlePalette, 0);
        ((EditText) findViewById(R.id.etTitleSize)).setText(Integer.toString(currentTemplate.getTextSize()));
        ((EditText) findViewById(R.id.etTitleDurationMs)).setText(Integer.toString(currentTemplate.getDurationMs()));
        titleAppearanceControls.sync();
        updatingTitleControls = false;
    }

    private void setTitleSelection(int id, int position) {
        Spinner spinner = findViewById(id);
        spinner.setTag(position);
        spinner.setSelection(position);
    }

    private void updateTemplatePreview() {
        if (restoringUi) return;
        if (currentTemplate == null) {
            tvTemplatePreview.setText(R.string.editor_template_none);
            updateMergeUi();
            return;
        }
        try {
            EditConfig geometry;
            try { geometry = titleGeometry(); }
            catch (IllegalArgumentException invalidGeometry) {
                titleBackgroundControls.refresh(null);
                ((TitlePreviewView) findViewById(R.id.titleCanvasPreview)).setBackgroundFrame(null);
                throw invalidGeometry;
            }
            titleBackgroundControls.refresh(geometry);
            ((TitlePreviewView) findViewById(R.id.titleCanvasPreview)).setBackgroundFrame(
                    cbEnableIntro.isChecked() && currentTemplate.hasSourceFrameBackground()
                            ? titleBackgroundControls.preview() : null);
            if (cbEnableIntro.isChecked() && currentTemplate.hasSourceFrameBackground()) {
                if (geometry == null) throw new IllegalArgumentException(getString(R.string.title_frame_select_source));
                titleBackgroundControls.snapshot(geometry);
            }
            EditConfig.IntroTitle title = new EditConfig.Builder(Uri.EMPTY, 1).sourceSize(640, 360)
                    .introTemplate(currentTemplate, etIntroText.getText().toString()).build().introTitle;
            Size size = new Size(640, 360);
            if (selectedMainVideo != null && !applyingPreset) {
                float[] crop = readCrop();
                size = Media3ExportEngine.editedCanvas(new EditConfig.Builder(Uri.EMPTY, 1)
                        .sourceSize(selectedMainVideo.width, selectedMainVideo.height)
                        .crop(crop[0], crop[1], crop[2], crop[3]).rotation(readRotation())
                        .outputHeight(HEIGHTS[spinnerResolution.getSelectedItemPosition()]).build());
            }
            android.widget.SeekBar time = findViewById(R.id.titlePreviewTime);
            TitleRenderer.validate(size.getWidth(), size.getHeight(), title);
            time.setMax(title.durationMs - 1);
            ((TitlePreviewView) findViewById(R.id.titleCanvasPreview)).setBorder(
                    cbEnableIntro.isChecked() ? readBorder() : VideoBorder.OFF);
            ((TitlePreviewView) findViewById(R.id.titleCanvasPreview)).setTitle(title, size.getWidth(), size.getHeight(), time.getProgress() * 1000L);
            tvTemplatePreview.setText(getString(R.string.editor_template_frame,
                    getResources().getStringArray(R.array.editor_title_styles)[
                            java.util.Arrays.asList(TITLE_STYLES).indexOf(title.animation)],
                    time.getProgress() / 1000d, title.durationMs / 1000d, size.getWidth(), size.getHeight()));
        } catch (IllegalArgumentException error) {
            tvTemplatePreview.setText(getString(R.string.editor_invalid_title, localizedValidation(error)));
            recordRawError(getString(R.string.editor_invalid_title, localizedValidation(error)), error);
            ((TitlePreviewView) findViewById(R.id.titleCanvasPreview)).setTitle(null, 640, 360, 0);
        }
        updateMergeUi();
    }

    private EditConfig titleGeometry() {
        if (selectedMainVideo == null) return null;
        float[] crop = readCrop();
        return new EditConfig.Builder(selectedMainVideo.source.uri, selectedMainVideo.duration)
                .inputSource(selectedMainVideo.source)
                .sourceSize(selectedMainVideo.width, selectedMainVideo.height)
                .crop(crop[0], crop[1], crop[2], crop[3]).rotation(readRotation())
                .outputHeight(HEIGHTS[spinnerResolution.getSelectedItemPosition()]).build();
    }

    private void showSaveTemplateDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.enter_template_name);
        if (currentTemplate != null) input.setText(currentTemplate.getName());
        new android.app.AlertDialog.Builder(this).setTitle(R.string.save_as_template).setView(input)
                .setPositiveButton(R.string.editor_save, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.editor_template_name_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    IntroTemplate template = currentTemplate != null ? currentTemplate.copy() : new IntroTemplate();
                    template.setName(name);
                    template.setText(etIntroText.getText().toString().trim());
                    try {
                    if (templateManager.saveTemplate(template)) {
                        templateManager.setLastUsedTemplate(name);
                        setupIntroTemplateSpinner();
                        Toast.makeText(this, R.string.template_saved, Toast.LENGTH_SHORT).show();
                    } else {
                        showError(getString(R.string.editor_template_save_failed), new IOException("Template manager could not save " + name));
                    }
                    } catch (RuntimeException error) {
                        showError(getString(R.string.editor_template_save_failed), error);
                    }
                }).setNegativeButton(R.string.editor_cancel, null).show();
    }

    private void showManageTemplatesDialog() {
        try {
        String[] names = templateManager.getTemplateNames();
        new android.app.AlertDialog.Builder(this).setTitle(R.string.manage_templates)
                .setItems(names, (dialog, which) ->
                        new android.app.AlertDialog.Builder(this).setTitle(R.string.editor_delete_template)
                                .setMessage(getString(R.string.confirm_delete_template, names[which]))
                                .setPositiveButton(R.string.editor_delete, (d, w) -> {
                                    try {
                                    if (templateManager.deleteTemplate(names[which])) {
                                        setupIntroTemplateSpinner();
                                    } else {
                                        showError(getString(R.string.editor_template_delete_failed),
                                                new IOException("Cannot delete template: " + names[which]));
                                    }
                                    } catch (RuntimeException error) {
                                        showError(getString(R.string.editor_template_delete_failed), error);
                                    }
                                }).setNegativeButton(R.string.editor_cancel, null).show())
                .setNegativeButton(R.string.editor_close, null).show();
        } catch (RuntimeException error) {
            showError(getString(R.string.title_preset_load_failed), error);
        }
    }

    private void showError(String title, Throwable error) {
        if (destroyed) return;
        tvErrorDetails.setText(error instanceof IllegalArgumentException
                ? title + "\n" + localizedValidation(error) : title);
        recordRawError(title, error);
        svErrorContainer.setVisibility(View.VISIBLE);
    }

    private void recordRawError(String title, Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        ((TextView) findViewById(R.id.tvRawError)).setText(title + "\n" + trace);
        findViewById(R.id.tvRawError).setVisibility(View.VISIBLE);
    }

    private String localizedValidation(Throwable error) {
        // UI-only summaries: engine exceptions and saved diagnostic details stay unchanged.
        String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        int resource = R.string.editor_validation_settings;
        if (message.contains("border")) {
            resource = R.string.editor_validation_border;
        } else if (message.contains("crop") || message.contains("切除")) {
            resource = R.string.editor_validation_crop;
        } else if (message.contains("angle") || message.contains("rotation")) {
            resource = R.string.editor_validation_rotation;
        } else if (message.contains("trim")) {
            resource = R.string.editor_validation_trim;
        } else if (message.contains("png") || message.contains("watermark")) {
            resource = R.string.editor_validation_png;
        } else if (message.contains("256 mib")) {
            resource = R.string.editor_validation_total_bytes;
        } else if (message.contains("128 mib") || message.contains("snapshot byte limit")) {
            resource = R.string.editor_validation_clip_bytes;
        } else if (message.contains("4096") || message.contains("outputheight") || message.contains("sourcesize")) {
            resource = R.string.editor_validation_dimensions;
        } else if (message.contains("120 second")) {
            resource = R.string.editor_validation_duration;
        } else if (message.contains("title") || message.contains("font") || message.contains("intro duration")) {
            resource = R.string.editor_validation_title;
        } else if (message.contains("appended") || message.contains("merge")) {
            resource = R.string.editor_validation_merge;
        } else if (message.contains("select a video") || message.contains("no document")) {
            resource = R.string.editor_validation_video;
        }
        return getString(resource);
    }

    private void clearError() {
        tvErrorDetails.setText("");
        svErrorContainer.setVisibility(View.GONE);
    }

    private void releaseThumbnail() {
        ivVideoThumbnail.setImageDrawable(null);
        // Both may still be referenced by the hardware renderer or the main merge row.
        previewBitmap = null;
        currentThumbnail = null;
    }

    private void deleteCachedInput() {
        if (selectedMainVideo != null) {
            selectedMainVideo.source.dispose();
            selectedMainVideo = null;
        }
    }

    private static void deleteCacheFile(String path) {
        if (path == null) return;
        File file = new File(path);
        if (!file.delete() && file.exists()) {
            android.util.Log.w("MainActivity", "Cannot delete cached media: " + file);
        }
    }

    private static void disposeLoadedVideo(LoadedVideo video) {
        if (video != null) video.dispose();
    }

    private static void clearLoadedVideos(List<LoadedVideo> videos) {
        for (LoadedVideo video : videos) {
            if (video != null) video.dispose();
        }
    }

    private void releaseAllSelections() {
        releaseThumbnail();
        deleteCachedInput();
        disposeLoadedVideo(selectedIntroVideo);
        selectedIntroVideo = null;
        disposeLoadedVideo(selectedMusic);
        selectedMusic = null;
        clearLoadedVideos(appendedVideos);
        appendedVideos.clear();
        deleteCacheFile(watermarkFilePath);
        watermarkFilePath = null;
        watermarkImage = null;
    }

    @Override
    protected void onPause() {
        if (offlineMusic != null) offlineMusic.stopAudition();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (offlineMusic != null) offlineMusic.close();
        destroyed = true;
        ++previewGeneration;
        if (previewRequest != null) main.removeCallbacks(previewRequest);
        if (previewTask != null) previewTask.cancel(true);
        publicationCancelled.set(true);
        if (!exporting || publishing) publicationWorker.shutdown();
        ++loadGeneration;
        if (loadTask != null) loadTask.cancel(true);
        loader.shutdownNow();
        if (exportEngine != null) exportEngine.cancel();
        if (titleBackgroundControls != null) titleBackgroundControls.close();
        if (exporting) {
            cleanupSelectionsWhenExportStops = isFinishing() || !savedSelections;
            releaseThumbnail();
        } else if (isFinishing() || !savedSelections) {
            releaseAllSelections();
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }
}
