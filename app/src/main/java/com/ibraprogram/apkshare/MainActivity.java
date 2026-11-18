package com.ibraprogram.apkshare;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.Collator;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements AppAdapter.Listener {

    private static final int REQ_WRITE = 41;
    private static final String SITE = "https://ibraprogram.netlify.app/";
    private static final String TELEGRAM = "https://t.me/ibraprogram";
    private static final String TIKTOK = "https://www.tiktok.com/@ibraprogram";

    private static final int SORT_NAME = 0, SORT_SIZE = 1, SORT_DATE = 2;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<AppInfo> allApps = new ArrayList<>();

    private SharedPreferences prefs;
    private AppAdapter adapter;
    private EditText search;
    private Switch switchSystem;
    private TextView countText, emptyText, selCount, progressText;
    private View selBar, progress, btnClearSearch;
    private String query = "";
    private Runnable afterPermission;
    private boolean loaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("ibra_apk_share", MODE_PRIVATE);

        ListView list = findViewById(R.id.list);
        search = findViewById(R.id.search);
        switchSystem = findViewById(R.id.switchSystem);
        countText = findViewById(R.id.countText);
        emptyText = findViewById(R.id.emptyText);
        selBar = findViewById(R.id.selBar);
        selCount = findViewById(R.id.selCount);
        progress = findViewById(R.id.progress);
        progressText = findViewById(R.id.progressText);
        btnClearSearch = findViewById(R.id.btnClearSearch);

        adapter = new AppAdapter(this, this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                AppInfo a = adapter.getItem(pos);
                if (!adapter.getSelectedApps().isEmpty()) adapter.toggle(a);
                else showAppDialog(a);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                adapter.toggle(adapter.getItem(pos));
                return true;
            }
        });

        switchSystem.setChecked(prefs.getBoolean("system", false));
        switchSystem.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("system", on).apply();
                applyFilter();
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                btnClearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                applyFilter();
            }
        });
        btnClearSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search.setText("");
            }
        });

        findViewById(R.id.btnInfo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        });
        findViewById(R.id.btnMore).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        findViewById(R.id.btnSelClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adapter.clearSelection();
            }
        });
        findViewById(R.id.btnSelAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adapter.selectAllVisible();
            }
        });
        findViewById(R.id.btnSelBt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendApps(adapter.getSelectedApps(), true);
            }
        });
        findViewById(R.id.btnSelSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveApps(adapter.getSelectedApps());
            }
        });
        findViewById(R.id.btnSelShare).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendApps(adapter.getSelectedApps(), false);
            }
        });

        // تنظيف الملفات المؤقتة من المرة السابقة
        if (savedInstanceState == null) {
            bg.execute(new Runnable() {
                @Override
                public void run() {
                    ApkUtils.clearShareDir(MainActivity.this);
                }
            });
        }
        loadApps();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // ربما ثُبّت أو حُذف تطبيق أثناء الغياب
        if (loaded) loadApps();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        adapter.shutdown();
        bg.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (!adapter.getSelectedApps().isEmpty()) {
            adapter.clearSelection();
            return;
        }
        super.onBackPressed();
    }

    // ===================== تحميل وفلترة =====================

    private void loadApps() {
        if (!loaded) showProgress(getString(R.string.loading));
        bg.execute(new Runnable() {
            @Override
            public void run() {
                final List<AppInfo> apps = AppInfo.loadAll(MainActivity.this);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        allApps.clear();
                        allApps.addAll(apps);
                        loaded = true;
                        hideProgress();
                        applyFilter();
                    }
                });
            }
        });
    }

    private void applyFilter() {
        boolean sys = switchSystem.isChecked();
        List<AppInfo> out = new ArrayList<>();
        for (AppInfo a : allApps) {
            if (!sys && a.system) continue;
            if (!query.isEmpty() && !a.labelLower.contains(query)
                    && !a.packageName.toLowerCase(Locale.ROOT).contains(query)) continue;
            out.add(a);
        }
        int sort = prefs.getInt("sort", SORT_NAME);
        if (sort == SORT_SIZE) {
            Collections.sort(out, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo x, AppInfo y) {
                    return Long.compare(y.size, x.size);
                }
            });
        } else if (sort == SORT_DATE) {
            Collections.sort(out, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo x, AppInfo y) {
                    return Long.compare(y.updateTime, x.updateTime);
                }
            });
        } else {
            final Collator col = Collator.getInstance();
            Collections.sort(out, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo x, AppInfo y) {
                    return col.compare(x.label, y.label);
                }
            });
        }
        adapter.setItems(out);
        countText.setText(getString(R.string.count_fmt, out.size()));
        emptyText.setVisibility(loaded && out.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onSelectionChanged(int count) {
        selBar.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        selCount.setText(getString(R.string.selected_fmt, count));
    }

    // ===================== القوائم والنوافذ =====================

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        int sort = prefs.getInt("sort", SORT_NAME);
        m.add(1, 10, 0, R.string.sort_name).setChecked(sort == SORT_NAME);
        m.add(1, 11, 1, R.string.sort_size).setChecked(sort == SORT_SIZE);
        m.add(1, 12, 2, R.string.sort_date).setChecked(sort == SORT_DATE);
        m.setGroupCheckable(1, true, true);
        m.add(2, 20, 3, R.string.send_as_zip).setCheckable(true).setChecked(prefs.getBoolean("zip", false));
        m.add(3, 30, 4, R.string.refresh);
        m.add(3, 40, 5, R.string.about);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 10:
                        setSort(SORT_NAME);
                        return true;
                    case 11:
                        setSort(SORT_SIZE);
                        return true;
                    case 12:
                        setSort(SORT_DATE);
                        return true;
                    case 20:
                        prefs.edit().putBoolean("zip", !prefs.getBoolean("zip", false)).apply();
                        return true;
                    case 30:
                        showProgress(getString(R.string.loading));
                        loaded = false;
                        loadApps();
                        return true;
                    case 40:
                        showAbout();
                        return true;
                }
                return false;
            }
        });
        pm.show();
    }

    private void setSort(int s) {
        prefs.edit().putInt("sort", s).apply();
        applyFilter();
    }

    private void showAppDialog(final AppInfo a) {
        View v = getLayoutInflater().inflate(R.layout.dialog_app, null);
        ((ImageView) v.findViewById(R.id.dIcon)).setImageDrawable(a.loadIcon(this));
        ((TextView) v.findViewById(R.id.dName)).setText(a.label);

        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.d_package, a.packageName)).append('\n');
        sb.append(getString(R.string.d_version, a.versionLabel(), a.versionCode)).append('\n');
        sb.append(getString(R.string.d_size, Formatter.formatFileSize(this, a.size))).append('\n');
        sb.append(getString(R.string.d_type, getString(a.system ? R.string.type_system : R.string.type_user))).append('\n');
        sb.append(getString(R.string.d_installed, df.format(new Date(a.installTime)))).append('\n');
        sb.append(getString(R.string.d_updated, df.format(new Date(a.updateTime))));
        if (a.isSplit()) sb.append("\n\n⚠️ ").append(getString(R.string.split_note));
        ((TextView) v.findViewById(R.id.dDetails)).setText(sb.toString());

        final AlertDialog d = new AlertDialog.Builder(this, R.style.IbraDialog).setView(v).create();
        final List<AppInfo> one = new ArrayList<>();
        one.add(a);

        v.findViewById(R.id.dBt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                sendApps(one, true);
            }
        });
        v.findViewById(R.id.dSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                saveApps(one);
            }
        });
        v.findViewById(R.id.dShare).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                sendApps(one, false);
            }
        });
        v.findViewById(R.id.dOpen).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                Intent li = getPackageManager().getLaunchIntentForPackage(a.packageName);
                if (li == null) {
                    toast(getString(R.string.no_launch));
                    return;
                }
                d.dismiss();
                safeStart(li);
            }
        });
        v.findViewById(R.id.dInfo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.packageName)));
            }
        });
        d.show();
    }

    private void showAbout() {
        View v = getLayoutInflater().inflate(R.layout.dialog_about, null);
        String ver = "1.0.0";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
        } catch (Exception ignored) {
        }
        ((TextView) v.findViewById(R.id.aVersion)).setText(getString(R.string.version_fmt, ver));
        final AlertDialog d = new AlertDialog.Builder(this, R.style.IbraDialog).setView(v).create();
        v.findViewById(R.id.aSite).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                openUrl(SITE);
            }
        });
        v.findViewById(R.id.aTelegram).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                openUrl(TELEGRAM);
            }
        });
        v.findViewById(R.id.aTiktok).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                openUrl(TIKTOK);
            }
        });
        v.findViewById(R.id.aShareSelf).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                try {
                    PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                    AppInfo self = AppInfo.from(getPackageManager(), pi);
                    if (self != null) {
                        List<AppInfo> one = new ArrayList<>();
                        one.add(self);
                        sendApps(one, false);
                    }
                } catch (Exception e) {
                    toast(getString(R.string.error_fmt, e.getMessage()));
                }
            }
        });
        d.show();
    }

    // ===================== الإرسال والحفظ =====================

    private void sendApps(final List<AppInfo> apps, final boolean bluetooth) {
        if (apps == null || apps.isEmpty()) return;
        final boolean zip = prefs.getBoolean("zip", false);
        showProgress(getString(R.string.preparing));
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final ArrayList<File> files = ApkUtils.prepareShareFiles(MainActivity.this, apps, zip);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideProgress();
                            launchSend(files, bluetooth);
                            adapter.clearSelection();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideProgress();
                            toast(getString(R.string.error_fmt, String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        });
    }

    private void launchSend(List<File> files, boolean bluetooth) {
        if (files.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<>();
        String mime = null;
        for (File f : files) {
            uris.add(ApkProvider.uriFor(this, f));
            String m = ApkUtils.mimeFor(f.getName());
            if (mime == null) mime = m;
            else if (!mime.equals(m)) mime = "*/*";
        }

        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND);
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.setType(mime);
        ClipData clip = ClipData.newRawUri("", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (bluetooth) {
            ComponentName bt = findBluetooth(send);
            if (bt == null) {
                // بعض الأجهزة لا تعلن قبول APK، نجرب بنوع عام
                Intent probe = new Intent(send);
                probe.setType("*/*");
                bt = findBluetooth(probe);
                if (bt != null) send.setType("*/*");
            }
            if (bt != null) {
                send.setComponent(bt);
                try {
                    startActivity(send);
                    return;
                } catch (Exception e) {
                    send.setComponent(null);
                }
            }
            toast(getString(R.string.no_bluetooth));
        }
        safeStart(Intent.createChooser(send, getString(R.string.share_title)));
    }

    private ComponentName findBluetooth(Intent intent) {
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(intent, 0);
        for (ResolveInfo r : list) {
            if (r.activityInfo == null) continue;
            String p = r.activityInfo.packageName.toLowerCase(Locale.ROOT);
            String n = r.activityInfo.name.toLowerCase(Locale.ROOT);
            if (p.contains("bluetooth") || n.contains("bluetooth")) {
                return new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            }
        }
        return null;
    }

    private void saveApps(final List<AppInfo> apps) {
        if (apps == null || apps.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            final List<AppInfo> copy = new ArrayList<>(apps);
            afterPermission = new Runnable() {
                @Override
                public void run() {
                    saveApps(copy);
                }
            };
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        showProgress(getString(R.string.saving));
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final int n = ApkUtils.saveToDownloads(MainActivity.this, apps);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideProgress();
                            adapter.clearSelection();
                            new AlertDialog.Builder(MainActivity.this, R.style.IbraDialog)
                                    .setTitle(R.string.saved_title)
                                    .setMessage(getString(R.string.saved_fmt, n, ApkUtils.saveLocation()))
                                    .setPositiveButton(R.string.ok, null)
                                    .show();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideProgress();
                            toast(getString(R.string.error_fmt, String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_WRITE) return;
        Runnable r = afterPermission;
        afterPermission = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (r != null) r.run();
        } else {
            toast(getString(R.string.perm_denied));
        }
    }

    // ===================== أدوات =====================

    private void openUrl(String url) {
        safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.error_fmt, "no app"));
        } catch (Exception e) {
            toast(getString(R.string.error_fmt, String.valueOf(e.getMessage())));
        }
    }

    private void showProgress(String msg) {
        progressText.setText(msg);
        progress.setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        progress.setVisibility(View.GONE);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
