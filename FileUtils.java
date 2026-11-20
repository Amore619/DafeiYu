package com.dafeiyu.chat;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

public final class FileUtils {
    // DeepSeek Files API limits by type (official): images 64 MiB,
    // PDF/Word/PPT documents 100 MiB, plain text/table/ebook 32 MiB.
    public static final long MAX_IMAGE = 64L * 1024 * 1024;
    public static final long MAX_DOC = 100L * 1024 * 1024;
    public static final long MAX_DATA = 32L * 1024 * 1024;

    private FileUtils() { }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public static String ext(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    public static String mimeFor(String name) {
        String e = ext(name);
        switch (e) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "pdf": return "application/pdf";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "txt": return "text/plain";
            case "md": return "text/markdown";
            case "csv": return "text/csv";
            case "json": return "application/json";
            case "html": case "htm": return "text/html";
            case "xml": return "application/xml";
            case "epub": return "application/epub+zip";
            default: return "application/octet-stream";
        }
    }

    public static boolean isImageMime(String mime, String name) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/")) return true;
        String e = ext(name);
        return e.equals("jpg") || e.equals("jpeg") || e.equals("png") || e.equals("webp")
                || e.equals("gif") || e.equals("bmp") || e.equals("heic") || e.equals("heif");
    }

    public static String purposeFor(Att a) {
        if (a.isImage()) return "user_data";
        String e = ext(a.name);
        // PDF/Word/PPT: rich layout recognition so the model also sees charts,
        // photos and diagrams in the document (matches DeepSeek app behaviour).
        if (e.equals("pdf") || e.equals("doc") || e.equals("docx") || e.equals("ppt") || e.equals("pptx"))
            return "rich_layout_recognition";
        return "user_data";
    }

    /** Per-type upload cap that mirrors the official Files API limits. */
    public static long sizeCapFor(Att a) {
        if (a.isImage()) return MAX_IMAGE;
        String p = a.purpose;
        if ("rich_layout_recognition".equals(p) || "text_recognition".equals(p)) return MAX_DOC;
        return MAX_DATA;
    }

    public static String sanitize(String s) {
        if (s == null) return "未命名";
        String v = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (v.isEmpty()) v = "未命名";
        if (v.length() > 48) v = v.substring(0, 48);
        return v;
    }

    public static String queryName(Context ctx, Uri uri, String fallback) {
        String name = fallback;
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.trim().isEmpty()) name = n;
                }
            }
        } catch (Exception ignored) { }
        return name;
    }

    public static long querySize(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    public static Att importImage(Context ctx, Uri uri, File dir) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        String name = queryName(ctx, uri, "图片_" + System.currentTimeMillis() + ".jpg");
        long declared = querySize(ctx, uri);
        if (declared > MAX_IMAGE) throw new Exception("图片太大（上限 64 MB）");

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取图片");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("无法识别这张图片");

        int sample = 1;
        int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxSide / sample > 4096) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取图片");
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new Exception("无法解码这张图片");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) {
                ExifInterface exif = new ExifInterface(in);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) { }

        Matrix matrix = new Matrix();
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) matrix.postRotate(90);
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) matrix.postRotate(180);
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) matrix.postRotate(270);
        else if (orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL) matrix.postScale(-1, 1);
        if (!matrix.isIdentity()) {
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            if (rotated != bmp) bmp.recycle();
            bmp = rotated;
        }

        int w = bmp.getWidth(), h = bmp.getHeight();
        int longSide = Math.max(w, h);
        if (longSide > 1600) {
            float scale = 1600f / longSide;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), true);
            bmp.recycle();
            bmp = scaled;
            w = bmp.getWidth();
            h = bmp.getHeight();
        }

        Att a = new Att();
        a.id = UUID.randomUUID().toString();
        a.type = Att.TYPE_IMAGE;
        a.name = name;
        a.mime = "image/jpeg";
        a.localName = "att_" + a.id + ".jpg";
        a.width = w;
        a.height = h;
        a.purpose = "user_data";
        File outFile = new File(dir, a.localName);
        // JPEG has no alpha; flatten onto white so screenshots stay readable.
        Bitmap flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flat);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bmp, 0, 0, null);
        bmp.recycle();
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            boolean ok = flat.compress(Bitmap.CompressFormat.JPEG, 86, out);
            if (!ok) throw new Exception("图片保存失败");
        } finally {
            flat.recycle();
        }
        a.size = outFile.length();
        return a;
    }

    public static Att importFile(Context ctx, Uri uri, File dir) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        String name = queryName(ctx, uri, "文件_" + System.currentTimeMillis());
        long declared = querySize(ctx, uri);
        String ext = ext(name);
        Att a = new Att();
        a.id = UUID.randomUUID().toString();
        a.type = Att.TYPE_FILE;
        a.name = name;
        a.mime = cr.getType(uri);
        if (a.mime == null || a.mime.isEmpty()) a.mime = mimeFor(name);
        a.localName = "att_" + a.id + (ext.isEmpty() ? "" : "." + ext);
        a.purpose = purposeFor(a);
        long cap = sizeCapFor(a);
        if (declared > cap) throw new Exception("文件太大（上限 " + (cap / 1024 / 1024) + " MB）");
        File outFile = new File(dir, a.localName);
        long total = 0;
        try (InputStream in = cr.openInputStream(uri); FileOutputStream out = new FileOutputStream(outFile)) {
            if (in == null) throw new Exception("无法读取文件");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > cap) throw new Exception("文件太大（上限 " + (cap / 1024 / 1024) + " MB）");
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            throw e;
        }
        if (total <= 0) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            throw new Exception("文件是空的");
        }
        a.size = total;
        return a;
    }

    public static boolean supportedFile(String name) {
        String e = ext(name);
        return e.equals("pdf") || e.equals("doc") || e.equals("docx") || e.equals("ppt") || e.equals("pptx")
                || e.equals("xls") || e.equals("xlsx") || e.equals("txt") || e.equals("md")
                || e.equals("csv") || e.equals("json") || e.equals("html") || e.equals("htm")
                || e.equals("xml") || e.equals("epub") || e.equals("mobi") || e.equals("log")
                || e.equals("py") || e.equals("java") || e.equals("js") || e.equals("ts")
                || e.equals("kt") || e.equals("c") || e.equals("cpp") || e.equals("h")
                || e.equals("go") || e.equals("rs") || e.equals("sql") || e.equals("sh")
                || e.equals("yaml") || e.equals("yml") || e.equals("toml") || e.equals("ini");
    }

    public static String typeLabel(Att a) {
        if (a.isImage()) return "图片";
        String e = ext(a.name).toUpperCase(Locale.ROOT);
        if (e.isEmpty()) return "文件";
        if (e.equals("DOCX") || e.equals("DOC")) return "Word 文档";
        if (e.equals("PDF")) return "PDF 文档";
        if (e.equals("PPTX") || e.equals("PPT")) return "演示文稿";
        if (e.equals("XLSX") || e.equals("XLS")) return "表格";
        return e + " 文件";
    }
}
