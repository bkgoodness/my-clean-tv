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
import android.util.Log;
import java.io.FileNotFoundException;
import java.io.IOException;
import android.view.Gravity;
import android.view.KeyEvent;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A private, read-only view of Android TV's provider-published Watch Next entries. */
final class ContinueWatching {
    private static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final String[] BASE_FIELDS = {
            "package_name", "title", "episode_title", "watch_next_type", "last_engagement_time_utc_millis",
            "last_playback_position_millis", "duration_millis", "intent_uri", "internal_provider_id", "poster_art_uri", "thumbnail_uri"
    };
    private static final String[] FIELDS = {
            "package_name", "title", "episode_title", "watch_next_type", "last_engagement_time_utc_millis",
            "last_playback_position_millis", "duration_millis", "intent_uri", "internal_provider_id", "poster_art_uri", "thumbnail_uri",
            "season_display_number", "episode_display_number", "type"
    };
    private final Activity activity;
    private final LinearLayout root;
    interface Navigation { void top(); void apps(); }
    private final SharedPreferences prefs;
    private final Navigation navigation;
    private RecyclerView row;
    private TextView heading;
    private List<Item> displayed = Collections.emptyList();
    private String lastFocusedKey;
    private int lastFocusedIndex;
    private int focusRequest;
    private int expandedHeight;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor artwork = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
    private final ArtworkNetwork online = new ArtworkNetwork();
    private final ArtworkDiskCache diskCache;
    private volatile boolean onlineEnabled;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> artCache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private List<Item> all = Collections.emptyList();
    private int generation, artworkGeneration;
    private volatile boolean closed;
    private final ArtworkDiagnostics diagnostics = new ArtworkDiagnostics();
    private final Runnable reportArtwork = () -> {
        if (!closed) Log.i("TvHome.Artwork", diagnostics.summary());
    };

    static final class Item {
        String pkg, appLabel, title, episode, seasonNumber, episodeNumber, intent, stableId, poster, thumbnail;
        long engaged, position, duration;
        int type, mediaType;
        boolean episodic() { return mediaType == TvContract.WatchNextPrograms.TYPE_TV_EPISODE
                || (episode != null && !episode.trim().isEmpty()) || (episodeNumber != null && !episodeNumber.trim().isEmpty())
                || (seasonNumber != null && !seasonNumber.trim().isEmpty()); }
        String key() { return identity(pkg, stableId, intent); }
        String alias() { return identity(pkg, "title:" + title + "\n" + (episode == null ? "" : episode), null); }
        float progress() { return position >= 0 && duration > 0 ? Math.min(1f, position / (float) duration) : -1f; }
    }

    ContinueWatching(Activity activity, LinearLayout root, Navigation navigation) {
        this.activity = activity;
        this.root = root;
        this.navigation = navigation;
        prefs = activity.getSharedPreferences("continue_watching", Activity.MODE_PRIVATE);
        diskCache = new ArtworkDiskCache(new java.io.File(activity.getCacheDir(), "continue-artwork"));
        onlineEnabled = prefs.getBoolean("online_artwork", true);
        online.setEnabled(onlineEnabled);
        draw("Loading…", Collections.emptyList());
    }

