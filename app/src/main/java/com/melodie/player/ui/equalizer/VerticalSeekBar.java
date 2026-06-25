package com.melodie.player.ui.equalizer;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.melodie.player.R;

/**
 * A fully custom vertical slider for the equalizer.
 * Does NOT extend SeekBar — draws everything itself, guaranteed visible.
 */
public class VerticalSeekBar extends View {

    public interface OnProgressChangeListener {
        void onProgressChanged(VerticalSeekBar seekBar, int progress, boolean fromUser);
    }

    private OnProgressChangeListener listener;

    private int max = 100;
    private int progress = 0;

    private final Paint trackBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float trackWidthPx;
    private float trackRadiusPx;
    private float thumbWidthPx;
    private float thumbHeightPx;
    private float thumbRadiusPx;

    private boolean tracking;

    public VerticalSeekBar(@NonNull Context context) {
        super(context);
        initAttrs(null);
        init();
    }

    public VerticalSeekBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initAttrs(attrs);
        init();
    }

    public VerticalSeekBar(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initAttrs(attrs);
        init();
    }

    private void initAttrs(@Nullable AttributeSet attrs) {
        if (attrs != null) {
            // Parse android:max from XML
            TypedArray a = getContext().obtainStyledAttributes(attrs, new int[]{android.R.attr.max});
            max = a.getInt(0, 100);
            if (max < 1) max = 100;
            a.recycle();
        }
    }

    private void init() {
        // Force the view to draw even without a background
        setWillNotDraw(false);

        float density = getResources().getDisplayMetrics().density;
        trackWidthPx = 18f * density;
        trackRadiusPx = 6f * density;
        thumbWidthPx = 32f * density;
        thumbHeightPx = 16f * density;
        thumbRadiusPx = 5f * density;
        float thumbBorderPx = 2f * density;

        // Track background: use a clearly visible dark gray (#2A2A3A)
        trackBgPaint.setColor(Color.parseColor("#2A2A3A"));
        trackBgPaint.setStyle(Paint.Style.FILL);

        trackFillPaint.setColor(ContextCompat.getColor(getContext(), R.color.melodie_purple));
        trackFillPaint.setStyle(Paint.Style.FILL);

        thumbPaint.setColor(ContextCompat.getColor(getContext(), R.color.melodie_purple));
        thumbPaint.setStyle(Paint.Style.FILL);

        thumbBorderPaint.setColor(Color.WHITE);
        thumbBorderPaint.setStyle(Paint.Style.STROKE);
        thumbBorderPaint.setStrokeWidth(thumbBorderPx);
    }

    public void setOnProgressChangeListener(OnProgressChangeListener l) {
        this.listener = l;
    }

    public void setMax(int max) {
        this.max = Math.max(1, max);
        invalidate();
    }

    public int getMax() {
        return max;
    }

    public void setProgress(int progress) {
        setProgress(progress, false);
    }

    public void setProgress(int progress, boolean fromUser) {
        this.progress = Math.max(0, Math.min(max, progress));
        invalidate();
        if (listener != null) {
            listener.onProgressChanged(this, this.progress, fromUser);
        }
    }

    public int getProgress() {
        return progress;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Ensure we have a valid size even if wrap_content is used
        int desiredWidth = (int) (thumbWidthPx + getPaddingLeft() + getPaddingRight());
        int desiredHeight = (int) (200 * getResources().getDisplayMetrics().density);

        int width = resolveSize(desiredWidth, widthMeasureSpec);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float paddingTop = getPaddingTop();
        float paddingBottom = getPaddingBottom();
        float usableHeight = h - paddingTop - paddingBottom;
        if (usableHeight <= 0) return;

        float centerX = w / 2f;

        // 1) Track background (full height, centered)
        float trackLeft = centerX - trackWidthPx / 2f;
        float trackRight = centerX + trackWidthPx / 2f;
        float trackTop = paddingTop;
        float trackBottom = paddingTop + usableHeight;

        rect.set(trackLeft, trackTop, trackRight, trackBottom);
        canvas.drawRoundRect(rect, trackRadiusPx, trackRadiusPx, trackBgPaint);

        // 2) Progress fill (from bottom up)
        float ratio = max > 0 ? (float) progress / max : 0f;
        float fillHeight = usableHeight * ratio;
        float fillTop = trackBottom - fillHeight;

        if (fillHeight > 0) {
            rect.set(trackLeft, fillTop, trackRight, trackBottom);
            canvas.drawRoundRect(rect, trackRadiusPx, trackRadiusPx, trackFillPaint);
        }

        // 3) Thumb at the fill top position (always visible)
        float thumbCenterY = fillTop;
        // Clamp thumb so it stays within view bounds
        thumbCenterY = Math.max(paddingTop + thumbHeightPx / 2f, 
                        Math.min(trackBottom - thumbHeightPx / 2f, thumbCenterY));

        float tLeft = centerX - thumbWidthPx / 2f;
        float tRight = centerX + thumbWidthPx / 2f;
        float tTop = thumbCenterY - thumbHeightPx / 2f;
        float tBottom = thumbCenterY + thumbHeightPx / 2f;

        rect.set(tLeft, tTop, tRight, tBottom);
        canvas.drawRoundRect(rect, thumbRadiusPx, thumbRadiusPx, thumbPaint);
        canvas.drawRoundRect(rect, thumbRadiusPx, thumbRadiusPx, thumbBorderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event.getY());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (tracking) updateFromTouch(event.getY());
                return true;

            case MotionEvent.ACTION_UP:
                tracking = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;

            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateFromTouch(float y) {
        float usableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (usableHeight <= 0) return;
        float touchY = y - getPaddingTop();
        float ratio = 1f - (touchY / usableHeight);
        ratio = Math.max(0f, Math.min(1f, ratio));
        int newProgress = Math.round(ratio * max);
        if (newProgress != progress) {
            setProgress(newProgress, true);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
