package com.simple.videoeditor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@androidx.media3.common.util.UnstableApi
public final class SelfTestActivity extends AppCompatActivity {
    private static final int CREATE_REPORT = 41;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor restoreExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            runnable -> new Thread(runnable, "selftest-report-restore"));
    private Future<?> restoreFuture;
    private int restoreGeneration;
    private volatile int selectionGeneration;
    private volatile boolean destroyed;
    private boolean restoring;
    private boolean destinationLoaded;
    private boolean startRequested;
    private boolean awaitingDocument;
    private SelfTestPlan.Mode selectedMode = SelfTestPlan.Mode.FULL;
    private boolean suiteRunning;
    private SelfTestRunner runner;
    private File reportBeforeStart;
    private File shareableReport;
    private Button startButton;
    private Button shortButton;
    private Button cancelButton;
    private Button shareButton;
    private Button openButton;
    private TextView destinationView;
    private volatile SavedReport savedReport;
    private TextView statusView;
    private TextView reportView;
    private LinearLayout outputsView;
    private TextView summaryView;
    private String summaryProblem;
    private boolean viewingExternalReport;
    private String report;
    private String failures = "";

    @Override
    protected void onCreate(Bundle state) {
        UiLocales.initialize(this);
        super.onCreate(state);
        report = getString(R.string.secondary_start_hint);
        if (state != null) {
            selectedMode = SelfTestPlan.Mode.valueOf(state.getString("diagnosticMode", "FULL"));
            awaitingDocument = state.getBoolean("awaitingDocument", false);
            startRequested = awaitingDocument;
        }
        setTitle(R.string.secondary_selftest_title);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        statusView = new TextView(this);
        statusView.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(statusView);
        destinationView = new TextView(this);
        destinationView.setTextIsSelectable(true);
        root.addView(destinationView);
        LinearLayout controls = new LinearLayout(this);
        root.addView(controls);
        startButton = button(controls, getString(R.string.secondary_start), () -> requestStart(SelfTestPlan.Mode.FULL));
        cancelButton = button(controls, getString(R.string.secondary_cancel), this::requestCancel);
        shortButton = button(root, getString(R.string.secondary_short_start),
                () -> requestStart(SelfTestPlan.Mode.SHORT_DIAGNOSTIC));
        TextView shortNotice = new TextView(this);
        shortNotice.setText(R.string.secondary_short_notice);
        root.addView(shortNotice);
        LinearLayout sharing = new LinearLayout(this);
        root.addView(sharing);
        button(sharing, getString(R.string.secondary_copy_report), this::copyReport);
        shareButton = button(sharing, getString(R.string.secondary_share_report), this::shareReport);
        openButton = button(root, getString(R.string.secondary_open_report), this::openSavedReport);
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        reportView = new TextView(this);
        reportView.setTextIsSelectable(true);
        reportView.setSaveEnabled(false);
        reportView.setFreezesText(false);
        content.addView(reportView);
        outputsView = new LinearLayout(this);
        outputsView.setOrientation(LinearLayout.VERTICAL);
        content.addView(outputsView);
        summaryView = new TextView(this);
        summaryView.setTag("terminal-suite-summary");
        summaryView.setTextSize(18);
        summaryView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        summaryView.setPadding(padding, padding, padding, padding);
        summaryView.setTextIsSelectable(true);
        summaryView.setSaveEnabled(false);
        summaryView.setFreezesText(false);
        summaryView.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(summaryView);
        setContentView(root);
        renderReport();
        restore();
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }

    private void restore() {
        restoring = true;
        updateControls();
        int generation = ++restoreGeneration;
        restoreFuture = restoreExecutor.submit(new RestoreTask(
                this, getApplicationContext(), main, generation));
    }

    private void restored(int generation, SelfTestRunner restoredRunner,
            String restoredReport, String error) {
        if (destroyed || generation != restoreGeneration) {
            return;
        }
        restoring = false;
        destinationLoaded = true;
        restoreFuture = null;
        runner = restoredRunner;
        if (error != null) {
            failures += "\nRESTORE FAILURE:\n" + error;
            summaryProblem = getString(R.string.secondary_restore_failed);
        }
        if (startRequested && savedReport != null && runner != null) {
            startSuite();
            return;
        }
        report = restoredReport;
        if (runner != null) {
            showArtifacts(runner.getReportFile());
        }
        renderReport();
        updateControls();
    }

    private void requestStart(SelfTestPlan.Mode mode) {
        if (suiteRunning || startRequested || restoring) {
            return;
        }
        if (SelfTestRunner.hasRunningSuite()) {
            failure("Previous suite still owns cleanup", new IOException("Wait for native cleanup, then retry"));
            return;
        }
        selectedMode = mode;
        summaryProblem = null;
        viewingExternalReport = false;
        savedReport = null;
        startRequested = true;
        awaitingDocument = true;
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, (mode == SelfTestPlan.Mode.FULL ? "selftest-" : "short-diagnostic-")
                            + System.currentTimeMillis() + ".txt")
                    .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, CREATE_REPORT);
        } catch (RuntimeException error) {
            startRequested = false;
            awaitingDocument = false;
            summaryProblem = getString(R.string.secondary_select_failed);
            failure("无法选择 TXT；测试未开始 / SELECT FAILED", error);
        }
        updateControls();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CREATE_REPORT) return;
        awaitingDocument = false;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            startRequested = false;
            summaryProblem = getString(R.string.secondary_select_cancelled);
            updateControls();
            return;
        }
        startRequested = true;
        final SelfTestPlan.Mode mode = selectedMode;
        int selection = ++selectionGeneration;
        restoreExecutor.execute(() -> {
            try {
                SavedReport selected = SavedReport.create(getApplicationContext(),
                        data.getData(), data.getFlags(), mode);
                savedReport = selected;
                if (destroyed || selection != selectionGeneration) {
                    selected.release();
                    return;
                }
                main.post(() -> {
                    if (destroyed || selection != selectionGeneration || !startRequested) {
                        selected.release();
                        return;
                    }
                    savedReport = selected;
                    if (runner == null && !restoring) {
                        startRequested = false;
                        summaryProblem = getString(R.string.secondary_runner_unavailable);
                        selected.release();
                        failure("START FAILED", new IOException("Runner unavailable; reopen screen"));
                        updateControls();
                        return;
                    }
                    if (runner != null && !restoring) startSuite();
                });
            } catch (IOException | RuntimeException error) {
                main.post(() -> {
                    if (destroyed || selection != selectionGeneration) return;
                    startRequested = false;
                    summaryProblem = getString(R.string.secondary_save_failed);
                    failure("TXT 保存失败；测试未开始 / SAVE FAILED", error);
                    updateControls();
                });
            }
        });
    }

    private void startSuite() {
        if (savedReport == null || !startRequested) return;
        startRequested = false;
        reportBeforeStart = runner.getReportFile();
        clearArtifacts();
        summaryProblem = null;
        viewingExternalReport = false;
        suiteRunning = true;
        report = new SelfTestPlan(selectedMode).description() + getString(R.string.secondary_starting_report);
        renderReport();
        updateControls();
        try {
            runner.start(savedReport, selectedMode);
        } catch (RuntimeException error) {
            savedReport.release();
            suiteRunning = runner.isRunning();
            summaryProblem = getString(R.string.secondary_start_failed);
            failure("START FAILED", error);
            updateControls();
        }
    }

    private void requestCancel() {
        if (startRequested) {
            ++selectionGeneration;
            startRequested = false;
            awaitingDocument = false;
            summaryProblem = getString(R.string.secondary_start_cancelled);
            if (savedReport != null) savedReport.release();
            report = getString(R.string.secondary_start_cancelled);
            renderReport();
        } else if (suiteRunning && runner != null) {
            try {
                runner.cancel();
                report += getString(runner.isFinalizing()
                        ? R.string.secondary_finalizing_report : R.string.secondary_cancelling_report);
                renderReport();
            } catch (RuntimeException error) {
                failure("CANCEL REQUEST FAILED; cleanup is not confirmed", error);
            }
        }
        updateControls();
    }

    private void onRunnerUpdate(String text, boolean running) {
        if (destroyed || runner == null) {
            return;
        }
        report = text;
        summaryProblem = null;
        viewingExternalReport = false;
        suiteRunning = running || runner.isRunning();
        if (suiteRunning) {
            clearArtifacts();
        } else {
            File candidate = runner.getReportFile();
            // start() keeps the preceding run's files until a new directory is initialized.
            if (candidate != null && !candidate.equals(reportBeforeStart)) {
                showArtifacts(candidate);
            } else {
                clearArtifacts();
                report += getString(R.string.secondary_no_new_artifacts);
            }
        }
        renderReport();
        updateControls();
    }

    private void clearArtifacts() {
        shareableReport = null;
        shareButton.setEnabled(false);
        outputsView.removeAllViews();
    }

    private void showArtifacts(File file) {
        clearArtifacts();
        shareableReport = file;
        List<File> outputs = runner.getOutputs();
        TextView label = new TextView(this);
        label.setText(getString(R.string.secondary_outputs_notice, outputs.size()));
        outputsView.addView(label);
        for (File output : outputs) {
            button(outputsView, getString(R.string.secondary_preview_output, output.getName()), () -> {
                if (suiteRunning || startRequested || destroyed) {
                    return;
                }
                try {
                    OutputSharing.preview(this, output);
                } catch (IOException | RuntimeException error) {
                    failure("PREVIEW FAILED: " + output, error);
                }
            });
        }
    }

    private void updateControls() {
        startButton.setEnabled(!suiteRunning && !startRequested && !restoring);
        shortButton.setEnabled(startButton.isEnabled());
        cancelButton.setEnabled(startRequested || (suiteRunning && runner != null && !runner.isFinalizing()));
        shareButton.setEnabled(!suiteRunning && !startRequested && !restoring
                && destinationLoaded && (shareableReport != null || SavedReport.lastUri(this) != null));
        openButton.setEnabled(!suiteRunning && !startRequested && !restoring
                && destinationLoaded && SavedReport.lastUri(this) != null);
        destinationView.setText(getString(R.string.secondary_destination_notice,
                destinationLoaded ? SecondaryOutcomeUi.savedLocation(this)
                        : getString(R.string.secondary_restoring_destination)));
        renderSummary();
        if (suiteRunning) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void renderSummary() {
        SuiteOutcome outcome = runner == null ? null : runner.outcome();
        boolean success = false;
        String headline;
        String detail;
        if (restoring || startRequested) {
            headline = getString(restoring ? R.string.secondary_restoring : R.string.secondary_preparing);
            detail = getString(R.string.secondary_wait_mode, selectedMode);
        } else if (summaryProblem != null || viewingExternalReport) {
            headline = getString(R.string.secondary_unconfirmed);
            detail = summaryProblem != null ? summaryProblem
                    : getString(R.string.secondary_external_warning);
        } else if (outcome != null) {
            headline = suiteRunning ? getString(runner.isFinalizing()
                    ? R.string.secondary_finalizing : R.string.secondary_running)
                    : SecondaryOutcomeUi.headline(this, outcome);
            detail = SecondaryOutcomeUi.details(this, outcome);
            success = !suiteRunning && outcome.isSuccess();
        } else {
            headline = getString(R.string.secondary_no_result);
            detail = getString(R.string.secondary_choose_mode);
        }
        int foreground = success ? Color.rgb(20, 92, 40) : Color.rgb(153, 27, 27);
        if (restoring || startRequested || suiteRunning) foreground = Color.rgb(112, 72, 0);
        summaryView.setText(getString(R.string.secondary_summary, headline, detail));
        summaryView.setTextColor(foreground);
        summaryView.setBackgroundColor(success ? Color.rgb(229, 245, 233) : Color.rgb(255, 239, 233));
        statusView.setText(headline);
        statusView.setTextColor(foreground);
    }

    private void renderReport() {
        ReportPreview preview = new ReportPreview();
        preview.append(report);
        preview.append(failures);
        String origin = report.startsWith("SAVED EXTERNAL TXT")
                ? getString(R.string.secondary_external_origin)
                : report.startsWith("RESTORED SNAPSHOT") ? getString(R.string.secondary_restored_origin) : "";
        reportView.setText(origin + preview.text()
                + getString(R.string.secondary_full_notice));
    }

    private void failure(String operation, Throwable error) {
        ReportPreview preview = new ReportPreview();
        preview.append(failures);
        preview.append("\n" + operation + ":\n" + details(error));
        failures = preview.text();
        renderReport();
        statusView.setText(R.string.secondary_operation_failed);
    }

    private void copyReport() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.secondary_copy_title)
                .setMessage(R.string.secondary_copy_warning)
                .setNegativeButton(R.string.secondary_cancel, null)
                .setPositiveButton(R.string.secondary_copy_preview, (dialog, which) -> copyPreview())
                .show();
    }

    private void copyPreview() {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                throw new IllegalStateException("Clipboard service is unavailable");
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.secondary_clip_label), reportView.getText()));
            statusView.setText(R.string.secondary_copied);
        } catch (RuntimeException error) {
            failure("COPY FAILED", error);
        }
    }

    private void openSavedReport() {
        if (suiteRunning || startRequested || restoring) return;
        viewingExternalReport = true;
        clearArtifacts();
        restoring = true;
        updateControls();
        restoreExecutor.execute(() -> {
            String text;
            try (InputStream input = getContentResolver().openInputStream(SavedReport.lastUri(this))) {
                if (input == null) throw new IOException("Selected TXT cannot be read");
                text = getString(R.string.secondary_saved_txt_header)
                        + SecondaryOutcomeUi.savedLocation(this) + "\n" + readText(input);
            } catch (IOException | RuntimeException error) {
                text = getString(R.string.secondary_open_saved_failed) + details(error);
            }
            String loaded = text;
            main.post(() -> {
                if (destroyed) return;
                restoring = false;
                report = loaded;
                renderReport();
                updateControls();
            });
        });
    }

    private void shareReport() {
        if (suiteRunning || startRequested || restoring) {
            return;
        }
        restoring = true;
        updateControls();
        File file = shareableReport;
        boolean external = viewingExternalReport || file == null;
        restoreExecutor.execute(() -> {
            try {
                android.net.Uri uri = external ? SavedReport.lastUri(this) : runner.boundSavedReportUri();
                Intent intent = uri != null ? OutputSharing.prepareSavedReport(this, uri, true)
                        : OutputSharing.prepareReport(this, file);
                main.post(() -> {
                    if (destroyed) return;
                    restoring = false;
                    try {
                        OutputSharing.launchReport(this, intent, getString(R.string.secondary_share_full));
                    } catch (RuntimeException error) {
                        failure("SHARE FAILED", error);
                    }
                    updateControls();
                });
            } catch (IOException | RuntimeException error) {
                main.post(() -> {
                    if (destroyed) return;
                    restoring = false;
                    failure("SHARE FAILED: " + file, error);
                    updateControls();
                });
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("diagnosticMode", selectedMode.name());
        state.putBoolean("awaitingDocument", awaitingDocument);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ++selectionGeneration;
        if (savedReport != null && (runner == null || !runner.isRunning())) savedReport.release();
        ++restoreGeneration;
        if (restoreFuture != null) {
            restoreFuture.cancel(true);
        }
        restoreExecutor.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (runner != null) {
            try {
                runner.cancel();
            } catch (RuntimeException error) {
                android.util.Log.e("SelfTestActivity", "Destroy cancellation failed", error);
            }
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    private static String details(Throwable error) {
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static String readReport(File file, String noReport, String snapshotHeader) throws IOException {
        if (file == null) {
            return noReport;
        }
        try (FileInputStream input = ReportJournal.open(file)) {
            return snapshotHeader + file + "\n\n" + readText(input);
        }
    }

    private static String readText(InputStream input) throws IOException {
        return ReportPreview.read(input);
    }

    private static final class WeakListener implements SelfTestRunner.Listener {
        private final WeakReference<SelfTestActivity> activity;

        WeakListener(WeakReference<SelfTestActivity> activity) {
            this.activity = activity;
        }

        @Override
        public void onUpdate(String report, boolean running) {
            SelfTestActivity target = activity.get();
            if (target != null && !target.destroyed) {
                target.onRunnerUpdate(report, running);
            }
        }
    }

    private static final class RestoreTask implements Runnable {
        private final WeakReference<SelfTestActivity> activity;
        private final Context context;
        private final Handler main;
        private final int generation;
        private final String noReport;
        private final String restoreError;
        private final String snapshotHeader;

        RestoreTask(SelfTestActivity activity, Context context, Handler main, int generation) {
            this.activity = new WeakReference<>(activity);
            this.context = context;
            this.main = main;
            this.generation = generation;
            this.noReport = activity.getString(R.string.secondary_no_report);
            this.restoreError = activity.getString(R.string.secondary_restore_retry);
            this.snapshotHeader = activity.getString(R.string.secondary_snapshot_header);
        }

        @Override
        public void run() {
            SelfTestRunner restored = null;
            String text = restoreError;
            String error = null;
            try {
                SavedReport.preferences(context).getString("uri", "");
                restored = new SelfTestRunner(context, new WeakListener(activity));
                File saved = SavedReport.snapshot(context);
                File boundReport = restored.getReportFile();
                text = readReport(boundReport != null ? boundReport
                        : ReportJournal.exists(saved) ? saved : null, noReport, snapshotHeader);
            } catch (IOException | RuntimeException failure) {
                error = details(failure);
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            SelfTestRunner result = restored;
            String report = text;
            String diagnostic = error;
            main.post(() -> {
                SelfTestActivity target = activity.get();
                if (target != null && !target.destroyed) {
                    target.restored(generation, result, report, diagnostic);
                }
            });
        }
    }
}
