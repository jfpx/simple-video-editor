package com.simple.videoeditor;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import com.simple.videoeditor.oracle.OracleAndroidDecoder;
import com.simple.videoeditor.oracle.MusicOracleContract;
import com.simple.videoeditor.oracle.IntroOracleContract;
import com.simple.videoeditor.oracle.TextOracleContract;
import com.simple.videoeditor.oracle.TitleOracleContract;
import com.simple.videoeditor.oracle.WatermarkOracleContract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Frozen independent-oracle checks of the shared export engine, not UI automation. */
@androidx.media3.common.util.UnstableApi
public final class SelfTestRunner {
    private static final String REPORT_BINDING_SCHEMA = "selftest-report-binding-v1";
    private static final int MAX_SUITE_JSON_BYTES = CompactSuiteIndex.MAX_BYTES;
    public interface Listener {
        void onUpdate(String report, boolean running);
    }

    private static final long SUITE_MS = 15 * 60_000L;
    private static final int BASE_CONTROL_COUNT = 36;
    private static final int CONTROL_COUNT = BASE_CONTROL_COUNT + 3 + IntroOracleContract.CONTROLS.size()
            + TextOracleContract.CONTROLS.size() + TitleOracleContract.CONTROLS.size()
            + WatermarkOracleContract.CONTROLS.size();
    private static final String[] CASE_NAMES = SelfTestPlan.FULL_CASES.toArray(new String[0]);
    private static final String LIMITATIONS =
            "\nCOVERAGE LIMITATIONS (not full feature coverage):\n"
            + "INTRO: independent one-second imported standard segment then four-second original;"
            + " checks mapped timecodes/pixels and original tone order across the join."
            + " Letterboxing and intro/edit/music combinations remain UNVERIFIED.\n"
            + "TITLE: fixed normal 48sp white OI/black centered title, silent 1s at30fps then"
            + " original 4s at24fps; all 30 title frames and mapped main timecodes/motion/audio."
            + " Known scaledDensity 0.75..4 normalizes checker coordinates only, never editor fonts."
            + " Other titles/styles/densities and combinations remain UNVERIFIED.\n"
            + "WATERMARK: fixed original 64x32 transparent PNG at width .2, free-space x/y .75/.75"
            + " on final 320x240 canvas: (192,156)-(256,188); alpha 0/128/255 RGB blending"
            + " against independent analytic backgrounds at nine probes, all barcode bits/time/audio retained."
            + " Other scaling/positions and title/intro/text combinations remain UNVERIFIED.\n"
            + "TEXT: fixed OI ring/stem topology, order, white color, 48px size, centered placement"
            + " and black backing; nine probes, unchanged surrounding pixels/audio, visible code bits 0/1/5/6."
            + " OEM fonts outside fixed bounds FAIL; no tolerance fitting. Not general OCR;"
            + " confusable glyphs, other text/styles and exact glyph outlines remain UNVERIFIED.\n"
            + "MUSIC: independent 1320 Hz one-second replacement loop over four seconds;"
            + " checks video unchanged and timed audio pitch/gain/continuity; other music/intro combinations unverified.\n"
            + "NOT EXERCISED: UI interactions, pickers, previews, copy/share, lifecycle UI and accessibility.\n"
            + "UNVERIFIED: arbitrary source rotation metadata, HDR, variable frame rate, stereo,"
            + " external media, long media, arbitrary angles/crops/resolutions and clipping at high gain.\n"
            + "UNVERIFIED: perceptual audio quality/phase and sample-exact A/V sync; sampled video"
            + " timing, PCM duration, pitch and RMS are checked independently.\n"
            + "STANDARD: frozen independent v1.0.0, 4 s 320x240/24fps, mono48k ordered tones.\n"
            + "LIMIT: exact geometry; ROI<=18, MAE<=12, p95<=45, markers/barcode<=36;"
            + " source alignment +/-1 frame; RMS<=12%+0.002, pitch<=12Hz;"
            + " audio PTS continuity<=50us. No tolerance calibration on this device.\n"
            + "CONTROLS: base 17 positives/19 negatives + music 1/2 + imported intro 1/4 + text 1/3 + title 1/7 + watermark 1/3;"
            + " 60 checker controls, not production exports.\n"
            + "LIMIT: 15 minute suite, 180 s/export, 30 s decode deadline."
            + " Polling is bounded; a hung platform native codec/retriever call cannot be forcibly"
            + " interrupted. Cancellation remains running until worker cleanup returns.\n";

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Run active;
    private volatile File reportFile;
    private volatile List<File> outputs = Collections.emptyList();
    private volatile SuiteOutcome outcome = SuiteOutcome.missing("未找到持久化 suite 结果。");
    private static volatile SelfTestRunner runningSuite;
    private static final Object suiteOwnershipLock = new Object();

    static boolean hasRunningSuite() { return runningSuite != null; }

    public SelfTestRunner(Context context, Listener listener) {
        if (context == null || listener == null) {
            throw new IllegalArgumentException("context and listener are required");
        }
        this.context = context.getApplicationContext();
        this.listener = listener;
        RestoredState restored = restoreLatest(this.context);
        reportFile = restored.reportFile;
        outputs = restored.outputs;
        outcome = restored.outcome;
    }

    public synchronized void start() {
        start(null);
    }

    synchronized void start(SavedReport savedReport) {
        start(savedReport, SelfTestPlan.Mode.FULL);
    }

    synchronized void start(SavedReport savedReport, SelfTestPlan.Mode mode) {
        SelfTestPlan plan = new SelfTestPlan(mode);
        if (active != null) {
            return;
        }
        synchronized (suiteOwnershipLock) {
            if (runningSuite != null) throw new IllegalStateException("Previous suite still owns cleanup");
            runningSuite = this;
        }
        Run run = new Run(plan);
        run.savedReport = savedReport;
        active = run;
        outcome = SuiteOutcome.running(plan);
        publish(run, plan.description() + "Starting offline selftest. UI interaction is NOT exercised.\n" + LIMITATIONS);
        main.postDelayed(run.watchdog, SUITE_MS);
        run.worker.execute(() -> execute(run));
    }

    public void cancel() {
        Run run = active;
        if (run != null) {
            if (!requestCancel(run)) {
                return;
            }
            SuiteOutcome cached = run.persistedOutcome != null ? run.persistedOutcome : outcome;
            outcome = cached != null && "STORAGE_ERROR".equals(cached.state())
                    ? cached : cached != null ? cached.cancelled() : SuiteOutcome.cancelled(run.plan);
            if (run.workerThread != null) run.workerThread.interrupt();
            main.post(() -> {
                if (active == run) {
                    listener.onUpdate(run.snapshot
                            + "\nCANCELLING: waiting for codec/export cleanup; files remain owned.\n", true);
                }
                if (run.engine != null) {
                    run.engine.cancel();
                }
            });
        }
    }

    public boolean isRunning() { return active != null; }
    public boolean isFinalizing() {
        Run run = active;
        if (run == null) return false;
        synchronized (run.terminalStateLock) {
            return run.finalizing || run.terminalCommitted;
        }
    }
    public File getReportFile() { return reportFile; }
    public List<File> getOutputs() { return new ArrayList<>(outputs); }
    public SuiteOutcome outcome() { return outcome; }

    /** Called only by the report I/O worker when preparing a share. */
    Uri boundSavedReportUri() throws IOException {
        File report = reportFile;
        if (report == null) throw new IOException("No bound run report to share");
        try {
            JSONObject evidence = new JSONObject(readText(
                    new File(report.getParentFile(), "suite.json"), MAX_SUITE_JSON_BYTES));
            String[] selectedUri = new String[1];
            String error = validateRestoredBinding(context, report.getParentFile(), evidence, selectedUri);
            if (error != null) throw new IOException(error);
            return selectedUri[0] == null ? null : Uri.parse(selectedUri[0]);
        } catch (JSONException error) {
            throw new IOException("Cannot read report sharing binding", error);
        }
    }

    /** Scans app-owned run reports and returns only a binding-valid report file. */
    public static synchronized File latestReportFile(Context context) {
        return restoreLatest(context).reportFile;
    }

    private static synchronized RestoredState restoreLatest(Context context) {
        File root = new File(context.getFilesDir(), "selftest");
        File[] runs = runDirectories(root);
        if (runs.length == 0) {
            return new RestoredState(null, Collections.emptyList(),
                    SuiteOutcome.missing("未找到任何自测运行目录。"));
        }
        File latest = runs[0];
        File report = readableReportFile(new File(latest, "report.txt"));
        List<File> restoredOutputs = reopenOutputs(latest);
        File suiteFile = new File(latest, "suite.json");
        if (!ReportJournal.exists(suiteFile)) {
            return new RestoredState(report, restoredOutputs, hasOlderSuite(runs)
                    ? SuiteOutcome.stale("最新运行缺少 suite.json；未回退到较旧结果。")
                    : SuiteOutcome.missing("最新运行缺少 suite.json；没有权威套件结论。"));
        }
        if (report == null) {
            return new RestoredState(null, restoredOutputs,
                    SuiteOutcome.missing("最新运行缺少可读 report.txt；不恢复该 suite 结论。"));
        }
        try {
            JSONObject evidence = new JSONObject(readText(suiteFile, MAX_SUITE_JSON_BYTES));
            String bindingError = validateRestoredBinding(context, latest, evidence);
            if (bindingError != null) {
                return new RestoredState(null, Collections.emptyList(), SuiteOutcome.stale(bindingError));
            }
            SuiteOutcome restored = SuiteOutcome.fromEvidence(evidence);
            return new RestoredState(report, restoredOutputs, restored);
        } catch (OversizedSuiteException error) {
            return new RestoredState(report, restoredOutputs,
                    SuiteOutcome.malformed("最新 suite.json 超过 256 KiB 上限；请重新运行以生成紧凑索引。"));
        } catch (IOException error) {
            android.util.Log.w("SelfTestRunner", "Cannot reopen latest suite metadata", error);
            return new RestoredState(report, restoredOutputs,
                    SuiteOutcome.malformed("最新 suite.json 无法读取。"));
        } catch (JSONException error) {
            android.util.Log.w("SelfTestRunner", "Cannot parse latest suite metadata", error);
            return new RestoredState(report, restoredOutputs,
                    SuiteOutcome.malformed("最新 suite.json 无法解析。"));
        }
    }

    private static boolean hasOlderSuite(File[] runs) {
        for (int i = 1; i < runs.length; i++) {
            if (ReportJournal.exists(new File(runs[i], "suite.json"))) return true;
        }
        return false;
    }

    private static File readableReportFile(File report) {
        if (!ReportJournal.exists(report)) return null;
        try (FileInputStream ignored = ReportJournal.open(report)) {
            return report;
        } catch (IOException error) {
            android.util.Log.w("SelfTestRunner", "Cannot reopen selftest report", error);
            return null;
        }
    }

