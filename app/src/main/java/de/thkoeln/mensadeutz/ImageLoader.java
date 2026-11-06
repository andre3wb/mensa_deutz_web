package de.thkoeln.mensadeutz;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ImageLoader {

    private static final LruCache<String, Bitmap> MEMORY_CACHE;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    static {
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = Math.max(4 * 1024, maxMemory / 8);
        MEMORY_CACHE = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    private ImageLoader() {}

    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    public static void load(Context context, String url, ImageView target, int targetSizePx, Callback optionalCallback) {
        if (url == null || url.isEmpty()) {
            target.setTag(null);
            target.setImageBitmap(null);
            if (optionalCallback != null) optionalCallback.onLoaded(null);
            return;
        }

        final String cacheKey = url + "#" + targetSizePx;
        target.setTag(cacheKey);

        Bitmap cached = MEMORY_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            if (optionalCallback != null) optionalCallback.onLoaded(cached);
            return;
        }

        target.setImageBitmap(null);
        final File diskDir = new File(context.getCacheDir(), "dish_images");
        if (!diskDir.exists()) {
            diskDir.mkdirs();
        }

        EXECUTOR.execute(() -> {
            Bitmap bmp = null;
            try {
                File diskFile = new File(diskDir, sha256(url));
                byte[] bytes = null;
                if (diskFile.exists() && diskFile.length() > 0) {
                    try (FileInputStream fis = new FileInputStream(diskFile)) {
                        bytes = readAllBytes(fis);
                    }
                }
                if (bytes == null || bytes.length == 0) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", "MensaDeutzAndroid/1.0");
                    if (conn.getResponseCode() == 200) {
                        try (InputStream is = conn.getInputStream()) {
                            bytes = readAllBytes(is);
                        }
                        if (bytes != null && bytes.length > 0) {
                            try (FileOutputStream fos = new FileOutputStream(diskFile)) {
                                fos.write(bytes);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    conn.disconnect();
                }

                if (bytes != null && bytes.length > 0) {
                    bmp = decodeSampledBitmap(bytes, targetSizePx, targetSizePx);
                    if (bmp != null) {
                        MEMORY_CACHE.put(cacheKey, bmp);
                    }
                }
            } catch (Exception ignored) {
            }

            final Bitmap finalBmp = bmp;
            MAIN_HANDLER.post(() -> {
                if (cacheKey.equals(target.getTag())) {
                    if (finalBmp != null) {
                        target.setAlpha(0f);
                        target.setImageBitmap(finalBmp);
                        target.animate().alpha(1f).setDuration(180).start();
                    }
                    if (optionalCallback != null) {
                        optionalCallback.onLoaded(finalBmp);
                    }
                }
            });
        });
    }

    private static Bitmap decodeSampledBitmap(byte[] data, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);

        int inSampleSize = 1;
        if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
            final int halfHeight = options.outHeight / 2;
            final int halfWidth = options.outWidth / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        options.inSampleSize = inSampleSize;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
