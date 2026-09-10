package com.simple.videoeditor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * 片头模板管理器
 * 负责模板的持久化存储和读取
 */
public class IntroTemplateManager {
    private static final String PREFS_NAME = "intro_templates";
    private static final String KEY_TEMPLATES = "templates";
    private static final String KEY_LAST_USED = "last_used_template";
    
    private Context context;
    private SharedPreferences prefs;
    
    public IntroTemplateManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * 保存模板
     */
    public boolean saveTemplate(IntroTemplate template) {
        List<IntroTemplate> templates = getAllTemplates();
        
        // 检查是否已存在同名模板，如果存在则更新
        boolean updated = false;
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).getName().equals(template.getName())) {
                templates.set(i, template);
                updated = true;
                break;
            }
        }
        
        // 如果不存在，则添加
        if (!updated) {
            templates.add(template);
        }
        
        return saveAllTemplates(templates);
    }
    
    /**
     * 删除模板
     */
    public boolean deleteTemplate(String templateName) {
        List<IntroTemplate> templates = getAllTemplates();
        boolean removed = templates.removeIf(t -> t.getName().equals(templateName));
        
        if (removed) {
            return saveAllTemplates(templates);
        }
        return false;
    }
    
    /**
     * 获取所有模板
     */
    public List<IntroTemplate> getAllTemplates() {
        List<IntroTemplate> templates = new ArrayList<>();
        
        String jsonString = prefs.getString(KEY_TEMPLATES, "[]");
        try {
            JSONArray jsonArray = new JSONArray(jsonString);
            for (int i = 0; i < jsonArray.length(); i++) {
                String templateJson = jsonArray.getString(i);
                templates.add(IntroTemplate.fromJson(templateJson));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        // 如果没有模板，创建几个默认模板
        if (templates.isEmpty()) {
            templates.addAll(createDefaultTemplates());
            saveAllTemplates(templates);
        }
        
        return templates;
    }
    
    /**
     * 根据名称获取模板
     */
    public IntroTemplate getTemplate(String name) {
        List<IntroTemplate> templates = getAllTemplates();
        for (IntroTemplate template : templates) {
            if (template.getName().equals(name)) {
                return template.copy();
            }
        }
        return null;
    }
    
    /**
     * 保存最后使用的模板名称
     */
    public void setLastUsedTemplate(String templateName) {
        prefs.edit().putString(KEY_LAST_USED, templateName).apply();
    }
    
    /**
     * 获取最后使用的模板
     */
    public IntroTemplate getLastUsedTemplate() {
        String lastUsedName = prefs.getString(KEY_LAST_USED, null);
        if (lastUsedName != null) {
            return getTemplate(lastUsedName);
        }
        
        // 如果没有记录，返回第一个模板
        List<IntroTemplate> templates = getAllTemplates();
        if (!templates.isEmpty()) {
            return templates.get(0).copy();
        }
        
        return new IntroTemplate();  // 返回默认模板
    }
    
    /**
     * 保存所有模板
     */
    private boolean saveAllTemplates(List<IntroTemplate> templates) {
        JSONArray jsonArray = new JSONArray();
        for (IntroTemplate template : templates) {
            jsonArray.put(template.toJson());
        }
        
        return prefs.edit()
                .putString(KEY_TEMPLATES, jsonArray.toString())
                .commit();
    }
    
    /**
     * 创建默认模板
     */
    private List<IntroTemplate> createDefaultTemplates() {
        List<IntroTemplate> templates = new ArrayList<>();
        
        // 模板1: 经典黑底白字
        IntroTemplate classic = new IntroTemplate();
        classic.setName("经典黑底白字");
        classic.setText("我的视频");
        classic.setTextSize(48);
        classic.setTextColor(0xFFFFFFFF);  // 白色
        classic.setBackgroundColor(0xFF000000);  // 黑色
        classic.setTextX(0.5f);
        classic.setTextY(0.5f);
        classic.setDurationMs(3000);
        classic.setFontStyle("bold");
        templates.add(classic);
        
        // 模板2: 现代蓝色
        IntroTemplate modern = new IntroTemplate();
        modern.setName("现代蓝色");
        modern.setText("我的视频");
        modern.setTextSize(52);
        modern.setTextColor(0xFFFFFFFF);  // 白色
        modern.setBackgroundColor(0xFF1976D2);  // 蓝色
        modern.setTextX(0.5f);
        modern.setTextY(0.4f);  // 偏上
        modern.setDurationMs(3000);
        modern.setFontStyle("bold");
        templates.add(modern);
        
        // 模板3: 红色标题
        IntroTemplate red = new IntroTemplate();
        red.setName("红色标题");
        red.setText("我的视频");
        red.setTextSize(56);
        red.setTextColor(0xFFFFFFFF);  // 白色
        red.setBackgroundColor(0xFFD32F2F);  // 红色
        red.setTextX(0.5f);
        red.setTextY(0.5f);
        red.setDurationMs(2500);
        red.setFontStyle("bold_italic");
        templates.add(red);
        
        return templates;
    }
    
    /**
     * 获取模板名称列表（用于 Spinner）
     */
    public String[] getTemplateNames() {
        List<IntroTemplate> templates = getAllTemplates();
        String[] names = new String[templates.size()];
        for (int i = 0; i < templates.size(); i++) {
            names[i] = templates.get(i).getName();
        }
        return names;
    }
}