    private static String readText(File file, int limitBytes) throws IOException {
        try (FileInputStream input = ReportJournal.open(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limitBytes, 8192));
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limitBytes) throw new OversizedSuiteException();
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static final class OversizedSuiteException extends IOException {
        OversizedSuiteException() { super("suite.json 超过 256 KiB 上限。"); }
    }

    private static String validateRestoredBinding(Context context, File directory, JSONObject evidence) {
        return validateRestoredBinding(context, directory, evidence, null);
    }

    private static String validateRestoredBinding(Context context, File directory, JSONObject evidence,
            String[] selectedUri) {
        try {
            Object bindingValue = evidence.opt("report_binding");
            if (!(bindingValue instanceof JSONObject)) {
                return "suite.json 缺少当前版本报告绑定；旧结果不再自动恢复。";
            }
            JSONObject binding = (JSONObject) bindingValue;
            boolean digestBinding = CompactSuiteIndex.BINDING_SCHEMA.equals(binding.optString("schema", ""));
            if (!digestBinding && !REPORT_BINDING_SCHEMA.equals(binding.optString("schema", ""))) {
                return "suite.json 报告绑定 schema 非法；不恢复该结果。";
            }
            if (!directory.getName().equals(strictString(binding, "run_id"))) {
                return "suite.json 运行标识与目录不一致；疑似复制或陈旧结果。";
            }
            String evidenceMode = strictString(evidence, "mode");
            String bindingMode = strictString(binding, "mode");
            if (!evidenceMode.equals(bindingMode)) {
                return "suite.json 报告绑定模式与结果模式不一致。";
            }
            if (strictBoolean(binding, "selected_report_required")) {
                SharedPreferences prefs = SavedReport.preferences(context);
                String currentUri = prefs.getString("uri", "");
                String currentMode = prefs.getString("mode", "");
                String currentDestination = prefs.getString("destination", "");
                String boundUri = strictString(binding, digestBinding
                        ? "selected_report_uri_sha256" : "selected_report_uri");
                String boundMode = strictString(binding, "selected_report_mode");
                String boundDestination = digestBinding
                        ? strictString(binding, "selected_report_destination_sha256")
                        : binding.optString("selected_report_destination", "");
                if (boundUri.isEmpty() || boundMode.isEmpty() || currentUri.isEmpty()) {
                    return "suite.json 报告绑定不完整；不恢复该结果。";
                }
                if (!evidenceMode.equals(boundMode) || !bindingMode.equals(boundMode)) {
                    return "suite.json 所选报告模式与结果模式不一致；不恢复该结果。";
                }
                boolean selectionMatches = digestBinding
                        ? boundUri.equals(CompactSuiteIndex.digest(currentUri))
                                && boundDestination.equals(CompactSuiteIndex.digest(currentDestination))
                        : boundUri.equals(currentUri)
                                && (boundDestination.isEmpty() || boundDestination.equals(currentDestination));
                if (!selectionMatches || !boundMode.equals(currentMode)) {
                    return "当前所选 TXT 或模式与该运行不一致；不恢复旧结果。";
                }
                if (selectedUri != null) selectedUri[0] = currentUri;
            }
            return null;
        } catch (IllegalArgumentException error) {
            return "suite.json 报告绑定损坏：" + error.getMessage();
        } catch (IOException error) {
            return "suite.json 报告绑定摘要无法验证。";
        }
    }

