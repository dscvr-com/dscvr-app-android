package com.iam360.iam360.views.record;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.TextureView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

/**
 * Created by emi on 16/06/16.
 */
public class RecorderPreviewView extends AutoFitTextureView {

    private static final String TAG = "RecordPreviewView";

    private AutoFitTextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession previewSession;
    private CaptureRequest.Builder previewBuilder;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private Semaphore cameraOpenCloseLock = new Semaphore(1);

    private Size previewSize;
    private Size videoSize;

    private CodecSurface surface;
    private HandlerThread decoderThread;
    private Handler decoderHandler;

    private RecorderPreviewListener
            dataListener;

    public RecorderPreviewView(Context ctx) {
        super(ctx);
        this.textureView = this;
        this.videoSize = new Size(720, 1280); //Size we want for stitcher input
    }

    // To be called from parent activity
    public void onResume() {
        startBackgroundThread();
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.getHolder().addCallback(surfaceTextureListener);
        }
    }

    public void setPreviewListener(RecorderPreviewListener dataListener) {
        this.dataListener = dataListener;
    }

    private final static int START_DECODER = 0;
    private final static int FETCH_FRAME = 1;
    private final static int EXIT_DECODER = 2;

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        decoderThread = new HandlerThread("CameraDecoder");
        decoderThread.start();
        this.decoderHandler = new Handler(decoderThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                Log.w(TAG, "Message tag: " + msg.what);
                if(msg.what == START_DECODER) {
                    createDecoderSurface();
                    // So I have no idea what we wait for. So we just wait.
                    try {
                        Thread.sleep(2500, 0);
                    } catch (InterruptedException e) { }
                } else if(msg.what == FETCH_FRAME) {
                    fetchFrame();
                } else if(msg.what == EXIT_DECODER) {
                    destroyDecoderSurface();
                }

            }

        };

        decoderHandler.obtainMessage(START_DECODER).sendToTarget();
    }

    public interface RecorderPreviewListener {
        void imageDataReady(byte[] data, int width, int height, Bitmap.Config colorFormat);
        void cameraOpened(CameraDevice device);
        void cameraClosed(CameraDevice device);
    }

    private void createDecoderSurface() {
        surface = new CodecSurface(videoSize.getWidth(), videoSize.getHeight());
        decoderHandler.obtainMessage(FETCH_FRAME).sendToTarget();
    }

    private void destroyDecoderSurface() {
        surface.release();
        surface = null;
    }

    private void fetchFrame() {
        if(surface == null) {
            return;
        }
        try {
            if(dataListener != null) {
                if(surface.awaitNewImage()) {
                    surface.drawImage(false);
                    Timber.d("Fetch frame success");
                    dataListener.imageDataReady(surface.fetchPixels(), surface.mWidth, surface.mHeight, surface.colorFormat);
                }
            } else {
                Timber.e("Fetch frame failed");
                Thread.sleep(10, 0);
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            // Do nothing
        }

        decoderHandler.obtainMessage(FETCH_FRAME).sendToTarget();
    }

    // To be called from parent activity
    public void onPause() {
        stopBackgroundThread();
        closeCamera();
    }

    public void stopPreviewFeed() {
        stopBackgroundThread();
        closeCamera();
    }

    private void stopBackgroundThread() {
        if(backgroundThread == null)
            return;
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
            decoderHandler.obtainMessage(EXIT_DECODER).sendToTarget();
            decoderThread.quitSafely();
            decoderThread.join();
            decoderThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            closePreviewSession();
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    private void configureTransform(int width, int height) {
        // Do nothing for now, we are locked in portrait anyway.
    }

    private void openCamera(int width, int height) {
        // Todo - open camera
        CameraManager manager = (CameraManager)getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            Log.d(TAG, "tryAcquire");
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            for (String cameraId : manager.getCameraIdList()){
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                if(characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT){
                    continue;
                }
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                previewSize = chooseOptimalPreviewSize(map.getOutputSizes(SurfaceTexture.class), width, height, videoSize);
                textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                configureTransform(width, height);
                manager.openCamera(cameraId, stateCallback, null);
                break;
            }

        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }

    private static Size chooseOptimalPreviewSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<Size>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }


    // Starts the preview, if all necassary parts are there.
    private void startPreview() {
        if (null == cameraDevice || !textureView.isAvailable() || null == previewSize) {
            return;
        }
        try {
            closePreviewSession();
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            Surface previewSurface = textureView.getHolder().getSurface();
            previewBuilder.addTarget(previewSurface);
            previewBuilder.addTarget(surface.getSurface());

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, surface.getSurface()), new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                    previewSession = cameraCaptureSession;
                    beginPreview();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                    Log.e(TAG, "Camera configure failed.");
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void beginPreview() {
        if (null == cameraDevice) {
            return;
        }
        try {
            previewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            previewSession.stopRepeating();
            previewSession.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void lockExposure() {
        if (null == cameraDevice) {
            return;
        }
        try {
            Log.w(TAG, "Locking Exposure.");
            previewBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
            previewBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
//            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            previewSession.stopRepeating();
            previewSession.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closePreviewSession() {
        if (previewSession != null) {
            previewSession.close();
            previewSession = null;
        }
    }

    // Callbacks for surface texture loading - open camera as soon as texture exists
    private SurfaceHolder.Callback surfaceTextureListener
            = new SurfaceHolder.Callback() {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            openCamera(videoSize.getWidth(), videoSize.getHeight());
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {

        }

    };

    // Callbacks for cam opening - save camera ref and start preview
    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            RecorderPreviewView.this.cameraDevice = cameraDevice;
            startPreview();
            cameraOpenCloseLock.release();
            if (null != textureView) {
                configureTransform(textureView.getWidth(), textureView.getHeight());
            }
            if(null != dataListener) {
                dataListener.cameraOpened(cameraDevice);
            }
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            cameraOpenCloseLock.release();
            cameraDevice.close();
            RecorderPreviewView.this.cameraDevice = null;
            if(null != dataListener) {
                dataListener.cameraClosed(cameraDevice);
            }
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            cameraOpenCloseLock.release();
            cameraDevice.close();
            RecorderPreviewView.this.cameraDevice = null;
        }

    };

    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

}
