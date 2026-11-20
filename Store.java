package com.dafeiyu.chat;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** File-backed conversation store. One JSON per conversation keeps large histories cheap to open. */
public class Store {
    private static final String TAG = "Store";
    private final Context ctx;
    private final File dir;
    private final File attDir;
    private final File indexFile;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final Object lock = new Object();
    // 串行后台写盘：避免在主线程做文件 IO 导致卡顿/ANR，同时保证写入顺序。
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "store-writer");
        t.setDaemon(true);
        return t;
    });

    public static class Entry {
        public String id;
        public String title;
        public long updatedAt;
        public long createdAt;
    }

    public Store(Context context) {
        ctx = context.getApplicationContext();
        dir = new File(ctx.getFilesDir(), "conversations");
        attDir = new File(ctx.getFilesDir(), "attachments");
        if (!dir.exists()) dir.mkdirs();
        if (!attDir.exists()) attDir.mkdirs();
        indexFile = new File(dir, "index.json");
        loadIndex();
    }

    public File attachmentsDir() { return attDir; }

    public File attachmentFile(String localName) {
        if (localName == null || localName.isEmpty()) return null;
        File f = new File(attDir, localName);
        if (!f.getParentFile().equals(attDir)) return null;
        return f;
    }

    public Conv create(Prefs p) {
        Conv c = new Conv();
        c.model = p.model();
        c.thinking = p.thinking();
        c.search = p.search();
        c.contextLimit = p.contextLimit();
        return c;
    }

    public Conv load(String id) {
        if (id == null || id.isEmpty()) return null;
        File f = convFile(id);
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            String s = readFully(in);
            Conv c = Conv.fromJson(new JSONObject(s));
            c.applyDefaults(Prefs.get(ctx));
            return c;
        } catch (Exception e) {
            Log.w(TAG, "load failed " + id, e);
            return null;
        }
    }

    /**
     * 异步保存：调用线程只做内存中的 JSON 序列化（快），实际磁盘写入放到后台串行执行，
     * 避免长对话/大附件在主线程写盘造成卡顿与 ANR。
     */
    public void save(Conv c) {
        if (c == null) return;
        c.touch();
        final String id = c.id;
        final long updatedAt = c.updatedAt, createdAt = c.createdAt;
        final String title = c.titleOr("新对话");
        final String json = c.toJson(true).toString();
        writer.execute(() -> writeConv(id, json, title, createdAt, updatedAt));
    }

    /** 真正写盘（后台执行）。 */
    private void writeConv(String id, String json, String title, long createdAt, long updatedAt) {
        synchronized (lock) {
            File f = convFile(id);
            File tmp = new File(dir, f.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                Log.w(TAG, "save failed", e);
                return;
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(f);
            }
            updateEntryMeta(id, title, createdAt, updatedAt);
            saveIndex();
        }
    }

    /** 同步保存，供删除前的最后写入等必须立即落盘的场景使用。 */
    public void saveNow(Conv c) {
        if (c == null) return;
        c.touch();
        writeConv(c.id, c.toJson(true).toString(), c.titleOr("新对话"),
                c.createdAt, c.updatedAt);
    }

    private void updateEntryMeta(String id, String title, long createdAt, long updatedAt) {
        for (Entry e : entries) {
            if (e.id.equals(id)) {
                e.title = title;
                e.updatedAt = updatedAt;
                e.createdAt = createdAt;
                return;
            }
        }
        Entry e = new Entry();
        e.id = id;
        e.title = title;
        e.updatedAt = updatedAt;
        e.createdAt = createdAt;
        entries.add(e);
    }

    public void delete(String id) {
        Conv c = load(id);
        if (c != null) {
            Set<String> names = new HashSet<>();
            for (Msg m : c.messages) for (Att a : m.attachments) if (a.localName != null && !a.localName.isEmpty()) names.add(a.localName);
            for (String n : names) {
                File f = attachmentFile(n);
                if (f != null) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        convFile(id).delete();
        synchronized (lock) {
            entries.removeIf(e -> e.id.equals(id));
            saveIndex();
        }
    }

    public void clearAll() {
        synchronized (lock) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
                f.delete();
            File[] atts = attDir.listFiles();
            if (atts != null) for (File f : atts) //noinspection ResultOfMethodCallIgnored
                f.delete();
            entries.clear();
            saveIndex();
        }
    }

    public ArrayList<Entry> list() {
        synchronized (lock) {
            ArrayList<Entry> out = new ArrayList<>(entries);
            out.removeIf(e -> !convFile(e.id).isFile());
            out.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
            return out;
        }
    }

    public void rename(String id, String title) {
        Conv c = load(id);
        if (c == null) return;
        c.title = title;
        c.titleAuto = false;
        save(c);
    }

    private File convFile(String id) {
        return new File(dir, "conv_" + id + ".json");
    }

    private void updateEntry(Conv c) {
        for (Entry e : entries) {
            if (e.id.equals(c.id)) {
                e.title = c.titleOr("新对话");
                e.updatedAt = c.updatedAt;
                e.createdAt = c.createdAt;
                return;
            }
        }
        Entry e = new Entry();
        e.id = c.id;
        e.title = c.titleOr("新对话");
        e.updatedAt = c.updatedAt;
        e.createdAt = c.createdAt;
        entries.add(e);
    }

    private void loadIndex() {
        synchronized (lock) {
            entries.clear();
            if (indexFile.isFile()) {
                try (InputStream in = new FileInputStream(indexFile)) {
                    JSONArray arr = new JSONArray(readFully(in));
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        Entry e = new Entry();
                        e.id = o.optString("id", "");
                        e.title = o.optString("title", "新对话");
                        e.updatedAt = o.optLong("updatedAt", 0);
                        e.createdAt = o.optLong("createdAt", 0);
                        if (!e.id.isEmpty()) entries.add(e);
                    }
                } catch (Exception ignored) { }
            }
            if (entries.isEmpty()) rebuildIndex();
        }
    }

    private void rebuildIndex() {
        File[] files = dir.listFiles((d, name) -> name.startsWith("conv_") && name.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            try (InputStream in = new FileInputStream(f)) {
                Conv c = Conv.fromJson(new JSONObject(readFully(in)));
                Entry e = new Entry();
                e.id = c.id;
                e.title = c.titleOr("新对话");
                e.updatedAt = c.updatedAt;
                e.createdAt = c.createdAt;
                if (!e.id.isEmpty()) entries.add(e);
            } catch (Exception ignored) { }
        }
        saveIndex();
    }

    private void saveIndex() {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", e.id);
                o.put("title", e.title);
                o.put("updatedAt", e.updatedAt);
                o.put("createdAt", e.createdAt);
            } catch (Exception ignored) { }
            arr.put(o);
        }
        File tmp = new File(dir, "index.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        //noinspection ResultOfMethodCallIgnored
        indexFile.delete();
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(indexFile);
    }

    private static String readFully(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
