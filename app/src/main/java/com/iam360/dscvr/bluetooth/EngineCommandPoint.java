package com.iam360.dscvr.bluetooth;

import android.graphics.Rect;

import java.util.Collection;

/**
 * Created by Charlotte on 17.02.2017.
 */
public class EngineCommandPoint {

    private final float p;
    private final float x;
    private final float y;

    public EngineCommandPoint(float p, float x, float y) {
        this.p = p;
        this.x = x;
        this.y = y;
    }

    public EngineCommandPoint(float x, float y) {
        this.p = 1;
        this.x = x;
        this.y = y;
    }

    public static EngineCommandPoint CreateMiddel(Collection<Rect> rects) {
        int centerX = 0;
        int centerY = 0;
        for (Rect rect : rects) {
            centerX += rect.centerX();
            centerY += rect.centerY();
        }
        return new EngineCommandPoint(centerX / rects.size(), centerY / rects.size());


    }

    public float getX() {
        return x * p;
    }

    public float getY() {
        return y * p;
    }

    public EngineCommandPoint add(EngineCommandPoint b) {
        return new EngineCommandPoint(p, x + b.x, y + b.y);
    }

    public EngineCommandPoint sub(EngineCommandPoint b) {
        return new EngineCommandPoint(p, x - b.x, y - b.y);
    }

    public EngineCommandPoint mul(EngineCommandPoint b) {
        return new EngineCommandPoint(p, x * b.x, y * b.y);
    }

    public EngineCommandPoint mul(float b) {
        return new EngineCommandPoint(p, x * b, y * b);
    }

    public EngineCommandPoint div(EngineCommandPoint b) {
        return new EngineCommandPoint(p, x / b.x, y / b.y);
    }

    public EngineCommandPoint div(float b) {
        return new EngineCommandPoint(p, x / b, y / b);
    }

    public EngineCommandPoint abs() {
        return new EngineCommandPoint(p, Math.abs(x), Math.abs(y));
    }

    public EngineCommandPoint min(EngineCommandPoint b) {
        return new EngineCommandPoint(p, Math.min(x, b.x), Math.min(y, b.y));
    }

    public EngineCommandPoint max(EngineCommandPoint b) {
        return new EngineCommandPoint(p, Math.max(x, b.x), Math.max(y, b.y));
    }


}
