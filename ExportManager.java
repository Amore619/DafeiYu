package com.dafeiyu.chat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** One-tap ZIP export plus forgiving import. The archive contains JSON, Markdown and attachments. */
public final class ExportManager {
    private static final String TAG = "ExportManager";
    public static final String MIME = "application/zip";
    public static final String EXT = ".dfy";

    public interface Progress {
        void onProgress(String label);
    }

    private ExportManager() { }

    public static String defaultName(Conv c) {
        String title = c == null ? "对话" : FileUtils.sanitize(c.titleOr("对话"));
        if (title.length() > 22) title = title.substring(0, 22);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(new Date());
        return "大肥鱼_" + title + "_" + stamp + EXT;
    }

    public static void writeArchive(Conv c, Store store, OutputStream os, Progress progress) throws Exception {
        if (c == null) throw new Exception("没有可导出的对话");
        if (progress != null) progress.onProgress("正在整理对话…");
        Conv copy = Conv.fromJson(c.toJson(true));
        java.util.HashMap<String, String> archiveNames = new java.util.HashMap<>();
        for (Msg m : copy.messages) {
            for (Att a : m.attachments) {
                String ext = FileUtils.ext(a.name);
                if (ext.isEmpty() && a.localName.contains(".")) ext = FileUtils.ext(a.localName);
                String archiveName = "attachments/" + a.id + (ext.isEmpty() ? "" : "." + ext);
                archiveNames.put(a.id, archiveName);
                a.localName = archiveName;
            }
        }
        JSONObject manifest = new JSONObject();
        manifest.put("format", "dafeiyu.conversation");
        manifest.put("version", 1);
        manifest.put("app", "大肥鱼");
        manifest.put("appVersion", "1.0.0");
        manifest.put("exportedAt", System.currentTimeMillis());
        manifest.put("conversation", copy.toJson(true));

        ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8);
        zip.setLevel(6);
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();

        int total = c.messages.size();
        int done = 0;
        for (Msg m : c.messages) {
            done++;
            for (Att a : m.attachments) {
                File f = store.attachmentFile(a.localName);
                if (f == null || !f.isFile()) continue;
                String archiveName = archiveNames.get(a.id);
                if (archiveName == null) continue;
                if (progress != null) progress.onProgress("正在打包附件 " + done + "/" + total);
                zip.putNextEntry(new ZipEntry(archiveName));
                try (InputStream in = new java.io.FileInputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                }
                zip.closeEntry();
            }
        }
        if (progress != null) progress.onProgress("正在生成可读副本…");
        zip.putNextEntry(new ZipEntry("对话.md"));
        zip.write(toMarkdown(c).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
        if (progress != null) progress.onProgress("导出完成");
    }

    public static String toMarkdown(Conv c) {
        StringBuilder b = new StringBuilder();
        b.append("# ").append(c.titleOr("未命名对话")).append("\n\n");
        b.append("> 由「大肥鱼」导出 · ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date()))
                .append(" · ").append(c.model).append("\n\n---\n\n");
        for (Msg m : c.messages) {
            if (m.isUser()) b.append("## 你\n\n");
            else b.append("## 大肥鱼\n\n");
            if (m.attachments != null && !m.attachments.isEmpty()) {
                for (Att a : m.attachments) {
                    b.append("> 附件：").append(a.name).append("（").append(FileUtils.typeLabel(a)).append("）\n\n");
                }
            }
            if (m.isUser() && m.content != null && !m.content.trim().isEmpty()) {
                b.append(m.content).append("\n\n");
            } else if (m.isAssistant()) {
                if (m.reasoning != null && !m.reasoning.trim().isEmpty()) {
                    b.append("<details>\n<summary>思考过程</summary>\n\n")
                            .append(m.reasoning).append("\n\n</details>\n\n");
                }
                b.append(m.content == null ? "" : m.content).append("\n\n");
            }
            if (m.status == Msg.STATUS_ERROR && m.error != null && !m.error.isEmpty()) {
                b.append("> 生成失败：").append(m.error).append("\n\n");
            }
        }
        return b.toString();
    }

    public static Uri quickExportToDownloads(Context ctx, Conv c, Store store) throws Exception {
        if (Build.VERSION.SDK_INT < 29) return null;
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, defaultName(c));
        v.put(MediaStore.Downloads.MIME_TYPE, MIME);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/大肥鱼");
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("无法创建导出文件");
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new Exception("无法写入导出文件");
            writeArchive(c, store, out, null);
        } catch (Exception e) {
            cr.delete(uri, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    public static void exportToUri(Context ctx, Conv c, Store store, Uri uri, Progress progress) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new Exception("无法写入文件");
            writeArchive(c, store, out, progress);
        }
    }

