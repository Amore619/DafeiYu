package com.dafeiyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

public class Msg {
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static final int STATUS_COMPLETE = 0;
    public static final int STATUS_STREAMING = 1;
    public static final int STATUS_ERROR = 2;

    public String id = UUID.randomUUID().toString();
    public String role = ROLE_USER;
    public String content = "";
    public String reasoning = "";
    public String error = "";
    public String finishReason = "";
    public long createdAt = System.currentTimeMillis();
    public int status = STATUS_COMPLETE;
    public boolean thinking;
    public boolean search;
    public String model = "";
    public ArrayList<Att> attachments = new ArrayList<>();
    public ArrayList<ToolCall> tools = new ArrayList<>();
    public ArrayList<Cite> cites = new ArrayList<>();
    public String serverToolCalls = "[]";
    public String annotations = "[]";
    public int promptTokens;
    public int completionTokens;
    public long thinkingMs;

    public boolean isUser() { return ROLE_USER.equals(role); }
    public boolean isAssistant() { return ROLE_ASSISTANT.equals(role); }
    public boolean isStreaming() { return status == STATUS_STREAMING; }
    public boolean hasVisibleContent() { return !content.trim().isEmpty(); }

    public String summaryForRequest() {
        return content == null ? "" : content;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("role", role);
            o.put("content", content);
            o.put("reasoning", reasoning);
            o.put("error", error);
            o.put("finishReason", finishReason);
            o.put("createdAt", createdAt);
            o.put("status", status);
            o.put("thinking", thinking);
            o.put("search", search);
            o.put("model", model);
            o.put("attachments", new JSONArray(Att.listToJson(attachments)));
            JSONArray ts = new JSONArray();
            for (ToolCall t : tools) {
                JSONObject to = new JSONObject();
                to.put("id", t.id);
                to.put("step", t.step);
                to.put("type", t.type);
                to.put("kind", t.kind);
                to.put("action", t.action);
                to.put("query", t.query);
                to.put("url", t.url);
                to.put("pageTitle", t.pageTitle);
                to.put("status", t.status);
                to.put("error", t.error);
                JSONArray ss = new JSONArray();
                for (ToolCall.Source s : t.sources) {
                    JSONObject so = new JSONObject();
                    so.put("title", s.title);
                    so.put("url", s.url);
                    so.put("site", s.site);
                    so.put("snippet", s.snippet);
                    ss.put(so);
                }
                to.put("sources", ss);
                ts.put(to);
            }
            o.put("tools", ts);
            o.put("cites", new JSONArray(Cite.listToJson(cites)));
            o.put("serverToolCalls", serverToolCalls == null ? "[]" : serverToolCalls);
            o.put("annotations", annotations == null ? "[]" : annotations);
            o.put("promptTokens", promptTokens);
            o.put("completionTokens", completionTokens);
            o.put("thinkingMs", thinkingMs);
        } catch (Exception ignored) { }
        return o;
    }

    public static Msg fromJson(JSONObject o) {
        Msg m = new Msg();
        m.id = o.optString("id", UUID.randomUUID().toString());
        m.role = o.optString("role", ROLE_USER);
        m.content = o.optString("content", "");
        m.reasoning = o.optString("reasoning", "");
        m.error = o.optString("error", "");
        m.finishReason = o.optString("finishReason", "");
        m.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        m.status = o.optInt("status", STATUS_COMPLETE);
        if (m.status == STATUS_STREAMING) {
            m.status = STATUS_ERROR;
            if (m.error.isEmpty()) m.error = "生成被中断";
        }
        m.thinking = o.optBoolean("thinking", false);
        m.search = o.optBoolean("search", false);
        m.model = o.optString("model", "");
        m.attachments = Att.listFromJson(o.optJSONArray("attachments"));
        m.serverToolCalls = o.optString("serverToolCalls", "[]");
        m.annotations = o.optString("annotations", "[]");
        m.promptTokens = o.optInt("promptTokens", 0);
        m.completionTokens = o.optInt("completionTokens", 0);
        m.thinkingMs = o.optLong("thinkingMs", 0);
        JSONArray ts = o.optJSONArray("tools");
        if (ts != null) for (int i = 0; i < ts.length(); i++) {
            JSONObject to = ts.optJSONObject(i);
            if (to == null) continue;
            ToolCall t = new ToolCall();
            t.id = to.optString("id", "");
            t.step = to.optInt("step", 0);
            t.type = to.optString("type", "");
            t.kind = to.optString("kind", "");
            t.action = to.optString("action", "");
            t.query = to.optString("query", "");
            t.url = to.optString("url", "");
            t.pageTitle = to.optString("pageTitle", "");
            t.status = to.optString("status", "completed");
            t.error = to.optString("error", "");
            JSONArray ss = to.optJSONArray("sources");
            if (ss != null) for (int j = 0; j < ss.length(); j++) {
                JSONObject so = ss.optJSONObject(j);
                if (so == null) continue;
                ToolCall.Source s = new ToolCall.Source();
                s.title = so.optString("title", "");
                s.url = so.optString("url", "");
                s.site = so.optString("site", "");
                s.snippet = so.optString("snippet", "");
                t.sources.add(s);
            }
            t.sourceCount = t.sources.size();
            m.tools.add(t);
        }
        m.cites = Cite.fromAnnotations(parseArray(m.annotations));
        return m;
    }

    public static JSONArray parseArray(String s) {
        try { return new JSONArray(s == null || s.isEmpty() ? "[]" : s); }
        catch (Exception e) { return new JSONArray(); }
    }
}
