package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

@androidx.media3.common.util.UnstableApi
public final class ColorAdjustmentExportTest extends InstrumentationTestCase {
    private File source, evidence;

    @Override protected void runTest() throws Throwable {
        record("RUNNING");
        try { super.runTest(); record("PASS"); }
        catch (Throwable error) { record("FAIL"); throw error; }
    }

    private void record(String status) throws Exception {
        String policy = VideoEncodingSettings.compatibilityEnabled(getInstrumentation().getTargetContext())
                ? "software" : "default";
        JSONObject result = new JSONObject().put("revision",BuildConfig.SOURCE_REVISION)
                .put("policy",policy).put("test",getName()).put("status",status);
        try(FileOutputStream stream=new FileOutputStream(new File(evidence,getName()+"-"+policy+"-result.json"))) {
            stream.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        evidence = new File(getInstrumentation().getTargetContext().getFilesDir(), "color-adjust-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        source = new File(evidence, "palette.mp4");
        try (InputStream input = getInstrumentation().getContext().getAssets().open("color-adjust/palette.mp4");
             FileOutputStream output = new FileOutputStream(source)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        }
    }

    public void testNeutralAndDisabledNativeExactDecodedIdentity() throws Exception {
        Bitmap original = frame(export(base().build(), "off"), 0);
        for (ColorAdjustment value : new ColorAdjustment[]{new ColorAdjustment(true,0,100,100),
                new ColorAdjustment(false,90,0,200)}) {
            Bitmap actual = frame(export(base().colorAdjustment(value).build(), "identity"), 0);
            try { assertTrue("no extra GL pass for neutral/off", original.sameAs(actual)); }
            finally { actual.recycle(); }
        }
        original.recycle();
    }

    public void testNativeSaturationZeroAndBrightnessContrastClip() throws Exception {
        for (ColorAdjustment value : new ColorAdjustment[]{new ColorAdjustment(true,0,100,0),
                new ColorAdjustment(true,25,175,100), new ColorAdjustment(true,-20,150,60)}) {
            EditConfig config = base().colorAdjustment(value).build();
            Bitmap input = frame(source, 0), preview = SelectedFramePreview.render(config);
            save(input, "source-decoded.png");
            File output = export(config, "palette-" + value.brightness);
            Bitmap actual = frame(output, 0);
            com.simple.videoeditor.oracle.OracleCoreVerifier.RgbImage raw = value.saturation == 0
                    ? new com.simple.videoeditor.oracle.OracleAndroidDecoder().decode(output).video.frames.get(0).image
                    : null;
            int changed = 0;
            try {
                for (int y : new int[]{20,60,100,140,170,190,210,230})
                    for (int x : new int[]{40,120,200,280}) {
                        int expected = ColorAdjustmentTest.expected(input.getPixel(x,y),
                                value.brightness,value.contrast,value.saturation);
                        ColorAdjustmentTest.assertColor(expected, preview.getPixel(x,y), 1);
                        // Two 8-bit GL/AVC/YUV conversions, including saturated primaries.
                        ColorAdjustmentTest.assertColor(expected, actual.getPixel(x,y), 24);
                        if (distance(input.getPixel(x,y),expected) > 32) changed++;
                        if (value.saturation == 0) {
                            // Check decoded YUV planes through the existing oracle, not the
                            // retriever's RGB conversion (which can tint neutral chroma).
                            int i = (y*raw.width+x)*3;
                            int p = 0xff000000 | (raw.rgb[i]&255)<<16 | (raw.rgb[i+1]&255)<<8 | (raw.rgb[i+2]&255);
                            assertTrue("gray red/green: "+Integer.toHexString(p),
                                    Math.abs(((p>>16)&255)-((p>>8)&255)) <= 3);
                            assertTrue("gray blue/green: "+Integer.toHexString(p),
                                    Math.abs((p&255)-((p>>8)&255)) <= 3);
                        }
                    }
                assertTrue("omitted settings cannot pass this palette", changed >= 8);
                save(preview, "preview-" + value.brightness + ".png");
                save(actual, "export-" + value.brightness + ".png");
            } finally { input.recycle(); preview.recycle(); actual.recycle(); }
        }
    }

    public void testCropRotationBeforeColorAndLogoBorderAfterColor() throws Exception {
        Bitmap logo = Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
        logo.eraseColor(0xff00cc66);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        logo.compress(Bitmap.CompressFormat.PNG,100,bytes); logo.recycle();
        ColorAdjustment value = new ColorAdjustment(true,10,130,0);
        EditConfig config = base().crop(.25f,0,.75f,1).rotation(90)
                .colorAdjustment(value).watermark(PngWatermark.fromBytes(bytes.toByteArray()),.2f,.5f,.5f)
                .border(new VideoBorder(true,0,5,VideoBorder.Scope.WHOLE)).build();
        Bitmap input = frame(source,0), preview = SelectedFramePreview.render(config);
        Bitmap actual = frame(export(config,"geometry-overlay"),0);
        try {
            assertEquals(240,actual.getWidth()); assertEquals(160,actual.getHeight());
            for (int y : new int[]{20,60,100,140}) for (int x : new int[]{20,60,180,220}) {
                int expected = ColorAdjustmentTest.expected(input.getPixel(80+y,239-x),10,130,0);
                ColorAdjustmentTest.assertColor(expected,preview.getPixel(x,y),1);
                ColorAdjustmentTest.assertColor(expected,actual.getPixel(x,y),24);
            }
            ColorAdjustmentTest.assertColor(0xff00cc66,preview.getPixel(120,80),0);
            ColorAdjustmentTest.assertColor(0xff00cc66,actual.getPixel(120,80),24);
            ColorAdjustmentTest.assertColor(0xffff0000,preview.getPixel(4,80),0);
            ColorAdjustmentTest.assertColor(0xffff0000,actual.getPixel(4,80),24);
            save(preview,"geometry-preview.png"); save(actual,"geometry-export.png");
        } finally { input.recycle(); preview.recycle(); actual.recycle(); }
    }

    public void testGeneratedImportedAndAppendedSegmentsUnaffected() throws Exception {
        IntroTemplate title = new IntroTemplate("color-test","TITLE",32,0xffffcc33,
                0xff305080,.5f,.5f,1000,"normal");
        EditConfig.ImportedVideo clip = new EditConfig.ImportedVideo(Uri.fromFile(source),1000,320,240,
                false,source.length());
        EditConfig.Builder builder = base().mainSourceMetadata(false,source.length())
                .introTemplate(title,"TITLE").introVideo(clip).mergeMode(true)
                .appendVideos(Collections.singletonList(clip)).overlayText("WHITE");
        File off = export(builder.build(),"scope-off");
        File on = export(builder.colorAdjustment(new ColorAdjustment(true,20,150,0)).build(),"scope-on");
        for (long time : new long[]{300000,1300000,3300000}) {
            Bitmap expected = frame(off,time), actual = frame(on,time);
            try {
                for (int y=12;y<228;y+=16) for (int x=12;x<308;x+=16)
                    ColorAdjustmentTest.assertColor(expected.getPixel(x,y),actual.getPixel(x,y),8);
            } finally { expected.recycle(); actual.recycle(); }
        }
        Bitmap original = frame(off,2300000), changed = frame(on,2300000);
        try {
            assertTrue("main segment changed", distance(original.getPixel(40,100),changed.getPixel(40,100))>40);
            int white = 0;
            for (int y=80;y<160;y++) for (int x=70;x<250;x++) {
                int p = original.getPixel(x,y);
                if (((p>>16)&255)>248 && ((p>>8)&255)>248 && (p&255)>248) {
                    ColorAdjustmentTest.assertColor(p,changed.getPixel(x,y),12); white++;
                }
            }
            assertTrue("text overlay sampled",white>10);
        } finally { original.recycle(); changed.recycle(); }
    }

    private EditConfig.Builder base() {
        return new EditConfig.Builder(Uri.fromFile(source),1000).sourceSize(320,240).volume(0);
    }

    private File export(EditConfig config, String name) throws Exception {
        String policy = VideoEncodingSettings.compatibilityEnabled(getInstrumentation().getTargetContext())
                ? "software" : "default";
        File output = new File(evidence,name+"-"+policy+"-"+UUID.randomUUID()+".mp4");
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            engine.set(new Media3ExportEngine(getInstrumentation().getTargetContext()));
            engine.get().export(config,output,new Media3ExportEngine.Listener() {
                @Override public void onProgress(int progress) {}
                @Override public void onCompleted(File file) { done.countDown(); }
                @Override public void onError(Exception e) { error.set(e); done.countDown(); }
            });
        });
        try {
            assertTrue("finite color export",done.await(150,TimeUnit.SECONDS));
            if (error.get()!=null) throw error.get();
            assertTrue(output.length()>0);
            JSONObject receipt = new JSONObject().put("revision",BuildConfig.SOURCE_REVISION)
                    .put("policy",policy).put("output",output.getName()).put("settings",config.toString())
                    .put("name",name).put("color",config.colorAdjustment.toJson());
            try (FileOutputStream stream = new FileOutputStream(new File(evidence,output.getName()+".json"))) {
                stream.write(receipt.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return output;
        } finally {
            CountDownLatch stopped = new CountDownLatch(1);
            main.post(() -> { if(engine.get()!=null) engine.get().cancel(); stopped.countDown(); });
            assertTrue(stopped.await(10,TimeUnit.SECONDS));
        }
    }

    private static Bitmap frame(File file,long time) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            Bitmap bitmap = retriever.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST);
            assertNotNull(bitmap); return bitmap;
        } finally { retriever.release(); }
    }

    private void save(Bitmap bitmap,String name) throws Exception {
        try (FileOutputStream output=new FileOutputStream(new File(evidence,name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));
        }
    }

    private static int distance(int a,int b) {
        int sum=0;
        for(int shift:new int[]{0,8,16}) sum+=Math.abs(((a>>shift)&255)-((b>>shift)&255));
        return sum;
    }
}
