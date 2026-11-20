package com.dafeiyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

public class Conv {
    public String id = UUID.randomUUID().toString();
    public String title = "";
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = System.currentTimeMillis();
    public ArrayList<Msg> messages = new ArrayList<>();
    public int lastPromptTokens;
    public int lastCompletionTokens;
    public int contextLimit;
    public int warnLevel; // 0 normal, 1 ten-round warning, 2 urgent
    public boolean warningDismissed;
    public String model = "deepseek-flash";
    public boolean thinking = true;
    public boolean search = false;
    public boolean titleAuto = true;

    public Msg last() { return messages.isEmpty() ? null : messages.get(messages.size() - 1); }

    public int turns() {
        int n = 0;
        for (Msg m : messages) if (m.isAssistant() && m.hasVisibleContent()) n++;
        return n;
    }

    public String titleOr(String fallback) {
        if (title != null && !title.trim().isEmpty()) return title.trim();
        for (Msg m : messages) {
            if (m.isUser() && m.content != null && !m.content.trim().isEmpty()) {
                String t = m.content.trim().replace('\n', ' ');
                return t.length() > 24 ? t.substring(0, 24) + "…" : t;
            }
        }
        return fallback;
    }

    public void touch() { updatedAt = System.currentTimeMillis(); }

    public JSONObject toJson(boolean includeMessages) {
        JSONObject o = new JSONObject();
        try {
            o.put("format", "dafeiyu.conversation");
            o.put("version", 1);
            o.put("id", id);
            o.put("title", title);
            o.put("createdAt", createdAt);
            o.put("updatedAt", updatedAt);
            o.put("lastPromptTokens", lastPromptTokens);
            o.put("lastCompletionTokens", lastCompletionTokens);
            o.put("contextLimit", contextLimit);
            o.put("warnLevel", warnLevel);
            o.put("warningDismissed", warningDismissed);
            o.put("model", model);
            o.put("thinking", thinking);
            o.put("search", search);
            o.put("titleAuto", titleAuto);
            if (includeMessages) {
                JSONArray arr = new JSONArray();
                for (Msg m : messages) arr.put(m.toJson());
                o.put("messages", arr);
            }
        } catch (Exception ignored) { }
        return o;
    }

    public static Conv fromJson(JSONObject o) {
        Conv c = new Conv();
        c.id = o.optString("id", UUID.randomUUID().toString());
        c.title = o.optString("title", "");
        c.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        c.updatedAt = o.optLong("updatedAt", c.createdAt);
        c.lastPromptTokens = o.optInt("lastPromptTokens", 0);
        c.lastCompletionTokens = o.optInt("lastCompletionTokens", 0);
        c.contextLimit = o.optInt("contextLimit", 0);
        c.warnLevel = o.optInt("warnLevel", 0);
        c.warningDismissed = o.optBoolean("warningDismissed", false);
        c.model = o.optString("model", "deepseek-flash");
        c.thinking = o.optBoolean("thinking", true);
        c.search = o.optBoolean("search", false);
        c.titleAuto = o.optBoolean("titleAuto", true);
        JSONArray arr = o.optJSONArray("messages");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null) c.messages.add(Msg.fromJson(m));
        }
        return c;
    }

    public void applyDefaults(Prefs p) {
        if (model == null || model.isEmpty()) model = p.model();
        if (contextLimit <= 0) contextLimit = p.contextLimit();
    }
}
