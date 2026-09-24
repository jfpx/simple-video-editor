package com.simple.videoeditor;

import android.content.Context;
import com.simple.videoeditor.oracle.IntroOracleContract;
import com.simple.videoeditor.oracle.TextOracleContract;
import com.simple.videoeditor.oracle.TitleOracleContract;
import com.simple.videoeditor.oracle.WatermarkOracleContract;
import com.simple.videoeditor.oracle.OracleContract;
import com.simple.videoeditor.oracle.OracleAndroidDecoder;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class SchemaRegressionMain {
    private static int guards;

    public static void main(String[] args) throws Exception {
        ImageIO.setUseCache(false);
        File output = new File("app\\build\\schema-regression");
        if (args.length == 1 && "--replay".equals(args[0])) {
            JSONArray evidence = read(new File(output, "results.json")).getJSONArray("results");
            for (int i = 0; i < evidence.length(); i++) {
                checkFormat(evidence.getJSONObject(i).getJSONObject("result"));
            }
            System.out.println("PASS replay: " + evidence.length() + " actual verify results");
            return;
        }
        File oracleRoot = new File(args[0]);
        boolean producerOnly = args.length == 2 && "--producer-only".equals(args[1]);
        System.setProperty("schema.oracleRoot", oracleRoot.getAbsolutePath());
        JSONObject parity = read(new File("app\\build\\oracle-parity\\video_oracle_parity_report.json"));
        require(parity.getBoolean("passed"), "Prior parity must pass");
        JSONArray controls = parity.getJSONArray("controls");
        require(controls.length() == 36, "Expected 36 cached controls");
        OracleVerifier verifier = new OracleVerifier(new Context(
                new File("app\\src\\main\\assets"), new File(output, "files")));
        JSONArray cases = verifier.loadContract().getJSONArray("cases");
        require(cases.length() == 12, "Expected all 12 contract cases");
        if (!producerOnly) {
            require("decoder".equals(OracleVerifier.class.getField("DECODER_KEY").get(null)),
                    "Producer decoder key changed");
        }
        JSONArray evidence = new JSONArray();
        Set<String> positives = new HashSet<>();
        Set<String> ids = new HashSet<>();
        int passes = 0;
        int failures = 0;
        for (int i = 0; i < controls.length(); i++) {
            JSONObject control = controls.getJSONObject(i);
            String id = control.getString("id");
            require(ids.add(id), "Duplicate control " + id);
            String caseId = control.getString("case_id");
            File candidate = new File(control.getString("input"));
            require(candidate.getCanonicalFile().toPath().startsWith(oracleRoot.getCanonicalFile().toPath()),
                    "Control outside supplied frozen root");
            JSONObject result = verifier.verify(caseId, candidate);
            JSONObject prior = control.getJSONObject("sparse").getJSONObject("report");
            for (String key : new String[]{"case", "candidate_sha256", "status", "passed",
                    "failed_assertions", "checks", "metrics"}) {
                require(new JSONObject(new JSONObject().put(key, result.get(key)).toString()).similar(
                        new JSONObject(new JSONObject().put(key, prior.get(key)).toString())),
                        id + ": parity mismatch " + key);
            }
            boolean positive = "positive".equals(control.getString("kind"));
            require((positive ? "PASS" : "FAIL").equals(result.getString("status")), id + ": wrong status");
            if (positive) {
                positives.add(caseId);
                passes++;
            } else {
                failures++;
                require(result.getJSONArray("failed_assertions").length() > 0, id + ": missing failure evidence");
            }
            enrich(result, cases);
            String text = producerOnly ? checkProducer(result) : checkFormat(result);
            evidence.put(new JSONObject().put("id", id).put("result", result).put("formatted", text));
            System.out.println("PASS " + id + " -> " + result.getString("status"));
        }
        require(passes == 17 && failures == 19 && positives.size() == 12,
                "Expected 17 positives across all 12 cases and 19 negatives");
        JSONObject first = controls.getJSONObject(0);
        OracleAndroidDecoder.injectImageFailure = true;
        JSONObject error;
        try {
            error = verifier.verify(first.getString("case_id"), new File(first.getString("input")));
        } finally {
            OracleAndroidDecoder.injectImageFailure = false;
        }
        require("ERROR".equals(error.getString("status")), "Injected checker fault must remain ERROR");
        require(error.getJSONArray("failed_assertions").toString().contains("checker.completed_without_error"),
                "Missing checker error evidence");
        enrich(error, cases);
        evidence.put(new JSONObject().put("id", "injected_checker_error").put("result", error)
                .put("formatted", producerOnly ? checkProducer(error) : checkFormat(error)));
        int baselineGuards = guards;
        require(producerOnly || baselineGuards == 259, "All 259 existing schema guards must run");
        JSONObject intro = checkIntro(output, evidence, producerOnly);
        JSONObject text = checkText(output, evidence, producerOnly);
        JSONObject title = checkTitle(output, evidence, producerOnly);
        JSONObject watermark = checkWatermark(output, evidence, producerOnly);
        JSONObject report = new JSONObject().put("passed", !producerOnly).put("producer_passed", true)
                .put("formatter_verified", !producerOnly).put("results", evidence)
                .put("positive_controls", passes).put("negative_controls", failures)
                .put("positive_cases", positives.size()).put("checker_errors", 1)
                .put("decoder_schema_guards", guards)
                .put("baseline_decoder_schema_guards", baselineGuards).put("intro", intro).put("text", text)
                .put("title", title)
                .put("watermark", watermark)
                .put("limitations", "Real OracleVerifier.verify and complete compiled SelfTestRunner source; "
                        + "filesystem Context/AssetManager and ffmpeg decoder substitute only. "
                        + "No Android codecs, exports, UI, lifecycle or instrumentation exercised. "
                        + "ERROR is a deliberate expected-image fault through the real core verifier. "
                        + "export_codecs is explicitly local test metadata, not an export claim.");
        String filename = producerOnly ? "producer-results.json" : "results.json";
        Files.write(new File(output, filename).toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
        System.out.println((producerOnly ? "PASS producer-only (formatter NOT TESTED): "
                : "PASS schema regression: ") + "12 base cases, 17 PASS, 19 FAIL, 1 ERROR; "
                + "intro 1 PASS/4 FAIL; text 1 PASS/3 FAIL; title 1 PASS/7 FAIL; watermark 1 PASS/3 FAIL; defaults 17 exports/60 controls; " + guards + " guards");
    }

    private static JSONObject checkIntro(File output, JSONArray evidence, boolean producerOnly) throws Exception {
        Field namesField = SelfTestRunner.class.getDeclaredField("CASE_NAMES");
        Field countField = SelfTestRunner.class.getDeclaredField("CONTROL_COUNT");
        namesField.setAccessible(true);
        countField.setAccessible(true);
        String[] names = (String[]) namesField.get(null);
        require(Arrays.equals(names, new String[]{"identity", "crop", "rotate90", "rotate180", "rotate270",
                "trim", "resize", "mute", "volume25", "speed2", "speed_half", "combo",
                 "music_loop", IntroOracleContract.CASE_ID, TextOracleContract.CASE_ID, TitleOracleContract.CASE_ID,
                 WatermarkOracleContract.CASE_ID}),
                "Default CASE_NAMES must retain all original 16 exports followed by PNG watermark");
        require(countField.getInt(null) == 60, "Default CONTROL_COUNT must be original 56 + 4 watermark controls");
        File assets = new File("app\\src\\main\\assets");
        File files = new File(output, "intro-files");
        require(files.isDirectory() || files.mkdirs(), "Cannot create intro filesDir");
        OracleVerifier verifier = new OracleVerifier(new Context(assets, new File(files, ".")),
                IntroOracleContract.create());
        for (OracleContract.Asset asset : IntroOracleContract.ASSETS) {
            File prepared = verifier.prepareIntroAsset(asset);
            require(prepared.equals(new File(new File(files.getCanonicalFile(),
                    IntroOracleContract.ASSET_DIRECTORY), asset.path)), "Noncanonical supplement path");
            checkAsset(prepared, asset);
        }
        File introFile = verifier.prepareIntroAsset(IntroOracleContract.INTRO);
        byte[] damaged = Files.readAllBytes(introFile.toPath());
        damaged[0] ^= 1;
        Files.write(introFile.toPath(), damaged);
        checkAsset(verifier.prepareIntroAsset(IntroOracleContract.INTRO), IntroOracleContract.INTRO);
        for (OracleContract.Asset unknown : new OracleContract.Asset[]{null,
                new OracleContract.Asset(IntroOracleContract.INTRO.path, IntroOracleContract.INTRO.bytes,
                        IntroOracleContract.INTRO.sha256),
                new OracleContract.Asset("..\\escape.mp4", 0, "")}) {
            try {
                verifier.prepareIntroAsset(unknown);
                throw new AssertionError("Unknown intro asset accepted");
            } catch (IOException expected) {
                require("Unknown supplementary intro asset".equals(expected.getMessage()),
                        "Wrong unknown-asset rejection: " + expected);
            }
        }
        File badAssets = new File(output, "intro-bad-assets");
        File badSource = new File(new File(badAssets, IntroOracleContract.ASSET_DIRECTORY),
                IntroOracleContract.INTRO.path);
        File badFiles = new File(output, "intro-bad-files");
        File badOutput = new File(new File(badFiles, IntroOracleContract.ASSET_DIRECTORY),
                IntroOracleContract.INTRO.path);
        require(badSource.getParentFile().isDirectory() || badSource.getParentFile().mkdirs(),
                "Cannot create corrupt asset fixture");
        try {
            Files.write(badSource.toPath(), damaged);
            OracleVerifier badVerifier = new OracleVerifier(new Context(badAssets, badFiles),
                    IntroOracleContract.create());
            try {
                badVerifier.prepareIntroAsset(IntroOracleContract.INTRO);
                throw new AssertionError("Same-length corrupt packaged intro accepted");
            } catch (IOException expected) {
                require(expected.getMessage().startsWith("Supplement asset integrity mismatch:"),
                        "Wrong integrity rejection: " + expected);
            }
            require(!badOutput.exists(), "Incomplete supplement output retained");
        } finally {
            Files.deleteIfExists(badSource.toPath());
            Files.deleteIfExists(badSource.getParentFile().toPath());
            Files.deleteIfExists(badAssets.toPath());
            Files.deleteIfExists(badOutput.toPath());
            Files.deleteIfExists(badOutput.getParentFile().toPath());
            Files.deleteIfExists(badFiles.toPath());
        }
        JSONObject manifest = read(verifier.prepareIntroAsset(IntroOracleContract.MANIFEST));
        require(IntroOracleContract.VERSION.equals(manifest.getString("version"))
                && IntroOracleContract.CASE_ID.equals(manifest.getString("case")), "Wrong intro manifest");
        Method referenceMethod = SelfTestRunner.class.getDeclaredMethod("introReference");
        referenceMethod.setAccessible(true);
        // Match the JSON-loaded base references, including numeric representation.
        JSONObject reference = new JSONObject(referenceMethod.invoke(null).toString());
        int startGuards = guards;
        int passes = 0;
        int failures = 0;
        for (IntroOracleContract.Control control : IntroOracleContract.CONTROLS) {
            String status = control.expectedPass ? "PASS" : "FAIL";
            require(status.equals(manifest.getJSONObject("controls").getJSONObject(control.asset.path)
                    .getString("expected_result")), "Manifest/control disagreement: " + control.id);
            JSONObject result = verifier.verify(control.caseId, verifier.prepareIntroAsset(control.asset));
            require(status.equals(result.getString("status"))
                    && result.getBoolean("passed") == control.expectedPass, control.id + ": wrong status");
            require(control.asset.sha256.equals(result.getString("candidate_sha256")),
                    control.id + ": wrong candidate hash");
            Set<String> failed = new HashSet<>();
            JSONArray assertions = result.getJSONArray("failed_assertions");
            for (int i = 0; i < assertions.length(); i++) failed.add(assertions.getString(i));
            require(control.expectedPass ? failed.isEmpty() : !failed.isEmpty(),
                    control.id + ": wrong failure evidence");
            require(failed.containsAll(control.requiredFailures), control.id + ": required failures missing: " + failed);
            JSONObject supplement = result.getJSONObject("pins").getJSONObject("supplement");
            require(supplement.similar(new JSONObject().put("version", IntroOracleContract.VERSION)
                    .put("contract_sha256", IntroOracleContract.CONTRACT_SHA256)
                    .put("assets", new JSONObject(IntroOracleContract.ASSET_HASHES))),
                    control.id + ": wrong supplement pins");
            require(IntroOracleContract.VERSION.equals(result.getString("contract_version")),
                    control.id + ": wrong contract version");
            result.put("reference", reference).put("export_codecs", "LOCAL CONTROL: no production export performed");
            String text = producerOnly ? checkProducer(result) : checkFormat(result);
            evidence.put(new JSONObject().put("id", control.id).put("result", result).put("formatted", text));
            if (control.expectedPass) passes++; else failures++;
            System.out.println("PASS " + control.id + " -> " + result.getString("status"));
        }
        require(passes == 1 && failures == 4, "Expected intro positive and four negatives");
        require(producerOnly || guards - startGuards == 35, "Expected 35 additional intro schema guards");
        return new JSONObject().put("positive_controls", passes).put("negative_controls", failures)
                .put("decoder_schema_guards", guards - startGuards).put("supplement_pins_verified", true)
                .put("planned_export_count", names.length).put("planned_control_count", countField.getInt(null))
                .put("loader_checks", "All seven pinned assets; canonical filesDir; corrupt cache repaired; "
                        + "null/forged/traversal assets rejected; same-length corrupt source rejected and removed");
    }

    private static JSONObject checkText(File output, JSONArray evidence, boolean producerOnly) throws Exception {
        OracleVerifier verifier = new OracleVerifier(new Context(new File("app\\src\\main\\assets"),
                new File(output, "text-files")), TextOracleContract.create());
        verifier.prepareFixture();
        for (OracleContract.Asset asset : TextOracleContract.ASSETS) checkAsset(verifier.prepareTextAsset(asset), asset);
        File cached = verifier.prepareTextAsset(TextOracleContract.REFERENCE);
        byte[] damaged = Files.readAllBytes(cached.toPath());
        damaged[0] ^= 1;
        Files.write(cached.toPath(), damaged);
        checkAsset(verifier.prepareTextAsset(TextOracleContract.REFERENCE), TextOracleContract.REFERENCE);
        for (OracleContract.Asset unknown : new OracleContract.Asset[]{null,
                new OracleContract.Asset("..\\escape.mp4", 0, ""),
                new OracleContract.Asset(TextOracleContract.REFERENCE.path,
                        TextOracleContract.REFERENCE.bytes, TextOracleContract.REFERENCE.sha256)}) {
            try {
                verifier.prepareTextAsset(unknown);
                throw new AssertionError("Unknown text asset accepted");
            } catch (IOException expected) {
                require("Unknown text asset".equals(expected.getMessage()), "Wrong text loader rejection");
            }
        }
        Method method = SelfTestRunner.class.getDeclaredMethod("textReference");
        method.setAccessible(true);
        JSONObject reference = new JSONObject(method.invoke(null).toString());
        require(reference.getJSONObject("expected_edit_config").getString("overlayText")
                .equals(TextOracleContract.TEXT), "Wrong production text config");
        int startGuards = guards;
        for (TextOracleContract.Control control : TextOracleContract.CONTROLS) {
            JSONObject result = verifier.verify(TextOracleContract.CASE_ID, verifier.prepareTextAsset(control.asset));
            String expected = control.expectedPass ? "PASS" : "FAIL";
            require(expected.equals(result.getString("status")), control.id + ": " + result.get("failed_assertions"));
            require(control.asset.sha256.equals(result.getString("candidate_sha256")), "Wrong text candidate hash");
            JSONArray failed = result.getJSONArray("failed_assertions");
            Set<String> failures = new HashSet<>();
            for (int i = 0; i < failed.length(); i++) failures.add(failed.getString(i));
            require(failures.containsAll(control.requiredFailures), control.id + ": required content failures missing");
            JSONObject supplement = result.getJSONObject("pins").getJSONObject("supplement");
            require(supplement.similar(new JSONObject().put("version", TextOracleContract.VERSION)
                    .put("contract_sha256", TextOracleContract.MANIFEST.sha256)
                    .put("assets", new JSONObject(TextOracleContract.ASSET_HASHES))), "Wrong text pins");
            JSONObject local = read(new File("app\\build\\text-parity", control.id + "-sparse.json"));
            for (String key : new String[]{"status", "checks", "metrics", "failed_assertions"}) {
                require(new JSONObject(new JSONObject().put(key, local.get(key)).toString()).similar(
                        new JSONObject(new JSONObject().put(key, result.get(key)).toString())),
                        "Text pure-core/producer mismatch " + key);
            }
            result.put("reference", reference).put("export_codecs", "LOCAL CONTROL: no production export performed");
            String formatted = producerOnly ? checkProducer(result) : checkFormat(result);
            evidence.put(new JSONObject().put("id", control.id).put("result", result).put("formatted", formatted));
            System.out.println("PASS " + control.id + " -> " + result.getString("status"));
        }
        require(producerOnly || guards - startGuards == 28, "All 28 new text schema guards must run");
        return new JSONObject().put("positive_controls", 1).put("negative_controls", 3)
                .put("decoder_schema_guards", guards - startGuards).put("supplement_pins_verified", true);
    }

    private static JSONObject checkTitle(File output, JSONArray evidence, boolean producerOnly) throws Exception {
        OracleVerifier verifier = new OracleVerifier(new Context(new File("app\\src\\main\\assets"),
                new File(output, "title-files")), TitleOracleContract.create());
        verifier.prepareFixture();
        for (OracleContract.Asset asset : TitleOracleContract.ASSETS) checkAsset(verifier.prepareTitleAsset(asset), asset);
        Method method = SelfTestRunner.class.getDeclaredMethod("titleReference");
        method.setAccessible(true);
        JSONObject reference = new JSONObject(method.invoke(null).toString());
        require(reference.getJSONObject("expected_edit_config").getInt("introTitleSizeSp") == 48,
                "Title must preserve production sp sizing");
        require(reference.getJSONObject("expected_edit_config").getString("introTitleStyle").equals("normal"),
                "Wrong title style");
        int startGuards = guards;
        for (TitleOracleContract.Control control : TitleOracleContract.CONTROLS) {
            JSONObject result = verifier.verify(TitleOracleContract.CASE_ID, verifier.prepareTitleAsset(control.asset));
            require((control.expectedPass ? "PASS" : "FAIL").equals(result.getString("status")),
                    control.id + ": " + result.get("failed_assertions"));
            require(control.asset.sha256.equals(result.getString("candidate_sha256")), "Title hash mismatch");
            Set<String> failures = new HashSet<>();
            JSONArray failed = result.getJSONArray("failed_assertions");
            for (int i = 0; i < failed.length(); i++) failures.add(failed.getString(i));
            require(failures.containsAll(control.requiredFailures), "Title required failures missing");
            JSONObject supplement = result.getJSONObject("pins").getJSONObject("supplement");
            require(supplement.getString("contract_sha256").equals(TitleOracleContract.MANIFEST.sha256)
                    && supplement.getJSONObject("assets").similar(new JSONObject(TitleOracleContract.ASSET_HASHES)),
                    "Wrong title pins");
            JSONObject local = read(new File("app\\build\\title-parity", control.id + "-sparse.json"));
            for (String key : new String[]{"status", "checks", "metrics", "failed_assertions"}) {
                require(new JSONObject(new JSONObject().put(key, local.get(key)).toString()).similar(
                        new JSONObject(new JSONObject().put(key, result.get(key)).toString())),
                        "Title pure-core/producer mismatch " + key);
            }
            result.put("reference", reference).put("export_codecs", "LOCAL CONTROL: no production export performed");
            String formatted = producerOnly ? checkProducer(result) : checkFormat(result);
            evidence.put(new JSONObject().put("id", control.id).put("result", result).put("formatted", formatted));
        }
        require(producerOnly || guards - startGuards == 56, "All 56 title schema guards must run");
        return new JSONObject().put("positive_controls", 1).put("negative_controls", 7)
                .put("decoder_schema_guards", guards - startGuards).put("supplement_pins_verified", true);
    }

    private static JSONObject checkWatermark(File output, JSONArray evidence, boolean producerOnly) throws Exception {
        OracleVerifier verifier = new OracleVerifier(new Context(new File("app\\src\\main\\assets"),
                new File(output, "watermark-files")), WatermarkOracleContract.create());
        verifier.prepareFixture();
        for (OracleContract.Asset asset : WatermarkOracleContract.ASSETS) {
            checkAsset(verifier.prepareWatermarkAsset(asset), asset);
        }
        File cached = verifier.prepareWatermarkAsset(WatermarkOracleContract.PNG);
        byte[] damaged = Files.readAllBytes(cached.toPath());
        damaged[0] ^= 1;
        Files.write(cached.toPath(), damaged);
        checkAsset(verifier.prepareWatermarkAsset(WatermarkOracleContract.PNG), WatermarkOracleContract.PNG);
        for (OracleContract.Asset asset : new OracleContract.Asset[]{null,
                new OracleContract.Asset("..\\escape.png", 0, ""),
                new OracleContract.Asset(WatermarkOracleContract.PNG.path,
                        WatermarkOracleContract.PNG.bytes, WatermarkOracleContract.PNG.sha256)}) {
            try {
                verifier.prepareWatermarkAsset(asset);
                throw new AssertionError("Untrusted watermark asset accepted");
            } catch (IOException expected) {
                require("Unknown watermark asset".equals(expected.getMessage()), "Wrong asset rejection");
            }
        }
        Method method = SelfTestRunner.class.getDeclaredMethod("watermarkReference");
        method.setAccessible(true);
        JSONObject reference = new JSONObject(method.invoke(null).toString());
        JSONObject config = reference.getJSONObject("expected_edit_config");
        require(config.getDouble("watermarkWidthFraction") == .2 && config.getDouble("watermarkX") == .75
                && config.getDouble("watermarkY") == .75
                && config.getString("watermarkSha256").equals(WatermarkOracleContract.PNG.sha256),
                "Wrong fixed PNG edit configuration");
        require(reference.getJSONArray("expected_rect_xyxy").similar(new JSONArray(Arrays.asList(192, 156, 256, 188))),
                "Wrong watermark free-space anchor geometry");
        int startGuards = guards;
        for (WatermarkOracleContract.Control control : WatermarkOracleContract.CONTROLS) {
            JSONObject result = verifier.verify(WatermarkOracleContract.CASE_ID,
                    verifier.prepareWatermarkAsset(control.asset));
            require(SelfTestRunner.watermarkControlMatches(result, control),
                    control.id + ": " + result.get("failed_assertions"));
            JSONObject supplement = result.getJSONObject("pins").getJSONObject("supplement");
            require(supplement.getString("contract_sha256").equals(WatermarkOracleContract.MANIFEST.sha256)
                    && supplement.getJSONObject("assets").similar(new JSONObject(WatermarkOracleContract.ASSET_HASHES))
                    && new JSONObject(supplement.getJSONObject("expected_edit_config").toString()).similar(config),
                    "Wrong watermark pins");
            JSONObject local = read(new File("app\\build\\watermark-parity", control.id + "-sparse.json"));
            for (String key : new String[]{"status", "checks", "metrics", "failed_assertions"}) {
                require(new JSONObject(new JSONObject().put(key, local.get(key)).toString()).similar(
                        new JSONObject(new JSONObject().put(key, result.get(key)).toString())),
                        "Watermark pure-core/producer mismatch " + key);
            }
            if (!control.expectedPass) {
                JSONObject fault = new JSONObject(result.toString()).put("status", "ERROR");
                require(!SelfTestRunner.watermarkControlMatches(fault, control), "ERROR counted as negative success");
                fault.put("status", "FAIL").getJSONArray("checks").put(new JSONObject()
                        .put("assertion", "checker.completed_without_error").put("passed", false));
                require(!SelfTestRunner.watermarkControlMatches(fault, control), "Checker fault counted as negative success");
            }
            result.put("reference", reference).put("export_codecs", "LOCAL CONTROL: no production export performed");
            String formatted = producerOnly ? checkProducer(result) : checkFormat(result);
            evidence.put(new JSONObject().put("id", control.id).put("result", result).put("formatted", formatted));
        }
        require(producerOnly || guards - startGuards == 28, "All 28 watermark schema guards must run");
        return new JSONObject().put("positive_controls", 1).put("negative_controls", 3)
                .put("decoder_schema_guards", guards - startGuards).put("supplement_pins_verified", true);
    }

    private static void checkAsset(File file, OracleContract.Asset asset) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        require(bytes.length == asset.bytes && asset.sha256.equals(hash.toString()), "Wrong asset: " + asset.path);
    }

    private static void enrich(JSONObject result, JSONArray cases) throws JSONException {
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.getJSONObject(i);
            if (item.getString("id").equals(result.getString("case"))) {
                result.put("reference", item.getJSONObject("reference"));
                result.put("export_codecs", "LOCAL CONTROL: no production export performed");
                return;
            }
        }
        throw new AssertionError("Unknown produced case");
    }

    private static String checkProducer(JSONObject result) throws JSONException {
        require(result.getString("schema").equals("independent-video-oracle-android-report"),
                "Must test real Android wrapper schema");
        require(result.has("decoder") && !result.has("decode"), "Unexpected producer decoder key");
        require(result.getJSONObject("decoder").getJSONObject("video").getBoolean("present"),
                "Missing decoder diagnostics");
        return "FORMATTER NOT TESTED";
    }

    private static String checkFormat(JSONObject result) throws Exception {
        checkProducer(result);
        JSONObject snapshot = new JSONObject(result.toString());
        String text = format(result);
        require(text.startsWith(result.getString("status") + " " + result.getString("case") + "\n"),
                "Formatter converted status or case");
        require(text.contains(result.getString("export_codecs")), "Lost export codec details");
        require(text.contains("OUTPUT SHA256 " + result.getString("candidate_sha256")), "Lost candidate hash");
        require(lineObject(text, "REFERENCE ").similar(result.getJSONObject("reference")), "Lost reference");
        require(lineObject(text, "DECODER ").similar(result.getJSONObject("decoder")), "Lost decoder details");
        require(text.endsWith("Full measurements: " + result.getString("case") + ".json\n"),
                "Wrong measurements filename");
        JSONArray checks = result.getJSONArray("checks");
        int shown = 0;
        int omitted = 0;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            if (shown >= 16 || (check.getBoolean("passed") && i >= 12)) {
                omitted++;
                continue;
            }
            shown++;
            require(text.contains((check.getBoolean("passed") ? "PASS " : "FAIL ")
                    + check.getString("assertion") + ": expected=" + check.opt("expected")
                    + ", actual=" + check.opt("actual") + "\n"), "Lost assertion summary evidence");
        }
        if (omitted > 0) require(text.contains(omitted
                + " additional measurements retained in the JSON report.\n"), "Lost omitted count");
        require(snapshot.similar(new JSONObject(result.toString())), "Formatter mutated producer evidence");
        JSONObject missing = new JSONObject(result.toString());
        missing.remove("decoder");
        reject(missing);
        missing.put("decode", result.getJSONObject("decoder"));
        reject(missing);
        for (Object wrong : new Object[]{JSONObject.NULL, "decoder", 7, true, new JSONArray()}) {
            JSONObject malformed = new JSONObject(result.toString()).put("decoder", wrong);
            reject(malformed);
        }
        return text;
    }

    private static JSONObject lineObject(String text, String prefix) throws JSONException {
        int start = text.indexOf("\n" + prefix);
        require(start >= 0, "Missing " + prefix);
        start += prefix.length() + 1;
        int end = text.indexOf('\n', start);
        require(end >= 0, "Missing line ending for " + prefix);
        return new JSONObject(text.substring(start, end));
    }

    private static String format(JSONObject result) throws Exception {
        // Lazy lookup permits explicit producer-only checks while the parent extraction is pending.
        Method formatter = SelfTestRunner.class.getDeclaredMethod("formatCaseResult", JSONObject.class);
        try {
            return (String) formatter.invoke(null, result);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            if (error.getCause() instanceof Error) throw (Error) error.getCause();
            throw new AssertionError(error.getCause());
        }
    }

    private static void reject(JSONObject malformed) throws Exception {
        try {
            format(malformed);
        } catch (JSONException expected) {
            guards++;
            return;
        }
        throw new AssertionError("Missing/wrong-type decoder must throw JSONException");
    }

    private static JSONObject read(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
