package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.io.InputStream;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.annotation.NonNull;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A private, read-only view of Android TV's provider-published Watch Next entries. */
final class ContinueWatching {
    private static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final String[] FIELDS = {
            "package_name", "title", "episode_title", "watch_next_type", "last_engagement_time_utc_millis",
            "last_playback_position_millis", "duration_millis", "intent_uri", "internal_provider_id", "poster_art_uri", "thumbnail_uri"
    };
    private final Activity activity;
    private final LinearLayout root;
    private final SharedPreferences prefs;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ExecutorService artwork = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> artCache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private List<Item> all = Collections.emptyList();
    private int generation;
    private volatile boolean closed;

    static final class Item {
        String pkg, appLabel, title, episode, intent, stableId, poster, thumbnail;
        long engaged, position, duration;
        int type;
        String key() { return identity(pkg, stableId, intent); }
        String alias() { return identity(pkg, "title:" + title + "\n" + (episode == null ? "" : episode), null); }
        float progress() { return position >= 0 && duration > 0 ? Math.min(1f, position / (float) duration) : -1f; }
    }

    ContinueWatching(Activity activity, LinearLayout root) {
        this.activity = activity;
        this.root = root;
        prefs = activity.getSharedPreferences("continue_watching", Activity.MODE_PRIVATE);
        draw("Loading…", Collections.emptyList());
    }

    boolean focus() {
        if (root.getChildCount() == 0) return false;
        View child = root.getChildAt(0);
        return child != null && child.requestFocus();
    }

    void close() { closed = true; generation++; loader.shutdownNow(); artwork.shutdownNow(); artCache.evictAll(); }