    boolean hasEntries() { return row != null && !displayed.isEmpty(); }
    boolean isCollapsed() { return root.getVisibility() == View.GONE && hasEntries(); }
    int expandedHeight() { return expandedHeight; }
    void setExpanded(boolean expanded) {
        if (!hasEntries()) expanded = true;
        int visibility = expanded ? View.VISIBLE : View.GONE;
        if (root.getVisibility() != visibility) {
            if (!expanded) { focusRequest++; expandedHeight = root.getHeight(); }
            root.setVisibility(visibility);
        }
    }
    boolean focusHeader() {
        focusRequest++;
        setExpanded(true);
        return heading != null && heading.requestFocus();
    }
    boolean focus() {
        setExpanded(true);
        if (!hasEntries()) return heading != null && heading.requestFocus();
        List<String> keys = new ArrayList<>();
        for (Item item : displayed) keys.add(focusKey(item));
        int index = ContinueNavigation.restoredIndex(keys, lastFocusedKey, lastFocusedIndex);
        RecyclerView target = row;
        int request = ++focusRequest;
        target.scrollToPosition(index);
        target.postOnAnimation(new Runnable() {
            int frames;
            @Override public void run() {
                if (closed || row != target || request != focusRequest || root.getVisibility() != View.VISIBLE) return;
                RecyclerView.ViewHolder holder = target.findViewHolderForAdapterPosition(index);
                if (holder != null) holder.itemView.requestFocus();
                else if (++frames < 10) target.postOnAnimation(this);
                else if (heading != null) heading.requestFocus();
            }
        });
        return true;
    }
    private static String focusKey(Item item) {
        String key = item.key(); return key == null ? item.alias() : key;
    }

