package com.melodie.player.ui.equalizer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatSeekBar;

/**
 * Simple vertical SeekBar used by the equalizer screen.
 */
public class VerticalSeekBar extends AppCompatSeekBar {

    public VerticalSeekBar(@NonNull Context context) {
        super(context);
    }

    public VerticalSeekBar(@NonNull Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VerticalSeekBar(@NonNull Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec);
        setMeasuredDimension(getMeasuredHeight(), getMeasuredWidth());
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        canvas.rotate(-90);
        canvas.translate(-getHeight(), 0);
        super.onDraw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float ratio = 1f - (event.getY() / (float) getHeight());
                ratio = Math.max(0f, Math.min(1f, ratio));
                int progress = Math.round(ratio * getMax());
                setProgress(progress);
                onSizeChanged(getWidth(), getHeight(), 0, 0);
                break;
            case MotionEvent.ACTION_CANCEL:
            default:
                break;
        }

        return true;
    }
}

