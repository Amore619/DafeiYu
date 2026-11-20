package com.dafeiyu.chat;

import android.app.Application;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获：把未捕获异常的完整堆栈写到公共「下载」目录，
 * 文件名固定为「大肥鱼-崩溃日志.txt」，方便用户直接发回排查。
 */
public class CrashApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String report = buildReport(thread, throwable);
            writeToDownloads(report);
            writeToPrivate(report);
            // 交给系统默认处理器，保持原本的“闪退”行为，避免卡死在黑屏
            if (previous != null) {
                try { previous.uncaughtException(thread, throwable); } catch (Throwable ignore) { }
            }
            try { android.os.Process.killProcess(android.os.Process.myPid()); } catch (Throwable ignore) { }
        });
    }

    private String buildReport(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("大肥鱼 崩溃日志");
        pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
        pw.println("线程: " + (thread == null ? "?" : thread.getName()));
        pw.println("设备: " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("系统: Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        pw.println("版本: 1.0.0");
        pw.println("--------------------------------------------------");
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private void writeToDownloads(String report) {
        OutputStream os = null;
        try {
            String name = "大肥鱼-崩溃日志.txt";
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            }
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                os = getContentResolver().openOutputStream(uri);
                if (os != null) {
                    os.write(report.getBytes("UTF-8"));
                    os.flush();
                }
            }
        } catch (Throwable ignore) {
        } finally {
            if (os != null) try { os.close(); } catch (Throwable ignore) { }
        }
    }

    private void writeToPrivate(String report) {
        FileWriter fw = null;
        try {
            File dir = new File(getExternalFilesDir(null), "crash");
            if (!dir.exists()) dir.mkdirs();
            fw = new FileWriter(new File(dir, "last-crash.txt"), false);
            fw.write(report);
            fw.flush();
        } catch (Throwable ignore) {
        } finally {
            if (fw != null) try { fw.close(); } catch (Throwable ignore) { }
        }
    }
}
