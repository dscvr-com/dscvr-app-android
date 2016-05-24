package com.iam360.iam360.record;

import android.graphics.Bitmap;

import timber.log.Timber;

/**
 * @author Nilan Marktanner
 * @date 2016-02-07
 */
public class Recorder {
    static {
        System.loadLibrary("ndkmodule");
    }

    private static boolean isInitialized = false;

    private static native void initRecorder(String storagePath, float sensorWidth, float sensorHeight, float focalLength, int mode);
    public static native void push(Bitmap bitmap, double[] extrinsicsData);
    public static native SelectionPoint[] getSelectionPoints();
    public static native void finish();
    private static native void dispose();
    public static native float[] getBallPosition();
    public static native boolean isFinished();
    public static native double getDistanceToBall();
    public static native float[] getAngularDistanceToBall();
    public static native void setIdle(boolean idle);
    public static native boolean hasStarted();
    public static native boolean isIdle();
    public static native void enableDebug(String storagePath);
    public static native void disableDebug();
    public static native SelectionPoint lastKeyframe();
    public static native int getRecordedImagesCount();
    public static native int getImagesToRecordCount();
    public static native float[] getCurrentRotation();
    public static native Bitmap getPreviewImage();
    public static native boolean previewAvailable();

    public static void initializeRecorder(String storagePath, float sensorWidth, float sensorHeight, float focalLength, int mode) {
        if (!isInitialized) {
            Timber.v("Initialized recorder");
//            enableDebug(storagePath);
            disableDebug();
            initRecorder(storagePath, sensorWidth, sensorHeight, focalLength, mode);
            isInitialized = true;
        } else {
            throw new RuntimeException("Recorder already initialized");
        }
    }

    public static void disposeRecorder() {
        dispose();
        isInitialized = false;
    }
}