    private static boolean strictBoolean(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof Boolean) return (Boolean) value;
        throw new IllegalArgumentException(key + " 必须为布尔值。");
    }

    private static String strictString(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof String) return (String) value;
        throw new IllegalArgumentException(key + " 必须为字符串。");
    }

    private void captureReportBinding(Run run) {
        if (run.bindingCaptured) return;
        run.bindingCaptured = true;
        run.selectedReportRequired = run.savedReport != null;
        if (run.savedReport != null) {
            SharedPreferences prefs = SavedReport.preferences(context);
            run.selectedReportUri = prefs.getString("uri", "");
            run.selectedReportMode = prefs.getString("mode", "");
            run.selectedReportDestination = prefs.getString("destination", "");
        }
    }

    private JSONObject reportBinding(Run run) throws JSONException {
        captureReportBinding(run);
        JSONObject binding = new JSONObject().put("schema", REPORT_BINDING_SCHEMA)
                .put("run_id", run.directory.getName())
                .put("mode", run.plan.mode.name())
                .put("selected_report_required", run.selectedReportRequired)
                .put("selected_report_uri", run.selectedReportUri)
                .put("selected_report_mode", run.selectedReportMode);
        if (!run.selectedReportDestination.isEmpty()) {
            binding.put("selected_report_destination", run.selectedReportDestination);
        }
        return binding;
    }

    SuiteOutcome persistTerminalOutcome(Run run, String status) {
        try {
            if (run.report != null) {
                synchronized (run) {
                    flushLog(run, true);
                }
            }
        } catch (IOException | RuntimeException error) {
            run.fatal = combine(run.fatal, error);
            appendLog(run, "REPORT PERSISTENCE FAILED: " + stack(error));
        }
        try {
            SuiteOutcome terminal = persistEvidence(run, status, false);
            outcome = terminal;
            return terminal;
        } catch (IOException | RuntimeException error) {
            run.fatal = combine(run.fatal, error);
            appendLog(run, "SUITE METADATA FAILED: " + stack(error));
            outcome = storageErrorOutcome(run,
                    "最终权威结果写入失败；请重新运行所选模式。");
            return outcome;
        }
    }

    private void execute(Run run) {
        run.workerThread = Thread.currentThread();
        File root = new File(context.getFilesDir(), "selftest");
        RandomAccessFile lockFile = null;
        FileLock lock = null;
        boolean cleanupConfirmed = false;
        RuntimeException unexpectedFailure = null;
        try {
            check(run);
            if (!root.isDirectory() && !root.mkdirs()) {
                throw new IOException("Cannot create app-owned selftest directory");
            }
            lockFile = new RandomAccessFile(new File(root, "suite.lock"), "rw");
            try {
                lock = lockFile.getChannel().tryLock();
            } catch (OverlappingFileLockException e) {
                throw new IOException("Another selftest runner is active; no shared files changed", e);
            }
            if (lock == null) {
                throw new IOException("Another process owns the selftest; no shared files changed");
            }
            run.directory = new File(root, String.format(Locale.US, "run-%013d-%s",
                    System.currentTimeMillis(), UUID.randomUUID().toString().substring(0, 8)));
            if (!run.directory.mkdir()) {
                throw new IOException("Cannot create unique run directory");
            }
            run.report = new File(run.directory, "report.txt");
            reportFile = run.report;
            outputs = Collections.emptyList();
            captureReportBinding(run);
            run.metadata.put("plan", run.plan.metadata());
            log(run, run.plan.description() + "IN PROGRESS / INCOMPLETE — initializing " + run.directory.getName()
                    + "\nRevision: " + BuildConfig.SOURCE_REVISION + "\nDevice: "
                    + Build.MANUFACTURER + " " + Build.MODEL + " API " + Build.VERSION.SDK_INT
                    + "\nCodec/config: bundled AVC 320x240 24fps / AAC mono48k; codecs pending.\n");
            run.metadata.put("app", appVersion());
            run.metadata.put("source_revision", BuildConfig.SOURCE_REVISION);
            run.metadata.put("installed_apk_sha256",
                    sha256(new File(context.getApplicationInfo().sourceDir)));
            run.metadata.put("sdk", Build.VERSION.SDK_INT);
            run.metadata.put("manufacturer", Build.MANUFACTURER);
            run.metadata.put("model", Build.MODEL);
            run.metadata.put("os", Build.VERSION.RELEASE);
            run.metadata.put("fingerprint", Build.FINGERPRINT);
            run.metadata.put("abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));
            run.metadata.put("media3", androidx.media3.common.MediaLibraryInfo.VERSION);
            run.metadata.put("started_utc_ms", System.currentTimeMillis());
            for (String name : run.plan.cases) {
                JSONObject pending = new JSONObject().put("id", name).put("status", "UNVERIFIED")
                        .put("expected_status", "PASS").put("actual_status", "NOT RUN");
                if (IntroOracleContract.CASE_ID.equals(name)) pending.put("reference", introReference());
                if (TextOracleContract.CASE_ID.equals(name)) pending.put("reference", textReference());
                if (TitleOracleContract.CASE_ID.equals(name)) pending.put("reference", titleReference());
                if (WatermarkOracleContract.CASE_ID.equals(name)) pending.put("reference", watermarkReference());
                run.caseResults.put(pending);
            }
            persistEvidence(run, "UNVERIFIED");
            synchronized (run) {
                run.log.append("FIXED ORACLE REPORT v2 / oracle 1.0.0 ").append(run.directory.getName())
                        .append("\nTARGET: Android SDK=").append(Build.VERSION.SDK_INT)
                        .append(", manufacturer=").append(Build.MANUFACTURER)
                        .append(", model=").append(Build.MODEL)
                        .append(", OS=").append(Build.VERSION.RELEASE)
                        .append(", fingerprint=").append(Build.FINGERPRINT)
                        .append("\nAPP: ").append(appVersion())
                        .append("\nREVISION: ").append(BuildConfig.SOURCE_REVISION)
                        .append("\nAPK SHA256: ").append(run.metadata.getString("installed_apk_sha256"))
                        .append(", Media3=").append(androidx.media3.common.MediaLibraryInfo.VERSION)
                        .append("\nIN PROGRESS / INCOMPLETE until individually verified."
                                + " An IN PROGRESS report after relaunch means the run was interrupted.\n")
                        .append("Outputs use ONLY Media3ExportEngine; frozen expectations are independent analytic data.\n")
                        .append(LIMITATIONS).append("\nPLANNED CHECKS:\n");
                for (String name : run.plan.cases) {
                    run.log.append("PENDING ").append(name).append('\n');
                }
            }
            log(run, "\nVALIDATING shipped frozen fixture and independent reference integrity\n");
            stage(run, 60_000);
            OracleVerifier verifier = new OracleVerifier(context);
            verifier.setObserver(decoderObserver(run));
            JSONObject contract = verifier.loadContract();
            File fixture = verifier.prepareFixture();
            JSONArray cases = contract.getJSONArray("cases");
            if (cases.length() != CASE_NAMES.length - 5) throw new IOException("Unexpected oracle case count");
            JSONObject musicReference = new JSONObject().put("path", MusicOracleContract.REFERENCE.path)
                    .put("sha256", MusicOracleContract.REFERENCE.sha256)
                    .put("version", "music-loop-1").put("frequency_hz", 1320)
                    .put("rms", 0.16 / Math.sqrt(2)).put("music_sha256", MusicOracleContract.MUSIC.sha256);
            cases.put(new JSONObject().put("id", MusicOracleContract.CASE_ID)
                    .put("reference", musicReference));
            JSONObject introReference = introReference();
            cases.put(new JSONObject().put("id", IntroOracleContract.CASE_ID)
                    .put("reference", introReference));
            run.metadata.put("intro_supplement", introReference);
            cases.put(new JSONObject().put("id", TextOracleContract.CASE_ID)
                    .put("reference", textReference())
                    .put("android_edit_config", new JSONObject(TextOracleContract.CASES.get(0).androidEditConfig)));
            run.metadata.put("text_supplement", textReference());
            cases.put(new JSONObject().put("id", TitleOracleContract.CASE_ID)
                    .put("reference", titleReference()));
            run.metadata.put("title_supplement", titleReference());
            cases.put(new JSONObject().put("id", WatermarkOracleContract.CASE_ID)
                    .put("reference", watermarkReference())
                    .put("android_edit_config", new JSONObject(WatermarkOracleContract.CASES.get(0).androidEditConfig)));
            run.metadata.put("watermark_supplement", watermarkReference());
            log(run, "PASS fixture/reference integrity\nFIXTURE " + contract.getJSONObject("fixture")
                    + "\nPROVENANCE " + contract.getJSONObject("pins") + "\n");
            run.fixturePassed = true;
            run.metadata.put("fixture", contract.getJSONObject("fixture"));
            run.metadata.put("pins", contract.getJSONObject("pins"));
            runControls(run, verifier);
            OracleVerifier musicVerifier = new OracleVerifier(context, MusicOracleContract.create());
            musicVerifier.setObserver(decoderObserver(run));
            File music = musicVerifier.prepareMusicAsset(MusicOracleContract.MUSIC);
            runMusicControls(run, musicVerifier, fixture);
            OracleVerifier introVerifier = null;
            OracleVerifier textVerifier = null;
            OracleVerifier watermarkVerifier = null;
            File intro = null;
            File watermark = null;
            if (!run.plan.isShort()) {
                introVerifier = new OracleVerifier(context, IntroOracleContract.create());
                introVerifier.setObserver(decoderObserver(run));
                intro = introVerifier.prepareIntroAsset(IntroOracleContract.INTRO);
                runIntroControls(run, introVerifier);
                textVerifier = new OracleVerifier(context, TextOracleContract.create());
                textVerifier.setObserver(decoderObserver(run));
                textVerifier.prepareFixture();
                runTextControls(run, textVerifier);
                OracleVerifier titleControls = new OracleVerifier(context, TitleOracleContract.create());
                titleControls.setObserver(decoderObserver(run));
                titleControls.prepareFixture();
                runTitleControls(run, titleControls);
                watermarkVerifier = new OracleVerifier(context, WatermarkOracleContract.create());
                watermarkVerifier.setObserver(decoderObserver(run));
                watermarkVerifier.prepareFixture();
                watermark = watermarkVerifier.prepareWatermarkAsset(WatermarkOracleContract.PNG);
                runWatermarkControls(run, watermarkVerifier);
            }
            for (int i = 0; i < run.plan.cases.size(); i++) {
                run.stepDeadlineNs = run.deadlineNs;
                check(run);
                String name = run.plan.cases.get(i);
                log(run, "\nRUN " + name + "\n");
                File output = new File(run.directory, String.format(Locale.US, "%02d-%s.mp4", i, name));
                try {
                    JSONObject vector = cases.getJSONObject(SelfTestPlan.FULL_CASES.indexOf(name));
                    if (!name.equals(vector.getString("id"))) throw new IOException("Oracle case order mismatch");
                    boolean isMusic = MusicOracleContract.CASE_ID.equals(name);
                    boolean isIntro = IntroOracleContract.CASE_ID.equals(name);
                    boolean isText = TextOracleContract.CASE_ID.equals(name);
                    boolean isTitle = TitleOracleContract.CASE_ID.equals(name);
                    boolean isWatermark = WatermarkOracleContract.CASE_ID.equals(name);
                    OracleVerifier titleVerifier = null;
                    if (isTitle) {
                        double density = context.getResources().getDisplayMetrics().scaledDensity;
                        vector.getJSONObject("reference").put("actual_scaled_density", density);
                        try {
                            titleVerifier = new OracleVerifier(context, TitleOracleContract.create(density));
                        } catch (IllegalArgumentException unsupported) {
                            throw new IOException("Title oracle density outside finite coverage", unsupported);
                        }
                        titleVerifier.setObserver(decoderObserver(run));
                    }
                    EditConfig config = isWatermark ? watermarkConfig(fixture, watermark) : isTitle ? titleConfig(fixture) : isIntro
                            ? new EditConfig.Builder(Uri.fromFile(fixture),
                                    IntroOracleContract.ORIGINAL_DURATION_MS).sourceSize(320, 240)
                                    .intro(Uri.fromFile(intro)).build()
                            : isMusic
                            ? new EditConfig.Builder(Uri.fromFile(fixture), 4000).sourceSize(320, 240)
                                    .replacementMusic(Uri.fromFile(music)).build()
                            : configFor(vector.getJSONObject("android_edit_config"), fixture);
                    log(run, "CONFIG " + config + "\n");
                    run.caseResults.getJSONObject(i).put("export_config", config.toString())
                            .put("reference", vector.getJSONObject("reference"));
                    persistEvidence(run, "UNVERIFIED");
                    run.codecInfo = "Encoder details not received";
                    stage(run, 180_000);
                    log(run, "STAGE EXPORT " + name + " BEFORE Media3/native codec setup\n");
                    File completed = export(run, config, output, name);
                    if (!completed.getCanonicalFile().equals(output.getCanonicalFile())) {
                        throw new IOException("Engine returned an unexpected output path");
                    }
                    run.completedOutputs.add(completed);
                    persistOutputs(run);
                    outputs = Collections.unmodifiableList(new ArrayList<>(run.completedOutputs));
                    stage(run, 60_000);
                    log(run, "STAGE VERIFY " + name + " BEFORE decoder inspection\n" + run.codecInfo + "\n");
                    JSONObject result = (isWatermark ? watermarkVerifier : isTitle ? titleVerifier : isText ? textVerifier : isIntro ? introVerifier : isMusic ? musicVerifier : verifier)
                            .verify(name, completed);
                    check(run);
                    result.put("app", appVersion());
                    result.put("run_metadata", run.metadata);
                    result.put("export_codecs", run.codecInfo);
                    result.put("export_config", config.toString());
                    result.put("reference", vector.getJSONObject("reference"));
                    recordExportResult(run, i, result);
                    persistEvidence(run, "UNVERIFIED");
                    log(run, formatCaseResult(result));
                } catch (IOException | TimeoutException | CancellationException error) {
                    recordExportFailure(run, i, output, error);
                    run.stepDeadlineNs = run.deadlineNs;
                    check(run);
                }
                if ("identity".equals(name) && output.isFile() && !run.cancelled && !run.timedOut) {
                    // The verifier's result is already durable. This separate experiment cannot replace it.
                    stage(run, 40_000);
                    log(run, "STAGE IDENTITY COLOR DIAGNOSTIC ONLY — verifier result unchanged\n");
                    Map<String, Object> color = com.simple.videoeditor.oracle.OracleColorDiagnostic.collect(
                            output, fixture, context.getAssets(), decoderObserver(run));
                    log(run, "IDENTITY COLOR DIAGNOSTIC FULL " + new JSONObject(color) + "\n");
                }
            }
        } catch (IOException | JSONException | TimeoutException | CancellationException error) {
            run.fatal = error;
            appendLog(run, "\nSTOPPED: " + stack(error));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            run.cancelled = true;
            run.fatal = error;
            appendLog(run, "\nSTOPPED: " + stack(error));
        } catch (RuntimeException error) {
            run.fatal = error;
            appendLog(run, "\nUNEXPECTED FAILURE: " + stack(error));
            unexpectedFailure = error;
        } finally {
            run.finished = true;
            main.removeCallbacks(run.watchdog);
            try {
                log(run, "\nSTAGE CLEANUP — before native engine release\n");
            } catch (IOException | RuntimeException error) {
                run.fatal = combine(run.fatal, error);
                appendLog(run, "\nCHECKPOINT FAILED: " + stack(error));
            }
            try {
                stopEngine(run);
                cleanupConfirmed = true;
            } catch (IOException | RuntimeException error) {
                run.fatal = combine(run.fatal, error);
                appendLog(run, "\nCLEANUP FAILURE: " + stack(error));
                if (unexpectedFailure == null && error instanceof RuntimeException) {
                    unexpectedFailure = (RuntimeException) error;
                }
            }
            String computedOverall;
            synchronized (run) {
                int passed = 0;
                int failed = 0;
                int errors = 0;
                run.log.append("\nFINAL CHECK RESULTS (supersede PENDING entries):\n")
                        .append(run.fixturePassed ? "PASS fixture\n" : "FAIL/UNVERIFIED fixture\n");
                for (int i = 0; i < run.plan.cases.size(); i++) {
                    String status = run.status[i] == null ? "NOT RUN / UNVERIFIED" : run.status[i];
                    passed += "PASS".equals(status) ? 1 : 0;
                    failed += "FAIL".equals(status) ? 1 : 0;
                    errors += "ERROR".equals(status) ? 1 : 0;
                    run.log.append(status).append(' ').append(run.plan.cases.get(i)).append('\n');
                }
                int controlsPassed = 0;
                int controlsFailed = 0;
                int controlsErrored = 0;
                run.log.append("\nCHECKER CONTROLS (not production exports):\n");
                for (int i = 0; i < run.controlResults.length(); i++) {
                    JSONObject result = run.controlResults.optJSONObject(i);
                    String status = result.optString("status", "UNVERIFIED");
                    controlsPassed += "PASS".equals(status) ? 1 : 0;
                    controlsFailed += "FAIL".equals(status) ? 1 : 0;
                    controlsErrored += "ERROR".equals(status) ? 1 : 0;
                    run.log.append(status).append(' ').append(result.optString("id"))
                            .append(" expected=").append(result.optString("expected_status"))
                            .append(" actual=").append(result.optString("actual_status")).append('\n');
                }
                computedOverall = failed > 0 || controlsFailed > 0 ? "FAIL"
                        : errors > 0 || controlsErrored > 0 || run.fatal != null ? "ERROR"
                        : passed == run.plan.cases.size() && controlsPassed == run.plan.controls.size()
                        && run.fixturePassed && !run.cancelled && !run.timedOut ? run.plan.successStatus() : "UNVERIFIED";
                run.log.append(run.plan.isShort() ? "SHORT DIAGNOSTIC / LIMITED COVERAGE: "
                        : "ORACLE v1 + music-loop-1 + intro-concat-1 + text-topology-1 + title-intro-1 + png-watermark-1: ").append(computedOverall)
                        .append("; checker controls ").append(controlsPassed).append('/').append(run.plan.controls.size());
                if (run.plan.isShort()) run.log.append("\nFULL FROZEN SUITE: NOT RUN / UNVERIFIED\n")
                        .append(SelfTestPlan.SHORT_NOTICE);
                run.log.append("\nFULL FEATURE COVERAGE: INCOMPLETE");
                if (run.timedOut) {
                    run.log.append(" / TIMEOUT");
                } else if (run.cancelled) {
                    run.log.append(" / CANCELLED");
                }
                if (failed > 0 || errors > 0 || (!run.cancelled && !run.timedOut
                        && (run.fatal != null || !run.fixturePassed))) {
                    run.log.append(" / FAILURES");
                }
                run.log.append("; ").append(passed).append('/').append(run.plan.cases.size())
                        .append(" exports verified, ").append(failed).append(" failed, ")
                        .append(errors).append(" errors, ")
                        .append(run.plan.cases.size() - passed - failed - errors).append(" not run.\n")
                        .append("Not full feature coverage: mandatory SKIPs and unexercised UI remain.\n")
                        .append("Only successfully completed engine outputs are listed for preview;"
                                + " inspection failures remain viewable and are labeled above.\n");
            }
            if (lock != null && cleanupConfirmed && run.directory != null) {
                try {
                    prune(root, run.directory);
                } catch (IOException | SecurityException error) {
                    appendLog(run, "RETENTION WARNING: " + stack(error));
                }
            }
            String overall = beginTerminalCommit(run, computedOverall);
            appendLog(run, "\nRESULT: " + run.plan.mode + " " + overall
                    + " on the covered suite inventory; final durable save pending.\n");
            publishFinalizing(run);
            try {
                persistTerminalOutcome(run, overall);
            } finally {
                finishTerminalCommit(run);
            }
            try {
                if (lock != null) {
                    lock.release();
                }
            } catch (IOException error) {
                appendLog(run, "LOCK RELEASE FAILED: " + stack(error));
            } finally {
                if (lockFile != null) {
                    try {
                        lockFile.close();
                    } catch (IOException error) {
                        appendLog(run, "LOCK CLOSE FAILED: " + stack(error));
                    }
                }
            }
            String finalReport;
            synchronized (run) {
                run.preview.append(run.log.toString());
                run.log = new StringBuilder();
                finalReport = run.preview.text();
            }
            synchronized (this) {
                if (active == run) {
                    active = null;
                }
            }
            if (run.savedReport != null) run.savedReport.release();
            synchronized (suiteOwnershipLock) {
                if (runningSuite == this) runningSuite = null;
            }
            main.post(() -> {
                if (active == null) {
                    listener.onUpdate(finalReport, false);
                }
            });
            run.worker.shutdown();
            if (unexpectedFailure != null) {
                throw unexpectedFailure;
            }
        }
    }

    private void runControls(Run run, OracleVerifier verifier)
            throws IOException, JSONException, TimeoutException {
        JSONObject manifest = verifier.loadControls();
        JSONArray controls = manifest.getJSONArray("controls");
        if (controls.length() != BASE_CONTROL_COUNT) throw new IOException("Control count mismatch");
        run.metadata.put("control_manifest", manifest);
        for (int i = 0; i < controls.length(); i++) {
            JSONObject control = controls.getJSONObject(i);
            if (!run.plan.controls.contains(control.getString("id"))) continue;
            run.controlResults.put(new JSONObject().put("id", control.getString("id"))
                    .put("expected_status", control.getString("expected_status"))
                    .put("actual_status", "NOT RUN").put("status", "UNVERIFIED"));
        }
        String[] musicNames = {"music_reference", "music_unchanged", "music_no_loop"};
        for (int i = 0; i < musicNames.length; i++) {
            run.controlResults.put(new JSONObject().put("id", musicNames[i])
                    .put("expected_status", i == 0 ? "PASS" : "FAIL")
                    .put("actual_status", "NOT RUN").put("status", "UNVERIFIED"));
        }
        for (IntroOracleContract.Control control : IntroOracleContract.CONTROLS) {
            if (run.plan.isShort()) continue;
            run.controlResults.put(new JSONObject().put("id", control.id)
                    .put("expected_status", control.expectedPass ? "PASS" : "FAIL")
                    .put("actual_status", "NOT RUN").put("status", "UNVERIFIED")
                    .put("reference", introReference()).put("input_sha256", control.asset.sha256)
                    .put("required_failures", new JSONArray(control.requiredFailures)));
        }
        for (TextOracleContract.Control control : TextOracleContract.CONTROLS) {
            if (run.plan.isShort()) continue;
            run.controlResults.put(new JSONObject().put("id", control.id)
                    .put("expected_status", control.expectedPass ? "PASS" : "FAIL")
                    .put("actual_status", "NOT RUN").put("status", "UNVERIFIED")
                    .put("reference", textReference()).put("input_sha256", control.asset.sha256)
                    .put("required_failures", new JSONArray(control.requiredFailures)));
        }
        for (TitleOracleContract.Control control : TitleOracleContract.CONTROLS) {
            if (run.plan.isShort()) continue;
            run.controlResults.put(new JSONObject().put("id", control.id)
                    .put("expected_status", control.expectedPass ? "PASS" : "FAIL")
                    .put("actual_status", "NOT RUN").put("status", "UNVERIFIED")
                    .put("reference", titleReference()).put("input_sha256", control.asset.sha256)
                    .put("required_failures", new JSONArray(control.requiredFailures)));
        }
        for (WatermarkOracleContract.Control control : WatermarkOracleContract.CONTROLS) {
            if (run.plan.isShort()) continue;
            run.controlResults.put(new JSONObject().put("id", control.id)
                    .put("expected_status", control.expectedPass ? "PASS" : "FAIL")
                    .put("actual_status", "NOT RUN").put("status", "UNVERIFIED")
                    .put("reference", watermarkReference()).put("input_sha256", control.asset.sha256)
                    .put("required_failures", new JSONArray(control.requiredFailures)));
        }
        if (run.controlResults.length() != run.plan.controls.size()) throw new IOException("Planned control count mismatch");
        for (int i = 0; i < run.controlResults.length(); i++) {
            if (!run.plan.controls.get(i).equals(run.controlResults.getJSONObject(i).getString("id"))) {
                throw new IOException("Planned control order mismatch");
            }
        }
        persistEvidence(run, "UNVERIFIED");
        for (int i = 0; i < controls.length(); i++) {
            JSONObject control = controls.getJSONObject(i);
            if (!run.plan.controls.contains(control.getString("id"))) continue;
            stage(run, 60_000);
            check(run);
            JSONObject summary = run.controlResults.getJSONObject(run.plan.controls.indexOf(control.getString("id")));
            String id = control.getString("id");
            log(run, "\nSTAGE CONTROL " + id + " BEFORE prepare/decode; expected="
                    + control.getString("expected_status") + "\n");
            try {
                File input = verifier.prepareControl(control);
                JSONObject result = verifier.verify(control.getString("case"), input);
                check(run);
                String actual = result.getString("status");
                boolean matches = control.getString("expected_status").equals(actual);
                String requiredFailure = control.optString("expected_failure_prefix");
                if (matches && !requiredFailure.isEmpty()) {
                    matches = hasFailedAssertion(result, requiredFailure);
                }
                summary.put("actual_status", actual)
                        .put("status", "ERROR".equals(actual) ? "ERROR" : matches ? "PASS" : "FAIL");
                summary.put("candidate_sha256", result.getString("candidate_sha256"));
                result.put("control", control).put("control_status", summary.getString("status"))
                        .put("run_metadata", run.metadata);
                String filename = "control-" + id + ".json";
                writeDiagnosticReport(run, filename, result);
                summary.put("report", filename);
                log(run, summary.getString("status") + " CONTROL " + id + " expected="
                        + control.getString("expected_status") + " actual=" + actual
                        + " sha256=" + result.getString("candidate_sha256") + "\n"
                        + assertionSummary(result) + "Full measurements: " + filename + "\n");
            } catch (IOException | TimeoutException | CancellationException error) {
                recordControlFailure(run, summary, "control-" + id + ".json", error);
                log(run, summary.getString("status") + " CONTROL " + id + "\n" + stack(error));
                run.stepDeadlineNs = run.deadlineNs;
                check(run);
            } finally {
                persistEvidence(run, "UNVERIFIED");
            }
        }
    }

    private void runMusicControls(Run run, OracleVerifier verifier, File source)
            throws IOException, JSONException, TimeoutException {
        runMusicControls(run, index -> {
            File input = index == 1 ? source : verifier.prepareMusicAsset(
                    index == 0 ? MusicOracleContract.REFERENCE : MusicOracleContract.NO_LOOP);
            return verifier.verify(MusicOracleContract.CASE_ID, input);
        });
    }

    interface ControlVerification {
        JSONObject verify(int index) throws IOException;
    }

    void runMusicControls(Run run, ControlVerification verification)
            throws IOException, JSONException, TimeoutException {
        String[] names = {"music_reference", "music_unchanged", "music_no_loop"};
        String[] expected = {"PASS", "FAIL", "FAIL"};
        for (int i = 0; i < names.length; i++) {
            stage(run, 60_000);
            check(run);
            JSONObject summary = run.controlResults.getJSONObject(run.plan.controls.indexOf(names[i]));
            log(run, "\nSTAGE CONTROL " + names[i] + " BEFORE prepare/decode\n");
            persistEvidence(run, "UNVERIFIED");
            String filename = "control-" + names[i] + ".json";
            try {
                JSONObject result = verification.verify(i);
                check(run);
                String actual = result.getString("status");
                boolean matches = expected[i].equals(actual)
                        && (i == 0 || hasFailedAssertion(result, "audio.window_"));
                summary.put("actual_status", actual)
                        .put("status", "ERROR".equals(actual) ? "ERROR" : matches ? "PASS" : "FAIL")
                        .put("candidate_sha256", result.getString("candidate_sha256"));
                result.put("control", copyDiagnosticRow(summary));
                writeDiagnosticReport(run, filename, result);
                summary.put("report", filename);
                log(run, summary.getString("status") + " CONTROL " + names[i]
                        + " expected=" + expected[i] + " actual=" + actual + "\n"
                        + assertionSummary(result));
            } catch (IOException | TimeoutException | CancellationException error) {
                recordControlFailure(run, summary, filename, error);
                log(run, summary.getString("status") + " CONTROL " + names[i] + "\n" + stack(error));
                run.stepDeadlineNs = run.deadlineNs;
                check(run);
            } finally {
                persistEvidence(run, "UNVERIFIED");
            }
        }
    }

    private static JSONObject introReference() throws JSONException {
        return new JSONObject().put("version", IntroOracleContract.VERSION)
                .put("path", IntroOracleContract.REFERENCE.path)
                .put("sha256", IntroOracleContract.REFERENCE.sha256)
                .put("contract_sha256", IntroOracleContract.CONTRACT_SHA256)
                .put("asset_sha256", new JSONObject(IntroOracleContract.ASSET_HASHES))
                .put("expected_duration_seconds", 5).put("expected_frames", 120)
                .put("expected_timeline", "output [0,1) -> standard [2,3); output [1,5) -> standard [0,4)")
                .put("expected_audio_hz", new JSONArray(Arrays.asList(880, 440, 660, 880, 1100)))
                .put("expected_edit_config", new JSONObject(IntroOracleContract.CASES.get(0).androidEditConfig)
                        .put("intro_asset", IntroOracleContract.INTRO.path)
                        .put("intro_sha256", IntroOracleContract.INTRO.sha256));
    }

    private void runIntroControls(Run run, OracleVerifier verifier)
            throws IOException, JSONException, TimeoutException {
        for (int i = 0; i < IntroOracleContract.CONTROLS.size(); i++) {
            stage(run, 60_000);
            check(run);
            IntroOracleContract.Control control = IntroOracleContract.CONTROLS.get(i);
            JSONObject summary = run.controlResults.getJSONObject(BASE_CONTROL_COUNT + 3 + i);
            String filename = "control-" + control.id + ".json";
            log(run, "\nSTAGE CONTROL " + control.id + " BEFORE prepare/decode\n");
            try {
                File input = verifier.prepareIntroAsset(control.asset);
                JSONObject result = verifier.verify(control.caseId, input);
                check(run);
                String actual = result.getString("status");
                boolean matches = summary.getString("expected_status").equals(actual);
                for (String assertion : control.requiredFailures) {
                    matches &= hasFailedAssertion(result, assertion);
                }
                summary.put("actual_status", actual)
                        .put("status", "ERROR".equals(actual) ? "ERROR" : matches ? "PASS" : "FAIL")
                        .put("candidate_sha256", result.getString("candidate_sha256"));
                result.put("control", copyDiagnosticRow(summary)).put("run_metadata", run.metadata)
                        .put("reference", introReference())
                        .put("export_codecs", "Frozen independent FFmpeg control; no production export");
                writeDiagnosticReport(run, filename, result);
                summary.put("report", filename);
                log(run, summary.getString("status") + " CONTROL " + control.id
                        + " expected=" + summary.getString("expected_status") + " actual=" + actual
                        + " sha256=" + result.getString("candidate_sha256") + "\n"
                        + assertionSummary(result) + "Full measurements: " + filename + "\n");
            } catch (IOException | TimeoutException | CancellationException error) {
                recordControlFailure(run, summary, filename, error);
                log(run, summary.getString("status") + " CONTROL " + control.id + "\n" + stack(error));
                run.stepDeadlineNs = run.deadlineNs;
                check(run);
            } finally {
                persistEvidence(run, "UNVERIFIED");
            }
        }
    }

    private static JSONObject textReference() throws JSONException {
        return new JSONObject().put("version", TextOracleContract.VERSION)
                .put("path", TextOracleContract.REFERENCE.path)
                .put("sha256", TextOracleContract.REFERENCE.sha256)
                .put("contract_sha256", TextOracleContract.MANIFEST.sha256)
                .put("asset_sha256", new JSONObject(TextOracleContract.ASSET_HASHES))
                .put("expected_edit_config", new JSONObject(TextOracleContract.CASES.get(0).androidEditConfig))
                .put("expected_content", "OI: closed ring then narrow solid stem, centered 48px white/black backing")
                .put("limits", "Bounded topology, not general OCR; OEM variation can fail; nine content probes");
    }

    private void runTextControls(Run run, OracleVerifier verifier)
            throws IOException, JSONException, TimeoutException {
        for (int i = 0; i < TextOracleContract.CONTROLS.size(); i++) {
            stage(run, 60_000);
            check(run);
            TextOracleContract.Control control = TextOracleContract.CONTROLS.get(i);
            JSONObject summary = run.controlResults.getJSONObject(
                    BASE_CONTROL_COUNT + 3 + IntroOracleContract.CONTROLS.size() + i);
            String filename = "control-" + control.id + ".json";
            log(run, "\nSTAGE CONTROL " + control.id + " BEFORE prepare/decode\n");
            try {
                JSONObject result = verifier.verify(TextOracleContract.CASE_ID,
                        verifier.prepareTextAsset(control.asset));
                check(run);
                String actual = result.getString("status");
                boolean matches = summary.getString("expected_status").equals(actual);
                for (String assertion : control.requiredFailures) matches &= hasFailedAssertion(result, assertion);
                JSONArray checks = result.getJSONArray("checks");
                for (int j = 0; j < checks.length(); j++) {
                    JSONObject assertion = checks.getJSONObject(j);
                    if (!assertion.getString("assertion").startsWith("text.")) {
                        matches &= assertion.getBoolean("passed");
                    }
                }
                summary.put("actual_status", actual)
                        .put("status", "ERROR".equals(actual) ? "ERROR" : matches ? "PASS" : "FAIL")
                        .put("candidate_sha256", result.getString("candidate_sha256"));
                result.put("control", copyDiagnosticRow(summary)).put("run_metadata", run.metadata)
                        .put("reference", textReference())
                        .put("export_codecs", "Independent analytic/FFmpeg control; no production export");
                writeDiagnosticReport(run, filename, result);
                summary.put("report", filename);
                log(run, summary.getString("status") + " CONTROL " + control.id
                        + " expected=" + summary.getString("expected_status") + " actual=" + actual
                        + "\n" + assertionSummary(result) + "Full measurements: " + filename + "\n");
            } catch (IOException | TimeoutException | CancellationException error) {
                recordControlFailure(run, summary, filename, error);
                log(run, summary.getString("status") + " CONTROL " + control.id + "\n" + stack(error));
                run.stepDeadlineNs = run.deadlineNs;
                check(run);
            } finally {
                persistEvidence(run, "UNVERIFIED");
            }
        }
    }

    static EditConfig titleConfig(File fixture) {
        IntroTemplate template = new IntroTemplate("Oracle OI", TitleOracleContract.TEXT,
                TitleOracleContract.SIZE_SP, 0xFFFFFFFF, 0xFF000000, .5f, .5f,
                TitleOracleContract.DURATION_MS, "normal");
        return new EditConfig.Builder(Uri.fromFile(fixture), 4000).sourceSize(320, 240)
                .introTemplate(template, TitleOracleContract.TEXT).build();
    }

    private static JSONObject titleReference() throws JSONException {
        return new JSONObject().put("version", TitleOracleContract.VERSION)
                .put("path", TitleOracleContract.REFERENCE.path)
                .put("sha256", TitleOracleContract.REFERENCE.sha256)
                .put("contract_sha256", TitleOracleContract.MANIFEST.sha256)
                .put("asset_sha256", new JSONObject(TitleOracleContract.ASSET_HASHES))
                .put("expected_edit_config", new JSONObject(TitleOracleContract.create()
                        .requireCase(TitleOracleContract.CASE_ID).androidEditConfig))
                .put("expected_duration_seconds", 5).put("expected_frames", 126)
                .put("expected_timeline", "silent OI [0,1) at30fps; original [1,5) at24fps")
                .put("limits", "Fixed 48sp normal OI on black; topology not OCR; native UNVERIFIED until run");
    }

    private void runTitleControls(Run run, OracleVerifier verifier)
            throws IOException, JSONException, TimeoutException {
        for (int i = 0; i < TitleOracleContract.CONTROLS.size(); i++) {
            stage(run, 60_000);
            check(run);
            TitleOracleContract.Control control = TitleOracleContract.CONTROLS.get(i);
            JSONObject summary = run.controlResults.getJSONObject(BASE_CONTROL_COUNT + 3
                    + IntroOracleContract.CONTROLS.size() + TextOracleContract.CONTROLS.size() + i);
            String filename = "control-" + control.id + ".json";
            log(run, "\nSTAGE CONTROL " + control.id + " BEFORE prepare/decode\n");
            try {
                JSONObject result = verifier.verify(TitleOracleContract.CASE_ID,
                        verifier.prepareTitleAsset(control.asset));
                check(run);
                String actual = result.getString("status");
                boolean matches = summary.getString("expected_status").equals(actual);
                for (String assertion : control.requiredFailures) matches &= hasFailedAssertion(result, assertion);
                matches &= !hasFailedAssertion(result, "checker.completed_without_error");
                summary.put("actual_status", actual)
                        .put("status", "ERROR".equals(actual) ? "ERROR" : matches ? "PASS" : "FAIL")
                        .put("candidate_sha256", result.getString("candidate_sha256"));
                result.put("control", copyDiagnosticRow(summary)).put("run_metadata", run.metadata)
                        .put("reference", titleReference())
                        .put("export_codecs", "Independent analytic/FFmpeg control; no production export");
                writeDiagnosticReport(run, filename, result);
                summary.put("report", filename);
                log(run, summary.getString("status") + " CONTROL " + control.id
                        + " expected=" + summary.getString("expected_status") + " actual=" + actual
                        + "\n" + assertionSummary(result) + "Full measurements: " + filename + "\n");
            } catch (IOException | TimeoutException | CancellationException error) {
                recordControlFailure(run, summary, filename, error);
                log(run, summary.getString("status") + " CONTROL " + control.id + "\n" + stack(error));
                run.stepDeadlineNs = run.deadlineNs;
                check(run);
            } finally {
                persistEvidence(run, "UNVERIFIED");
            }
        }
    }

    static EditConfig watermarkConfig(File fixture, File image) throws IOException {
        byte[] png = new byte[(int) WatermarkOracleContract.PNG.bytes];
        try (java.io.DataInputStream input = new java.io.DataInputStream(new FileInputStream(image))) {
            input.readFully(png);
            if (input.read() != -1) throw new IOException("Unexpected watermark fixture length");
        }
        return new EditConfig.Builder(Uri.fromFile(fixture), 4000).sourceSize(320, 240)
                .watermark(PngWatermark.fromBytes(png), .2f, .75f, .75f).build();
    }

    private static JSONObject watermarkReference() throws JSONException {
        return new JSONObject().put("version", WatermarkOracleContract.VERSION)
                .put("path", WatermarkOracleContract.REFERENCE.path)
                .put("sha256", WatermarkOracleContract.REFERENCE.sha256)
                .put("contract_sha256", WatermarkOracleContract.MANIFEST.sha256)
                .put("asset_sha256", new JSONObject(WatermarkOracleContract.ASSET_HASHES))
                .put("expected_edit_config", new JSONObject(WatermarkOracleContract.CASES.get(0).androidEditConfig))
                .put("expected_rect_xyxy", new JSONArray(Arrays.asList(192, 156, 256, 188)))
                .put("limits", "Fixed synthetic PNG, numeric alpha 0/128/255 on analytic background; nine probes; other compositions unverified");
    }

    private void runWatermarkControls(Run run, OracleVerifier verifier)
            throws IOException, JSONException, TimeoutException {
        for (int i = 0; i < WatermarkOracleContract.CONTROLS.size(); i++) {
            stage(run, 60_000);
            check(run);
            WatermarkOracleContract.Control control = WatermarkOracleContract.CONTROLS.get(i);
            JSONObject summary = run.controlResults.getJSONObject(
                    CONTROL_COUNT - WatermarkOracleContract.CONTROLS.size() + i);
            String filename = "control-" + control.id + ".json";
            log(run, "\nSTAGE CONTROL " + control.id + " BEFORE prepare/decode\n");
            try {
                JSONObject result = verifier.verify(WatermarkOracleContract.CASE_ID,
                        verifier.prepareWatermarkAsset(control.asset));
                check(run);
                String actual = result.getString("status");
                boolean matches = watermarkControlMatches(result, control);
                summary.put("actual_status", actual)
                        .put("status", "ERROR".equals(actual) ? "ERROR" : matches ? "PASS" : "FAIL")
                        .put("candidate_sha256", result.getString("candidate_sha256"));
                result.put("control", copyDiagnosticRow(summary)).put("run_metadata", run.metadata)
                        .put("reference", watermarkReference())
                        .put("export_codecs", "Independent analytic/FFmpeg control; no production export");
                writeDiagnosticReport(run, filename, result);
                summary.put("report", filename);
                log(run, summary.getString("status") + " CONTROL " + control.id
                        + " expected=" + summary.getString("expected_status") + " actual=" + actual
                        + "\n" + assertionSummary(result) + "Full measurements: " + filename + "\n");
            } catch (IOException | TimeoutException | CancellationException error) {
                recordControlFailure(run, summary, filename, error);
                log(run, summary.getString("status") + " CONTROL " + control.id + "\n" + stack(error));
                run.stepDeadlineNs = run.deadlineNs;
                check(run);
            } finally {
                persistEvidence(run, "UNVERIFIED");
            }
        }
    }

    static boolean watermarkControlMatches(JSONObject result, WatermarkOracleContract.Control control)
            throws JSONException {
        boolean matches = (control.expectedPass ? "PASS" : "FAIL").equals(result.getString("status"))
                && control.asset.sha256.equals(result.getString("candidate_sha256"));
        for (String failure : control.requiredFailures) {
            for (int probe = 0; probe < 9; probe++) {
                matches &= hasFailedAssertion(result, failure.replace("probe_0", "probe_" + probe));
            }
        }
        JSONArray checks = result.getJSONArray("checks");
        int watermarkChecks = 0;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            if (check.getString("assertion").startsWith("watermark.")) watermarkChecks++;
            else matches &= check.getBoolean("passed");
        }
        return matches && watermarkChecks == 45;
    }

    private static boolean hasFailedAssertion(JSONObject result, String prefix) throws JSONException {
        JSONArray checks = result.getJSONArray("checks");
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            if (!check.getBoolean("passed") && check.getString("assertion").startsWith(prefix)) return true;
        }
        return false;
    }

    private SuiteOutcome persistEvidence(Run run, String status) throws IOException {
        return persistEvidence(run, status, true);
    }

    private SuiteOutcome persistEvidence(Run run, String status, boolean publishOutcome) throws IOException {
        try {
            captureReportBinding(run);
            JSONArray exports = pendingInventory(run.caseResults, run.plan.cases, false);
            JSONArray controls = pendingInventory(run.controlResults, run.plan.controls, true);
            JSONArray exportIndex = CompactSuiteIndex.results(exports, run.plan.cases.size());
            JSONArray controlIndex = CompactSuiteIndex.results(controls, run.plan.controls.size());
            SuiteOutcome snapshot = SuiteOutcome.fromSnapshot(run.plan, status, run.finished,
                    run.fixturePassed, exports, controls, run.cancelled,
                    run.timedOut, run.fatal != null, run.reportFailure != null);
            JSONObject rawBinding = reportBinding(run);
            JSONObject raw = new JSONObject().put("metadata", run.metadata)
                    .put("report_binding", rawBinding);
            if (run.fatal != null) raw.put("fatal_error", stack(run.fatal));
            if (run.reportFailure != null) raw.put("report_storage_error", stack(run.reportFailure));
            JSONObject evidence = run.plan.metadata().put("schema", SuiteOutcome.EVIDENCE_SCHEMA)
                    .put("status", status).put("complete", run.finished)
                    .put("full_feature_coverage", "UNVERIFIED")
                    .put("full_suite_status", run.plan.isShort() ? "NOT_RUN" : status)
                    .put("limitations", (run.plan.isShort() ? SelfTestPlan.SHORT_NOTICE : "") + LIMITATIONS)
                    .put("metadata", CompactSuiteIndex.metadata(run.metadata)).put("fixture_verified", run.fixturePassed)
                    .put("exports", exportIndex).put("checker_controls", controlIndex)
                    .put("cancelled", run.cancelled).put("timed_out", run.timedOut)
                    .put("updated_utc_ms", System.currentTimeMillis())
                    .put("report_storage_ok", run.reportFailure == null)
                    .put("report_binding", CompactSuiteIndex.binding(rawBinding))
                    .put("outcome", snapshot.toJson());
            if (run.fatal != null) evidence.put("fatal_error", CompactSuiteIndex.reference("fatal_error"));
            if (run.reportFailure != null) {
                evidence.put("report_storage_error", CompactSuiteIndex.reference("report_storage_error"));
            }
            if (run.failedReportWrites.length() != 0) {
                raw.put("failed_report_writes", run.failedReportWrites);
                evidence.put("failed_report_writes", CompactSuiteIndex.reference("failed_report_writes"));
            }
            String index = CompactSuiteIndex.serialize(evidence);
            try {
                CompactSuiteIndex.writeRaw(new File(run.directory, CompactSuiteIndex.MANIFEST), raw);
            } catch (IOException | JSONException | RuntimeException error) {
                if (run.failedReportWrites.length() != 0) {
                    appendLog(run, "\nRAW_DIAGNOSTICS_UNAVAILABLE: per-case and suite metadata persistence failed\n");
                    try {
                        synchronized (run) {
                            flushLog(run, false);
                        }
                    } catch (IOException | RuntimeException logging) {
                        error.addSuppressed(logging);
                    }
                }
                throw error;
            }
            writeAtomic(new File(run.directory, "suite.json"), index);
            run.persistedOutcome = snapshot;
            if (publishOutcome) outcome = snapshot;
            return snapshot;
        } catch (JSONException error) {
            outcome = SuiteOutcome.malformed("suite outcome 序列化失败。");
            throw new IOException("Cannot serialize suite evidence", error);
        } catch (IOException error) {
            outcome = storageErrorOutcome(run,
                    "suite.json 更新失败；请重新运行以获得权威结果。");
            throw error;
        }
    }

    private static JSONArray pendingInventory(JSONArray results, List<String> ids, boolean controls)
            throws JSONException {
        if (results.length() != 0) return results;
        JSONArray pending = new JSONArray();
        for (String id : ids) {
            pending.put(new JSONObject().put("id", id).put("status", "UNVERIFIED")
                    .put("expected_status", controls && !SelfTestPlan.positive(id) ? "FAIL" : "PASS")
                    .put("actual_status", "NOT RUN"));
        }
        return pending;
    }

    private SuiteOutcome snapshotOutcome(Run run, String status) {
        try {
            return SuiteOutcome.fromSnapshot(run.plan, status, run.finished, run.fixturePassed,
                    run.caseResults, run.controlResults, run.cancelled, run.timedOut,
                    run.fatal != null, run.reportFailure != null);
        } catch (JSONException error) {
            return SuiteOutcome.malformed("suite outcome 计算失败。");
        }
    }

    private SuiteOutcome storageErrorOutcome(Run run, String reason) {
        try {
            return SuiteOutcome.storageError(run.plan, run.caseResults, run.controlResults, reason);
        } catch (JSONException error) {
            return SuiteOutcome.malformed(reason);
        }
    }

    boolean requestCancel(Run run) {
        if (run == null) return false;
        synchronized (run.terminalStateLock) {
            if (run.finalizing || run.terminalCommitted) {
                return false;
            }
            run.cancelled = true;
            return true;
        }
    }

    String beginTerminalCommit(Run run, String computedStatus) {
        synchronized (run.terminalStateLock) {
            run.finalizing = true;
            return run.cancelled ? "UNVERIFIED" : computedStatus;
        }
    }

    void finishTerminalCommit(Run run) {
        synchronized (run.terminalStateLock) {
            run.finalizing = false;
            run.terminalCommitted = true;
        }
    }

    private void publishFinalizing(Run run) {
        outcome = finalizingOutcome(run);
        synchronized (run) {
            publish(run, run.preview.text()
                    + "\nFINALIZING: writing terminal report and suite metadata; cancellation is no longer accepted.\n");
        }
    }

    private SuiteOutcome finalizingOutcome(Run run) {
        try {
            return SuiteOutcome.finalizing(run.plan, run.caseResults, run.controlResults);
        } catch (JSONException error) {
            return SuiteOutcome.malformed("suite outcome 计算失败。");
        }
    }

    private static String sha256(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(Locale.US, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private File export(Run run, EditConfig config, File output, String name)
            throws IOException, InterruptedException, TimeoutException {
        return export(run, config, output, name,
                message -> log(run, "STAGE EXPORT DIAGNOSTIC " + name + "\n" + message));
    }

    void recordExportFailure(Run run, int index, File output, Exception error)
            throws IOException, JSONException {
        String name = run.plan.cases.get(index);
        run.status[index] = run.cancelled || run.timedOut ? "UNVERIFIED" : "ERROR";
        JSONObject summary = run.caseResults.getJSONObject(index);
        summary.remove("report");
        summary.put("status", run.status[index]).put("actual_status", "ERROR");
        JSONObject failure = copyDiagnosticRow(summary).put("id", name)
                .put("status", run.status[index]).put("expected_status", "PASS")
                .put("actual_status", "ERROR").put("error", stack(error))
                .put("export_codecs", run.codecInfo).put("run_metadata", run.metadata);
        appendLog(run, run.status[index] + " " + name + "\n" + stack(error));
        if (output.isFile()) {
            try {
                failure.put("candidate_sha256", sha256(output));
            } catch (IOException hashing) {
                failure.put("candidate_sha256_error", stack(hashing));
            }
        }
        writeDiagnosticReport(run, name + ".json", failure);
        failure.put("report", name + ".json");
        run.caseResults.put(index, CompactSuiteIndex.results(new JSONArray().put(failure), 1).getJSONObject(0));
        persistEvidence(run, "UNVERIFIED");
        log(run, "");
    }

    void recordControlFailure(Run run, JSONObject summary, String filename, Exception error)
            throws IOException, JSONException {
        summary.remove("report");
        summary.put("status", run.cancelled || run.timedOut ? "UNVERIFIED" : "ERROR")
                .put("actual_status", "ERROR").put("error", stack(error))
                .put("run_metadata", run.metadata);
        JSONObject failure = copyDiagnosticRow(summary);
        try {
            writeDiagnosticReport(run, filename, failure);
        } catch (IOException saving) {
            appendLog(run, summary.getString("status") + " CONTROL " + summary.getString("id")
                    + "\n" + stack(error));
            throw saving;
        }
        summary.put("report", filename);
    }

    void recordExportResult(Run run, int index, JSONObject result) throws IOException, JSONException {
        String name = run.plan.cases.get(index);
        run.caseResults.getJSONObject(index).remove("report");
        writeDiagnosticReport(run, name + ".json", result);
        run.status[index] = result.getString("status");
        run.caseResults.put(index, new JSONObject().put("id", name)
                .put("status", run.status[index]).put("expected_status", "PASS")
                .put("actual_status", run.status[index])
                .put("candidate_sha256", result.getString("candidate_sha256"))
                .put("export_config", result.get("export_config"))
                .put("reference", result.get("reference"))
                .put("report", name + ".json"));
    }

    private static JSONObject copyDiagnosticRow(JSONObject row) throws JSONException {
        JSONObject copy = new JSONObject();
        java.util.Iterator<String> keys = row.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            copy.put(key, row.get(key));
        }
        return copy;
    }

    private static void writeDiagnosticReport(Run run, String filename, JSONObject raw)
            throws IOException, JSONException {
        try {
            CompactSuiteIndex.writeRaw(new File(run.directory, filename), raw);
        } catch (IOException | JSONException | RuntimeException error) {
            IOException saving = new IOException("Cannot persist raw diagnostic: " + filename, error);
            if (run.reportFailure == null) run.reportFailure = saving;
            // Retain only failed writes, including an original verifier result before its error row.
            JSONArray failures = run.failedReportWrites.optJSONArray(filename);
            if (failures == null) {
                failures = new JSONArray();
                run.failedReportWrites.put(filename, failures);
            }
            failures.put(new JSONObject().put("diagnostic", raw).put("storage_error", stack(saving)));
            throw saving;
        }
    }

    interface DiagnosticCheckpoint {
        void write(String message) throws IOException;
    }

    File export(Run run, EditConfig config, File output, String name, DiagnosticCheckpoint checkpoint)
            throws IOException, InterruptedException, TimeoutException {
        long startedNs = System.nanoTime();
        synchronized (run) {
            run.progress = -1;
            run.codecInfo = "Encoder details not received";
        }
        log(run, "STAGE EXPORT " + name + " START progress=-1% " + run.codecInfo + "\n");
        Completion<File> completion = new Completion<>();
        DiagnosticQueue diagnostics = new DiagnosticQueue(completion);
        main.post(() -> {
            if (run.cancelled || run.timedOut || run.finished) {
                completion.completeExceptionally(new CancellationException("Export not started"));
                return;
            }
            try {
                run.engine = new Media3ExportEngine(context);
                run.engine.export(config, output, new Media3ExportEngine.Listener() {
                    private int lastProgress = -10;
                    @Override public void onProgress(int percent) {
                        if (percent >= lastProgress + 10 && active == run && !run.finished) {
                            lastProgress = percent;
                            run.progress = percent;
                            listener.onUpdate(run.snapshot + "\n" + name + ": " + percent + "%\n", true);
                        }
                    }
                    @Override public void onCompleted(File file) {
                        diagnostics.submit(() -> completion.complete(file));
                    }
                    @Override public void onError(Exception error) {
                        diagnostics.submit(() -> completion.completeExceptionally(error));
                    }
                    @Override public void onCodecs(String video, String audio) {
                        run.codecInfo = "ENCODER video=" + video + ", audio=" + audio;
                    }
                    @Override public void onDiagnostic(String message) {
                        Runnable persist = () -> {
                            synchronized (run) {
                                completion.checkpoint(name, message, checkpoint);
                            }
                        };
                        // Lifecycle diagnostics emit on main; native codec checkpoints remain synchronous.
                        if (Looper.myLooper() == Looper.getMainLooper()) diagnostics.submit(persist);
                        else persist.run();
                    }
                });
            } catch (RuntimeException error) {
                completion.completeExceptionally(error);
            }
        });
        Exception failure = null;
        String lastCodecs = "";
        int lastProgress = -1;
        try {
            while (true) {
                check(run);
                if (!lastCodecs.equals(run.codecInfo) || lastProgress != run.progress) {
                    lastCodecs = run.codecInfo;
                    lastProgress = run.progress;
                    logExportProgress(run, completion, "STAGE EXPORT " + name + " progress=" + lastProgress
                            + "% " + lastCodecs + "\n");
                }
                try {
                    return completion.await(200, TimeUnit.MILLISECONDS);
                } catch (TimeoutException poll) {
                    // Poll the independent suite/stage clock, not just the backend's timeout.
                }
            }
        } catch (IOException | InterruptedException | TimeoutException | RuntimeException error) {
            failure = error;
            try {
                log(run, "EXPORT STOPPED before native cleanup:\n" + stack(error));
            } catch (IOException | RuntimeException saving) {
                if (saving != error) error.addSuppressed(saving);
            }
            throw error;
        } finally {
            Exception cleanupFailure = null;
            try {
                log(run, "STAGE EXPORT CLEANUP " + name + " BEFORE native release"
                        + " exportElapsedMs=" + (System.nanoTime() - startedNs) / 1_000_000 + "\n");
            } catch (IOException | RuntimeException saving) {
                appendLog(run, "\nCHECKPOINT FAILED: " + stack(saving));
                cleanupFailure = saving;
            }
            long cleanupStartedNs = System.nanoTime();
            try {
                stopEngine(run);
            } catch (IOException | RuntimeException error) {
                cleanupFailure = combine(cleanupFailure, error);
            }
            diagnostics.close();
            try {
                log(run, "STAGE EXPORT CLEANUP " + name + " AFTER native release"
                        + " cleanupMs=" + (System.nanoTime() - cleanupStartedNs) / 1_000_000
                        + " exportTotalMs=" + (System.nanoTime() - startedNs) / 1_000_000 + "\n");
            } catch (IOException | RuntimeException saving) {
                cleanupFailure = combine(cleanupFailure, saving);
            }
            if (cleanupFailure != null) {
                if (failure != null) {
                    if (failure != cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                } else if (cleanupFailure instanceof RuntimeException) {
                    throw (RuntimeException) cleanupFailure;
                } else {
                    throw new IOException("Export cleanup/checkpoint failed", cleanupFailure);
                }
            }
        }
    }

    void logExportProgress(Run run, Completion<?> completion, String message) throws IOException {
        synchronized (run) {
            // Diagnostic failure publication uses this lock: later writes must not replace it.
            if (!completion.isDone()) log(run, message);
        }
    }

    static final class DiagnosticQueue {
        private final Completion<?> completion;
        private final java.util.concurrent.ThreadPoolExecutor worker =
                new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(16),
                        runnable -> new Thread(runnable, "selftest-main-diagnostics"));

        DiagnosticQueue(Completion<?> completion) { this.completion = completion; }

        void submit(Runnable task) {
            try {
                worker.execute(() -> {
                    try { task.run(); }
                    catch (RuntimeException error) {
                        // Publish the failure to the suite, not an uncaught worker-thread crash.
                        completion.completeExceptionally(error);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException error) {
                completion.completeExceptionally(new IOException(
                        "Diagnostic storage queue unavailable; suite stopped", error));
            }
        }

        void close() {
            worker.shutdown();
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        if (worker.awaitTermination(10, TimeUnit.SECONDS)) return;
                    } catch (InterruptedException error) { interrupted = true; }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private void stopEngine(Run run) throws IOException {
        Completion<Void> stopped = new Completion<>();
        main.post(() -> requestStop(run, stopped, null));
        boolean warned = false;
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    stopped.get(10, TimeUnit.SECONDS);
                    return;
                } catch (TimeoutException pending) {
                    if (!warned) {
                        warned = true;
                        String pendingReport = appendLog(run,
                                "CLEANUP PENDING: main thread has not acknowledged;"
                                + " retaining run ownership and outputs until it does.\n");
                        main.post(() -> {
                            if (active == run) {
                                listener.onUpdate(pendingReport, true);
                            }
                        });
                    }
                } catch (InterruptedException pending) {
                    interrupted = true;
                } catch (ExecutionException error) {
                    if (error.getCause() instanceof RuntimeException) {
                        throw (RuntimeException) error.getCause();
                    }
                    throw new IOException("Cannot stop export engine", error.getCause());
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void requestStop(Run run, Completion<Void> stopped, RuntimeException failure) {
        try {
            if (run.engine != null) {
                run.engine.cancel();
            }
        } catch (RuntimeException error) {
            if (failure == null) {
                failure = error;
            } else if (failure != error) {
                failure.addSuppressed(error);
            }
        }
        if (run.engine != null && run.engine.isRunning()) {
            // Never release the suite lock or permit retention while an export still owns its file.
            RuntimeException pendingFailure = failure;
            main.postDelayed(() -> requestStop(run, stopped, pendingFailure), 1000);
            return;
        }
        run.engine = null;
        if (failure != null) {
            stopped.completeExceptionally(failure);
        } else {
            stopped.complete(null);
        }
    }

    private static EditConfig configFor(JSONObject config, File fixture) throws JSONException {
        return new EditConfig.Builder(Uri.fromFile(fixture), config.getLong("sourceDurationMs"))
                .trim(config.getLong("startMs"), config.getLong("endMs"))
                .crop((float) config.getDouble("cropLeft"), (float) config.getDouble("cropTop"),
                        (float) config.getDouble("cropRight"), (float) config.getDouble("cropBottom"))
                .rotation(config.getInt("rotationDegrees")).outputHeight(config.getInt("outputHeight"))
                .speed((float) config.getDouble("speed")).volume((float) config.getDouble("volume"))
                .overlayText(config.getString("overlayText")).build();
    }

    private String appVersion() throws IOException {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return context.getPackageName() + " " + info.versionName + " (" + info.versionCode + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            throw new IOException("Cannot read own app version", error);
        }
    }

    static String formatCaseResult(JSONObject result) throws JSONException {
        String name = result.getString("case");
        return result.getString("status") + " " + name + "\n" + result.getString("export_codecs")
                + "\nOUTPUT SHA256 " + result.getString("candidate_sha256")
                + "\nREFERENCE " + result.getJSONObject("reference")
                + "\nDECODER " + result.getJSONObject(OracleVerifier.DECODER_KEY)
                + "\n" + assertionSummary(result)
                + "Full measurements: " + name + ".json\n";
    }

    private static String assertionSummary(JSONObject result) throws JSONException {
        StringBuilder text = new StringBuilder();
        String decoder = String.valueOf(result.opt("decoder"));
        text.append("DECODER: ").append(decoder.length() > 4096
                ? decoder.substring(0, 4096) + " [truncated; full JSON retained]" : decoder).append('\n');
        JSONArray checks = result.getJSONArray("checks");
        int omitted = 0;
        int shown = 0;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            if (shown >= 16 || (check.getBoolean("passed") && i >= 12)) {
                omitted++;
                continue;
            }
            shown++;
            text.append(check.getBoolean("passed") ? "PASS " : "FAIL ")
                    .append(check.getString("assertion")).append(": expected=")
                    .append(check.opt("expected")).append(", actual=")
                    .append(check.opt("actual")).append('\n');
        }
        if (omitted > 0) text.append(omitted)
                .append(" additional measurements retained in the JSON report.\n");
        return text.toString();
    }

    private static void stage(Run run, long milliseconds) {
        run.stepDeadlineNs = Math.min(run.deadlineNs, System.nanoTime() + milliseconds * 1_000_000);
    }

    private static void check(Run run) throws TimeoutException {
        if (run.timedOut || System.nanoTime() >= run.deadlineNs) {
            run.timedOut = true;
            throw new TimeoutException("Fifteen-minute total selftest budget exhausted");
        }
        if (run.cancelled || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Selftest cancelled");
        }
        if (System.nanoTime() >= run.stepDeadlineNs) {
            throw new TimeoutException("Current selftest step exceeded its deadline");
        }
    }

    OracleAndroidDecoder.Observer decoderObserver(Run run) {
        return diagnostics -> log(run, "STAGE DECODER " + new JSONObject(diagnostics) + "\n");
    }

    void log(Run run, String message) throws IOException {
        synchronized (run) {
            run.log.append("\nUTC_MS ").append(System.currentTimeMillis()).append('\n').append(message);
            flushLog(run, false);
            publish(run, run.preview.text());
        }
    }

    private static void flushLog(Run run, boolean terminal) throws IOException {
        String delta = run.log.toString();
        run.log = new StringBuilder();
        run.preview.append(delta);
        try {
            // Even after SAF failure, preserve final errors privately without retrying the provider.
            ReportJournal.append(run.report, delta);
            if (run.reportFailure != null) {
                throw new IOException("Report storage previously failed; suite stopped");
            }
            if (run.savedReport != null) run.savedReport.appendCheckpoint(delta, terminal);
        } catch (IOException error) {
            if (run.reportFailure == null) run.reportFailure = error;
            throw error;
        }
    }

    private static String appendLog(Run run, String message) {
        synchronized (run) {
            run.log.append(message);
            ReportPreview pending = new ReportPreview();
            pending.append(run.preview.text());
            pending.append(run.log.toString());
            return pending.text();
        }
    }

    private void publish(Run run, String text) {
        synchronized (run.updateLock) {
            run.snapshot = text;
            if (!run.updatePending) {
                run.updatePending = true;
                main.postDelayed(run.update, 100);
            }
        }
    }

    private static void persistOutputs(Run run) throws IOException {
        StringBuilder names = new StringBuilder();
        for (File file : run.completedOutputs) {
            names.append(file.getName()).append('\n');
        }
        writeAtomic(new File(run.directory, "outputs.txt"), names.toString());
    }

    private static synchronized void writeAtomic(File file, String text) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            stream.write(bytes);
            stream.flush();
            stream.getFD().sync();
            atomic.finishWrite(stream);
            CompactSuiteIndex.verifyCommit(file, bytes.length);
        } catch (IOException | RuntimeException error) {
            if (stream != null) {
                atomic.failWrite(stream);
            }
            throw error;
        }
    }

    private static synchronized List<File> reopenOutputs(File directory) {
        List<File> files = new ArrayList<>();
        File manifest = new File(directory, "outputs.txt");
        if (!manifest.isFile() && !new File(directory, "outputs.txt.bak").isFile()) {
            return files;
        }
        try (FileInputStream input = new AtomicFile(manifest).openRead();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (int i = 0; i < CASE_NAMES.length; i++) {
                String name = reader.readLine();
                if (name == null) {
                    break;
                }
                if (name.matches("[0-9]{2}-[a-z0-9_-]+\\.mp4")) {
                    File file = new File(directory, name);
                    if (file.isFile() && file.length() > 0 && file.length() <= 32L * 1024 * 1024) {
                        files.add(file);
                    }
                }
            }
        } catch (IOException error) {
            // A damaged index cannot prove completion; do not expose possibly partial exports.
            android.util.Log.w("SelfTestRunner", "Cannot reopen completed-output index", error);
        }
        return Collections.unmodifiableList(files);
    }

    private static File[] runDirectories(File root) {
        File[] runs = root.listFiles(file -> file.isDirectory()
                && file.getName().matches("run-[0-9]{13}-[a-f0-9]{8}"));
        if (runs == null) {
            return new File[0];
        }
        Arrays.sort(runs, (a, b) -> b.getName().compareTo(a.getName()));
        return runs;
    }

    private static void prune(File root, File current) throws IOException {
        int kept = 1;
        String canonicalRoot = root.getCanonicalPath() + File.separator;
        for (File directory : runDirectories(root)) {
            if (directory.equals(current)) {
                continue;
            }
            if (kept++ < 2) {
                continue;
            }
            if (!directory.getCanonicalPath().startsWith(canonicalRoot)) {
                throw new IOException("Refusing retention outside app-owned root");
            }
            File[] files = directory.listFiles();
            if (files == null) {
                throw new IOException("Cannot list stopped run for retention");
            }
            for (File file : files) {
                if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())
                        || file.isDirectory()) {
                    throw new IOException("Refusing unexpected nested or linked retention entry");
                }
                if (!file.delete()) {
                    throw new IOException("Cannot delete old selftest artifact " + file.getName());
                }
            }
            if (!directory.delete()) {
                throw new IOException("Cannot delete old selftest run");
            }
        }
    }

    private static Exception combine(Exception first, Exception next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static String stack(Exception error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        // Driver/backend exceptions may carry a URI. Reports only need local diagnostic stacks.
        return writer.toString().replaceAll("(?i)https?://\\S+", "[remote address omitted]");
    }

    // FutureTask is available on API 23, unlike CompletableFuture.
    static final class Completion<T> extends FutureTask<T> {
        Completion() { super(() -> null); }
        void complete(T value) { set(value); }
        void completeExceptionally(Throwable error) { setException(error); }

        void checkpoint(String name, String message, DiagnosticCheckpoint checkpoint) {
            try {
                checkpoint.write(message);
            } catch (IOException saving) {
                // FutureTask is first-wins: publish the checked failure before any engine wrapper.
                completeExceptionally(saving);
                // Abort synchronously, without waiting for main-looper cancellation here.
                throw new RuntimeException("Durable export diagnostic checkpoint failed: " + name, saving);
            } catch (RuntimeException saving) {
                completeExceptionally(saving);
                throw saving;
            }
        }

        T await(long timeout, TimeUnit unit)
                throws IOException, InterruptedException, TimeoutException {
            try {
                return get(timeout, unit);
            } catch (ExecutionException error) {
                if (error.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) error.getCause();
                }
                if (error.getCause() instanceof IOException) {
                    throw (IOException) error.getCause();
                }
                throw new IOException("Shared Media3 export failed", error.getCause());
            }
        }
    }

    private static final class RestoredState {
        final File reportFile;
        final List<File> outputs;
        final SuiteOutcome outcome;

        RestoredState(File reportFile, List<File> outputs, SuiteOutcome outcome) {
            this.reportFile = reportFile;
            this.outputs = outputs;
            this.outcome = outcome;
        }
    }

    final class Run {
        final SelfTestPlan plan;
        Run() { this(new SelfTestPlan(SelfTestPlan.Mode.FULL)); }
        Run(SelfTestPlan plan) {
            this.plan = plan;
            status = new String[plan.cases.size()];
        }
        final long deadlineNs = System.nanoTime() + SUITE_MS * 1_000_000;
        volatile long stepDeadlineNs = deadlineNs;
        final ExecutorService worker = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "offline-selftest"));
        StringBuilder log = new StringBuilder();
        final ReportPreview preview = new ReportPreview();
        IOException reportFailure;
        // Never acquired by persistence; main must not wait for the run's storage monitor.
        final Object updateLock = new Object();
        boolean updatePending;
        final Runnable update = () -> {
            String text;
            synchronized (updateLock) {
                updatePending = false;
                text = this.snapshot;
            }
            if (active == this && (!this.finished || isFinalizing())) listener.onUpdate(text, true);
        };
        final String[] status;
        final JSONObject metadata = new JSONObject();
        final JSONObject failedReportWrites = new JSONObject();
        final JSONArray caseResults = new JSONArray();
        final JSONArray controlResults = new JSONArray();
        final List<File> completedOutputs = new ArrayList<>();
        final Object terminalStateLock = new Object();
        volatile boolean cancelled;
        volatile boolean timedOut;
        volatile boolean finished;
        volatile Thread workerThread;
        volatile String codecInfo;
        volatile int progress = -1;
        volatile SuiteOutcome persistedOutcome;
        boolean bindingCaptured;
        boolean selectedReportRequired;
        String selectedReportUri = "";
        String selectedReportMode = "";
        String selectedReportDestination = "";
        boolean finalizing;
        boolean terminalCommitted;
        SavedReport savedReport;
        volatile String snapshot = "";
        Media3ExportEngine engine; // Main looper only.
        File directory;
        File report;
        Exception fatal;
        boolean fixturePassed;
        final Runnable watchdog = () -> {
            if (active == this && !finished) {
                timedOut = true;
                if (workerThread != null) workerThread.interrupt();
                if (engine != null) {
                    engine.cancel();
                }
            }
        };
    }
}
