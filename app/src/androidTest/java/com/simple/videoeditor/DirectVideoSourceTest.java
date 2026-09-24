package com.simple.videoeditor;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class DirectVideoSourceTest extends InstrumentationTestCase {
    private Context context;

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        call("direct.reset");
    }

    @Override protected void tearDown() throws Exception {
        call("direct.cleanup");
        super.tearDown();
    }

    static Uri uri(String id) { return DocumentsContract.buildDocumentUri(DirectVideoDocuments.AUTHORITY, id); }

    private Bundle call(String method) {
        return context.getContentResolver().call(Uri.parse("content://com.simple.videoeditor.test.directcontrol"), method, null, null);
    }

    public void testBoundedProbe64BitUnknownSizeAndNoCacheCopy() throws Exception {
        long before = cacheBytes(context.getCacheDir());
        VideoSource source = VideoSource.document(context, uri("large"));
        assertEquals(5L * 1024 * 1024 * 1024 + 17, source.sizeBytes);
        assertNull(source.ownedFile);
        source.checkCurrent(context);
        assertEquals(source.sizeBytes, VideoSource.restore(context, source.save()).sizeBytes);
        assertEquals("Metadata/seek probe must not read the 5 GiB logical payload", 0, call("direct.stats").getLong("readBytes"));
        assertEquals(before, cacheBytes(context.getCacheDir()));
        new EditConfig.Builder(source.uri, 4000).inputSource(source).sourceSize(320, 240)
                .mainSourceMetadata(true, source.sizeBytes).build();
        VideoSource unknown = VideoSource.document(context, uri("unknown"));
        assertEquals(-1L, unknown.sizeBytes);
        new EditConfig.Builder(unknown.uri, 4000).inputSource(unknown)
                .mainSourceMetadata(true, -1).sourceSize(320, 240).build();
        source.dispose();
        source.checkCurrent(context);
        VideoSource reselected = VideoSource.document(context, source.uri);
        EditConfig first = new EditConfig.Builder(source.uri, 4000).inputSource(source).sourceSize(320, 240).build();
        EditConfig second = new EditConfig.Builder(reselected.uri, 4000).inputSource(reselected).sourceSize(320, 240).build();
        assertFalse("Reselecting the same URI must invalidate cached title backgrounds",
                TitleBackground.key(first, 1000).equals(TitleBackground.key(second, 1000)));
        VideoSource restored = VideoSource.restore(context, source.save());
        assertEquals("Recreation preserves source selection identity", TitleBackground.key(first, 1000),
                TitleBackground.key(new EditConfig.Builder(restored.uri, 4000).inputSource(restored)
                        .sourceSize(320, 240).build(), 1000));
    }

    public void testPipeNeedsConsentAndPermissionFailureDoesNotSuggestCopy() throws Exception {
        long before = cacheBytes(context.getCacheDir());
        try {
            VideoSource.document(context, uri("pipe"));
            fail("Non-seekable document accepted");
        } catch (VideoSource.NeedsLocalCopyException expected) {
            assertEquals(VideoSource.Reason.NON_SEEKABLE, expected.reason);
            assertTrue(expected.sizeBytes > 0);
        }
        assertEquals(before, cacheBytes(context.getCacheDir()));
        VideoSource source = VideoSource.document(context, uri("seekable"));
        call("direct.deny");
        try {
            source.checkCurrent(context);
            fail("Permission failure accepted");
        } catch (VideoSource.NeedsLocalCopyException wrong) { throw new AssertionError("Permission is not fixed by copying", wrong); }
        catch (IOException expected) {}
    }

    public void testConfirmedCopyCancelRetryAndOwnership() throws Exception {
        File target = new File(context.getCacheDir(), "direct-cancel-" + UUID.randomUUID() + ".mp4");
        try {
            try {
                VideoSource.stage(context, uri("slowpipe"), target, 0, bytes -> Thread.currentThread().interrupt());
                fail("Cancelled copy accepted");
            } catch (IOException expected) {
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            assertFalse("Owned partial removed", target.exists());
            VideoSource staged = VideoSource.stage(context, uri("pipe"), target, 0, bytes -> {});
            assertEquals(call("direct.stats").getLong("fixtureBytes"), staged.sizeBytes);
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                VideoSource.setDataSource(context, retriever, uri("seekable"));
                String originalDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                VideoSource.setDataSource(context, retriever, staged.uri);
                assertEquals(originalDuration, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            } finally { retriever.release(); }
            staged.dispose();
            assertFalse(target.exists());
            VideoSource.document(context, uri("seekable")).checkCurrent(context);
        } finally { target.delete(); }
    }

    public void testTemporaryFlagsAndForgedOwnedStateCannotDeleteOriginal() throws Exception {
        Uri uri = uri("seekable");
        assertFalse(VideoSource.retainReadGrant(context, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION));
        VideoSource source = VideoSource.document(context, uri);
        assertFalse(source.persistentRead);
        Bundle saved = source.save();
        saved.putString("videoSource.provenance", "owned");
        saved.putString("videoSource.ownedPath", new File(context.getFilesDir(), "original.mp4").getAbsolutePath());
        try {
            VideoSource.restore(context, saved);
            fail("Forged ownership accepted");
        } catch (IOException expected) {}
        source.dispose();
        source.checkCurrent(context);
    }

    public void testAssetDescriptorOffsetLengthPreviewAndNativeExport() throws Exception {
        Uri uri = Uri.parse("content://com.simple.videoeditor.test.directcontrol/range");
        VideoSource source = VideoSource.document(context, uri);
        long before = cacheBytes(context.getCacheDir());
        EditConfig config = new EditConfig.Builder(uri, 4000).inputSource(source)
                .sourceSize(320, 240).trim(1000, 3000).build();
        Bitmap preview = SelectedFramePreview.render(context, config);
        File reference = new OracleVerifier(context, com.simple.videoeditor.oracle.OracleGeneratedContract.create()).prepareFixture();
        Bitmap expected = TitleBackgroundTest.frame(reference, 1000000);
        assertTrue("Retriever must respect the nonzero descriptor offset", TitleBackgroundTest.mae(preview, expected) < 2);
        preview.recycle(); expected.recycle();
        File directory = new File(context.getFilesDir(), "direct-uri-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File output = new File(directory, "range-" + UUID.randomUUID() + ".mp4");
        export(config, output, false);
        Bitmap actual = TitleBackgroundTest.frame(output, 1000000);
        expected = TitleBackgroundTest.frame(reference, 2000000);
        try {
            assertTrue("Media3 must respect content descriptor range", TitleBackgroundTest.mae(actual, expected) < 12);
            assertEquals(before, cacheBytes(context.getCacheDir()));
        } finally { actual.recycle(); expected.recycle(); }
    }

    public void testDirectPreviewAbsoluteTitleAndNativeExportPixels() throws Exception {
        VideoSource source = VideoSource.document(context, uri("seekable"));
        File reference = new OracleVerifier(context, com.simple.videoeditor.oracle.OracleGeneratedContract.create()).prepareFixture();
        EditConfig geometry = new EditConfig.Builder(source.uri, 4000).inputSource(source).sourceSize(320, 240)
                .trim(2000, 4000).crop(.1f, .1f, .9f, .9f).rotation(90).build();
        long cacheBefore = cacheBytes(context.getCacheDir());
        Bitmap preview = SelectedFramePreview.render(context, geometry);
        EditConfig local = new EditConfig.Builder(Uri.fromFile(reference), 4000).sourceSize(320, 240)
                .trim(2000, 4000).crop(.1f, .1f, .9f, .9f).rotation(90).build();
        Bitmap expectedPreview = SelectedFramePreview.render(local);
        assertTrue("Direct URI uses real decoded pixels", TitleBackgroundTest.mae(preview, expectedPreview) < 2);
        preview.recycle(); expectedPreview.recycle();
        TitleBackground background = TitleBackground.capture(context, geometry, 1000);
        TitleBackground referenceBackground = TitleBackground.capture(context, local, 1000);
        Bitmap expected = referenceBackground.decode(4096);
        Bitmap actual = background.decode(4096);
        assertTrue("Absolute 1s background independent of 2s trim", TitleBackgroundTest.mae(actual, expected) < 2);
        actual.recycle();
        assertEquals(cacheBefore, cacheBytes(context.getCacheDir()));
        assertTrue("Decoder actually read the provider", call("direct.stats").getLong("readBytes") > 0);
        IntroTemplate title = new IntroTemplate();
        title.setAnimation("scale"); title.setDurationMs(3000);
        title.setSourceFrameBackground(true); title.setSourceFrameSeconds("1");
        EditConfig config = new EditConfig.Builder(source.uri, 4000).inputSource(source).sourceSize(320, 240)
                .mainSourceMetadata(true, source.sizeBytes).trim(2000, 4000)
                .crop(.1f, .1f, .9f, .9f).rotation(90).introTemplate(title, "").titleBackground(background).build();
        File directory = new File(context.getFilesDir(), "direct-uri-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File output = new File(directory, UUID.randomUUID() + ".mp4");
        try {
            export(config, output, false);
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(output.getAbsolutePath());
                Bitmap frame = retriever.getFrameAtTime(500000, MediaMetadataRetriever.OPTION_CLOSEST);
                double error = TitleBackgroundTest.mae(frame, expected);
                assertTrue("Native exported original background pixels; MAE=" + error, error < 12);
                frame.recycle();
            } finally { retriever.release(); }
            assertEquals("No app input copy after export", cacheBefore, cacheBytes(context.getCacheDir()));
            File cancelled = new File(directory, UUID.randomUUID() + ".mp4");
            export(config, cancelled, true);
            assertFalse(cancelled.exists());
            source.checkCurrent(context);
            org.json.JSONObject result = new org.json.JSONObject().put("revision", BuildConfig.SOURCE_REVISION)
                    .put("providerReadBytes", call("direct.stats").getLong("readBytes"))
                    .put("cacheInputDeltaBytes", 0).put("export", output.getName())
                    .put("largePhysicalPhoneValidated", false);
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new File(directory, "result.json"))) {
                stream.write(result.toString(2).getBytes("UTF-8"));
            }
        } finally {
            expected.recycle(); background.file.delete(); referenceBackground.file.delete();
        }
    }

    private void export(EditConfig config, File output, boolean cancel) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> {
            engine.set(new Media3ExportEngine(context));
            engine.get().export(config, output, new Media3ExportEngine.Listener() {
                public void onProgress(int percent) {}
                public void onCompleted(File file) { done.countDown(); }
                public void onError(Exception error) { failure.set(error); done.countDown(); }
            });
            if (cancel) engine.get().cancel();
        });
        try {
            assertTrue("Native test wait only", done.await(180, TimeUnit.SECONDS));
            if (cancel) assertTrue(failure.get() instanceof java.util.concurrent.CancellationException);
            else if (failure.get() != null) throw failure.get();
        } finally { getInstrumentation().runOnMainSync(() -> engine.get().cancel()); }
    }

    public void testLegacyStillTitleDirectAndFileExportParity() throws Exception {
        VideoSource source = VideoSource.document(context, uri("seekable"));
        File reference = new OracleVerifier(context, com.simple.videoeditor.oracle.OracleGeneratedContract.create()).prepareFixture();
        EditConfig directGeometry = new EditConfig.Builder(source.uri, 4000).inputSource(source).sourceSize(320, 240)
                .trim(2000, 4000).crop(.1f, .1f, .9f, .9f).rotation(90).build();
        EditConfig fileGeometry = new EditConfig.Builder(Uri.fromFile(reference), 4000).sourceSize(320, 240)
                .trim(2000, 4000).crop(.1f, .1f, .9f, .9f).rotation(90).build();
        TitleBackground directBackground = TitleBackground.capture(context, directGeometry, 1000);
        TitleBackground fileBackground = TitleBackground.capture(context, fileGeometry, 1000);
        IntroTemplate title = new IntroTemplate();
        title.setDurationMs(1000); title.setSourceFrameBackground(true); title.setSourceFrameSeconds("1");
        File directory = new File(context.getFilesDir(), "direct-uri-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File direct = new File(directory, "legacy-uri-" + UUID.randomUUID() + ".mp4");
        File local = new File(directory, "legacy-file-" + UUID.randomUUID() + ".mp4");
        try {
            export(new EditConfig.Builder(source.uri, 4000).inputSource(source).sourceSize(320, 240)
                    .trim(2000, 4000).crop(.1f, .1f, .9f, .9f).rotation(90)
                    .introTemplate(title, "").titleBackground(directBackground).build(), direct, false);
            export(new EditConfig.Builder(Uri.fromFile(reference), 4000).sourceSize(320, 240)
                    .trim(2000, 4000).crop(.1f, .1f, .9f, .9f).rotation(90)
                    .introTemplate(title, "").titleBackground(fileBackground).build(), local, false);
            Bitmap uriFrame = TitleBackgroundTest.frame(direct, 500000);
            Bitmap fileFrame = TitleBackgroundTest.frame(local, 500000);
            Bitmap original = fileBackground.decode(4096);
            try {
                double parity = TitleBackgroundTest.mae(uriFrame, fileFrame);
                org.json.JSONObject receipt = new org.json.JSONObject().put("revision", BuildConfig.SOURCE_REVISION)
                        .put("uriVsFileMae", parity).put("uriVsOriginalMae", TitleBackgroundTest.mae(uriFrame, original))
                        .put("fileVsOriginalMae", TitleBackgroundTest.mae(fileFrame, original));
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(directory, "legacy-parity.json"))) {
                    output.write(receipt.toString(2).getBytes("UTF-8"));
                }
                assertTrue("Legacy still-image path must be unchanged by direct URI access: " + receipt, parity < 2);
            } finally { uriFrame.recycle(); fileFrame.recycle(); original.recycle(); }
        } finally { directBackground.file.delete(); fileBackground.file.delete(); }
    }

    static long cacheBytes(File directory) {
        long bytes = 0;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) bytes += file.isDirectory() ? cacheBytes(file) : file.length();
        return bytes;
    }
}
