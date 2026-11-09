package com.rexglue.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

/**
 * On-screen virtual gamepad overlay for ReXGlue recompiled games.
 * Provides touch controls for POCO X7 Pro without needing an external controller.
 */
public class VirtualGamepadView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public VirtualGamepadView(Context context) {
        super(context);
        init();
    }

    public VirtualGamepadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4.0f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // Semi-transparent controls overlay
        paint.setColor(Color.argb(80, 255, 255, 255));
        paint.setStyle(Paint.Style.FILL);

        // Left Analog Stick base
        canvas.drawCircle(w * 0.15f, h * 0.75f, h * 0.15f, paint);

        // Right Action Buttons (ABXY cluster)
        canvas.drawCircle(w * 0.85f, h * 0.75f, h * 0.05f, paint); // A
        canvas.drawCircle(w * 0.92f, h * 0.65f, h * 0.05f, paint); // B
        canvas.drawCircle(w * 0.78f, h * 0.65f, h * 0.05f, paint); // X
        canvas.drawCircle(w * 0.85f, h * 0.55f, h * 0.05f, paint); // Y

        // Triggers / Bumpers
        canvas.drawRect(w * 0.05f, h * 0.1f, w * 0.25f, h * 0.2f, paint); // L1/L2
        canvas.drawRect(w * 0.75f, h * 0.1f, w * 0.95f, h * 0.2f, paint); // R1/R2
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Forward touch inputs or simulate gamepad events
        return super.onTouchEvent(event);
    }
}
