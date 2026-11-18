package com.ibraprogram.apkshare;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** نسخ وحفظ وتجهيز ملفات APK. */
public final class ApkUtils {

    public static final String FOLDER = "Ibra APK Share";
    public static final String MIME_APK = "application/vnd.android.package-archive";
    public static final String MIME_ZIP = "application/zip";

    private ApkUtils() {
    }

    public static File shareDir(Context c) {
        File d = new File(c.getCacheDir(), "share");
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    public static void clearShareDir(Context c) {
        File[] fs = shareDir(c).listFiles();
        if (fs == null) return;
        for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    public static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".apk")) return MIME_APK;
        if (n.endsWith(".zip") || n.endsWith(".apks")) return MIME_ZIP;
        return "application/octet-stream";
    }

    /** اسم آمن للملف: اسم التطبيق + الإصدار. */
    public static String baseName(AppInfo a) {
        String n = a.label + "_v" + a.versionLabel();
        n = n.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").replaceAll("_+", "_");
        if (n.length() > 80) n = n.substring(0, 80);
        return n;
    }

    /** الامتداد: .apk للتطبيق العادي، .apks للمقسّم، .zip عند اختيار وضع ZIP. */
    public static String fileName(AppInfo a, boolean zip) {
        if (zip) return baseName(a) + ".zip";
        return baseName(a) + (a.isSplit() ? ".apks" : ".apk");
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
    }

    private static void addEntry(ZipOutputStream zos, String entryName, File f) throws IOException {
        ZipEntry e = new ZipEntry(entryName);
        e.setTime(f.lastModified());
        zos.putNextEntry(e);
        InputStream in = new FileInputStream(f);
        try {
            copy(in, zos);
        } finally {
            in.close();
        }
        zos.closeEntry();
    }

    /** يكتب التطبيق إلى مخرج: APK مباشر، أو ملف مضغوط يضم كل أجزاء التطبيق. */
    public static void writeApp(AppInfo a, OutputStream rawOut, boolean zip) throws IOException {
        OutputStream out = new BufferedOutputStream(rawOut, 256 * 1024);
        try {
            if (!zip && !a.isSplit()) {
                InputStream in = new FileInputStream(a.sourceDir);
                try {
                    copy(in, out);
                } finally {
                    in.close();
                }
            } else {
                ZipOutputStream zos = new ZipOutputStream(out);
                zos.setLevel(Deflater.BEST_SPEED);
                if (a.isSplit()) {
                    addEntry(zos, "base.apk", new File(a.sourceDir));
                    Set<String> used = new HashSet<>();
                    used.add("base.apk");
                    for (String sp : a.splitDirs) {
                        if (sp == null) continue;
                        File f = new File(sp);
                        String n = f.getName();
                        int i = 1;
                        while (used.contains(n)) n = (i++) + "_" + f.getName();
                        used.add(n);
                        addEntry(zos, n, f);
                    }
                } else {
                    addEntry(zos, baseName(a) + ".apk", new File(a.sourceDir));
                }
                zos.finish();
            }
            out.flush();
        } finally {
            out.close();
        }
    }

    /** يجهّز ملفات الإرسال في مجلد مؤقت داخل التطبيق. */
    public static ArrayList<File> prepareShareFiles(Context c, List<AppInfo> apps, boolean zip) throws IOException {
        File dir = shareDir(c);
        ArrayList<File> files = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (AppInfo a : apps) {
            String name = fileName(a, zip);
            int i = 2;
            while (used.contains(name)) {
                int dot = name.lastIndexOf('.');
                name = name.substring(0, dot) + "_" + (i++) + name.substring(dot);
            }
            used.add(name);
            File f = new File(dir, name);
            File tmp = new File(dir, name + ".part");
            writeApp(a, new FileOutputStream(tmp), zip);
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            if (!tmp.renameTo(f)) throw new IOException("rename failed");
            files.add(f);
        }
        return files;
    }

    /** المسار الذي يظهر للمستخدم بعد الحفظ. */
    public static String saveLocation() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER;
    }

    /** يحفظ التطبيقات في مجلد التنزيلات/Ibra APK Share ويرجع عدد الملفات المحفوظة. */
    public static int saveToDownloads(Context c, List<AppInfo> apps) throws IOException {
        int count = 0;
        for (AppInfo a : apps) {
            String name = fileName(a, false);
            String mime = mimeFor(name);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, saveLocation());
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("MediaStore insert failed");
                try {
                    OutputStream os = cr.openOutputStream(uri);
                    if (os == null) throw new IOException("open failed");
                    writeApp(a, os, false);
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                } catch (IOException | RuntimeException e) {
                    try {
                        cr.delete(uri, null, null);
                    } catch (Exception ignored) {
                    }
                    throw e;
                }
            } else {
                @SuppressWarnings("deprecation")
                File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File dir = new File(root, FOLDER);
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                File f = new File(dir, name);
                int i = 2;
                while (f.exists()) {
                    int dot = name.lastIndexOf('.');
                    f = new File(dir, name.substring(0, dot) + " (" + (i++) + ")" + name.substring(dot));
                }
                writeApp(a, new FileOutputStream(f), false);
                MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
            }
            count++;
        }
        return count;
    }
}
