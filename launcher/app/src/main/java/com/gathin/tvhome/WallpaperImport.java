package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/** Local storage entry point for future import sources. The caller owns and closes the stream. */
final class WallpaperImport {
    private WallpaperImport() { }
    static WallpaperLibrary.ImportResult save(Context context, InputStream stream) throws IOException {
        File user = new File(context.getFilesDir(), WallpaperLibrary.USER_DIR);
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        return WallpaperLibrary.importImage(user, context.getCacheDir(), stream, (file, format) -> {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), bounds);
                String mime = "jpg".equals(format) ? "image/jpeg" : "image/" + format;
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || !mime.equals(bounds.outMimeType)
                        || screen.widthPixels <= 0 || screen.heightPixels <= 0) return false;
                int screenSample = WallpaperSize.sampleSize(bounds.outWidth, bounds.outHeight, screen.widthPixels, screen.heightPixels);
                if (!WallpaperSize.accepted(bounds.outWidth / screenSample, bounds.outHeight / screenSample)) return false;
                BitmapFactory.Options preview = new BitmapFactory.Options();
                preview.inSampleSize = 1;
                while (bounds.outWidth / preview.inSampleSize > 256 || bounds.outHeight / preview.inSampleSize > 256)
                    preview.inSampleSize *= 2;
                preview.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), preview);
                if (bitmap == null) return false;
                bitmap.recycle();
                return true;
            } catch (RuntimeException | OutOfMemoryError e) { return false; }
        });
    }
}