    public static Conv importFromUri(Context ctx, Store store, Uri uri, Progress progress) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        File tmpDir = new File(ctx.getCacheDir(), "import_" + UUID.randomUUID());
        if (!tmpDir.mkdirs()) throw new Exception("无法创建临时目录");
        try (InputStream raw = cr.openInputStream(uri)) {
            if (raw == null) throw new Exception("无法读取文件");
            java.io.BufferedInputStream bin = new java.io.BufferedInputStream(raw);
            bin.mark(4);
            int b1 = bin.read(), b2 = bin.read();
            bin.reset();
            if (b1 == 'P' && b2 == 'K') {
                return importZip(ctx, store, bin, tmpDir, progress);
            } else {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = bin.read(buf)) > 0) {
                    total += n;
                    if (total > 50 * 1024 * 1024) throw new Exception("文件太大");
                    bos.write(buf, 0, n);
                }
                return importJson(ctx, store, new String(bos.toByteArray(), StandardCharsets.UTF_8), tmpDir, progress);
            }
        } finally {
            deleteTree(tmpDir);
        }
    }

    private static Conv importZip(Context ctx, Store store, InputStream in, File tmpDir, Progress progress) throws Exception {
        String manifest = null;
        long total = 0;
        ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8);
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            String name = e.getName().replace('\\', '/');
            if (name.contains("..") || name.startsWith("/")) continue;
            File out = new File(tmpDir, name.replace("/", File.separator));
            if (!out.getCanonicalPath().startsWith(tmpDir.getCanonicalPath())) continue;
            if (e.isDirectory()) continue;
            out.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n, len = 0;
                while ((n = zip.read(buf)) > 0) {
                    len += n;
                    total += n;
                    if (total > 300L * 1024 * 1024) throw new Exception("压缩包过大");
                    if (len > 100 * 1024 * 1024) throw new Exception("压缩包内单个文件过大");
                    fos.write(buf, 0, n);
                }
            }
            if (name.equals("manifest.json")) {
                manifest = readText(new File(tmpDir, "manifest.json"), 30 * 1024 * 1024);
            }
        }
        if (manifest == null) throw new Exception("不是有效的大肥鱼对话文件（缺少 manifest）");
        if (progress != null) progress.onProgress("正在恢复对话…");
        return importJson(ctx, store, manifest, tmpDir, progress);
    }

    private static Conv importJson(Context ctx, Store store, String json, File tmpDir, Progress progress) throws Exception {
        JSONObject root = new JSONObject(json);
        String format = root.optString("format", "");
        if (!format.isEmpty() && !format.equals("dafeiyu.conversation"))
            throw new Exception("不支持的对话格式");
        JSONObject co = root.optJSONObject("conversation");
        if (co == null) {
            if (root.has("messages")) co = root;
            else throw new Exception("文件里没有对话内容");
        }
        Conv c = Conv.fromJson(co);
        if (c.messages.isEmpty()) throw new Exception("这份对话是空的");
        c.id = UUID.randomUUID().toString();
        c.updatedAt = System.currentTimeMillis();
        int index = 0;
        for (Msg m : c.messages) {
            index++;
            for (Att a : m.attachments) {
                File src = null;
                if (a.localName != null && a.localName.startsWith("attachments/")) {
                    src = new File(tmpDir, a.localName.replace("/", File.separator));
                }
                if (src != null && src.isFile()) {
                    String ext = FileUtils.ext(a.name);
                    if (ext.isEmpty() && a.localName.contains(".")) ext = FileUtils.ext(a.localName);
                    String local = "att_" + a.id + (ext.isEmpty() ? "" : "." + ext);
                    File dst = new File(store.attachmentsDir(), local);
                    copyFile(src, dst);
                    a.localName = local;
                }
                if (a.fileId == null) a.fileId = "";
                a.state = a.fileId.isEmpty() ? 0 : 2;
            }
        }
        if (progress != null) progress.onProgress("正在保存对话…");
        store.save(c);
        return c;
    }

    private static String readText(File f, int max) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new Exception("文件内容过大");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        dst.getParentFile().mkdirs();
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ==================================================================== 全量备份 / 恢复
    // 把本机所有对话打包成单个 .dfy，写入公共 Download 目录（卸载 App 不会被删除）。
    // 格式与单对话导出兼容同一套附件拷贝逻辑，但 manifest.format 为 dafeiyu.full-backup。

    public static final String FORMAT_FULL = "dafeiyu.full-backup";
    private static final String FULL_NAME = "大肥鱼-完整备份.dfy";
    private static final String FULL_REL_PATH = Environment.DIRECTORY_DOWNLOADS + "/大肥鱼";

    /** 完整备份的默认文件名（带时间戳，避免多次备份互相覆盖）。 */
    public static String defaultFullBackupName() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(new Date());
        return "大肥鱼-完整备份_" + stamp + EXT;
    }

    /** 把所有对话及其附件写入输出流。 */
    public static void writeFullBackup(Store store, OutputStream os, Progress progress) throws Exception {
        java.util.List<Store.Entry> list = store.list();
        JSONObject manifest = new JSONObject();
        manifest.put("format", FORMAT_FULL);
        manifest.put("version", 1);
        manifest.put("app", "大肥鱼");
        manifest.put("appVersion", "1.0.0");
        manifest.put("exportedAt", System.currentTimeMillis());
        manifest.put("count", list.size());

        JSONArray convs = new JSONArray();
        java.util.HashMap<String, String> archiveNames = new java.util.HashMap<>();
        int total = list.size(), done = 0;
        for (Store.Entry e : list) {
            done++;
            if (progress != null) progress.onProgress("正在备份 " + done + "/" + total + " · " + e.title);
            Conv c = store.load(e.id);
            if (c == null) continue;
            Conv copy = Conv.fromJson(c.toJson(true));
            for (Msg m : copy.messages) {
                if (m.attachments == null) continue;
                for (Att a : m.attachments) {
                    String ext = FileUtils.ext(a.name);
                    if (ext.isEmpty() && a.localName != null && a.localName.contains("."))
                        ext = FileUtils.ext(a.localName);
                    String archiveName = "attachments/" + a.id + (ext.isEmpty() ? "" : "." + ext);
                    archiveNames.put(a.id, archiveName);
                    a.localName = archiveName;
                }
            }
            convs.put(copy.toJson(true));
        }
        manifest.put("conversations", convs);

        ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8);
        zip.setLevel(6);
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();

        // 收集全部附件，按 att id 去重
        java.util.HashSet<String> written = new java.util.HashSet<>();
        for (Store.Entry e : list) {
            Conv c = store.load(e.id);
            if (c == null) continue;
            for (Msg m : c.messages) {
                if (m.attachments == null) continue;
                for (Att a : m.attachments) {
                    String archiveName = archiveNames.get(a.id);
                    if (archiveName == null || written.contains(a.id)) continue;
                    File f = store.attachmentFile(a.localName);
                    if (f == null || !f.isFile()) continue;
                    zip.putNextEntry(new ZipEntry(archiveName));
                    try (InputStream in = new java.io.FileInputStream(f)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                    } catch (Exception ignore) { }
                    zip.closeEntry();
                    written.add(a.id);
                }
            }
        }

        // 生成一份所有对话的可读 Markdown 汇总
        if (progress != null) progress.onProgress("正在生成可读副本…");
        StringBuilder md = new StringBuilder();
        md.append("# 大肥鱼 · 聊天记录完整备份\n\n");
        md.append("> 共 ").append(list.size()).append(" 个对话 · 导出于 ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date())).append("\n\n---\n\n");
        for (Store.Entry e : list) {
            Conv c = store.load(e.id);
            if (c == null) continue;
            md.append(toMarkdown(c)).append("\n\n---\n\n");
        }
        zip.putNextEntry(new ZipEntry("全部对话.md"));
        zip.write(md.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
        if (progress != null) progress.onProgress("备份完成");
    }

    /** 把完整备份写到公共 Download/大肥鱼 目录，返回 Uri。minSdk 30 走 MediaStore。 */
    public static Uri fullBackupToDownloads(Context ctx, Store store, Progress progress) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, defaultFullBackupName());
        v.put(MediaStore.Downloads.MIME_TYPE, MIME);
        v.put(MediaStore.Downloads.RELATIVE_PATH, FULL_REL_PATH);
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("无法创建备份文件");
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new Exception("无法写入备份文件");
            writeFullBackup(store, out, progress);
        } catch (Exception e) {
            cr.delete(uri, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        // 只保留最近 2 份完整备份，避免占用过多空间
        pruneOldBackups(ctx);
        return uri;
    }

    /** 删除 Download/大肥鱼 下过旧的完整备份，仅保留最新 2 份。失败不影响主流程。 */
    private static void pruneOldBackups(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            String[] proj = { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_ADDED };
            String sel = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                    + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            String[] args = { FULL_REL_PATH + "/", "大肥鱼-完整备份_%.dfy" };
            android.database.Cursor cur = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    proj, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (cur == null) return;
            java.util.ArrayList<Uri> old = new java.util.ArrayList<>();
            int idCol = cur.getColumnIndex(MediaStore.MediaColumns._ID);
            int skip = 0;
            try {
                while (cur.moveToNext()) {
                    if (skip++ < 2) continue; // 保留最新 2 份
                    long id = cur.getLong(idCol);
                    old.add(android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id));
                }
            } finally { cur.close(); }
            for (Uri u : old) cr.delete(u, null, null);
        } catch (Throwable ignore) { }
    }

    /** 从 .dfy 恢复：自动识别完整备份（多对话）与单对话两种格式。 */
    public static int restoreFromUri(Context ctx, Store store, Uri uri, Progress progress) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        File tmpDir = new File(ctx.getCacheDir(), "restore_" + UUID.randomUUID());
        if (!tmpDir.mkdirs()) throw new Exception("无法创建临时目录");
        try (InputStream raw = cr.openInputStream(uri)) {
            if (raw == null) throw new Exception("无法读取备份文件");
            java.io.BufferedInputStream bin = new java.io.BufferedInputStream(raw);
            bin.mark(4);
            int b1 = bin.read(), b2 = bin.read();
            bin.reset();
            if (b1 != 'P' || b2 != 'K') throw new Exception("不是有效的 .dfy 备份文件");
            ZipInputStream zip = new ZipInputStream(bin, StandardCharsets.UTF_8);
            ZipEntry e;
            long total = 0;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (name.contains("..") || name.startsWith("/")) continue;
                File out = new File(tmpDir, name.replace("/", File.separator));
                if (!out.getCanonicalPath().startsWith(tmpDir.getCanonicalPath())) continue;
                if (e.isDirectory()) continue;
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n, len = 0;
                    while ((n = zip.read(buf)) > 0) {
                        len += n; total += n;
                        if (total > 500L * 1024 * 1024) throw new Exception("备份文件过大");
                        if (len > 150L * 1024 * 1024) throw new Exception("备份内单个文件过大");
                        fos.write(buf, 0, n);
                    }
                }
            }
        } finally {
            // 临时目录在恢复完成后统一清理
        }
        File manifestFile = new File(tmpDir, "manifest.json");
        if (!manifestFile.isFile()) {
            deleteTree(tmpDir);
            throw new Exception("备份里缺少清单文件");
        }
        String manifestStr = readText(manifestFile, 40 * 1024 * 1024);
        JSONObject root = new JSONObject(manifestStr);
        String format = root.optString("format", "");
        int restored;
        try {
            if (FORMAT_FULL.equals(format)) {
                restored = restoreFull(root, tmpDir, store, progress);
            } else {
                // 兼容单对话 .dfy：复用原有导入，返回 1
                importJson(ctx, store, manifestStr, tmpDir, progress);
                restored = 1;
            }
        } finally {
            deleteTree(tmpDir);
        }
        return restored;
    }

    /** 恢复完整备份，按原 id 去重：已存在的对话用更新更新时间者覆盖，避免重复副本。 */
    private static int restoreFull(JSONObject root, File tmpDir, Store store, Progress progress) throws Exception {
        JSONArray convs = root.optJSONArray("conversations");
        if (convs == null) throw new Exception("备份里没有对话内容");
        int n = convs.length(), restored = 0, done = 0;
        for (int i = 0; i < n; i++) {
            done++;
            if (progress != null) progress.onProgress("正在恢复 " + done + "/" + n);
            JSONObject co = convs.optJSONObject(i);
            if (co == null) continue;
            Conv c = Conv.fromJson(co);
            if (c.messages.isEmpty()) continue;
            Conv existing = store.load(c.id);
            if (existing != null && existing.updatedAt >= c.updatedAt) continue; // 本机已是较新版本
            // 附件：从备份目录拷回，沿用 att_<id> 命名
            for (Msg m : c.messages) {
                if (m.attachments == null) continue;
                for (Att a : m.attachments) {
                    File src = null;
                    if (a.localName != null && a.localName.startsWith("attachments/"))
                        src = new File(tmpDir, a.localName.replace("/", File.separator));
                    if (src != null && src.isFile()) {
                        String ext = FileUtils.ext(a.name);
                        if (ext.isEmpty() && a.localName.contains(".")) ext = FileUtils.ext(a.localName);
                        String local = "att_" + a.id + (ext.isEmpty() ? "" : "." + ext);
                        copyFile(src, new File(store.attachmentsDir(), local));
                        a.localName = local;
                    } else {
                        // 备份中缺附件时，保留可被引用但本地缺失的文件名
                        if (a.localName != null && a.localName.startsWith("attachments/")) {
                            a.localName = "att_" + a.id;
                        }
                    }
                    if (a.fileId == null) a.fileId = "";
                    a.state = a.fileId.isEmpty() ? 0 : 2;
                }
            }
            store.save(c);
            restored++;
        }
        return restored;
    }
}
