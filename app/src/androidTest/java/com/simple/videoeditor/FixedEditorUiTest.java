package com.simple.videoeditor;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.tabs.TabLayout;
import com.simple.videoeditor.oracle.OracleGeneratedContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

@androidx.media3.common.util.UnstableApi
public final class FixedEditorUiTest extends InstrumentationTestCase {
    private static final int[] PANELS = {R.id.panelPicture, R.id.panelSound, R.id.panelTitle,
            R.id.panelAssets, R.id.panelMore};
    private static final int[] TAB_LABELS = {R.string.editor_tab_picture, R.string.editor_tab_sound,
            R.string.editor_tab_title, R.string.editor_tab_assets, R.string.editor_tab_more};
    private static final int[] TITLE_INPUTS = {R.id.spinnerIntroTemplate, R.id.etIntroText,
            R.id.spinnerTitleStyle, R.id.spinnerTitleDuration, R.id.spinnerTitleFont,
            R.id.spinnerTitleWeight, R.id.spinnerTitleAlign, R.id.spinnerTitlePalette, R.id.etTitleSize,
            R.id.etTitleDurationMs, R.id.spinnerTitleLayout, R.id.btnTitleTextColor,
            R.id.btnTitleBackgroundColor, R.id.btnTitleGradientColor, R.id.cbTitleGradient};
    private static final int[] TEXT_INPUTS = {R.id.etCropLeft, R.id.etCropTop, R.id.etCropRight,
            R.id.etCropBottom, R.id.etCustomAngle, R.id.etTrimStart, R.id.etTrimEnd,
            R.id.etOverlayText, R.id.etIntroText, R.id.etTitleSize, R.id.etWatermarkWidth,
            R.id.etWatermarkX, R.id.etWatermarkY, R.id.etWholePresetName};
    private static final int[] OPTIONS = {R.id.spinnerResolution, R.id.spinnerSpeed,
            R.id.spinnerVolume, R.id.spinnerIntroTemplate, R.id.spinnerTitleStyle,
            R.id.spinnerTitleDuration, R.id.spinnerTitleFont, R.id.spinnerTitleWeight,
            R.id.spinnerTitleAlign, R.id.spinnerTitlePalette, R.id.spinnerWholePreset,
            R.id.spinnerBorderColor, R.id.spinnerBorderWidth, R.id.spinnerBorderScope};
    private static final int[] CHECKS = {R.id.cbEnableTrim, R.id.cbEnableVolume, R.id.cbEnableIntro,
            R.id.cbEnableMerge, R.id.cbEnableWatermark, R.id.cbCompatibilityEncoding, R.id.cbEnableBorder};
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private boolean originalEnglish;
    private File source;
    private String presetName;
    private final ArrayList<File> ownedFiles = new ArrayList<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        instrumentation = getInstrumentation();
        context = instrumentation.getTargetContext();
        android.accessibilityservice.AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        originalEnglish = UiLocales.isEnglish(context);
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        await("editor initialized", () -> !flag("restoringUi") && view(R.id.editorRoot).getHeight() > 0);
        // Await recovery before installing any deterministic UI-only result fixture.
        java.util.concurrent.ExecutorService worker = read(() ->
                (java.util.concurrent.ExecutorService) field("publicationWorker"));
        worker.submit(() -> {}).get(15, java.util.concurrent.TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
        hideKeyboard();
    }

