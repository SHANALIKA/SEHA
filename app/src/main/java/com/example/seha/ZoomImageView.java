package com.example.seha;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/** Full-screen image: pinch to zoom, drag to move, double-tap to zoom in / reset. */
public class ZoomImageView extends AppCompatImageView {

    private final Matrix matrix = new Matrix();
    private final float[] v = new float[9];
    private float fitScale = 1f;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private float lastX, lastY;
    private int activePointer = -1;

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float cur = currentScale();
                float target = Math.max(fitScale, Math.min(cur * d.getScaleFactor(), fitScale * 8f));
                float f = target / cur;
                matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                fixBounds();
                setImageMatrix(matrix);
                return true;
            }
        });

        tapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (currentScale() > fitScale * 1.2f) {
                    fit();
                } else {
                    float f = (fitScale * 3f) / currentScale();
                    matrix.postScale(f, f, e.getX(), e.getY());
                    fixBounds();
                    setImageMatrix(matrix);
                }
                return true;
            }
        });
    }

    private float currentScale() {
        matrix.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        post(this::fit);
    }

    private void fit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        fitScale = Math.min(getWidth() / dw, getHeight() / dh);
        matrix.reset();
        matrix.postScale(fitScale, fitScale);
        matrix.postTranslate((getWidth() - dw * fitScale) / 2f, (getHeight() - dh * fitScale) / 2f);
        setImageMatrix(matrix);
    }

    private void fixBounds() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(r);
        float dx = 0, dy = 0;
        if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        tapDetector.onTouchEvent(e);

        if (!scaleDetector.isInProgress()) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    activePointer = e.getPointerId(0);
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_MOVE: {
                    int idx = e.findPointerIndex(activePointer);
                    if (idx >= 0 && e.getPointerCount() == 1) {
                        float x = e.getX(idx), y = e.getY(idx);
                        matrix.postTranslate(x - lastX, y - lastY);
                        fixBounds();
                        setImageMatrix(matrix);
                        lastX = x;
                        lastY = y;
                    }
                    break;
                }
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    activePointer = -1;
                    // re-anchor the finger that is left
                    if (e.getPointerCount() > 1) {
                        int up = e.getActionIndex();
                        int keep = up == 0 ? 1 : 0;
                        activePointer = e.getPointerId(keep);
                        lastX = e.getX(keep);
                        lastY = e.getY(keep);
                    }
                    break;
                default:
                    break;
            }
        } else {
            lastX = e.getX();
            lastY = e.getY();
        }
        return true;
    }
}
