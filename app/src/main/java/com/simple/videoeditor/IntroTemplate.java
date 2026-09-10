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
    private int textSize;          // 字体大小 (sp)
    private int textColor;         // 文字颜色 (ARGB)
    private int backgroundColor;   // 背景颜色 (ARGB)
    private float textX;           // 文字X位置 (0-1, 相对位置)
    private float textY;           // 文字Y位置 (0-1, 相对位置)
    private int durationMs;        // 片头时长 (毫秒)
    private String fontStyle;      // 字体风格: "normal", "bold", "italic", "bold_italic"
    
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
            json.put("name", name);
            json.put("text", text);
            json.put("textSize", textSize);
            json.put("textColor", textColor);
            json.put("backgroundColor", backgroundColor);
            json.put("textX", textX);
            json.put("textY", textY);
            json.put("durationMs", durationMs);
            json.put("fontStyle", fontStyle);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json.toString();
    }
    
    /**
     * 从 JSON 字符串创建模板
     */
    public static IntroTemplate fromJson(String jsonString) {
        try {
            JSONObject json = new JSONObject(jsonString);
            return new IntroTemplate(
                json.getString("name"),
                json.getString("text"),
                json.getInt("textSize"),
                json.getInt("textColor"),
                json.getInt("backgroundColor"),
                (float) json.getDouble("textX"),
                (float) json.getDouble("textY"),
                json.getInt("durationMs"),
                json.getString("fontStyle")
            );
        } catch (JSONException e) {
            e.printStackTrace();
            return new IntroTemplate();  // 返回默认模板
        }
    }
    
    /**
     * 创建模板的副本
     */
    public IntroTemplate copy() {
        return new IntroTemplate(name, text, textSize, textColor, backgroundColor,
                                textX, textY, durationMs, fontStyle);
    }
    
    @Override
    public String toString() {
        return name + " (" + text + ")";
    }
}
