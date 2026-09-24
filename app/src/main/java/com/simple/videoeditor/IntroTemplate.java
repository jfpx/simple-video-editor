package com.simple.videoeditor;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 片头模板
 * 存储片头视频的配置参数（文字、字体、颜色、大小、位置等）
 */
public class IntroTemplate {
    private String name;           // 模板名称
    private String text;           // 显示文字
    private int textSize;
    private int textColor;         // 文字颜色 (ARGB)
    private int backgroundColor;   // 背景颜色 (ARGB)
    private float textX;           // 文字X位置 (0-1, 相对位置)
    private float textY;           // 文字Y位置 (0-1, 相对位置)
    private int durationMs;        // 片头时长 (毫秒)
    private String fontStyle;      // 字体风格: "normal", "bold", "italic", "bold_italic"
    private String animation = "legacy", fontFamily = "sans-serif", alignment = "center";
    private Integer gradientColor;
    private String layout;
    static final int SCHEMA_VERSION = 2;
    private boolean sourceFrameBackground;
    private String sourceFrameSeconds = "0";

    public boolean hasSourceFrameBackground() { return sourceFrameBackground; }
    public String getSourceFrameSeconds() { return sourceFrameSeconds; }
    public void setSourceFrameBackground(boolean value) { sourceFrameBackground = value; }
    public void setSourceFrameSeconds(String value) { sourceFrameSeconds = value; }
    
    // 默认构造
    public IntroTemplate() {
        this.name = "默认模板";
        this.text = "我的视频";
        this.textSize = 48;
        this.textColor = 0xFFFFFFFF;  // 白色
        this.backgroundColor = 0xFF000000;  // 黑色
        this.textX = 0.5f;  // 居中
        this.textY = 0.5f;  // 居中
        this.durationMs = 3000;  // 3秒
        this.fontStyle = "bold";
    }
    
    // 完整构造
    public IntroTemplate(String name, String text, int textSize, int textColor, 
                        int backgroundColor, float textX, float textY, 
                        int durationMs, String fontStyle) {
        this.name = name;
        this.text = text;
        this.textSize = textSize;
        this.textColor = textColor;
        this.backgroundColor = backgroundColor;
        this.textX = textX;
        this.textY = textY;
        this.durationMs = durationMs;
        this.fontStyle = fontStyle;
    }
    
    // Getters
    public String getName() { return name; }
    public String getText() { return text; }
    public int getTextSize() { return textSize; }
    public int getTextColor() { return textColor; }
    public int getBackgroundColor() { return backgroundColor; }
    public float getTextX() { return textX; }
    public float getTextY() { return textY; }
    public int getDurationMs() { return durationMs; }
    public String getFontStyle() { return fontStyle; }
    public String getAnimation() { return animation; }
    public String getFontFamily() { return fontFamily; }
    public String getAlignment() { return alignment; }
    public int getGradientColor() { return gradientColor == null ? backgroundColor : gradientColor; }
    public boolean hasGradient() { return gradientColor != null; }
    public void clearGradient() { gradientColor = null; }
    // Unversioned programmatic templates retain their original renderer until snapshotted.
    public String getLayout() { return layout != null ? layout : animation.equals("legacy") ? "legacy-sp" : "canvas"; }
    public void setLayout(String value) { layout = value; }
    public void setAnimation(String value) { animation = value; }
    public void setFontFamily(String value) { fontFamily = value; }
    public void setAlignment(String value) { alignment = value; }
    public void setGradientColor(int value) { gradientColor = value; }
    
    // Setters
    public void setName(String name) { this.name = name; }
    public void setText(String text) { this.text = text; }
    public void setTextSize(int textSize) { this.textSize = textSize; }
    public void setTextColor(int textColor) { this.textColor = textColor; }
    public void setBackgroundColor(int backgroundColor) { this.backgroundColor = backgroundColor; }
    public void setTextX(float textX) { this.textX = textX; }
    public void setTextY(float textY) { this.textY = textY; }
    public void setDurationMs(int durationMs) { this.durationMs = durationMs; }
    public void setFontStyle(String fontStyle) { this.fontStyle = fontStyle; }
    
