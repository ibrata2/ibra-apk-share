package com.ibraprogram.apkshare;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** معلومات تطبيق مثبّت على الجهاز. */
public class AppInfo {
    public String label;
    public String labelLower;
    public String packageName;
    public String versionName;
    public long versionCode;
    public String sourceDir;
    public String[] splitDirs;
    public boolean system;
    public long size;
    public long installTime;
    public long updateTime;

    public boolean isSplit() {
        return splitDirs != null && splitDirs.length > 0;
    }

    public String versionLabel() {
        return (versionName == null || versionName.isEmpty()) ? String.valueOf(versionCode) : versionName;
    }

    public Drawable loadIcon(Context c) {
        PackageManager pm = c.getPackageManager();
        try {
            return pm.getApplicationIcon(packageName);
        } catch (Exception e) {
            return pm.getDefaultActivityIcon();
        }
    }

    @SuppressWarnings("deprecation")
    public static AppInfo from(PackageManager pm, PackageInfo pi) {
        if (pi == null) return null;
        ApplicationInfo ai = pi.applicationInfo;
        if (ai == null || ai.sourceDir == null) return null;
        AppInfo a = new AppInfo();
        a.packageName = pi.packageName;
        String label = null;
        try {
            CharSequence l = ai.loadLabel(pm);
            if (l != null) label = l.toString().trim();
        } catch (Exception ignored) {
        }
        a.label = (label == null || label.isEmpty()) ? pi.packageName : label;
        a.labelLower = a.label.toLowerCase(Locale.ROOT);
        a.versionName = pi.versionName;
        a.versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        a.sourceDir = ai.publicSourceDir != null ? ai.publicSourceDir : ai.sourceDir;
        a.splitDirs = ai.splitPublicSourceDirs != null ? ai.splitPublicSourceDirs : ai.splitSourceDirs;
        a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        a.installTime = pi.firstInstallTime;
        a.updateTime = pi.lastUpdateTime;
        long s = new File(a.sourceDir).length();
        if (a.splitDirs != null) {
            for (String sp : a.splitDirs) {
                if (sp != null) s += new File(sp).length();
            }
        }
        a.size = s;
        return a;
    }

    /** يجلب كل التطبيقات المثبتة (مع حل احتياطي للأجهزة القديمة). */
    public static List<AppInfo> loadAll(Context c) {
        PackageManager pm = c.getPackageManager();
        List<AppInfo> out = new ArrayList<>();
        List<PackageInfo> pkgs = null;
        try {
            pkgs = pm.getInstalledPackages(0);
        } catch (RuntimeException e) {
            // بعض الأجهزة القديمة ترمي خطأ عند كثرة التطبيقات
            pkgs = new ArrayList<>();
            try {
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    try {
                        pkgs.add(pm.getPackageInfo(ai.packageName, 0));
                    } catch (Exception ignored) {
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (pkgs != null) {
            for (PackageInfo pi : pkgs) {
                AppInfo a = from(pm, pi);
                if (a != null) out.add(a);
            }
        }
        return out;
    }
}
