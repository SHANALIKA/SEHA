package com.example.seha;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Photo helpers: shrink + fix rotation so uploads are small and fast. */
public final class ImageUtil {

    private ImageUtil() { }

    private static int sampleFor(int w, int h, int maxSide) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= maxSide) s *= 2;
        return s;
    }

    private static Bitmap rotate(Bitmap bmp, int orientation) {
        int deg = 0;
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
        else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
        if (deg == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
    }

    private static Bitmap scaleDown(Bitmap bmp, int maxSide) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (Math.max(w, h) <= maxSide) return bmp;
        float sc = maxSide / (float) Math.max(w, h);
        return Bitmap.createScaledBitmap(bmp, Math.round(w * sc), Math.round(h * sc), true);
    }

    /** Decode a photo file, apply its EXIF rotation, and limit the longest side. */
    public static Bitmap decodeFile(File f, int maxSide) throws Exception {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), b);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleFor(b.outWidth, b.outHeight, maxSide);
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (bmp == null) throw new Exception("decode failed");
        int orientation = new ExifInterface(f.getAbsolutePath())
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        return scaleDown(rotate(bmp, orientation), maxSide);
    }

    /** Decode a gallery image (content Uri). */
    public static Bitmap decodeUri(Context c, Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, b);
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleFor(b.outWidth, b.outHeight, maxSide);
        Bitmap bmp;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, o);
        }
        if (bmp == null) throw new Exception("decode failed");
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            orientation = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) { }
        return scaleDown(rotate(bmp, orientation), maxSide);
    }

    public static String toBase64(Bitmap bmp, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /**
     * Small upright copy of a camera photo for uploading to the face server
     * (a 12 MP photo takes very long through a tunnel). Falls back to the original file.
     */
    public static File prepareForUpload(Context c, File src, int maxSide, int quality) {
        try {
            Bitmap bmp = decodeFile(src, maxSide);
            File out = new File(c.getCacheDir(), "up_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, fo);
            }
            return out;
        } catch (Exception e) {
            return src;
        }
    }
}