    @Override protected void runTest() throws Throwable {
        try {
            super.runTest();
            capture(getName() + "-passed");
        } catch (Throwable error) {
            try { capture(getName() + "-failed"); }
            catch (Throwable evidenceError) { error.addSuppressed(evidenceError); }
            throw error;
        }
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (activity != null && !activity.isDestroyed()) {
                ui(() -> {
                    for (String name : new String[]{"loading", "exporting", "publishing"}) setField(name, false);
                    invoke("updateWorkControls");
                });
                hideKeyboard();
                if (!read(() -> activity.hasWindowFocus())) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                    settle();
                }
                if (UiLocales.isEnglish(context) != originalEnglish) switchLanguage();
            }
        } finally {
            try {
                if (activity != null) ui(() -> activity.finish());
                if (presetName != null) new WholeEditPresets(context).delete(presetName);
                for (File file : ownedFiles) if (file.exists()) assertTrue(file.delete());
            } finally {
                super.tearDown();
            }
        }
    }

    public void testTitleDraftAndAppearanceSurviveLocaleRecreation() throws Exception {
        selectTab(2);
        setChecked(R.id.cbEnableIntro, true);
        ui(() -> {
            IntroTemplate title = (IntroTemplate) field("currentTemplate");
            title.setTextColor(0x8044EE22);
            title.setBackgroundColor(0xFF120034);
            title.setGradientColor(0xCC557799);
            title.setLayout("canvas");
            invoke("syncTitleControls");
        });
        edit(R.id.etTitleSize, "", 0, 0);
        edit(R.id.etTitleDurationMs, "", 0, 0);
        switchLanguage();
        selectTab(2);
        ui(() -> {
            IntroTemplate title = (IntroTemplate) field("currentTemplate");
            assertNotNull("unfinished title survives recreation", title);
            assertEquals(0x8044EE22, title.getTextColor());
            assertEquals(0xFF120034, title.getBackgroundColor());
            assertEquals("", ((EditText) view(R.id.etTitleSize)).getText().toString());
            assertEquals("", ((EditText) view(R.id.etTitleDurationMs)).getText().toString());
            assertTrue(((TextView) view(R.id.btnTitleTextColor)).getText().toString().contains("#8044EE22"));
            assertTrue(((TextView) view(R.id.btnTitleBackgroundColor)).getText().toString().contains("#FF120034"));
        });
        edit(R.id.etTitleSize, "64", 2, 2);
        edit(R.id.etTitleDurationMs, "2400", 4, 4);
        selectOption(R.id.spinnerTitleStyle, 6);
        ui(() -> {
            IntroTemplate title = (IntroTemplate) field("currentTemplate");
            title.validate();
            assertEquals(64, title.getTextSize());
            assertEquals(2400, title.getDurationMs());
            assertEquals("dissolve", title.getAnimation());
            assertEquals(0x8044EE22, title.getTextColor());
            assertEquals(0xCC557799, title.getGradientColor());
        });
    }

    public void testFixedHeaderTabsAndDockArePeersAcrossAllPanels() throws Exception {
        ui(() -> {
            ViewGroup root = view(R.id.editorRoot);
            for (int id : new int[]{R.id.editorPreviewHeader, R.id.editorTabs,
                    R.id.editorPanels, R.id.editorActionDock}) assertSame(root, view(id).getParent());
            ViewGroup panels = view(R.id.editorPanels);
            assertEquals(5, panels.getChildCount());
            for (int id : PANELS) {
                assertTrue(view(id) instanceof ScrollView);
                assertSame(panels, view(id).getParent());
            }
            assertEquals(5, tabs().getTabCount());
            for (int i = 0; i < PANELS.length; i++)
                assertEquals(activity.getString(TAB_LABELS[i]), tabs().getTabAt(i).getText().toString());
        });
        for (int i = 0; i < PANELS.length; i++) {
            selectTab(i);
            Rect header = bounds(R.id.editorPreviewHeader), dock = bounds(R.id.editorActionDock);
            ui(() -> currentPanel().scrollTo(0, currentPanel().getChildAt(0).getHeight()));
            settle();
            assertEquals(header, bounds(R.id.editorPreviewHeader));
            assertEquals(dock, bounds(R.id.editorActionDock));
            assertFixedVisible();
            capture("fixed-peers-panel-" + i);
        }
    }

    public void testPanelSwitchRetainsInputsSelectionsCursorsAndIndependentScroll() throws Exception {
        populateWidgets();
        Map<Integer, View> identities = read(() -> {
            Map<Integer, View> result = new LinkedHashMap<>();
            for (int id : TEXT_INPUTS) result.put(id, view(id));
            for (int id : PANELS) result.put(id, view(id));
            return result;
        });
        Map<Integer, String> expected = widgetState();
        int[] positions = seedScrollPositions();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < PANELS.length; i++) {
                selectTab(i);
                final int panel = i;
                ui(() -> {
                    assertEquals("panel scroll " + panel, positions[panel], currentPanel().getScrollY());
                    for (Map.Entry<Integer, View> entry : identities.entrySet())
                        assertSame("persistent resource " + entry.getKey(), entry.getValue(), view(entry.getKey()));
                });
                assertEquals(expected, widgetState());
                assertFixedVisible();
            }
        }
        selectTab(0);
        edit(R.id.etOverlayText, "picture cursor", 3, 8);
        selectTab(2);
        edit(R.id.etIntroText, "title cursor", 2, 6);
        selectTab(0);
        ui(() -> {
            assertTrue(view(R.id.etOverlayText).hasFocus());
            assertSelection(R.id.etOverlayText, 3, 8);
        });
        selectTab(2);
        ui(() -> {
            assertTrue(view(R.id.etIntroText).hasFocus());
            assertSelection(R.id.etIntroText, 2, 6);
        });
    }

    public void testTrimAndCropRefreshFixedSelectedFrame() throws Exception {
        importMain();
        Bitmap zero = waitFrame(0, 0, 320, 240, false, null);
        setChecked(R.id.cbEnableTrim, true);
        edit(R.id.etTrimEnd, "3", 1, 1);
        edit(R.id.etTrimStart, "1", 1, 1);
        Bitmap trimmed = waitFrame(1, 0, 320, 240, false, zero);
        assertTrue("trim must decode another frame, not just replace its label",
                read(() -> !zero.sameAs(trimmed)));
        for (int id : new int[]{R.id.etCropLeft, R.id.etCropTop, R.id.etCropRight, R.id.etCropBottom})
            edit(id, "10", 1, 2);
        Bitmap cropped = waitFrame(1, 0, 256, 192, false, trimmed);
        assertEquals(256, cropped.getWidth());
        assertEquals(192, cropped.getHeight());
        selectTab(4);
        Bitmap refreshed = waitFrame(1, 0, 256, 192, false, cropped);
        ui(() -> assertTrue("tab refresh retains selected-frame pixels", cropped.sameAs(refreshed)));
        assertFixedVisible();
    }

    public void testTitleCheckboxGatesEveryEditorButKeepsPresetManagersEnabled() throws Exception {
        selectTab(2);
        setChecked(R.id.cbEnableIntro, true);
        edit(R.id.etIntroText, "Retained title", 2, 7);
        Map<Integer, String> before = widgetState();
        setChecked(R.id.cbEnableIntro, false);
        ui(() -> {
            assertSelection(R.id.etIntroText, 2, 7);
            for (int id : TITLE_INPUTS) assertFalse("disabled individual title input " + id, view(id).isEnabled());
            assertFalse("OFF title may not keep an editing focus", view(R.id.etIntroText).hasFocus());
            assertTrue(view(R.id.btnSaveTemplate).isEnabled());
            assertTrue(view(R.id.btnManageTemplates).isEnabled());
            assertEquals(View.VISIBLE, view(R.id.layoutIntroControls).getVisibility());
        });
        tap(R.id.btnManageTemplates, 2);
        assertDialog(R.string.manage_templates, R.string.editor_close);
        dismissDialog();
        setChecked(R.id.cbEnableIntro, true);
        ui(() -> {
            for (int id : TITLE_INPUTS) assertTrue("re-enabled individual title input " + id, view(id).isEnabled());
        });
        assertEquals(before, widgetState());
        // OFF-title selection also survives a real locale recreation and re-enable.
        setChecked(R.id.cbEnableIntro, false);
        switchLanguage();
        selectTab(2);
        setChecked(R.id.cbEnableIntro, true);
        assertEquals(before, widgetState());
        assertDeletedLastUsedTemplateFallback();
    }

    public void testWholePresetApplyRefreshesHiddenPanelsAndFixedPreview() throws Exception {
        importMain();
        setChecked(R.id.cbEnableTrim, true);
        edit(R.id.etTrimStart, "1", 1, 1);
        edit(R.id.etTrimEnd, "3", 1, 1);
        for (int id : new int[]{R.id.etCropLeft, R.id.etCropTop, R.id.etCropRight, R.id.etCropBottom})
            edit(id, "10", 2, 2);
        edit(R.id.etOverlayText, "saved overlay", 2, 4);
        selectTab(1);
        setChecked(R.id.cbEnableVolume, true);
        selectOption(R.id.spinnerVolume, 1);
        selectTab(2);
        setChecked(R.id.cbEnableIntro, true);
        edit(R.id.etIntroText, "Preset title", 1, 4);
        edit(R.id.etTitleSize, "32", 2, 2);
        selectOption(R.id.spinnerTitleStyle, 1);
        selectOption(R.id.spinnerTitleDuration, 1);
        selectOption(R.id.spinnerTitleFont, 1);
        ui(() -> ((SeekBar) view(R.id.titlePreviewTime)).setProgress(0));
        selectTab(4);
        presetName = "fixed-ui-" + UUID.randomUUID();
        edit(R.id.etWholePresetName, presetName, 0, presetName.length());
        tap(R.id.btnSaveWholePreset, 4);
        await("whole preset saved", () -> !flag("loading")
                && text(R.id.tvProgress).equals(activity.getString(R.string.editor_preset_saved, presetName)));
        ui(() -> {
            Spinner spinner = view(R.id.spinnerWholePreset);
            int found = -1;
            for (int i = 0; i < spinner.getCount(); i++)
                if (presetName.equals(spinner.getItemAtPosition(i))) found = i;
            assertTrue("saved preset selectable", found >= 0);
            spinner.setSelection(found);
        });
        selectTab(0);
        edit(R.id.etCropLeft, "0", 1, 1);
        edit(R.id.etTrimStart, "0", 1, 1);
        edit(R.id.etOverlayText, "changed", 0, 0);
        selectTab(1);
        selectOption(R.id.spinnerVolume, 6);
        selectTab(2);
        edit(R.id.etIntroText, "Changed title", 0, 0);
        selectOption(R.id.spinnerTitleFont, 2);
        setChecked(R.id.cbEnableIntro, false);
        Bitmap beforeApply = read(this::shownBitmap);
        tap(R.id.btnApplyWholePreset, 4);
        await("whole preset applied", () -> !flag("loading")
                && text(R.id.tvProgress).equals(activity.getString(R.string.editor_preset_applied, presetName)));
        waitFrame(1, 0, 256, 192, false, beforeApply);
        ui(() -> {
            assertEquals(4, tabs().getSelectedTabPosition());
            assertEquals("saved overlay", text(R.id.etOverlayText));
            assertEquals("Preset title", text(R.id.etIntroText));
            assertEquals(1, ((Spinner) view(R.id.spinnerVolume)).getSelectedItemPosition());
            assertEquals(1, ((Spinner) view(R.id.spinnerTitleFont)).getSelectedItemPosition());
            assertEquals(1, ((Spinner) view(R.id.spinnerTitleStyle)).getSelectedItemPosition());
            assertTrue(((CheckBox) view(R.id.cbEnableIntro)).isChecked());
            for (int id : TITLE_INPUTS) assertTrue(view(id).isEnabled());
            EditConfig config = (EditConfig) invoke("snapshotConfig");
            assertEquals(1000L, config.startMs);
            assertEquals(3000L, config.endMs);
            assertEquals(.1f, config.cropLeft, 0f);
            assertEquals(.9f, config.cropRight, 0f);
            assertNotNull(config.introTitle);
            assertEquals("Preset title", config.introTitle.text);
            assertEquals(activity.getString(R.string.editor_template_frame,
                    activity.getResources().getStringArray(R.array.editor_title_styles)[1],
                    0d, 3d, 256, 192), text(R.id.tvTemplatePreview));
            TitlePreviewView titlePreview = view(R.id.titleCanvasPreview);
            Field renderedTitle = TitlePreviewView.class.getDeclaredField("title");
            renderedTitle.setAccessible(true);
            assertEquals("Preset title", ((EditConfig.IntroTitle) renderedTitle.get(titlePreview)).text);
        });
        selectTab(2);
        ui(() -> ((SeekBar) view(R.id.titlePreviewTime)).setProgress(700));
        ui(() -> assertEquals(activity.getString(R.string.editor_template_frame,
                activity.getResources().getStringArray(R.array.editor_title_styles)[1],
                .7d, 3d, 256, 192), text(R.id.tvTemplatePreview)));
        assertFixedVisible();
    }

    public void testBorderLanguageDisabledStateTitlePreviewAndBusyGroups() throws Exception {
        importMain();
        selectTab(0);
        setChecked(R.id.cbEnableBorder, true);
        selectOption(R.id.spinnerBorderColor, 0);
        selectOption(R.id.spinnerBorderWidth, 4);
        selectTab(2);
        setChecked(R.id.cbEnableIntro, true);
        selectOption(R.id.spinnerTitleStyle, 1);
        ui(() -> {
            TitlePreviewView preview = view(R.id.titleCanvasPreview);
            preview.layout(0, 0, 320, 240);
            Bitmap actual = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
            preview.draw(new android.graphics.Canvas(actual));
            assertEquals(0xFFFF0000, actual.getPixel(0, 0));
            assertEquals(0xFFFF0000, actual.getPixel(319, 239));
            actual.recycle();
        });
        setChecked(R.id.cbEnableBorder, false);
        for (int round = 0; round < 2; round++) {
            switchLanguage();
            selectTab(0);
            ui(() -> {
                assertEquals(UiLocales.isEnglish(context) ? "Colored video border" : "视频彩色外边框",
                        ((CheckBox) view(R.id.cbEnableBorder)).getText().toString());
                assertEquals(4, ((Spinner) view(R.id.spinnerBorderWidth)).getSelectedItemPosition());
                assertEquals(UiLocales.isEnglish(context) ? "Red" : "红色",
                        ((Spinner) view(R.id.spinnerBorderColor)).getSelectedItem().toString());
                for (int id : new int[]{R.id.spinnerBorderColor, R.id.spinnerBorderWidth, R.id.spinnerBorderScope})
                    assertFalse(view(id).isEnabled());
                assertFalse(((EditConfig) invoke("snapshotConfig")).border.enabled);
            });
            reveal(R.id.spinnerBorderScope, 0);
            capture("border-locale-" + round);
        }
        setChecked(R.id.cbEnableBorder, true);
        for (String busy : new String[]{"loading", "exporting", "publishing"}) {
            ui(() -> {
                setField(busy, true); invoke("updateWorkControls");
                for (int id : new int[]{R.id.cbEnableBorder, R.id.spinnerBorderColor,
                        R.id.spinnerBorderWidth, R.id.spinnerBorderScope}) assertFalse(view(id).isEnabled());
                setField(busy, false); invoke("updateWorkControls");
                for (int id : new int[]{R.id.cbEnableBorder, R.id.spinnerBorderColor,
                        R.id.spinnerBorderWidth, R.id.spinnerBorderScope}) assertTrue(view(id).isEnabled());
            });
        }
        setChecked(R.id.cbEnableIntro, false);
        ui(() -> assertFalse(view(R.id.btnProcess).isEnabled()));
        setChecked(R.id.cbEnableBorder, false);
        ui(() -> assertTrue(view(R.id.btnProcess).isEnabled()));
    }

    public void testColorLocaleRecreationDisabledValuesAndBusyGroups() throws Exception {
        importMain();
        selectTab(0);
        setChecked(R.id.cbEnableColor,true);
        ui(() -> {
            ((SeekBar)view(R.id.colorBrightness)).setProgress(125);
            ((SeekBar)view(R.id.colorContrast)).setProgress(175);
            ((SeekBar)view(R.id.colorSaturation)).setProgress(0);
        });
        setChecked(R.id.cbEnableColor,false);
        for(int round=0;round<2;round++) {
            switchLanguage(); selectTab(0);
            ui(() -> {
                ColorAdjustment value=((EditConfig)invoke("snapshotConfig")).colorAdjustment;
                assertFalse(value.enabled); assertEquals(25,value.brightness);
                assertEquals(175,value.contrast); assertEquals(0,value.saturation);
                assertEquals(UiLocales.isEnglish(context)?"Adjust main-video color":"启用主视频调色",
                        ((CheckBox)view(R.id.cbEnableColor)).getText().toString());
                for(int id:new int[]{R.id.colorBrightness,R.id.colorContrast,R.id.colorSaturation})
                    assertFalse(view(id).isEnabled());
                assertTrue(view(R.id.btnProcess).isEnabled());
            });
            reveal(R.id.btnResetColor,0); capture("color-locale-"+round);
        }
        setChecked(R.id.cbEnableColor,true);
        for(String busy:new String[]{"loading","exporting","publishing"}) {
            ui(() -> {
                setField(busy,true); invoke("updateWorkControls");
                for(int id:new int[]{R.id.cbEnableColor,R.id.colorBrightness,R.id.colorContrast,
                        R.id.colorSaturation,R.id.btnResetColor}) assertFalse(view(id).isEnabled());
                setField(busy,false); invoke("updateWorkControls");
                for(int id:new int[]{R.id.cbEnableColor,R.id.colorBrightness,R.id.colorContrast,
                        R.id.colorSaturation,R.id.btnResetColor}) assertTrue(view(id).isEnabled());
            });
        }
        tap(R.id.btnResetColor,0);
        ui(() -> assertTrue(((EditConfig)invoke("snapshotConfig")).colorAdjustment.isIdentity()));
    }

    public void testLanguageRoundTripRetainsMediaValuesTabScrollAndCursors() throws Exception {
        importMain();
        deliverMedia("REQUEST_CODE_INTRO", source);
        deliverMedia("REQUEST_CODE_MUSIC", source);
        deliverMedia("REQUEST_CODE_APPEND", source);
        File png = new File(context.getCacheDir(), "fixed-ui-" + UUID.randomUUID() + ".png");
        ownedFiles.add(png);
        Bitmap image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xff00ff00);
        try (FileOutputStream out = new FileOutputStream(png)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { image.recycle(); }
        deliverMedia("REQUEST_CODE_WATERMARK", png);
        populateWidgets();
        selectTab(3);
        setChecked(R.id.cbEnableMerge, true);
        setChecked(R.id.cbEnableWatermark, true);
        PublishedVideo output = resultFixture();
        ui(() -> invoke("showVideo", new Class<?>[]{PublishedVideo.class}, output));
        Map<String, String> media = mediaState();
        int[] scroll = seedScrollPositions();
        selectTab(4);
        edit(R.id.etWholePresetName, "Locale 名称", 2, 6);
        reveal(R.id.btnLanguage, 4);
        Map<Integer, String> expected = widgetState();
        scroll[4] = read(() -> currentPanel().getScrollY());
        boolean initial = UiLocales.isEnglish(context);
        for (int round = 0; round < 2; round++) {
            switchLanguage();
            waitFrame(1, 0, 288, 240, true, null);
            assertEquals(round == 0 ? !initial : initial, UiLocales.isEnglish(context));
            assertEquals(expected, widgetState());
            assertEquals(media, mediaState());
            assertVisible(R.id.layoutSuccessContainer);
            ui(() -> {
                assertEquals(4, tabs().getSelectedTabPosition());
                assertTrue(view(R.id.etWholePresetName).hasFocus());
                assertSelection(R.id.etWholePresetName, 2, 6);
                for (int i = 0; i < PANELS.length; i++)
                    assertEquals(activity.getString(TAB_LABELS[i]), tabs().getTabAt(i).getText().toString());
            });
            for (int i = 0; i < PANELS.length; i++) {
                selectTab(i);
                assertEquals("locale retains panel scroll " + i, scroll[i],
                        (int) read(() -> currentPanel().getScrollY()));
                capture("locale-" + (UiLocales.isEnglish(context) ? "en" : "zh") + "-panel-" + i);
            }
            assertFixedVisible();
            capture("locale-" + (UiLocales.isEnglish(context) ? "en" : "zh"));
        }
    }

    public void testTitleFrameLocaleRecreationAndInactiveControls() throws Exception {
        importMain();
        selectTab(2);
        setChecked(R.id.cbEnableIntro, true);
        setChecked(R.id.cbTitleSourceFrame, true);
        ui(() -> ((EditText) view(R.id.etTitleSourceSeconds)).setText("1.125"));
        await("title frame ready", () -> view(R.id.btnSaveTitleFrame).isEnabled());
        for (int round = 0; round < 2; round++) {
            switchLanguage();
            selectTab(2);
            await("restored original frame", () -> view(R.id.btnSaveTitleFrame).isEnabled());
            ui(() -> {
                assertEquals("1.125", text(R.id.etTitleSourceSeconds));
                EditConfig config = (EditConfig) invoke("snapshotConfig");
                assertEquals(1125L, config.introTitle.sourceFrameTimeMs);
                assertNotNull(config.titleBackground);
                assertEquals(activity.getString(R.string.title_frame_enable),
                        ((CheckBox) view(R.id.cbTitleSourceFrame)).getText().toString());
            });
            reveal(R.id.ivTitleSourceFrame, 2);
            capture("title-frame-locale-" + round);
            for (String busy : new String[]{"loading", "exporting", "publishing"}) {
                ui(() -> {
                    setField(busy, true); invoke("updateWorkControls");
                    for (int id : new int[]{R.id.cbTitleSourceFrame, R.id.etTitleSourceSeconds,
                            R.id.btnExtractTitleFrame, R.id.btnSaveTitleFrame}) assertFalse(view(id).isEnabled());
                    setField(busy, false); invoke("updateWorkControls");
                });
            }
        }
        setChecked(R.id.cbEnableIntro, false);
        ui(() -> {
            assertEquals("1.125", text(R.id.etTitleSourceSeconds));
            for (int id : new int[]{R.id.cbTitleSourceFrame, R.id.etTitleSourceSeconds,
                    R.id.btnExtractTitleFrame, R.id.btnSaveTitleFrame}) assertFalse(view(id).isEnabled());
            assertNull(((EditConfig) invoke("snapshotConfig")).titleBackground);
        });
    }

    public void testDialogsUseCurrentLocaleAndPreserveEditorValues() throws Exception {
        populateWidgets();
        for (int round = 0; round < 2; round++) {
            switchLanguage();
            Map<Integer, String> before = widgetState();
            tap(R.id.btnSaveTemplate, 2);
            assertDialog(R.string.save_as_template, R.string.editor_cancel);
            dismissDialog();
            tap(R.id.btnManageTemplates, 2);
            assertDialog(R.string.manage_templates, R.string.editor_close);
            dismissDialog();
            ui(() -> invoke("showError", new Class<?>[]{String.class, Throwable.class},
                    activity.getString(R.string.editor_preview_unavailable), new IOException("fixed-ui detail")));
            tap(R.id.btnErrorDetails, -1);
            assertDialog(R.string.editor_error_details, android.R.string.ok);
            assertAccessibleText("fixed-ui detail", false);
            dismissDialog();
            PublishedVideo result = resultFixture();
            ui(() -> invoke("showVideo", new Class<?>[]{PublishedVideo.class}, result));
            tap(R.id.btnOutputLocation, -1);
            assertDialog(R.string.editor_output_location, android.R.string.ok);
            assertAccessibleText(result.info(), true);
            dismissDialog();
            assertEquals(before, widgetState());
        }
    }

    public void testBusyStatesAndResultsStayInFixedDock() throws Exception {
        importMain();
        waitFrame(0, 0, 320, 240, false, null);
        selectTab(2);
        setChecked(R.id.cbEnableIntro, true);
        PublishedVideo result = resultFixture();
        ui(() -> invoke("showVideo", new Class<?>[]{PublishedVideo.class}, result));
        for (int tab = 0; tab < PANELS.length; tab++) {
            selectTab(tab);
            assertVisible(R.id.layoutSuccessContainer);
            for (int id : new int[]{R.id.btnOpenOutputFolder, R.id.btnShareVideo,
                    R.id.btnCopyVideoInfo, R.id.btnOutputLocation}) assertVisible(id);
            assertFixedVisible();
        }
        tap(R.id.btnCopyVideoInfo, -1);
        ui(() -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            assertNotNull(clipboard.getPrimaryClip());
            assertEquals(result.info(), clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        });
        for (String busy : new String[]{"loading", "exporting", "publishing"}) {
            ui(() -> {
                for (int id : TITLE_INPUTS) assertTrue("title enabled before busy", view(id).isEnabled());
                setField(busy, true);
                invoke("updateWorkControls");
                invoke("showVideo", new Class<?>[]{PublishedVideo.class}, result);
            });
            settle();
            for (int tab = 0; tab < PANELS.length; tab++) {
                selectTab(tab);
                ui(() -> {
                    assertFalse(view(R.id.btnLanguage).isEnabled());
                    assertEquals(View.GONE, view(R.id.btnProcess).getVisibility());
                    assertEquals(View.GONE, view(R.id.layoutSuccessContainer).getVisibility());
                    boolean cancellable = "exporting".equals(busy) || "loading".equals(busy);
                    assertEquals(cancellable, view(R.id.btnCancelExport).isEnabled());
                    assertEquals(cancellable ? View.VISIBLE : View.GONE,
                            view(R.id.btnCancelExport).getVisibility());
                    for (int id : TITLE_INPUTS) assertFalse(view(id).isEnabled());
                    assertFalse(view(R.id.btnManageTemplates).isEnabled());
                    assertFalse(view(R.id.btnApplyWholePreset).isEnabled());
                });
                assertVisible(R.id.editorPreviewHeader);
                assertVisible(R.id.progressBar);
                if ("exporting".equals(busy) || "loading".equals(busy)) assertVisible(R.id.btnCancelExport);
            }
            boolean english = UiLocales.isEnglish(context);
            capture("busy-" + busy);
            ui(() -> view(R.id.btnLanguage).performClick());
            assertEquals("busy guard rejects programmatic language action too", english, UiLocales.isEnglish(context));
            ui(() -> {
                setField(busy, false);
                invoke("updateWorkControls");
                invoke("showVideo", new Class<?>[]{PublishedVideo.class}, result);
                assertTrue(view(R.id.btnLanguage).isEnabled());
                assertTrue(view(R.id.btnProcess).isEnabled());
                for (int id : TITLE_INPUTS) assertTrue("title restored after busy", view(id).isEnabled());
                assertEquals(View.GONE, view(R.id.progressBar).getVisibility());
            });
            settle();
            assertVisible(R.id.layoutSuccessContainer);
        }
    }

    public void testSmallViewportKeepsPreviewAndActionsReachable() throws Exception {
        ViewGroup.LayoutParams original = read(() -> view(R.id.editorRoot).getLayoutParams());
        int width = original.width, height = original.height;
        try {
            ui(() -> {
                View root = view(R.id.editorRoot);
                ViewGroup.LayoutParams params = root.getLayoutParams();
                params.width = Math.min(root.getWidth(), dp(320));
                params.height = Math.min(root.getHeight(), dp(320));
                root.setLayoutParams(params);
            });
            settle();
            for (int i = 0; i < PANELS.length; i++) {
                selectTab(i);
                assertFixedVisible();
                ui(() -> assertTrue("panel retains editable viewport", currentPanel().getHeight() > 0));
            }
            capture("small-viewport");
        } finally {
            hideKeyboard();
            ui(() -> {
                ViewGroup.LayoutParams params = view(R.id.editorRoot).getLayoutParams();
                params.width = width;
                params.height = height;
                view(R.id.editorRoot).setLayoutParams(params);
            });
            settle();
        }
    }

    public void testKeyboardKeepsPreviewAndActionsReachableWhenImeSupported() throws Exception {
        selectTab(0);
        edit(R.id.etCropLeft, "10", 1, 2);
        ui(() -> {
            for (int id : TEXT_INPUTS) assertTrue("no fullscreen IME extraction " + id,
                    (((EditText) view(id)).getImeOptions() & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    activity.getWindow().getAttributes().softInputMode
                            & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        });
        boolean noIme = read(() -> ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                .getEnabledInputMethodList().isEmpty());
        boolean hardwareKeyboard = read(() -> {
            Configuration config = activity.getResources().getConfiguration();
            return config.keyboard != Configuration.KEYBOARD_NOKEYS
                    && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
        });
        try {
            ui(() -> ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(view(R.id.etCropLeft), InputMethodManager.SHOW_IMPLICIT));
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (!read(this::imeVisible) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            if (!read(this::imeVisible) && (noIme || hardwareKeyboard)) {
                Bundle evidence = new Bundle();
                evidence.putString("stream", "FixedEditorUiTest: IME viewport scenario UNSUPPORTED "
                        + "(no enabled soft IME or visible hardware keyboard); extraction/resize flags verified.\n");
                instrumentation.sendStatus(0, evidence);
                return;
            }
            assertTrue("enabled software IME must become visible before viewport assertions", read(this::imeVisible));
            settle();
            capture("ime-visible");
            assertFixedVisible();
            ui(() -> {
                assertTrue("keyboard leaves editable panel height", currentPanel().getHeight() > 0);
                assertSelection(R.id.etCropLeft, 1, 2);
            });
        } finally { hideKeyboard(); }
    }

    public void testLandscapeKeepsPreviewAndDockVisibleWithoutPanelHunt() throws Exception {
        int original = read(() -> activity.getRequestedOrientation());
        int originalConfiguration = read(() -> activity.getResources().getConfiguration().orientation);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        try {
            populateWidgets();
            Map<Integer, String> expected = widgetState();
            ui(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            await("landscape configuration", () -> {
                if (monitor.getLastActivity() != null) activity = (MainActivity) monitor.getLastActivity();
                return !flag("restoringUi") && activity.getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE;
            });
            await("landscape window laid out", () -> activity.hasWindowFocus()
                    && view(R.id.editorRoot).isShown()
                    && view(R.id.editorRoot).getWidth() > view(R.id.editorRoot).getHeight());
            settle();
            capture("landscape");
            for (int i = 0; i < PANELS.length; i++) {
                selectTab(i);
                assertFixedVisible();
                assertEquals(expected, widgetState());
                ui(() -> assertTrue("landscape panel has usable height", currentPanel().getHeight() > 0));
            }
        } finally {
            try {
                ui(() -> activity.setRequestedOrientation(original));
                await("original orientation restored", () -> {
                    if (monitor.getLastActivity() != null) activity = (MainActivity) monitor.getLastActivity();
                    return !activity.isDestroyed() && !flag("restoringUi") && activity.hasWindowFocus()
                            && view(R.id.editorRoot).isShown()
                            && activity.getResources().getConfiguration().orientation == originalConfiguration;
                });
            } finally { instrumentation.removeMonitor(monitor); }
        }
    }

    private void assertDeletedLastUsedTemplateFallback() throws Exception {
        importMain();
        IntroTemplateManager manager = new IntroTemplateManager(context);
        IntroTemplate original = manager.getLastUsedTemplate();
        String name = "fixed-ui-title-" + UUID.randomUUID();
        try {
            ui(() -> {
                IntroTemplate template = ((IntroTemplate) field("currentTemplate")).copy();
                template.setName(name);
                template.setText("Deleted last-used title");
                assertTrue(manager.saveTemplate(template));
                manager.setLastUsedTemplate(name);
                invoke("setupIntroTemplateSpinner");
            });
            settle();
            ui(() -> assertEquals(name, ((Spinner) view(R.id.spinnerIntroTemplate)).getSelectedItem()));
            tap(R.id.btnManageTemplates, 2);
            assertDialog(R.string.manage_templates, R.string.editor_close);
            tapAccessibleText(name);
            assertDialog(R.string.editor_delete_template, R.string.editor_cancel);
            tapAccessibleText(read(() -> activity.getString(R.string.editor_delete)
                    .toUpperCase(activity.getResources().getConfiguration().locale)));
            await("template deletion dialog dismissed", () -> activity.hasWindowFocus());
            ui(() -> {
                assertNull("deleted template removed", manager.getTemplate(name));
                IntroTemplate fallback = (IntroTemplate) field("currentTemplate");
                assertNotNull("stale last-used name must fall back", fallback);
                assertEquals(manager.getTemplateNames()[0], fallback.getName());
                assertEquals(fallback.getName(), ((Spinner) view(R.id.spinnerIntroTemplate)).getSelectedItem());
                assertEquals(fallback.getText(), text(R.id.etIntroText));
                Field title = TitlePreviewView.class.getDeclaredField("title");
                title.setAccessible(true);
                assertEquals(fallback.getText(), ((EditConfig.IntroTitle)
                        title.get(view(R.id.titleCanvasPreview))).text);
                assertFalse(activity.getString(R.string.editor_template_none).equals(text(R.id.tvTemplatePreview)));
                assertNotNull("whole-preset snapshot remains valid", invoke("wholePresetSnapshot"));
            });
            capture("deleted-last-used-template");
        } finally {
            if (manager.getTemplate(name) != null) assertTrue(manager.deleteTemplate(name));
            if (original != null) manager.setLastUsedTemplate(original.getName());
        }
    }

    private void tapAccessibleText(String text) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                boolean clicked = false;
                try {
                    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                        try {
                            if (node.getText() != null && text.contentEquals(node.getText())
                                    && node.isVisibleToUser() && node.isEnabled())
                                clicked |= node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        } finally { node.recycle(); }
                    }
                    if (!clicked) {
                        for (AccessibilityNodeInfo list : root.findAccessibilityNodeInfosByViewId(
                                "android:id/select_dialog_listview")) {
                            try { list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); }
                            finally { list.recycle(); }
                        }
                    }
                } finally { root.recycle(); }
                if (clicked) { settle(); return; }
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("dialog action missing: " + text);
    }

    private void populateWidgets() throws Exception {
        selectTab(0);
        setChecked(R.id.cbEnableTrim, true);
        edit(R.id.etCropLeft, "10.0", 1, 3);
        edit(R.id.etTrimStart, "1.0", 0, 2);
        edit(R.id.etTrimEnd, "3.0", 1, 3);
        edit(R.id.etOverlayText, "Overlay 字幕", 2, 6);
        selectOption(R.id.spinnerSpeed, 3);
        selectTab(1);
        setChecked(R.id.cbEnableVolume, true);
        selectOption(R.id.spinnerVolume, 1);
        selectTab(2);
        setChecked(R.id.cbEnableIntro, true);
        selectOption(R.id.spinnerTitleStyle, 1);
        selectOption(R.id.spinnerTitleDuration, 1);
        selectOption(R.id.spinnerTitleFont, 1);
        edit(R.id.etTitleSize, "32", 0, 2);
        edit(R.id.etIntroText, "Title 标题", 1, 5);
        ui(() -> ((SeekBar) view(R.id.titlePreviewTime)).setProgress(700));
        selectTab(4);
        edit(R.id.etWholePresetName, "Draft 名称", 2, 5);
        hideKeyboard();
    }

    private int[] seedScrollPositions() throws Exception {
        int[] result = new int[PANELS.length];
        for (int i = 0; i < PANELS.length; i++) {
            selectTab(i);
            final int index = i;
            ui(() -> {
                ScrollView panel = currentPanel();
                panel.scrollTo(0, Math.min(dp(80 + index * 30),
                        Math.max(0, panel.getChildAt(0).getHeight() - panel.getHeight())));
            });
            settle();
            result[i] = read(() -> currentPanel().getScrollY());
        }
        assertTrue("picture must actually scroll", result[0] > 0);
        assertTrue("title must actually scroll", result[2] > 0);
        return result;
    }

    private Map<Integer, String> widgetState() throws Exception {
        return read(() -> {
            Map<Integer, String> state = new LinkedHashMap<>();
            for (int id : TEXT_INPUTS) {
                EditText edit = view(id);
                state.put(id, edit.getText() + "|" + edit.getSelectionStart() + "|" + edit.getSelectionEnd());
            }
            for (int id : OPTIONS) state.put(id, Integer.toString(((Spinner) view(id)).getSelectedItemPosition()));
            for (int id : CHECKS) state.put(id, Boolean.toString(((CheckBox) view(id)).isChecked()));
            state.put(R.id.titlePreviewTime, Integer.toString(((SeekBar) view(R.id.titlePreviewTime)).getProgress()));
            return state;
        });
    }

    private Map<String, String> mediaState() throws Exception {
        return read(() -> {
            EditConfig config = (EditConfig) invoke("snapshotConfig");
            Map<String, String> result = new LinkedHashMap<>();
            Uri[] uris = {config.input, config.intro, config.replacementMusic};
            String[] keys = {"main", "intro", "music"};
            for (int i = 0; i < uris.length; i++) {
                assertNotNull(keys[i], uris[i]);
                assertTrue(new File(uris[i].getPath()).isFile());
                result.put(keys[i], uris[i].toString());
            }
            assertEquals(1, config.appendedVideos.size());
            Uri clip = config.appendedVideos.get(0).uri;
            assertTrue(new File(clip.getPath()).isFile());
            result.put("clip", clip.toString());
            String png = (String) field("watermarkFilePath");
            assertNotNull(png);
            assertTrue(new File(png).isFile());
            result.put("png", Uri.fromFile(new File(png)).toString());
            PublishedVideo output = (PublishedVideo) field("lastVideo");
            assertNotNull(output);
            assertNotNull(output.uri);
            result.put("output", output.uri.toString());
            result.put("outputInfo", output.info());
            return result;
        });
    }

    private void importMain() throws Exception {
        source = new OracleVerifier(context, OracleGeneratedContract.create()).prepareFixture();
        deliverMedia("VIDEO_PICK_CODE", source);
        ui(() -> {
            assertNotNull(field("selectedMainVideo"));
            assertTrue(view(R.id.btnProcess).isEnabled());
        });
    }

    private void deliverMedia(String request, File file) throws Exception {
        // Exercise the real import callback/worker without driving another app's document picker.
        ui(() -> activity.onActivityResult((Integer) field(request), Activity.RESULT_OK,
                new Intent().setData(Uri.fromFile(file))));
        await("media import " + request, () -> !flag("loading") && !flag("restoringAssets"));
        ui(() -> assertEquals("import failed: " + text(R.id.tvRawError),
                View.GONE, view(R.id.svErrorContainer).getVisibility()));
    }

    private Bitmap waitFrame(double seconds, int rotation, int width, int height, boolean png,
                             Bitmap previous) throws Exception {
        await("selected-frame refresh", () -> {
            String expected = activity.getString(R.string.editor_preview_frame, seconds, rotation, width, height,
                    png ? activity.getString(R.string.editor_preview_png) : "");
            Bitmap current = shownBitmap();
            return current != null && current != previous && expected.equals(text(R.id.tvGeometryStatus))
                    && expected.contentEquals(view(R.id.ivVideoThumbnail).getContentDescription());
        });
        return read(this::shownBitmap);
    }

    private Bitmap shownBitmap() {
        ImageView image = view(R.id.ivVideoThumbnail);
        return image.getDrawable() instanceof BitmapDrawable
                ? ((BitmapDrawable) image.getDrawable()).getBitmap() : null;
    }

    private PublishedVideo resultFixture() throws Exception {
        File directory = new File(context.getFilesDir(), "exports");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, "fixed-ui-result-" + UUID.randomUUID() + ".mp4");
        ownedFiles.add(file);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(1); }
        // UI metadata fixture only: no export/publication or media playback claim.
        Uri uri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        return new PublishedVideo(uri, file.getName(), file.getAbsolutePath(), "UI fixture", file);
    }

    private void switchLanguage() throws Exception {
        reveal(R.id.btnLanguage, 4);
        boolean english = UiLocales.isEnglish(context);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        try {
            tap(R.id.btnLanguage, 4);
            Activity recreated = monitor.waitForActivityWithTimeout(15000);
            assertNotNull("language switch must recreate editor with translated resources", recreated);
            activity = (MainActivity) recreated;
            await("localized editor ready", () -> !flag("restoringUi") && !flag("loading")
                    && !flag("restoringAssets") && activity.hasWindowFocus());
            assertEquals(!english, UiLocales.isEnglish(context));
            assertEquals(!english ? "en" : "zh", read(() ->
                    activity.getResources().getConfiguration().locale.getLanguage()));
            hideKeyboard();
            settle();
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private void setChecked(int id, boolean checked) throws Exception {
        ui(() -> {
            CheckBox check = view(id);
            assertTrue("checkbox enabled " + id, check.isEnabled());
            // CompoundButton toggles even when no OnClickListener handles the click.
            if (check.isChecked() != checked) check.performClick();
            assertEquals("checkbox state " + id, checked, check.isChecked());
        });
        settle();
    }

    private void edit(int id, String value, int start, int end) throws Exception {
        ui(() -> {
            EditText edit = view(id);
            assertTrue("input enabled " + id, edit.isEnabled());
            assertTrue("input is in selected peer " + id, edit.isShown());
            edit.requestFocus();
            edit.setText(value);
            edit.setSelection(start, end);
        });
        hideKeyboard();
        settle();
    }

    private void selectOption(int id, int option) throws Exception {
        ui(() -> {
            Spinner spinner = view(id);
            assertTrue(spinner.isEnabled());
            assertTrue(spinner.isShown());
            spinner.setSelection(option);
        });
        settle();
    }

    private void selectTab(int index) throws Exception {
        ui(() -> tabs().selectTab(tabs().getTabAt(index)));
        settle();
        ui(() -> {
            assertEquals(index, tabs().getSelectedTabPosition());
            for (int i = 0; i < PANELS.length; i++)
                assertEquals("only selected peer visible", i == index ? View.VISIBLE : View.GONE,
                        view(PANELS[i]).getVisibility());
        });
    }

    private void reveal(int id, int panel) throws Exception {
        if (panel >= 0 && read(() -> tabs().getSelectedTabPosition()) != panel) selectTab(panel);
        ui(() -> {
            View target = view(id);
            if (panel >= 0) {
                ScrollView owner = view(PANELS[panel]);
                assertSame("resource belongs to explicit panel", target, owner.findViewById(id));
                Rect rect = new Rect(0, 0, target.getWidth(), target.getHeight());
                owner.offsetDescendantRectToMyCoords(target, rect);
                // Cancel a pending focus/restore animation before revealing the control.
                owner.smoothScrollTo(0, rect.top - owner.getPaddingTop());
                owner.smoothScrollTo(0, rect.top - owner.getPaddingTop());
            }
        });
        settle();
        assertVisible(id);
    }

    private void tap(int id, int panel) throws Exception {
        reveal(id, panel);
        assertTrue("enabled action " + id, read(() -> view(id).isEnabled()));
        Rect rect = read(() -> {
            View target = view(id);
            Rect visible = new Rect();
            assertTrue(target.getLocalVisibleRect(visible));
            int[] screen = new int[2];
            target.getLocationOnScreen(screen);
            visible.offset(screen[0], screen[1]);
            return visible;
        });
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0);
        MotionEvent up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0);
        try {
            instrumentation.sendPointerSync(down);
            instrumentation.sendPointerSync(up);
        } finally { down.recycle(); up.recycle(); }
        instrumentation.waitForIdleSync();
    }

    private void assertFixedVisible() throws Exception {
        for (int id : new int[]{R.id.editorPreviewHeader, R.id.ivVideoThumbnail, R.id.editorTabs,
                R.id.editorActionDock, R.id.btnSelectVideo, R.id.btnProcess}) assertVisible(id);
        Rect header = bounds(R.id.editorPreviewHeader), tabs = bounds(R.id.editorTabs);
        Rect panel = bounds(PANELS[read(() -> tabs().getSelectedTabPosition())]);
        Rect dock = bounds(R.id.editorActionDock);
        assertTrue("header above tabs", header.bottom <= tabs.top);
        assertTrue("tabs above panel", tabs.bottom <= panel.top);
        assertTrue("panel above fixed dock", panel.bottom <= dock.top);
    }

    private void assertVisible(int id) throws Exception {
        ui(() -> {
            View target = view(id);
            assertTrue("shown resource " + id, target.isShown());
            Rect visible = new Rect();
            assertTrue("visible bounds " + id, target.getGlobalVisibleRect(visible));
            assertEquals("unclipped width " + id, target.getWidth(), visible.width());
            assertEquals("unclipped height " + id, target.getHeight(), visible.height());
            assertTrue("nonempty bounds " + id, visible.width() > 0 && visible.height() > 0);
        });
    }

    private Rect bounds(int id) throws Exception {
        return read(() -> {
            Rect bounds = new Rect();
            assertTrue(view(id).getGlobalVisibleRect(bounds));
            return bounds;
        });
    }

    private void assertSelection(int id, int start, int end) {
        EditText input = view(id);
        assertEquals(start, input.getSelectionStart());
        assertEquals(end, input.getSelectionEnd());
    }

    private void assertDialog(int title, int button) throws Exception {
        assertAccessibleText(read(() -> activity.getString(title)), true);
        String id = button == android.R.string.ok ? "android:id/button1" : "android:id/button2";
        String expected = read(() -> activity.getString(button));
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(id)) {
                        try {
                            String label = String.valueOf(node.getText());
                            if (node.isVisibleToUser() && node.isEnabled()
                                    && (expected.equals(label) || expected.toUpperCase(
                                    read(() -> activity.getResources().getConfiguration().locale)).equals(label))) return;
                        } finally { node.recycle(); }
                    }
                } finally { root.recycle(); }
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("localized dialog button missing: " + id + " " + expected);
    }

    private void assertAccessibleText(String expected, boolean exact) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try { if (containsText(root, expected, exact)) return; }
                finally { root.recycle(); }
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("localized dialog text missing: " + expected);
    }

    private boolean containsText(AccessibilityNodeInfo node, String expected, boolean exact) {
        CharSequence text = node.getText();
        if (node.isVisibleToUser() && text != null
                && (exact ? expected.contentEquals(text) : text.toString().contains(expected))) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (containsText(child, expected, exact)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    private void capture(String name) throws Exception {
        File directory = new File(context.getFilesDir(), "public-output-preview-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        assertNotNull("evidence hierarchy", root);
        try (FileOutputStream out = new FileOutputStream(new File(directory, "fixed-" + name + ".tree.txt"))) {
            StringBuilder tree = new StringBuilder();
            appendTree(root, tree);
            out.write(tree.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally { root.recycle(); }
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("evidence screenshot", screenshot);
        try (FileOutputStream out = new FileOutputStream(new File(directory, "fixed-" + name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { screenshot.recycle(); }
    }

    private void appendTree(AccessibilityNodeInfo node, StringBuilder out) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        out.append(node.getViewIdResourceName()).append(' ').append(node.getClassName()).append(' ')
                .append(node.getText()).append(' ').append(node.getContentDescription()).append(' ')
                .append(bounds).append(" visible=").append(node.isVisibleToUser())
                .append(" enabled=").append(node.isEnabled()).append(" focused=").append(node.isFocused())
                .append(" checked=").append(node.isChecked()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { appendTree(child, out); } finally { child.recycle(); }
        }
    }

    private void dismissDialog() throws Exception {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("dialog dismissed", () -> activity.hasWindowFocus());
        hideKeyboard();
    }

    private void hideKeyboard() throws Exception {
        ui(() -> ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(view(R.id.editorRoot).getWindowToken(), 0));
        instrumentation.waitForIdleSync();
    }

    private boolean imeVisible() {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view(R.id.editorRoot));
        return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
    }

    private void settle() {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(100);
        instrumentation.waitForIdleSync();
    }

    private void await(String message, Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 65000;
        do {
            if (read(condition)) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message + ": " + read(() -> text(R.id.tvGeometryStatus) + "; " + text(R.id.tvRawError)));
    }

    private TabLayout tabs() { return view(R.id.editorTabs); }
    private ScrollView currentPanel() { return view(PANELS[tabs().getSelectedTabPosition()]); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private String text(int id) { return ((TextView) view(id)).getText().toString(); }
    private <T extends View> T view(int id) {
        T target = activity.findViewById(id);
        assertNotNull("required resource " + id, target);
        return target;
    }
    private boolean flag(String name) throws Exception { return (Boolean) field(name); }
    private Object field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }
    private void setField(String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }
    private Object invoke(String name) throws Exception { return invoke(name, new Class<?>[0]); }
    private Object invoke(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(activity, values);
    }
    private interface UiAction { void run() throws Exception; }
    private void ui(UiAction action) throws Exception { read(() -> { action.run(); return null; }); }
    private <T> T read(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.call()); } catch (Throwable thrown) { error.set(thrown); }
        });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() instanceof Exception) throw (Exception) error.get();
        return result.get();
    }
}