    void close() { closed = true; focusRequest++; generation++; main.removeCallbacks(reportArtwork);
        online.close(); loader.shutdownNow(); artwork.shutdownNow(); artCache.evictAll(); }

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
            try (Cursor c = queryWatchNext()) {
                if (c == null) error = "TV listings are unavailable.";
                else {
                    int seasonIndex = c.getColumnIndex("season_display_number");
                    int episodeIndex = c.getColumnIndex("episode_display_number");
                    int typeIndex = c.getColumnIndex("type");
                    while (c.moveToNext()) {
                    Item i = new Item();
                    i.pkg = c.getString(0); i.title = c.getString(1); i.episode = c.getString(2);
                    i.type = c.getInt(3); i.engaged = c.getLong(4);
                    i.position = c.isNull(5) ? -1 : c.getLong(5); i.duration = c.isNull(6) ? -1 : c.getLong(6);
                    i.intent = c.getString(7); i.stableId = c.getString(8);
                    i.poster = c.getString(9); i.thumbnail = c.getString(10);
                    i.seasonNumber = seasonIndex < 0 ? null : c.getString(seasonIndex);
                    i.episodeNumber = episodeIndex < 0 ? null : c.getString(episodeIndex);
                    i.mediaType = typeIndex < 0 || c.isNull(typeIndex) ? -1 : c.getInt(typeIndex);
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

    private Cursor queryWatchNext() {
        try {
            return activity.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, FIELDS, null, null, null);
        } catch (IllegalArgumentException | android.database.sqlite.SQLiteException unsupportedColumns) {
            // Some TV providers omit optional metadata columns; retain the previous projection there.
            return activity.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, BASE_FIELDS, null, null, null);
        }
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
        boolean focusedCard = row != null && row.hasFocus();
        focusRequest++;
        artworkGeneration++;
        root.removeAllViews();
        row = null;
        displayed = items;
        heading = text("Continue Watching  •  Settings  •  Hold OK on a title to hide");
        heading.setOnClickListener(v -> settings());
        heading.setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (key == KeyEvent.KEYCODE_DPAD_UP) navigation.top();
                else if (!hasEntries()) navigation.apps();
                else focus();
            }
            return true;
        });
        root.addView(heading);
        if (message != null) {
            TextView info = new TextView(activity); info.setText(message); info.setTextColor(0xffdddddd);
            root.addView(info);
        } else {
            row = new RecyclerView(activity);
            row.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
            row.setAdapter(new RecyclerView.Adapter<TitleHolder>() {
                @NonNull @Override public TitleHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    return new TitleHolder();
                }
                @Override public void onBindViewHolder(@NonNull TitleHolder holder, int index) {
                    Item item = items.get(index);
                    String subtitle = ContinueArtwork.metadata(item.appLabel, item.seasonNumber, item.episodeNumber, item.episode);
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
                    holder.item = item; holder.binding = token; holder.scheduled = null;
                    if (holder.card.isAttachedToWindow()) { holder.active = true; scheduleArtwork(holder, item, token); }
                    holder.card.setOnFocusChangeListener((v, focused) -> {
                        holder.card.setForeground(surface(Color.TRANSPARENT, 8, focused ? Color.WHITE : 0));
                        if (focused) {
                            focusRequest++;
                            int position = holder.getBindingAdapterPosition();
                            if (position != RecyclerView.NO_POSITION) lastFocusedIndex = position;
                            lastFocusedKey = focusKey(item);
                        }
                    });
                    holder.card.setOnKeyListener((v, key, event) -> {
                        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                focusRequest++;
                                if (key == KeyEvent.KEYCODE_DPAD_UP) navigation.top(); else navigation.apps();
                            }
                            return true;
                        }
                        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            int position = holder.getBindingAdapterPosition();
                            boolean rtl = row != null && row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                            return ContinueNavigation.horizontalEdge(position, items.size(), rtl, key == KeyEvent.KEYCODE_DPAD_LEFT);
                        }
                        return false;
                    });
                    holder.card.setOnClickListener(v -> open(item));
                    holder.card.setOnLongClickListener(v -> { hide(item); return true; });
                }
                @Override public void onViewAttachedToWindow(@NonNull TitleHolder holder) {
                    super.onViewAttachedToWindow(holder); holder.active = true;
                    if (holder.item != null) scheduleArtwork(holder, holder.item, holder.binding);
                }
                @Override public void onViewDetachedFromWindow(@NonNull TitleHolder holder) {
                    holder.active = false; holder.scheduled = null; super.onViewDetachedFromWindow(holder);
                }
                @Override public int getItemCount() { return items.size(); }
            });
            root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        }
        if (!hasEntries()) setExpanded(true);
        if (hadFocus) { if (focusedCard && hasEntries()) focus(); else heading.requestFocus(); }
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
        volatile boolean active; volatile Object binding; Object scheduled; Item item;
        TitleHolder() { this(new LinearLayout(activity)); }
        private TitleHolder(LinearLayout root) {
            super(root); card = root; card.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(190), dp(150));
            params.setMarginEnd(dp(8)); card.setLayoutParams(params);
            card.setFocusable(true); card.setClickable(true);
            card.setBackground(surface(0xe6202931, 8, 0));
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

    private void reportSoon() {
        main.removeCallbacks(reportArtwork);
        main.postDelayed(reportArtwork, 500);
    }
    private void scheduleArtwork(TitleHolder holder, Item item, Object token) {
        if (closed || token == null || holder.scheduled == token) return;
        holder.scheduled = token;
        holder.card.post(() -> {
            if (closed || !holder.active || holder.binding != token) return;
            loadArtwork(holder, item.poster, item.thumbnail, item.episodic(), token);
        });
    }
    private void loadArtwork(TitleHolder holder, String address, String thumbnail, boolean episodic, Object token) {
        if (closed) return;
        diagnostics.sources(address, thumbnail); reportSoon();
        List<String> candidates = ContinueArtwork.candidates(address, thumbnail, episodic, onlineEnabled);
        if (candidates.isEmpty()) return;
        int requestedGeneration = artworkGeneration;
        try {
            artwork.execute(() -> {
                Bitmap result = ContinueArtwork.firstAvailable(candidates, candidate -> {
                    if (closed || !holder.active || holder.binding != token) return null;
                    return decodeArtwork(candidate);
                });
                main.post(() -> {
                    if (!ContinueArtwork.bindingCurrent(closed, artworkGeneration, requestedGeneration,
                            holder.card.getTag(), token)) { diagnostics.stale(); reportSoon(); return; }
                    if (result == null) { reportSoon(); return; }
                    boolean detached = !holder.card.isAttachedToWindow();
                    holder.image.setImageBitmap(result); holder.fallback.setVisibility(View.GONE);
                    diagnostics.bound(detached); reportSoon();
                    holder.image.post(() -> {
                        if (ContinueArtwork.bindingCurrent(closed, artworkGeneration, requestedGeneration,
                                holder.card.getTag(), token) && holder.image.isShown()
                                && holder.image.getDrawable() != null && holder.fallback.getVisibility() == View.GONE) {
                            diagnostics.renderReady(); reportSoon();
                        }
                    });
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            holder.scheduled = null;
            if (!closed) holder.card.postDelayed(() -> {
                if (holder.active && holder.binding == token) scheduleArtwork(holder, holder.item, token);
            }, 500);
        }
    }
    private Bitmap decodeArtwork(String address) {
        boolean remote = ContinueArtwork.source(address) == ContinueArtwork.Source.WEB;
        if (remote && (!onlineEnabled || !online.enabled())) return null;
        Bitmap cached = artCache.get(address);
        if (cached != null) { diagnostics.cacheHit(); return cached; }
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            byte[] data = null; boolean disk = false;
            if (remote) {
                try { data = diskCache.get(address, System.currentTimeMillis()); } catch (IOException ignored) { }
                disk = data != null;
                if (disk) diagnostics.remoteCacheHit();
                if (data == null) { diagnostics.remoteRequest(); data = online.fetch(address); }
                if (!onlineEnabled || !online.enabled()) return null;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
            } else {
                Uri uri = Uri.parse(address);
                try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                    if (in == null) { diagnostics.unavailable(); return null; }
                    BitmapFactory.decodeStream(in, null, options);
                }
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                if (remote && disk) try { diskCache.remove(address); } catch (IOException ignored) { }
                diagnostics.decodeFailed(); return null;
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = ContinueArtwork.sampleSize(options.outWidth, options.outHeight);
            Bitmap result;
            if (remote) result = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            else try (InputStream in = activity.getContentResolver().openInputStream(Uri.parse(address))) {
                if (in == null) { diagnostics.unavailable(); return null; }
                result = BitmapFactory.decodeStream(in, null, options);
            }
            if (remote && (!onlineEnabled || !online.enabled())) { if (result != null) result.recycle(); return null; }
            if (result == null) {
                if (remote && disk) try { diskCache.remove(address); } catch (IOException ignored) { }
                diagnostics.decodeFailed();
            } else {
                diagnostics.decoded(); if (!closed) artCache.put(address, result);
                if (remote && !disk && onlineEnabled) try { diskCache.put(address, data, System.currentTimeMillis()); } catch (IOException ignored) { }
            }
            return result;
        } catch (SecurityException e) { diagnostics.denied(); }
        catch (FileNotFoundException e) { diagnostics.unavailable(); }
        catch (IOException e) { diagnostics.unavailable(); if (remote) diagnostics.remoteFailed(); }
        catch (RuntimeException | OutOfMemoryError e) { diagnostics.decodeFailed(); }
        return null;
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
                .setItems(new String[]{"Allow or refresh TV listings", "Streaming apps", "Freshness", "Sort order", "Restore hidden titles",
                        activity.getString(onlineEnabled ? R.string.online_artwork_on : R.string.online_artwork_off)}, (d, which) -> {
                    if (which == 0) {
                        if (activity.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
                            activity.requestPermissions(new String[]{PERMISSION}, 82);
                        else refresh();
                    } else if (which == 1) apps(); else if (which == 2) days();
                    else if (which == 3) sort(); else if (which == 4) restore(); else onlineSetting();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void onlineSetting() {
        new AlertDialog.Builder(activity).setTitle(R.string.online_artwork_title)
                .setMessage(R.string.online_artwork_description)
                .setPositiveButton(onlineEnabled ? R.string.online_artwork_turn_off : R.string.online_artwork_turn_on, (dialog, which) -> {
                    boolean value = !onlineEnabled;
                    onlineEnabled = value; online.setEnabled(value);
                    prefs.edit().putBoolean("online_artwork", value).apply();
                    artCache.evictAll(); showFiltered(null);
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
