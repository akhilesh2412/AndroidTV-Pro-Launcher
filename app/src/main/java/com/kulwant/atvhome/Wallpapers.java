package com.kulwant.atvhome;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The home-screen photos. Each is a 1280x720 JPEG (cropped on the phone) at files/wallpapers/<id>.jpg,
 * plus, when the phone sent one, the full photo at <id>_o.jpg so it can be re-framed later.
 * On first run the wallpapers bundled with the app (res/drawable-nodpi/wallpaper_N.jpg) are copied
 * here, so every photo can be removed or replaced the same way.
 */
final class Wallpapers {

    interface Listener {
        /** Called on the main thread; show is a photo to put on screen now, or null after a delete. */
        void onWallpapersChanged(File added);
    }

    static final int MAX_PHOTOS = 60;
    private static volatile Listener listener;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Wallpapers() {}

    static void setListener(Listener l) {
        listener = l;
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "wallpapers");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Oldest first. */
    static synchronized List<File> userPhotos(Context c) {
        seed(c);
        File[] fs = dir(c).listFiles((d, n) -> n.matches("\\d+\\.jpg"));
        List<File> out = new ArrayList<>();
        if (fs == null) return out;
        Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        out.addAll(Arrays.asList(fs));
        return out;
    }

    private static void seed(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        if (p.getBoolean("seeded", false)) return;
        long base = System.currentTimeMillis();
        for (int i = 1; i <= 20; i++) {
            int res = c.getResources().getIdentifier("wallpaper_" + i, "drawable", c.getPackageName());
            if (res == 0) continue;
            copy(c, res, new File(dir(c), (base + i) + ".jpg"));
            // optional full photo behind the crop (wallpaper_N_o.jpg), for re-framing on the phone
            int orig = c.getResources().getIdentifier("wallpaper_" + i + "_o", "drawable", c.getPackageName());
            if (orig != 0) copy(c, orig, new File(dir(c), (base + i) + "_o.jpg"));
        }
        p.edit().putBoolean("seeded", true).apply();
    }

    private static void copy(Context c, int res, File to) {
        try (java.io.InputStream in = c.getResources().openRawResource(res);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ignored) {
        }
    }

    static File original(Context c, String id) {
        File f = photo(c, id);
        if (f == null) return null;
        File o = new File(dir(c), id + "_o.jpg");
        return o.isFile() ? o : f;
    }

    static File photo(Context c, String id) {
        if (id == null || !id.matches("\\d+")) return null;
        File f = new File(dir(c), id + ".jpg");
        return f.isFile() ? f : null;
    }

    /** Saves a JPEG if it decodes as an image; null if it doesn't or the album is full. */
    static synchronized File add(Context c, byte[] jpeg) throws Exception {
        if (!isImage(jpeg) || userPhotos(c).size() >= MAX_PHOTOS) return null;
        File f = new File(dir(c), System.currentTimeMillis() + ".jpg");
        write(f, jpeg);
        notifyChanged(f);
        return f;
    }

    /** New framing (or a different photo) for an existing slot; keeps its place in the rotation. */
    static synchronized File replace(Context c, String id, byte[] jpeg) throws Exception {
        File f = photo(c, id);
        if (f == null || !isImage(jpeg)) return null;
        write(f, jpeg);
        notifyChanged(f);
        return f;
    }

    /** The full photo behind a crop, kept so the phone can re-frame it later. */
    static synchronized boolean setOriginal(Context c, String id, byte[] jpeg) throws Exception {
        if (photo(c, id) == null || !isImage(jpeg)) return false;
        write(new File(dir(c), id + "_o.jpg"), jpeg);
        return true;
    }

    static synchronized boolean delete(Context c, String id) {
        File f = photo(c, id);
        if (f == null || !f.delete()) return false;
        new File(dir(c), id + "_o.jpg").delete();
        notifyChanged(null);
        return true;
    }

    /** Puts a photo on screen now. */
    static void show(Context c, String id) {
        File f = photo(c, id);
        if (f != null) notifyChanged(f);
    }

    private static boolean isImage(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        return o.outWidth > 0 && o.outHeight > 0;
    }

    private static void write(File f, byte[] data) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed");
    }

    private static void notifyChanged(File added) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onWallpapersChanged(added);
        });
    }
}
