package com.dafeiyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

/** A local attachment. Files live in the app's private attachment directory. */
public class Att {
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_FILE = "file";

    public String id = UUID.randomUUID().toString();
    public String type = TYPE_FILE;
    public String name = "";
    public String mime = "application/octet-stream";
    public String localName = "";
    public long size;
    public int width;
    public int height;
    public String fileId = "";
    public String purpose = "user_data";

    // UI-only state, not persisted.
    public transient int state; // 0 idle, 1 uploading, 2 done, 3 failed
    public transient int progress;
    public transient String error = "";

    public boolean isImage() { return TYPE_IMAGE.equals(type); }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("type", type);
            o.put("name", name);
            o.put("mime", mime);
            o.put("localName", localName);
            o.put("size", size);
            o.put("width", width);
            o.put("height", height);
            o.put("fileId", fileId);
            o.put("purpose", purpose);
        } catch (Exception ignored) { }
        return o;
    }

    public static Att fromJson(JSONObject o) {
        Att a = new Att();
        a.id = o.optString("id", UUID.randomUUID().toString());
        a.type = o.optString("type", TYPE_FILE);
        a.name = o.optString("name", "附件");
        a.mime = o.optString("mime", "application/octet-stream");
        a.localName = o.optString("localName", "");
        a.size = o.optLong("size", 0);
        a.width = o.optInt("width", 0);
        a.height = o.optInt("height", 0);
        a.fileId = o.optString("fileId", "");
        a.purpose = o.optString("purpose", "user_data");
        a.state = a.fileId.isEmpty() ? 0 : 2;
        return a;
    }

    public static String listToJson(ArrayList<Att> list) {
        JSONArray arr = new JSONArray();
        if (list != null) for (Att a : list) arr.put(a.toJson());
        return arr.toString();
    }

    public static ArrayList<Att> listFromJson(JSONArray arr) {
        ArrayList<Att> list = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) list.add(fromJson(o));
        }
        return list;
    }
}