    /**
     * 转换为 JSON 字符串
     */
    public String toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("schemaVersion", SCHEMA_VERSION);
            json.put("layout", getLayout());
            json.put("name", name);
            json.put("text", text);
            json.put("textSize", textSize);
            json.put("textColor", textColor);
            json.put("backgroundColor", backgroundColor);
            json.put("textX", textX);
            json.put("textY", textY);
            json.put("durationMs", durationMs);
            json.put("fontStyle", fontStyle);
            json.put("animation", animation);
            json.put("fontFamily", fontFamily);
            json.put("alignment", alignment);
            if (gradientColor != null) json.put("gradientColor", gradientColor);
            json.put("sourceFrameBackground", sourceFrameBackground);
            json.put("sourceFrameSeconds", sourceFrameSeconds);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Cannot serialize title", e);
        }
        return json.toString();
    }
    
    /**
     * 从 JSON 字符串创建模板
     */
    public static IntroTemplate fromJson(String jsonString) {
        try {
            return fromJsonStrict(jsonString);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid title preset: " + e.getMessage(), e);
        }
    }

    static IntroTemplate fromJsonStrict(String jsonString) throws JSONException {
        return fromJsonStrict(new JSONObject(jsonString));
    }

    static IntroTemplate fromJsonStrict(JSONObject json) throws JSONException {
        return parse(json, true);
    }

    static IntroTemplate fromDraftJson(String jsonString) throws JSONException {
        IntroTemplate draft = parse(new JSONObject(jsonString), false);
        IntroTemplate valid = draft.copy();
        valid.setTextSize(48);
        valid.setDurationMs(3000);
        valid.setSourceFrameBackground(false);
        valid.validate();
        return draft;
    }

    private static IntroTemplate parse(JSONObject json, boolean validate) throws JSONException {
            int version = json.has("schemaVersion") ? integer(json, "schemaVersion") : 1;
            if (version != 1 && version != SCHEMA_VERSION) throw new JSONException("Unsupported title schema: " + version);
            IntroTemplate template = new IntroTemplate(
                string(json, "name"),
                string(json, "text"),
                integer(json, "textSize"),
                integer(json, "textColor"),
                integer(json, "backgroundColor"),
                number(json, "textX"),
                number(json, "textY"),
                integer(json, "durationMs"),
                string(json, "fontStyle")
            );
            template.setAnimation(optionalString(json, "animation", "legacy", version));
            template.setFontFamily(optionalString(json, "fontFamily", "sans-serif", version));
            template.setAlignment(optionalString(json, "alignment", "center", version));
            template.setLayout(version == 1 ? template.getLayout() : string(json, "layout"));
            if (json.has("gradientColor")) template.setGradientColor(integer(json, "gradientColor"));
            if (json.has("sourceFrameBackground") || version == SCHEMA_VERSION) {
                Object enabled = json.get("sourceFrameBackground");
                if (!(enabled instanceof Boolean)) throw new JSONException("sourceFrameBackground must be boolean");
                template.setSourceFrameBackground((Boolean) enabled);
            }
            template.setSourceFrameSeconds(optionalString(json, "sourceFrameSeconds", "0", version));
            try { if (validate) template.validate(); }
            catch (IllegalArgumentException error) { throw new JSONException(error.getMessage()); }
            return template;
    }

    void validate() {
        new EditConfig.Builder(android.net.Uri.EMPTY, 1).sourceSize(640, 360)
                .introTemplate(this, text).build();
    }

    private static String optionalString(JSONObject json, String key, String legacy, int version) throws JSONException {
        return version == 1 && !json.has(key) ? legacy : string(json, key);
    }

    private static String string(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof String)) throw new JSONException(key + " must be a string");
        return (String) value;
    }

    private static int integer(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new JSONException(key + " must be a signed 32-bit integer");
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
            throw new JSONException(key + " is outside signed 32-bit range");
        return (int) number;
    }

    private static float number(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Number)) throw new JSONException(key + " must be a number");
        return ((Number) value).floatValue();
    }
    
    /**
     * 创建模板的副本
     */
    public IntroTemplate copy() {
        IntroTemplate copy = new IntroTemplate(name, text, textSize, textColor, backgroundColor,
                textX, textY, durationMs, fontStyle);
        copy.animation = animation;
        copy.fontFamily = fontFamily;
        copy.alignment = alignment;
        copy.gradientColor = gradientColor;
        copy.layout = getLayout();
        copy.sourceFrameBackground = sourceFrameBackground;
        copy.sourceFrameSeconds = sourceFrameSeconds;
        return copy;
    }
    
    @Override
    public String toString() {
        return name + " (" + text + ")";
    }
}
