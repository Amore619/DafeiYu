package com.dafeiyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class Cite {
    public String url = "";
    public String title = "";
    public int start;
    public int end;

    public static ArrayList<Cite> fromAnnotations(JSONArray arr) {
        ArrayList<Cite> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            JSONObject c = o.optJSONObject("url_citation");
            if (c == null) continue;
            Cite cite = new Cite();
            cite.url = c.optString("url", "");
            cite.title = c.optString("title", cite.url);
            cite.start = c.optInt("start_index", 0);
            cite.end = c.optInt("end_index", 0);
            if (!cite.url.isEmpty()) out.add(cite);
        }
        return out;
    }

    public static String listToJson(ArrayList<Cite> list) {
        JSONArray arr = new JSONArray();
        if (list != null) for (Cite c : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("type", "url_citation");
                JSONObject u = new JSONObject();
                u.put("url", c.url);
                u.put("title", c.title);
                u.put("start_index", c.start);
                u.put("end_index", c.end);
                o.put("url_citation", u);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        return arr.toString();
    }
}
