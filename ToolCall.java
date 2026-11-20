package com.dafeiyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Visual/search call metadata, kept separately from the raw API payload. */
public class ToolCall {
    public String id = "";
    public int step;
    public String type = "deepseek_web_search";
    public String kind = "search";
    public String action = "search";
    public String query = "";
    public String url = "";
    public String pageTitle = "";
    public String status = "completed";
    public String error = "";
    public int sourceCount;
    public ArrayList<Source> sources = new ArrayList<>();

    public static class Source {
        public String title = "";
        public String url = "";
        public String site = "";
        public String snippet = "";
    }

    public boolean isSearch() {
        return "search".equals(kind) || "deep_search".equals(kind)
                || "image_search".equals(kind) || "deep_image_search".equals(kind);
    }

    public String titleText() {
        if (isSearch()) {
            if (!query.isEmpty()) return "搜索 " + query;
            return "图片搜索";
        }
        if (!pageTitle.isEmpty()) return pageTitle;
        if (!url.isEmpty()) return url;
        return "阅读网页";
    }

    public static ToolCall fromCall(JSONObject call, int step) {
        ToolCall t = new ToolCall();
        t.step = step;
        t.id = call.optString("id", "");
        t.type = call.optString("type", "deepseek_web_search");
        t.kind = call.optString("kind", "search");
        t.status = call.optString("status", "completed");
        t.error = call.optString("error", "");
        JSONObject action = call.optJSONObject("action");
        if (action != null) {
            t.action = action.optString("type", "search");
            JSONArray qs = action.optJSONArray("queries");
            if (qs != null && qs.length() > 0) t.query = qs.optString(0, "");
            t.url = action.optString("url", "");
        }
        JSONObject page = call.optJSONObject("page");
        if (page != null) {
            t.pageTitle = page.optString("title", "");
            if (t.url.isEmpty()) t.url = page.optString("url", "");
        }
        JSONArray src = call.optJSONArray("sources");
        if (src != null) {
            for (int i = 0; i < src.length(); i++) {
                JSONObject s = src.optJSONObject(i);
                if (s == null) continue;
                Source source = new Source();
                source.title = s.optString("title", "");
                source.url = s.optString("url", "");
                source.site = s.optString("site_name", "");
                source.snippet = s.optString("snippet", "");
                t.sources.add(source);
            }
        }
        t.sourceCount = t.sources.size();
        return t;
    }

    public static ArrayList<ToolCall> fromServerCalls(JSONArray steps) {
        ArrayList<ToolCall> out = new ArrayList<>();
        if (steps == null) return out;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            JSONArray calls = step.optJSONArray("calls");
            if (calls == null) continue;
            for (int j = 0; j < calls.length(); j++) {
                JSONObject call = calls.optJSONObject(j);
                if (call != null) out.add(fromCall(call, i));
            }
        }
        return out;
    }

    public static String summary(ArrayList<ToolCall> tools) {
        if (tools == null || tools.isEmpty()) return "";
        int searches = 0, pages = 0, sources = 0;
        for (ToolCall t : tools) {
            if (t.isSearch()) { searches++; sources += t.sourceCount; }
            else pages++;
        }
        if (searches > 0 && sources > 0) return "已搜索 " + searches + " 次 · " + sources + " 个来源";
        if (searches > 0) return "已联网搜索 " + searches + " 次";
        if (pages > 0) return "已阅读 " + pages + " 个网页";
        return "已使用联网工具";
    }
}
