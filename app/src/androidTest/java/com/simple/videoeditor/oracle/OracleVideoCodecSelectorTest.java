package com.simple.videoeditor.oracle;

import android.media.MediaFormat;
import android.test.InstrumentationTestCase;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class OracleVideoCodecSelectorTest extends InstrumentationTestCase {
    private static final long TIMEOUT_MS = 5000;
    private static final String MIME = "video/avc";
    private static final int WIDTH = 80;
    private static final int HEIGHT = 120;

    @Override protected void runTest() throws Throwable {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    runTestBody();
                } catch (Throwable error) {
                    failure.set(error);
                }
            }
        }, "oracle-codec-selector-test");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(TIMEOUT_MS);
            assertFalse("Selector test exceeded " + TIMEOUT_MS + " ms", worker.isAlive());
        } finally {
            if (worker.isAlive()) worker.interrupt();
        }
        if (failure.get() != null) throw failure.get();
    }

    private void runTestBody() throws Throwable {
        super.runTest();
    }

    public void testUnsupportedFirstSkippedThenSupportedSelected() throws Exception {
        Fixture f = new Fixture();
        Handle selected = f.select(candidate("unsupported", false, false),
                candidate("hardware", false, true));
        assertEquals("hardware", selected.name);
        assertSame(selected, f.factory.created.get(0));
        assertEquals(1, f.factory.created.size());
        assertEquals(0, selected.releases);
        f.assertEvents("checkpoint:candidates_enumerated",
                "checkpoint:candidate_ineligible:unsupported",
                "checkpoint:before_create:hardware", "create:hardware",
                "checkpoint:before_configure:hardware", "configure:hardware",
                "checkpoint:configured:hardware");
    }

    public void testConfigureIllegalArgumentReleasesRetriesAndAttemptsSoftware() throws Exception {
        Fixture f = new Fixture();
        f.factory.configureFailures.put("hardware-one", new IllegalArgumentException("bad profile"));
        f.factory.configureFailures.put("hardware-two", new IllegalArgumentException("bad color"));
        Handle selected = f.select(candidate("hardware-one", false, true),
                candidate("hardware-two", false, true), candidate("software", true, true));
        assertEquals("software", selected.name);
        assertEquals(3, f.factory.created.size());
        assertEquals(1, f.factory.created.get(0).releases);
        assertEquals(1, f.factory.created.get(1).releases);
        assertEquals(0, selected.releases);
        assertEquals(Boolean.TRUE, f.checkpoint("before_configure", "software")
                .get("software_fallback"));
        f.assertEvents("checkpoint:candidates_enumerated",
                "checkpoint:before_create:hardware-one", "create:hardware-one",
                "checkpoint:before_configure:hardware-one", "configure:hardware-one",
                "checkpoint:configure_failed:hardware-one", "release:hardware-one",
                "checkpoint:released:hardware-one",
                "checkpoint:before_create:hardware-two", "create:hardware-two",
                "checkpoint:before_configure:hardware-two", "configure:hardware-two",
                "checkpoint:configure_failed:hardware-two", "release:hardware-two",
                "checkpoint:released:hardware-two",
                "checkpoint:before_create:software", "create:software",
                "checkpoint:before_configure:software", "configure:software",
                "checkpoint:configured:software");
    }

    public void testSuppliedCandidateOrderIsPreserved() throws Exception {
        Fixture f = new Fixture();
        Handle selected = f.select(candidate("software-first", true, true),
                candidate("hardware-later", false, true));
        assertEquals("software-first", selected.name);
        f.assertEvents("checkpoint:candidates_enumerated",
                "checkpoint:before_create:software-first", "create:software-first",
                "checkpoint:before_configure:software-first", "configure:software-first",
                "checkpoint:configured:software-first");
    }

    public void testAllFailuresThrowContextualIOExceptionWithSuppressedCauses() throws Exception {
        Fixture f = new Fixture();
        IOException createError = new IOException("create unavailable");
        IllegalArgumentException configureError = new IllegalArgumentException("format rejected");
        f.factory.createFailures.put("hardware", createError);
        f.factory.configureFailures.put("software", configureError);
        try {
            f.select(candidate("unsupported", false, false),
                    candidate("hardware", false, true), candidate("software", true, true));
            fail("Exhaustion must throw IOException");
        } catch (IOException expected) {
            assertContains(expected.getMessage(), "exhausted", MIME,
                    String.valueOf(WIDTH), String.valueOf(HEIGHT),
                    "unsupported", "hardware", "software");
            Throwable[] suppressed = expected.getSuppressed();
            assertEquals(2, suppressed.length);
            assertTrue(suppressed[0] instanceof IOException);
            assertTrue(suppressed[1] instanceof IOException);
            assertSame(createError, suppressed[0].getCause());
            assertSame(configureError, suppressed[1].getCause());
            assertContains(suppressed[0].getMessage(), "hardware", "create", "create unavailable");
            assertContains(suppressed[1].getMessage(), "software", "configure", "format rejected");
        }
        assertEquals(1, f.factory.created.size());
        assertEquals(1, f.factory.created.get(0).releases);
        f.assertEvents("checkpoint:candidates_enumerated",
                "checkpoint:candidate_ineligible:unsupported",
                "checkpoint:before_create:hardware", "create:hardware",
                "checkpoint:create_failed:hardware",
                "checkpoint:before_create:software", "create:software",
                "checkpoint:before_configure:software", "configure:software",
                "checkpoint:configure_failed:software", "release:software",
                "checkpoint:released:software", "checkpoint:exhausted:software");
    }

    public void testConfigureNullPointerExceptionReleasesAndDoesNotRetry() throws Exception {
        assertUnrelatedConfigureFailure(new NullPointerException("unrelated null"));
    }

    public void testNoSupportedCandidatesThrowsWithoutCreatingCodec() throws Exception {
        Fixture f = new Fixture();
        try {
            f.select(candidate("unsupported-hardware", false, false),
                    candidate("unsupported-software", true, false));
            fail("Unsupported formats must not be configured");
        } catch (IOException expected) {
            assertContains(expected.getMessage(), "exhausted", "unsupported-hardware",
                    "unsupported-software");
            assertEquals(0, expected.getSuppressed().length);
        }
        assertTrue(f.factory.created.isEmpty());
        f.assertEvents("checkpoint:candidates_enumerated",
                "checkpoint:candidate_ineligible:unsupported-hardware",
                "checkpoint:candidate_ineligible:unsupported-software",
                "checkpoint:exhausted:unsupported-software");
    }

    public void testConfigureIllegalStateExceptionReleasesAndDoesNotRetry() throws Exception {
        assertUnrelatedConfigureFailure(new IllegalStateException("unrelated state"));
    }

    private void assertUnrelatedConfigureFailure(RuntimeException error) throws Exception {
        Fixture f = new Fixture();
        f.factory.configureFailures.put("hardware", error);
        try {
            f.select(candidate("hardware", false, true), candidate("software", true, true));
            fail("Unrelated runtime failure must escape unchanged");
        } catch (RuntimeException expected) {
            assertSame(error, expected);
            assertEquals(0, expected.getSuppressed().length);
        }
        assertEquals(1, f.factory.created.size());
        assertEquals(1, f.factory.created.get(0).releases);
        f.assertEvents("checkpoint:candidates_enumerated",
                "checkpoint:before_create:hardware", "create:hardware",
                "checkpoint:before_configure:hardware", "configure:hardware",
                "release:hardware", "checkpoint:released:hardware");
    }

    public void testObserverIOExceptionBeforeConfigureReleasesAndAborts() throws Exception {
        Fixture f = new Fixture();
        IOException observerError = new IOException("checkpoint persistence failed");
        f.observerFailure = observerError;
        try {
            f.select(candidate("hardware", false, true), candidate("software", true, true));
            fail("Observer failure must abort selection");
        } catch (IOException expected) {
            assertSame(observerError, expected);
            assertEquals(0, expected.getSuppressed().length);
        }
        assertEquals(1, f.factory.created.size());
        assertEquals(1, f.factory.created.get(0).releases);
        f.assertEvents("checkpoint:candidates_enumerated",
                "checkpoint:before_create:hardware", "create:hardware",
                "checkpoint:before_configure:hardware", "release:hardware",
                "checkpoint:released:hardware");
    }

    public void testBeforeConfigureEvidenceIsCompleteImmutableAndHistorical() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> capability = capability(true);
        OracleVideoCodecSelector.Candidate software = new OracleVideoCodecSelector.Candidate(
                "software", true, true, capability);
        capability.put("size_supported", false);
        IllegalArgumentException previous = new IllegalArgumentException("first rejected");
        f.factory.configureFailures.put("hardware", previous);
        f.select(candidate("unsupported", false, false), candidate("hardware", false, true),
                software, candidate("not-yet-attempted", true, true));

        final Map<String, Object> first = f.checkpoint("before_configure", "hardware");
        final Map<String, Object> retry = f.checkpoint("before_configure", "software");
        assertEquals("before_configure", first.get("stage"));
        assertEquals("hardware", first.get("decoder_name"));
        assertEquals(Boolean.FALSE, first.get("software_fallback"));
        assertTrue(failures(first).isEmpty());
        assertTrue(failures(f.snapshots.get(0)).isEmpty());
        assertEquals("before_configure", retry.get("stage"));
        assertEquals("software", retry.get("decoder_name"));
        assertEquals(Boolean.TRUE, retry.get("software_fallback"));
        assertEquals(MIME, retry.get("mime"));
        assertEquals(WIDTH, retry.get("width"));
        assertEquals(HEIGHT, retry.get("height"));
        assertEquals(f.format.toString(), retry.get("configure_format"));

        final List<Map<String, Object>> all = candidates(retry);
        assertEquals(4, all.size());
        String[] names = {"unsupported", "hardware", "software", "not-yet-attempted"};
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], all.get(i).get("name"));
            assertEquals(i >= 2, all.get(i).get("software"));
            assertEquals(i != 0, all.get(i).get("eligible_attempt"));
            assertEquals(capability(i != 0), all.get(i).get("capability"));
        }
        assertEquals(capability(true), retry.get("capability"));
        final List<Map<String, Object>> history = failures(retry);
        assertEquals(1, history.size());
        assertEquals("hardware", history.get(0).get("name"));
        assertEquals("configure", history.get(0).get("operation"));
        assertEquals(previous.toString(), history.get(0).get("error"));

        assertImmutableMap(retry);
        assertImmutableList(all);
        for (Map<String, Object> item : all) {
            assertImmutableMap(item);
            assertImmutableMap(map(item.get("capability")));
            assertImmutableList((List<?>) map(item.get("capability")).get("color_formats"));
        }
        assertImmutableMap(map(retry.get("capability")));
        assertImmutableList(history);
        assertImmutableMap(history.get(0));
        assertImmutableList(failures(first));
        assertEquals("before_configure", retry.get("stage"));
        assertEquals("configured", f.snapshots.get(f.snapshots.size() - 1).get("stage"));
    }

    public void testFactoryCreateIOExceptionRetriesWithoutReleasingUncreatedCodec() throws Exception {
        Fixture f = new Fixture();
        IOException failure = new IOException("create failed");
        f.factory.createFailures.put("hardware", failure);
        Handle selected = f.select(candidate("hardware", false, true),
                candidate("software", true, true));
        assertEquals("software", selected.name);
        assertEquals(1, f.factory.created.size());
        assertEquals(0, selected.releases);
        Map<String, Object> previous = failures(f.checkpoint("before_configure", "software")).get(0);
        assertEquals("hardware", previous.get("name"));
        assertEquals("create", previous.get("operation"));
        assertEquals(failure.toString(), previous.get("error"));
        f.assertEvents("checkpoint:candidates_enumerated",
                "checkpoint:before_create:hardware", "create:hardware",
                "checkpoint:create_failed:hardware",
                "checkpoint:before_create:software", "create:software",
                "checkpoint:before_configure:software", "configure:software",
                "checkpoint:configured:software");
    }

    public void testExpiredDeadlineAbortsBeforeCreate() throws Exception {
        Fixture f = new Fixture();
        try {
            OracleVideoCodecSelector.configure(Arrays.asList(candidate("hardware", false, true)),
                    f.format, f.factory, f, System.nanoTime() - 1);
            fail("Expired deadline must abort");
        } catch (IOException expected) {
            assertContains(expected.getMessage(), "timed out");
        }
        assertTrue(f.factory.created.isEmpty());
        f.assertEvents("checkpoint:candidates_enumerated");
    }

    public void testInterruptionAfterCreateReleasesAndAbortsBeforeConfigure() throws Exception {
        Fixture f = new Fixture();
        f.factory.interruptAfterCreate = true;
        try {
            f.select(candidate("hardware", false, true), candidate("software", true, true));
            fail("Interruption must abort");
        } catch (IOException expected) {
            assertContains(expected.getMessage(), "interrupted");
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, f.factory.created.size());
        assertEquals(1, f.factory.created.get(0).releases);
        f.assertEvents("checkpoint:candidates_enumerated",
                "checkpoint:before_create:hardware", "create:hardware",
                "release:hardware", "checkpoint:released:hardware");
    }

    public void testLevelOnlySoftwareAttemptKeepsOriginalFormatAndHonestEvidence() throws Exception {
        Fixture f = new Fixture(titleFormat());
        String original = f.format.toString();
        List<MediaFormat> queries = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        boolean eligible = OracleVideoCodecSelector.eligibleAttempt(f.format, true, true, query -> {
            queries.add(query);
            return !query.containsKey(MediaFormat.KEY_LEVEL);
        }, evidence);
        assertTrue(eligible);
        assertEquals(2, queries.size());
        assertSame(f.format, queries.get(0));
        assertNotSame(f.format, queries.get(1));
        assertEquals(original, f.format.toString());
        assertEquals(Boolean.FALSE, evidence.get("format_supported"));
        assertEquals(Boolean.TRUE, evidence.get("level_query_performed"));
        assertEquals(Boolean.TRUE, evidence.get("format_without_level_supported"));
        assertEquals(Boolean.TRUE, evidence.get("level_advisory_fallback"));
        assertEquals(524288, evidence.get("declared_level"));
        assertEquals(queries.get(1).toString(), evidence.get("format_without_level"));
        OracleVideoCodecSelector.Candidate software =
                new OracleVideoCodecSelector.Candidate("software", true, eligible, evidence);
        assertFalse(software.evidence().containsKey("supported"));
        assertEquals("software", f.select(software).name);
        assertEquals(1, f.factory.created.size());
        assertEquals(original, f.checkpoint("before_configure", "software").get("configure_format"));
        assertEquals(evidence, f.checkpoint("before_configure", "software").get("capability"));
        assertEquals(original, f.format.toString());
    }

    public void testLevelRejectedHardwareNeverGetsSecondaryQueryOrAttempt() throws Exception {
        Fixture f = new Fixture(titleFormat());
        Map<String, Object> evidence = new LinkedHashMap<>();
        int[] queries = {0};
        boolean eligible = OracleVideoCodecSelector.eligibleAttempt(f.format, false, true, query -> {
            queries[0]++;
            assertSame(f.format, query);
            return false;
        }, evidence);
        assertFalse(eligible);
        assertEquals(1, queries[0]);
        assertEquals(Boolean.FALSE, evidence.get("level_query_performed"));
        assertEquals(Boolean.FALSE, evidence.get("level_advisory_fallback"));
        assertEquals("software", f.select(new OracleVideoCodecSelector.Candidate(
                "hardware", false, eligible, evidence), candidate("software", true, true)).name);
        assertEquals(1, f.factory.created.size());
    }

    public void testMissingDeclaredLevelCannotEnableSoftwareAttempt() {
        MediaFormat original = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT);
        int[] queries = {0};
        Map<String, Object> evidence = new LinkedHashMap<>();
        assertFalse(OracleVideoCodecSelector.eligibleAttempt(original, true, true, query -> {
            queries[0]++;
            return false;
        }, evidence));
        assertEquals(1, queries[0]);
        assertEquals(Boolean.FALSE, evidence.get("level_query_performed"));
        assertFalse(evidence.containsKey("format_without_level_supported"));
    }

    public void testFullAdvertisedSupportDoesNotNeedLevelAdvisory() {
        for (boolean software : new boolean[]{false, true}) {
            int[] queries = {0};
            Map<String, Object> evidence = new LinkedHashMap<>();
            assertTrue(OracleVideoCodecSelector.eligibleAttempt(titleFormat(), software, true, query -> {
                queries[0]++;
                return true;
            }, evidence));
            assertEquals(1, queries[0]);
            assertEquals(Boolean.TRUE, evidence.get("format_supported"));
            assertEquals(Boolean.FALSE, evidence.get("level_query_performed"));
            assertEquals(Boolean.FALSE, evidence.get("level_advisory_fallback"));
        }
    }

    public void testSecondaryRejectionOfOtherConstraintsCannotEnableSoftwareAttempt() {
        String[] keys = {MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_FRAME_RATE,
                MediaFormat.KEY_PROFILE, MediaFormat.KEY_COLOR_FORMAT, MediaFormat.KEY_BIT_RATE,
                "max-bitrate", "feature-low-latency"};
        for (String rejectedKey : keys) {
            MediaFormat original = titleFormat();
            original.setInteger("feature-low-latency", 1);
            int[] queries = {0};
            Map<String, Object> evidence = new LinkedHashMap<>();
            assertFalse(rejectedKey, OracleVideoCodecSelector.eligibleAttempt(
                    original, true, true, query -> {
                        queries[0]++;
                        assertEquals(original.getInteger(rejectedKey), query.getInteger(rejectedKey));
                        return !query.containsKey(MediaFormat.KEY_LEVEL)
                                && query.getInteger(rejectedKey) != original.getInteger(rejectedKey);
                    }, evidence));
            assertEquals(2, queries[0]);
            assertEquals(Boolean.FALSE, evidence.get("format_without_level_supported"));
            assertEquals(Boolean.FALSE, evidence.get("level_advisory_fallback"));
        }
    }

    public void testBufferIncompatibleCandidateCannotUseLevelAdvisory() {
        for (boolean fullSupport : new boolean[]{false, true}) {
            int[] queries = {0};
            Map<String, Object> evidence = new LinkedHashMap<>();
            assertFalse(OracleVideoCodecSelector.eligibleAttempt(titleFormat(), true, false, query -> {
                queries[0]++;
                return fullSupport;
            }, evidence));
            assertEquals(1, queries[0]);
            assertEquals(Boolean.FALSE, evidence.get("level_query_performed"));
            assertEquals(Boolean.FALSE, evidence.get("level_advisory_fallback"));
        }
    }

    public void testCapabilityQueryCopiesConstraintsWithoutMutatingMetadataOrCsd() {
        MediaFormat original = titleFormat();
        String[] keys = {MediaFormat.KEY_PROFILE, MediaFormat.KEY_COLOR_FORMAT,
                MediaFormat.KEY_BIT_RATE, "max-bitrate", "color-standard", "color-range",
                "color-transfer", "feature-adaptive-playback", "feature-secure-playback",
                "feature-tunneled-playback", "feature-partial-frame", "feature-frame-parsing",
                "feature-multiple-frames", "feature-dynamic-timestamp", "feature-low-latency",
                "feature-dynamic-color-aspects", "feature-detached-surface"};
        for (String key : keys) if (!original.containsKey(key)) original.setInteger(key, 1);
        ByteBuffer csd = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        csd.position(1);
        original.setByteBuffer("csd-0", csd);
        original.setString("language", "und");
        String before = original.toString();
        MediaFormat query = OracleVideoCodecSelector.legacyCapabilityFormatWithoutLevel(original);
        assertNotSame(original, query);
        assertEquals(MIME, query.getString(MediaFormat.KEY_MIME));
        assertEquals(320, query.getInteger(MediaFormat.KEY_WIDTH));
        assertEquals(240, query.getInteger(MediaFormat.KEY_HEIGHT));
        assertEquals(25, query.getInteger(MediaFormat.KEY_FRAME_RATE));
        for (String key : keys) assertEquals(key, original.getInteger(key), query.getInteger(key));
        assertFalse(query.containsKey(MediaFormat.KEY_LEVEL));
        assertFalse(query.containsKey("csd-0"));
        assertEquals(before, original.toString());
        assertSame(csd, original.getByteBuffer("csd-0"));
        assertEquals(1, csd.position());
    }

    public void testCapabilityQueryPreservesFractionalFrameRate() {
        MediaFormat original = titleFormat();
        original.setFloat(MediaFormat.KEY_FRAME_RATE, 29.97f);
        MediaFormat query = OracleVideoCodecSelector.capabilityFormatWithoutLevel(original);
        assertEquals(29.97f, query.getFloat(MediaFormat.KEY_FRAME_RATE), 0f);
        MediaFormat legacyQuery = OracleVideoCodecSelector.legacyCapabilityFormatWithoutLevel(original);
        assertEquals(29.97f, legacyQuery.getFloat(MediaFormat.KEY_FRAME_RATE), 0f);
        assertEquals(29.97f, original.getFloat(MediaFormat.KEY_FRAME_RATE), 0f);
        assertEquals(524288, original.getInteger(MediaFormat.KEY_LEVEL));
    }

    public void testFeatureOnlyRejectionCannotBeMisreportedAsLevelAdvisory() {
        for (String key : new String[]{"feature-dynamic-color-aspects", "feature-detached-surface"}) {
            MediaFormat original = titleFormat();
            original.setInteger(MediaFormat.KEY_LEVEL, 1);
            original.setInteger(key, 1);
            Map<String, Object> evidence = new LinkedHashMap<>();
            int[] queries = {0};
            assertFalse(OracleVideoCodecSelector.eligibleAttempt(original, true, true, query -> {
                queries[0]++;
                return !query.containsKey(key) || query.getInteger(key) == 0;
            }, evidence));
            assertEquals(2, queries[0]);
            assertEquals(Boolean.FALSE, evidence.get("level_advisory_fallback"));
            assertEquals(Boolean.FALSE, evidence.get("format_without_level_supported"));
            assertEquals(1, OracleVideoCodecSelector.legacyCapabilityFormatWithoutLevel(original)
                    .getInteger(key));
        }
    }

    public void testSecondaryQueryUnexpectedRuntimeStillSurfacesWithoutFormatMutation() {
        MediaFormat original = titleFormat();
        String before = original.toString();
        IllegalStateException failure = new IllegalStateException("unrelated capability bug");
        Map<String, Object> evidence = new LinkedHashMap<>();
        try {
            OracleVideoCodecSelector.eligibleAttempt(original, true, true, query -> {
                if (!query.containsKey(MediaFormat.KEY_LEVEL)) throw failure;
                return false;
            }, evidence);
            fail("Unexpected runtime must surface");
        } catch (IllegalStateException actual) {
            assertSame(failure, actual);
        }
        assertEquals(before, original.toString());
        assertEquals(Boolean.FALSE, evidence.get("format_supported"));
        assertEquals(Boolean.TRUE, evidence.get("level_query_performed"));
        assertEquals(Boolean.FALSE, evidence.get("level_advisory_fallback"));
    }

    public void testRejectedLevelAttemptsReleaseOnceAndExhaustWithoutChangingConfigure() throws Exception {
        Fixture f = new Fixture(titleFormat());
        String before = f.format.toString();
        Map<String, Object> evidence = new LinkedHashMap<>();
        assertTrue(OracleVideoCodecSelector.eligibleAttempt(f.format, true, true,
                query -> !query.containsKey(MediaFormat.KEY_LEVEL), evidence));
        f.factory.configureFailures.put("software-one", new IllegalArgumentException("level rejected"));
        f.factory.configureFailures.put("software-two", new IllegalArgumentException("level rejected"));
        try {
            f.select(new OracleVideoCodecSelector.Candidate("software-one", true, true, evidence),
                    new OracleVideoCodecSelector.Candidate("software-two", true, true, evidence));
            fail("Rejected native level attempts must exhaust");
        } catch (IOException expected) {
            assertEquals(2, expected.getSuppressed().length);
        }
        assertEquals(2, f.factory.created.size());
        for (Handle handle : f.factory.created) {
            assertEquals(1, handle.releases);
            assertEquals(before, f.checkpoint("before_configure", handle.name).get("configure_format"));
        }
        assertEquals(before, f.format.toString());
        assertEquals("exhausted", f.snapshots.get(f.snapshots.size() - 1).get("stage"));
    }

    private static MediaFormat titleFormat() {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, 320, 240);
        format.setInteger(MediaFormat.KEY_PROFILE, 8);
        format.setInteger(MediaFormat.KEY_LEVEL, 524288);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, 0x7f420888);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
        format.setInteger("max-bitrate", 32209);
        return format;
    }

    private static OracleVideoCodecSelector.Candidate candidate(
            String name, boolean software, boolean supported) {
        return new OracleVideoCodecSelector.Candidate(name, software, supported, capability(supported));
    }

    private static Map<String, Object> capability(boolean supported) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("size_supported", supported);
        result.put("format_supported", supported);
        result.put("flexible_yuv420", true);
        result.put("color_formats", Collections.singletonList(0x7f420888));
        result.put("width_range", "[2, 4096]");
        result.put("height_range", "[2, 2160]");
        return result;
    }

    private static void assertContains(String actual, String... parts) {
        assertNotNull(actual);
        for (String part : parts) assertTrue(actual + " must contain " + part, actual.contains(part));
    }

    private static void assertImmutableMap(Map<String, Object> value) {
        try {
            value.put("unexpected_mutation", true);
            fail("Evidence map must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Mutation must be rejected, not merely hidden by a later snapshot.
        }
    }

    private static void assertImmutableList(List<?> value) {
        try {
            value.add(null);
            fail("Evidence list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Also works for empty failure lists.
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> candidates(Map<String, Object> snapshot) {
        return (List<Map<String, Object>>) snapshot.get("candidates");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> failures(Map<String, Object> snapshot) {
        return (List<Map<String, Object>>) snapshot.get("failures");
    }

    private static final class Handle {
        final String name;
        int releases;

        Handle(String name) {
            this.name = name;
        }
    }

    private static final class Fixture implements OracleVideoCodecSelector.Checkpoint {
        final MediaFormat format;
        final List<String> events = new ArrayList<>();
        final List<Map<String, Object>> snapshots = new ArrayList<>();
        final FakeFactory factory = new FakeFactory(this);
        IOException observerFailure;

        Fixture() {
            this(MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT));
        }

        Fixture(MediaFormat format) {
            this.format = format;
        }

        Handle select(OracleVideoCodecSelector.Candidate... supplied) throws IOException {
            return OracleVideoCodecSelector.configure(Arrays.asList(supplied), format, factory,
                    this, System.nanoTime() + TIMEOUT_MS * 1000000L);
        }

        @Override public void write(Map<String, Object> selection) throws IOException {
            String stage = (String) selection.get("stage");
            Object name = selection.get("decoder_name");
            events.add("checkpoint:" + stage + (name == null ? "" : ":" + name));
            snapshots.add(selection);
            if ("before_configure".equals(stage) && observerFailure != null) {
                throw observerFailure;
            }
        }

        Map<String, Object> checkpoint(String stage, String name) {
            for (Map<String, Object> snapshot : snapshots) {
                if (stage.equals(snapshot.get("stage")) && name.equals(snapshot.get("decoder_name"))) {
                    return snapshot;
                }
            }
            throw new AssertionError("Missing checkpoint " + stage + ":" + name);
        }

        void assertEvents(String... expected) {
            assertEquals(Arrays.asList(expected), events);
        }
    }

    private static final class FakeFactory implements OracleVideoCodecSelector.Factory<Handle> {
        final Fixture fixture;
        final Map<String, IOException> createFailures = new LinkedHashMap<>();
        final Map<String, RuntimeException> configureFailures = new LinkedHashMap<>();
        final List<Handle> created = new ArrayList<>();
        boolean interruptAfterCreate;

        FakeFactory(Fixture fixture) {
            this.fixture = fixture;
        }

        @Override public Handle create(String name) throws IOException {
            fixture.events.add("create:" + name);
            if (createFailures.containsKey(name)) throw createFailures.get(name);
            Handle handle = new Handle(name);
            created.add(handle);
            if (interruptAfterCreate) Thread.currentThread().interrupt();
            return handle;
        }

        @Override public void configure(Handle codec, MediaFormat format) {
            assertSame(fixture.format, format);
            assertEquals(0, codec.releases);
            // Check synchronously, inside the risky call, rather than only inspecting final logs.
            assertEquals("checkpoint:before_configure:" + codec.name,
                    fixture.events.get(fixture.events.size() - 1));
            Map<String, Object> snapshot = fixture.checkpoint("before_configure", codec.name);
            assertEquals(MIME, snapshot.get("mime"));
            assertEquals(fixture.format.getInteger(MediaFormat.KEY_WIDTH), snapshot.get("width"));
            assertEquals(fixture.format.getInteger(MediaFormat.KEY_HEIGHT), snapshot.get("height"));
            fixture.events.add("configure:" + codec.name);
            if (configureFailures.containsKey(codec.name)) throw configureFailures.get(codec.name);
        }

        @Override public void release(Handle codec) {
            fixture.events.add("release:" + codec.name);
            assertEquals("Handle released more than once", 0, codec.releases);
            codec.releases++;
        }
    }
}