    void refresh() {
        if (closed) return;
        int request = ++generation;
        if (activity.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            all = Collections.emptyList();
            draw("Select Continue Watching to allow TV listings access.", Collections.emptyList());
            return;
        }
        loader.execute(() -> {
            List<Item> items = new ArrayList<>();
            java.util.Map<String, String> labels = new java.util.HashMap<>();
            String error = null;
            try (Cursor c = activity.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                    FIELDS, null, null, null)) {
                if (c == null) error = "TV listings are unavailable.";
                else while (c.moveToNext()) {
                    Item i = new Item();
                    i.pkg = c.getString(0); i.title = c.getString(1); i.episode = c.getString(2);
                    i.type = c.getInt(3); i.engaged = c.getLong(4);
                    i.position = c.isNull(5) ? -1 : c.getLong(5); i.duration = c.isNull(6) ? -1 : c.getLong(6);
                    i.intent = c.getString(7); i.stableId = c.getString(8);
                    i.poster = c.getString(9); i.thumbnail = c.getString(10);
                    if (i.pkg != null && i.title != null && !i.title.trim().isEmpty()) {
                        i.appLabel = labels.get(i.pkg);
                        if (i.appLabel == null) {
                            try { i.appLabel = activity.getPackageManager().getApplicationLabel(
                                    activity.getPackageManager().getApplicationInfo(i.pkg, 0)).toString(); }
                            catch (PackageManager.NameNotFoundException e) { i.appLabel = i.pkg; }
                            labels.put(i.pkg, i.appLabel);
                        }
                        items.add(i);
                    }
                }
            } catch (RuntimeException e) { error = "TV listings are unavailable or access was denied."; }
            String message = error;
            main.post(() -> {
                if (closed || request != generation || activity.isFinishing()) return;
                all = items;
                showFiltered(message);
            });
        });
    }

    private void showFiltered(String error) {
        if (error != null) { draw(error, Collections.emptyList()); return; }
        Set<String> excluded = prefs.getStringSet("excluded", Collections.emptySet());
        Set<String> hidden = hiddenKeys();
        int days = prefs.getInt("days", 30);
        boolean progress = prefs.getBoolean("progress", false);
        long now = System.currentTimeMillis();
        List<Item> visible = new ArrayList<>();
        for (Item i : all) if (eligible(i, excluded, hidden, now, days)) visible.add(i);
        visible.sort((a, b) -> compare(a.progress(), a.engaged, b.progress(), b.engaged, progress));
        draw(visible.isEmpty() ? "No available titles. Select here for settings." : null, visible);
    }

    static boolean eligible(Item item, Set<String> excluded, Set<String> hidden, long now, int days) {
        return !excluded.contains(item.pkg) && !hidden.contains(item.key()) && !hidden.contains(item.alias())
                && fresh(item.engaged, now, days);
    }
    static boolean fresh(long engaged, long now, int days) {
        return days == 0 || (engaged > 0 && engaged <= now && now - engaged <= days * 86400000L);
    }
    static int compare(float a, long timeA, float b, long timeB, boolean progress) {
        if (progress) { int c = Float.compare(b, a); if (c != 0) return c; }
        return Long.compare(timeB, timeA);
    }
    static String identity(String pkg, String providerId, String intent) {
        String value = providerId != null && !providerId.isEmpty() ? "id:" + providerId
                : intent != null && !intent.isEmpty() ? "intent:" + intent : null;
        if (pkg == null || value == null) return null;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((pkg + "\n" + value).getBytes(StandardCharsets.UTF_8));
            char[] digits = "0123456789abcdef".toCharArray();
            char[] out = new char[hash.length * 2];
            for (int n = 0; n < hash.length; n++) {
                out[n * 2] = digits[(hash[n] & 255) >>> 4];
                out[n * 2 + 1] = digits[hash[n] & 15];
            }
            return new String(out);
        } catch (Exception e) { return null; }
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private TextView text(String label) {
        TextView v = new TextView(activity); v.setText(label); v.setTextColor(0xffffffff);
        v.setTextSize(15); v.setPadding(dp(12), dp(8), dp(12), dp(8));
        v.setFocusable(true); v.setBackgroundColor(0xaa303840);
        v.setOnFocusChangeListener((view, focus) -> view.setBackgroundColor(focus ? 0xff526a80 : 0xaa303840));
        return v;
    }
    private void draw(String message, List<Item> items) {
        boolean hadFocus = root.hasFocus();
        root.removeAllViews();
        TextView heading = text("Continue Watching  •  Settings  •  Hold OK on a title to hide");
        heading.setOnClickListener(v -> settings()); root.addView(heading);
        if (message != null) {
            TextView info = new TextView(activity); info.setText(message); info.setTextColor(0xffdddddd);
            root.addView(info);
        } else {
            RecyclerView row = new RecyclerView(activity);
            row.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
            row.setAdapter(new RecyclerView.Adapter<TitleHolder>() {
                @NonNull @Override public TitleHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    return new TitleHolder();
                }
                @Override public void onBindViewHolder(@NonNull TitleHolder holder, int index) {
                    Item item = items.get(index);
                    String subtitle = item.episode == null || item.episode.isEmpty()
                            ? item.appLabel : item.appLabel + " · " + item.episode;
                    holder.title.setText(item.title);
                    holder.subtitle.setText(subtitle);
                    holder.card.setContentDescription(item.title + ", " + subtitle
                            + (item.progress() < 0 ? "" : ", " + Math.round(item.progress() * 100) + "%"));
                    holder.fallback.setText(item.appLabel);
                    holder.fallback.setVisibility(View.VISIBLE);
                    holder.image.setImageDrawable(null);
                    holder.progress.setProgress(item.progress());
                    Object token = new Object();
                    holder.card.setTag(token);
                    loadArtwork(holder, item.poster, item.thumbnail, token);
                    holder.card.setOnClickListener(v -> open(item));
                    holder.card.setOnLongClickListener(v -> { hide(item); return true; });
                }
                @Override public int getItemCount() { return items.size(); }
            });
            root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        }
        if (hadFocus) heading.requestFocus();
    }
    private GradientDrawable surface(int color, int radius, int stroke) {
        GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius));
        if (stroke != 0) result.setStroke(dp(2), stroke);
        return result;
    }
    private TextView cardLine(int size, int color) {
        TextView view = new TextView(activity); view.setTextSize(size); view.setTextColor(color);
        view.setSingleLine(true); view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(dp(6), 0, dp(6), 0); view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private final class TitleHolder extends RecyclerView.ViewHolder {
        final LinearLayout card; final FrameLayout art; final ImageView image;
        final TextView fallback, title, subtitle; final ProgressStrip progress;
        TitleHolder() { this(new LinearLayout(activity)); }
        private TitleHolder(LinearLayout root) {
            super(root); card = root; card.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(190), dp(150));
            params.setMarginEnd(dp(8)); card.setLayoutParams(params);
            card.setFocusable(true); card.setClickable(true);
            card.setBackground(surface(0xe6202931, 8, 0));
            card.setOnFocusChangeListener((v, focused) ->
                    card.setForeground(surface(Color.TRANSPARENT, 8, focused ? Color.WHITE : 0)));
            art = new FrameLayout(activity); art.setBackground(surface(0xff303b46, 8, 0));
            art.setClipToOutline(true); card.addView(art, new LinearLayout.LayoutParams(dp(190), dp(107)));
            image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            art.addView(image, new FrameLayout.LayoutParams(-1, -1));
            fallback = new TextView(activity); fallback.setTextColor(0xffd8e2eb); fallback.setTextSize(16);
            fallback.setGravity(Gravity.CENTER); fallback.setPadding(dp(10), dp(6), dp(10), dp(6));
            fallback.setMaxLines(2); fallback.setEllipsize(TextUtils.TruncateAt.END);
            fallback.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            art.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
            progress = new ProgressStrip();
            FrameLayout.LayoutParams line = new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM);
            art.addView(progress, line);
            title = cardLine(15, Color.WHITE); subtitle = cardLine(13, 0xffc8d3dc);
            card.addView(title, new LinearLayout.LayoutParams(-1, dp(23)));
            card.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(20)));
        }
    }
    private final class ProgressStrip extends View {
        private final Paint paint = new Paint(); private float progress = -1;
        ProgressStrip() { super(activity); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void setProgress(float value) { progress = value; setVisibility(value < 0 ? View.GONE : View.VISIBLE); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); paint.setColor(0x99000000); canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setColor(Color.WHITE); canvas.drawRect(0, 0, getWidth() * progress, getHeight(), paint);
        }
    }

    private void loadArtwork(TitleHolder holder, String address, String thumbnail, Object token) {
        if (closed || address == null && thumbnail == null) return;
        List<String> candidates = ContinueArtwork.candidates(address, thumbnail);
        if (candidates.isEmpty()) return;
        artwork.execute(() -> {
            Bitmap result = ContinueArtwork.firstAvailable(candidates, this::decodeArtwork);
            main.post(() -> {
                if (closed || result == null || !holder.card.isAttachedToWindow() || holder.card.getTag() != token) return;
                holder.image.setImageBitmap(result); holder.fallback.setVisibility(View.GONE);
            });
        });
    }
    private Bitmap decodeArtwork(String address) {
        Bitmap cached = artCache.get(address); if (cached != null) return cached;
        try {
            Uri uri = Uri.parse(address);
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, options); }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inJustDecodeBounds = false; options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 512 || options.outHeight / options.inSampleSize > 512)
                options.inSampleSize *= 2;
            Bitmap result;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) { result = BitmapFactory.decodeStream(in, null, options); }
            if (result != null && !closed) artCache.put(address, result);
            return result;
        } catch (Exception | OutOfMemoryError ignored) { return null; }
    }
    private void open(Item i) {
        Intent target = null;
        if (i.intent != null) try {
            Intent candidate = Intent.parseUri(i.intent, Intent.URI_INTENT_SCHEME);
            candidate.setSelector(null);
            String pkg = candidate.getComponent() == null ? candidate.getPackage() : candidate.getComponent().getPackageName();
            if (pkg == null || pkg.equals(i.pkg)) {
                if (pkg == null) candidate.setPackage(i.pkg);
                candidate.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                target = candidate;
            }
        } catch (URISyntaxException ignored) { }
        if (target == null) target = activity.getPackageManager().getLeanbackLaunchIntentForPackage(i.pkg);
        if (target == null) target = activity.getPackageManager().getLaunchIntentForPackage(i.pkg);
        try { if (target != null) activity.startActivity(target); else throw new IllegalStateException(); }
        catch (RuntimeException e) { Toast.makeText(activity, "This title cannot be opened.", Toast.LENGTH_SHORT).show(); }
    }
    private void hide(Item i) {
        String key = i.key();
        if (key == null) { Toast.makeText(activity, "This title has no identifier for reliable hiding.", Toast.LENGTH_LONG).show(); return; }
        new AlertDialog.Builder(activity).setTitle(i.title).setItems(new String[]{"Hide from Continue Watching"}, (d, n) -> {
            Set<String> next = new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
            String label = Base64.encodeToString((i.title + " • " + i.pkg).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            next.add(key + "," + i.alias() + ":" + label); prefs.edit().putStringSet("hidden", next).apply(); showFiltered(null);
        }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private Set<String> hiddenKeys() {
        Set<String> keys = new HashSet<>();
        for (String s : prefs.getStringSet("hidden", Collections.emptySet())) { int n = s.indexOf(':'); if (n > 0) Collections.addAll(keys, s.substring(0, n).split(",")); }
        return keys;
    }
    private void settings() {
        new AlertDialog.Builder(activity).setTitle("Continue Watching")
                .setItems(new String[]{"Allow or refresh TV listings", "Streaming apps", "Freshness", "Sort order", "Restore hidden titles"}, (d, which) -> {
                    if (which == 0) {
                        if (activity.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
                            activity.requestPermissions(new String[]{PERMISSION}, 82);
                        else refresh();
                    } else if (which == 1) apps(); else if (which == 2) days();
                    else if (which == 3) sort(); else restore();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void apps() {
        Set<String> packages = new java.util.TreeSet<>(); for (Item i : all) packages.add(i.pkg);
        List<String> list = new ArrayList<>(packages);
        if (list.isEmpty()) { Toast.makeText(activity, "No readable streaming apps yet.", Toast.LENGTH_SHORT).show(); return; }
        Set<String> excluded = new HashSet<>(prefs.getStringSet("excluded", Collections.emptySet()));
        boolean[] checked = new boolean[list.size()]; String[] labels = new String[list.size()];
        for (int n = 0; n < list.size(); n++) {
            String pkg = list.get(n); checked[n] = !excluded.contains(pkg);
            try { labels[n] = activity.getPackageManager().getApplicationLabel(activity.getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
            catch (PackageManager.NameNotFoundException e) { labels[n] = pkg; }
        }
        new AlertDialog.Builder(activity).setTitle("Include streaming apps")
                .setMultiChoiceItems(labels, checked, (d, n, on) -> { if (on) excluded.remove(list.get(n)); else excluded.add(list.get(n)); })
                .setPositiveButton("Save", (d, n) -> { prefs.edit().putStringSet("excluded", excluded).apply(); showFiltered(null); })
                .setNegativeButton(android.R.string.cancel, null).show();
    }
    private void days() {
        String[] labels = {"7 days", "30 days", "90 days", "All available"}; int[] values = {7, 30, 90, 0};
        new AlertDialog.Builder(activity).setTitle("Freshness") .setItems(labels, (d, n) -> {
            prefs.edit().putInt("days", values[n]).apply(); showFiltered(null);
        }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void sort() {
        new AlertDialog.Builder(activity).setTitle("Sort order").setItems(new String[]{"Recent activity", "Watch progress"}, (d, n) -> {
            prefs.edit().putBoolean("progress", n == 1).apply(); showFiltered(null);
        }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void restore() {
        List<String> entries = new ArrayList<>(prefs.getStringSet("hidden", Collections.emptySet())); Collections.sort(entries);
        if (entries.isEmpty()) { Toast.makeText(activity, "No hidden titles.", Toast.LENGTH_SHORT).show(); return; }
        String[] labels = new String[entries.size()];
        for (int n = 0; n < entries.size(); n++) try {
            labels[n] = new String(Base64.decode(entries.get(n).substring(entries.get(n).indexOf(':') + 1), Base64.NO_WRAP), StandardCharsets.UTF_8);
        } catch (RuntimeException e) { labels[n] = "Hidden title"; }
        new AlertDialog.Builder(activity).setTitle("Restore a hidden title").setItems(labels, (d, n) -> {
            Set<String> next = new HashSet<>(entries); next.remove(entries.get(n));
            prefs.edit().putStringSet("hidden", next).apply(); showFiltered(null);
        }).setNegativeButton(android.R.string.cancel, null).show();
    }
}
