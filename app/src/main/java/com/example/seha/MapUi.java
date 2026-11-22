package com.example.seha;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;

import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;

/** Shared map helpers used by the ambulance / trip screens. */
public final class MapUi {

    private MapUi() { }

    public static void init(Context appCtx) {
        Configuration.getInstance().load(appCtx, PreferenceManager.getDefaultSharedPreferences(appCtx));
        Configuration.getInstance().setUserAgentValue(
                "SEHA-Medical-App/1.0 (com.example.seha; Android student project)");
    }

    public static void setup(MapView map) {
        map.setTileSource(MapTiles.STREET);
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(false);
        map.setTilesScaledToDpi(true);
        map.setHorizontalMapRepetitionEnabled(false);
        map.setVerticalMapRepetitionEnabled(false);
        map.setMinZoomLevel(5.0);
        map.setMaxZoomLevel(20.0);
        map.getController().setZoom(15.0);
        map.getController().setCenter(new GeoPoint(6.9271, 79.8612));
    }

    public static Drawable icon(Context c, int resId, int dp) {
        Drawable d = ContextCompat.getDrawable(c, resId);
        if (d == null) return null;
        int size = (int) (dp * c.getResources().getDisplayMetrics().density);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.draw(canvas);
        return new BitmapDrawable(c.getResources(), bmp);
    }

    /** Creates the marker the first time, afterwards just moves it. */
    public static Marker marker(MapView map, Marker existing, GeoPoint p,
                                String title, Drawable icon) {
        Marker m = existing;
        if (m == null) {
            m = new Marker(map);
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            m.setPanToView(false);
            map.getOverlays().add(m);
        }
        if (icon != null) m.setIcon(icon);
        m.setPosition(p);
        m.setTitle(title);
        m.setSnippet(title);
        map.invalidate();
        return m;
    }

    public static void removeMarker(MapView map, Marker m) {
        if (m != null) {
            map.getOverlays().remove(m);
            map.invalidate();
        }
    }

    /** Live route (drawn above the planned trip line, below the markers). */
    public static Polyline line(MapView map, Polyline existing, List<GeoPoint> pts, int color) {
        return line(map, existing, pts, color, 12f, false);
    }

    /**
     * @param planned true = the whole planned trip (start -> end), thick light line at the very bottom
     */
    public static Polyline line(MapView map, Polyline existing, List<GeoPoint> pts, int color,
                                float width, boolean planned) {
        if (existing != null) map.getOverlays().remove(existing);
        Polyline pl = new Polyline();
        pl.setPoints(pts);
        pl.getOutlinePaint().setColor(color);
        pl.getOutlinePaint().setStrokeWidth(width);
        int idx = 0;
        if (!planned) {
            // after the last existing polyline, still below all markers
            for (int i = 0; i < map.getOverlays().size(); i++) {
                if (map.getOverlays().get(i) instanceof Polyline) idx = i + 1;
            }
        }
        map.getOverlays().add(idx, pl);
        map.invalidate();
        return pl;
    }

    public static void removeLine(MapView map, Polyline pl) {
        if (pl != null) {
            map.getOverlays().remove(pl);
            map.invalidate();
        }
    }

    /** Zoom so all given (non-null) points are visible. */
    public static void fit(MapView map, GeoPoint... pts) {
        List<GeoPoint> list = new ArrayList<>();
        for (GeoPoint p : pts) if (p != null) list.add(p);
        if (list.isEmpty()) return;
        if (list.size() == 1) {
            map.getController().setZoom(16.0);
            map.getController().setCenter(list.get(0));
            return;
        }
        BoundingBox box = BoundingBox.fromGeoPoints(list);
        if (box.getDiagonalLengthInMeters() < 40) {
            map.getController().setZoom(17.0);
            map.getController().setCenter(list.get(0));
            return;
        }
        map.post(() -> map.zoomToBoundingBox(box, true, 140));
    }
}
