package com.ibraprogram.apkshare;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppAdapter extends BaseAdapter {

    public interface Listener {
        void onSelectionChanged(int count);
    }

    private final Context ctx;
    private final Listener listener;
    private final LayoutInflater inflater;
    private final List<AppInfo> items = new ArrayList<>();
    private final LinkedHashMap<String, AppInfo> selected = new LinkedHashMap<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(220);
    private final ExecutorService iconExec = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    public AppAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.inflater = LayoutInflater.from(ctx);
    }

    public void setItems(List<AppInfo> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public void toggle(AppInfo a) {
        if (selected.remove(a.packageName) == null) selected.put(a.packageName, a);
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    public void clearSelection() {
        selected.clear();
        notifyDataSetChanged();
        listener.onSelectionChanged(0);
    }

    public void selectAllVisible() {
        for (AppInfo a : items) selected.put(a.packageName, a);
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    public List<AppInfo> getSelectedApps() {
        return new ArrayList<>(selected.values());
    }

    public void shutdown() {
        iconExec.shutdownNow();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public AppInfo getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    static class Holder {
        View row;
        ImageView icon;
        TextView name, pkg, meta, badgeSys, badgeSplit;
        CheckBox check;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final Holder h;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_app, parent, false);
            h = new Holder();
            h.row = convertView.findViewById(R.id.row);
            h.icon = convertView.findViewById(R.id.icon);
            h.name = convertView.findViewById(R.id.name);
            h.pkg = convertView.findViewById(R.id.pkg);
            h.meta = convertView.findViewById(R.id.meta);
            h.badgeSys = convertView.findViewById(R.id.badgeSys);
            h.badgeSplit = convertView.findViewById(R.id.badgeSplit);
            h.check = convertView.findViewById(R.id.check);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        final AppInfo a = items.get(position);
        boolean isSel = selected.containsKey(a.packageName);
        h.name.setText(a.label);
        h.pkg.setText(a.packageName);
        h.meta.setText(a.versionLabel() + "  •  " + Formatter.formatShortFileSize(ctx, a.size));
        h.badgeSys.setVisibility(a.system ? View.VISIBLE : View.GONE);
        h.badgeSplit.setVisibility(a.isSplit() ? View.VISIBLE : View.GONE);
        h.row.setActivated(isSel);
        h.check.setChecked(isSel);
        h.check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle(a);
            }
        });

        final String pkg = a.packageName;
        h.icon.setTag(pkg);
        Drawable cached = icons.get(pkg);
        if (cached != null) {
            h.icon.setImageDrawable(cached);
        } else {
            h.icon.setImageDrawable(null);
            if (!iconExec.isShutdown()) {
                iconExec.execute(new Runnable() {
                    @Override
                    public void run() {
                        final Drawable d = a.loadIcon(ctx);
                        icons.put(pkg, d);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (pkg.equals(h.icon.getTag())) h.icon.setImageDrawable(d);
                            }
                        });
                    }
                });
            }
        }
        return convertView;
    }
}
