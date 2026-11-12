package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Remote-operated selector. Theme files are read-only; user images stay outside theme storage. */
public final class WallpaperLibraryActivity extends Activity {
    private SharedPreferences choices;
    private File userDir;
    private List<WallpaperLibrary.Item> items;
    private RecyclerView grid;
    private TextView empty;
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    private boolean closed;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(LauncherLocale.wrap(base)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        userDir = new File(getFilesDir(), WallpaperLibrary.USER_DIR);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(0xff101419);
        root.setPadding(dp(48), dp(25), dp(48), dp(20));
        TextView title = new TextView(this);
        title.setText(R.string.wallpaper_library_title); title.setTextColor(Color.WHITE); title.setTextSize(28);
        root.addView(title);
        TextView help = new TextView(this);
        help.setText(R.string.wallpaper_library_help); help.setTextColor(0xffdddddd); help.setTextSize(15);
        root.addView(help);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        TextView theme = button(getString(R.string.wallpaper_theme_default));
        TextView none = button(getString(R.string.wallpaper_none));
        controls.addView(theme); controls.addView(none); root.addView(controls);
        theme.setOnClickListener(v -> select(null));
        none.setOnClickListener(v -> select(WallpaperRotation.CHOICE_NONE));
        empty = new TextView(this); empty.setText(R.string.wallpaper_library_empty); empty.setTextColor(Color.WHITE);
        root.addView(empty);
        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 4));
        grid.setClipToPadding(false);
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        reload();
        if (state == null) theme.requestFocus();
    }

    private TextView button(String value) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextColor(Color.WHITE); text.setTextSize(16);
        text.setPadding(dp(16), dp(10), dp(16), dp(10));
        text.setFocusable(true); text.setClickable(true); text.setBackgroundColor(0xff36434d);
        text.setOnFocusChangeListener((v, focused) -> v.setBackgroundColor(focused ? 0xff56718a : 0xff36434d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), dp(12), dp(10)); text.setLayoutParams(lp);
        return text;
    }

    private void reload() {
        items = WallpaperLibrary.entries(Config.dir(this), userDir);
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        grid.setAdapter(new GridAdapter());
    }
    private void select(String value) {
        SharedPreferences.Editor edit = choices.edit();
        if (value == null) edit.remove(Config.WALLPAPER_CHOICE_KEY); else edit.putString(Config.WALLPAPER_CHOICE_KEY, value);
        edit.putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        setResult(RESULT_OK); finish();
    }
    private void remove(WallpaperLibrary.Item item) {
        if (!item.user) return;
        new AlertDialog.Builder(this).setTitle(R.string.wallpaper_delete_title).setMessage(R.string.wallpaper_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.wallpaper_delete_action, (dialog, which) -> {
                    if (!WallpaperLibrary.deleteUser(userDir, item)) {
                        Toast.makeText(this, R.string.wallpaper_delete_failed, Toast.LENGTH_SHORT).show(); return;
                    }
                    if (item.id.equals(choices.getString(Config.WALLPAPER_CHOICE_KEY, null))) {
                        choices.edit().remove(Config.WALLPAPER_CHOICE_KEY)
                                .putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
                    }
                    reload();
                }).show();
    }

    static String choiceLabel(Context context, String choice) {
        if (WallpaperLibrary.THEME_SINGLE.equals(choice)) return context.getString(R.string.wallpaper_theme_item, "wallpaper.jpg");
        if (choice.startsWith(WallpaperLibrary.THEME)) return context.getString(R.string.wallpaper_theme_item, choice.substring(WallpaperLibrary.THEME.length()));
        if (choice.startsWith(WallpaperLibrary.USER)) return context.getString(R.string.wallpaper_user_choice);
        return choice;
    }
    private String label(WallpaperLibrary.Item item) {
        if (!item.user) return choiceLabel(this, item.id);
        int number = 0;
        for (WallpaperLibrary.Item candidate : items) {
            if (candidate.user) number++;
            if (candidate == item) break;
        }
        return getString(R.string.wallpaper_user_item, Integer.toString(number));
    }

    private final class GridAdapter extends RecyclerView.Adapter<Holder> {
        private final List<File> selectedFile = WallpaperLibrary.chosen(items,
                choices.getString(Config.WALLPAPER_CHOICE_KEY, null));
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(WallpaperLibraryActivity.this);
            card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(5), dp(5), dp(5), dp(5));
            card.setFocusable(true); card.setClickable(true);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(155));
            params.setMargins(dp(4), dp(4), dp(4), dp(4)); card.setLayoutParams(params);
            ImageView image = new ImageView(WallpaperLibraryActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackgroundColor(0xff27323c);
            card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            TextView label = new TextView(WallpaperLibraryActivity.this);
            label.setTextColor(Color.WHITE); label.setTextSize(14); label.setGravity(Gravity.CENTER_VERTICAL);
            label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
            card.setOnFocusChangeListener((v, focused) -> {
                GradientDrawable bg = new GradientDrawable(); bg.setColor(focused ? 0xff426078 : 0xff252d34);
                bg.setStroke(dp(focused ? 2 : 0), Color.WHITE); v.setBackground(bg);
            });
            card.setBackgroundColor(0xff252d34);
            return new Holder(card, image, label);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            WallpaperLibrary.Item item = items.get(position);
            String value = label(item);
            if (selectedFile != null && selectedFile.contains(item.file)) value = getString(R.string.wallpaper_selected_item, value);
            holder.label.setText(value); holder.card.setContentDescription(value);
            holder.image.setImageDrawable(null); holder.image.setContentDescription(getString(R.string.wallpaper_preview_unavailable));
            holder.image.setTag(item.id);
            holder.card.setTag(Boolean.FALSE);
            holder.card.setOnClickListener(v -> {
                if (Boolean.TRUE.equals(v.getTag())) select(item.id);
                else Toast.makeText(WallpaperLibraryActivity.this, R.string.wallpaper_unreadable, Toast.LENGTH_SHORT).show();
            });
            holder.card.setOnLongClickListener(item.user ? v -> { remove(item); return true; } : null);
            loadThumbnail(holder, item);
        }
        @Override public int getItemCount() { return items.size(); }
    }
    private static final class Holder extends RecyclerView.ViewHolder {
        final View card; final ImageView image; final TextView label;
        Holder(View card, ImageView image, TextView label) { super(card); this.card = card; this.image = image; this.label = label; }
    }
    private void loadThumbnail(Holder holder, WallpaperLibrary.Item item) {
        ImageView view = holder.image;
        thumbnails.execute(() -> {
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(item.file.getPath(), bounds);
                int sample = bounds.outWidth > 0 && bounds.outHeight > 0
                        ? WallpaperSize.sampleSize(bounds.outWidth, bounds.outHeight,
                                Math.max(1, getResources().getDisplayMetrics().widthPixels),
                                Math.max(1, getResources().getDisplayMetrics().heightPixels)) : 1;
                if (bounds.outWidth > 0 && bounds.outHeight > 0
                        && WallpaperSize.accepted(bounds.outWidth / sample, bounds.outHeight / sample)) {
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                    while (bounds.outWidth / options.inSampleSize > 320 || bounds.outHeight / options.inSampleSize > 180)
                        options.inSampleSize *= 2;
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    bitmap = BitmapFactory.decodeFile(item.file.getPath(), options);
                }
            } catch (RuntimeException | OutOfMemoryError ignored) { }
            Bitmap result = bitmap;
            runOnUiThread(() -> {
                if (closed || !item.id.equals(view.getTag())) { if (result != null) result.recycle(); return; }
                if (result != null) { view.setImageBitmap(result); view.setContentDescription(null); holder.card.setTag(Boolean.TRUE); }
            });
        });
    }
    @Override protected void onDestroy() { closed = true; thumbnails.shutdownNow(); super.onDestroy(); }
}
