package com.dafeiyu.chat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small file-backed image loader with an LRU cache for thumbnails. */
public final class ImageLoader {
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> cache =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private ImageLoader() { }

    public static void load(File file, ImageView view, int targetPx, int placeholderColor) {
        if (file == null || !file.isFile()) return;
        String key = file.getAbsolutePath() + "@" + targetPx;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageBitmap(null);
        final String fKey = key;
        pool.execute(() -> {
            Bitmap bmp = decode(file, targetPx);
            if (bmp != null) cache.put(fKey, bmp);
            main.post(() -> {
                if (bmp != null) view.setImageBitmap(bmp);
            });
        });
    }

    public static Bitmap decode(File file, int targetPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max / sample > targetPx * 2) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
