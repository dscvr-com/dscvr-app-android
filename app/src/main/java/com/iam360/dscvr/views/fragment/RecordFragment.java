package com.iam360.dscvr.views.fragment;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.util.SizeF;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.iam360.dscvr.DscvrApp;
import com.iam360.dscvr.R;
import com.iam360.dscvr.bluetooth.BluetoothConnectionReciever;
import com.iam360.dscvr.bluetooth.BluetoothEngineControlService;
import com.iam360.dscvr.record.Edge;
import com.iam360.dscvr.record.GlobalState;
import com.iam360.dscvr.record.LineNode;
import com.iam360.dscvr.record.Recorder;
import com.iam360.dscvr.record.RecorderOverlayView;
import com.iam360.dscvr.record.SelectionPoint;
import com.iam360.dscvr.views.activity.RecorderActivity;
import com.iam360.dscvr.views.record.CancelRecorderJob;
import com.iam360.dscvr.views.record.FinishRecorderJob;
import com.iam360.dscvr.views.record.RecorderPreviewView;
import com.iam360.dscvr.sensors.CustomRotationMatrixSource;
import com.iam360.dscvr.sensors.DefaultListeners;
import com.iam360.dscvr.util.Cache;
import com.iam360.dscvr.util.CameraUtils;
import com.iam360.dscvr.util.Constants;
import com.iam360.dscvr.util.DeviceName;
import com.iam360.dscvr.util.Maths;
import com.iam360.dscvr.util.MixpanelHelper;
import com.iam360.dscvr.util.Vector3;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import timber.log.Timber;

/**
 * @author Nilan Marktanner
 * @date 2016-02-08
 */
public class RecordFragment extends Fragment {
    private String TAG = RecordFragment.class.getSimpleName();
    private RecorderPreviewView recordPreview;
    private RecorderOverlayView recorderOverlayView;
    private Vector3 ballPosition = new Vector3();
    private Vector3 ballSpeed = new Vector3();
    private SelectionPoint lastKeyframe;


    private Timer timer = new Timer();

    private float exposureDuration;
    private float sensorWidthInMeters = 0.004f;
    private long time = -1;
    private int captureWidth;
    private int mode;
    private boolean fromPause = false;

    // TODO: use this map
    private Map<Edge, LineNode> edgeLineNodeMap = new HashMap<>();

    // Map globalIds of the edge's selection points : LineNode
    private Map<String, LineNode> edgeLineNodeGlobalIdMap = new HashMap<>();

    // motor variables
    private float currentDegree = (float) 0.03;
    private long lastElapsedTime = System.currentTimeMillis();
    private float currentTheta = (float) 0.0;
    private float currentPhi = (float) 0.0;
    private boolean isRecording = false;
    CustomRotationMatrixSource customRotationMatrixSource;

    private Cache cache;

    private SizeF size;
    private float focalLength;
    private long currentTime = 0;

    //FIXME: don't use a bool
    private boolean isRecorderReady = false;

    private RecorderPreviewView.RecorderPreviewListener previewListener = new RecorderPreviewView.RecorderPreviewListener() {
        @Override
        public void imageDataReady(byte[] data, int width, int height, Bitmap.Config colorFormat) {
            if (!isRecorderReady) {
                return;
            }
            if (Recorder.isFinished()) {
                return;// sync hack
            }
            assert colorFormat == Bitmap.Config.ARGB_8888;

            // build extrinsics
            float[] coreMotionMatrix = ((DscvrApp) getActivity().getApplicationContext()).getMatrixProvider().getRotationMatrix();
            double[] extrinsicsData = Maths.convertFloatsToDoubles(coreMotionMatrix);

            Log.w(TAG, "Pushing data: " + width + "x" + height);
            assert width * height * 4 == data.length;

            Recorder.push(data, width, height, extrinsicsData);

            // progress bar
            ((RecorderActivity) getActivity()).setProgressLocation((float) (Recorder.getRecordedImagesCount()) / (float) (Recorder.getImagesToRecordCount()));

            // normal towards ring
            float angle = Recorder.getAngularDistanceToBall()[2];
            ((RecorderActivity) getActivity()).setAngleRotation(angle);

            float[] unit = {0, 0, 1, 0};
            Vector3 ballHeading = new Vector3(ballPosition);
            ballHeading.normalize();

//            float[] currentRotation = Recorder.getCurrentRotation();
            recorderOverlayView.getRecorderOverlayRenderer().setRotationMatrix(coreMotionMatrix);


            float[] currentHeading = new float[4];
            Matrix.multiplyMV(currentHeading, 0, coreMotionMatrix, 0, unit, 0);
            Vector3 currentHeadingVec = new Vector3(currentHeading[0], currentHeading[1], currentHeading[2]);

            // Use 3D diff as dist
            Vector3 diff = Vector3.subtract(ballHeading, currentHeadingVec);
            float distXY = diff.length();

            // Helpers for bearing and distance. Relative to ball.
            float[] angularBallHeading = recorderOverlayView.getPointOnScreen(new float[]{ballPosition.x, ballPosition.y, ballPosition.z, 0});
            float[] angularCurrentHeading = recorderOverlayView.getPointOnScreen(currentHeading);

            float[] angularDiff = new float[2];
            angularDiff[0] = angularBallHeading[0] - angularCurrentHeading[0];
            angularDiff[1] = angularBallHeading[1] - angularCurrentHeading[1];

            new Handler(getActivity().getMainLooper()).post(new Runnable() {
                public void run() {
                    ((RecorderActivity) getActivity()).setArrowRotation((float) Math.atan2(angularDiff[0], angularDiff[1]));
                    ((RecorderActivity) getActivity()).setArrowVisible(distXY > 0.15 ? true : false);
                    ((RecorderActivity) getActivity()).setGuideLinesVisible((Math.abs(angle) > 0.05 && distXY < 0.15) ? true : false);
                }
            });

            updateBallPosition();

            // shading of recorded nodes
            if (Recorder.hasStarted()) {
                SelectionPoint currentKeyframe = Recorder.lastKeyframe();
                if (lastKeyframe == null) {
                    lastKeyframe = currentKeyframe;
                } else if (currentKeyframe.getGlobalId() != lastKeyframe.getGlobalId()) {
                    Edge recordedEdge = new Edge(lastKeyframe, currentKeyframe);
                    if (edgeLineNodeGlobalIdMap.get(recordedEdge.getGlobalIds()) != null)
                        recorderOverlayView.colorChildNode(edgeLineNodeGlobalIdMap.get(recordedEdge.getGlobalIds()));
                    lastKeyframe = currentKeyframe;
                }
            }

            if (Recorder.isFinished()) {
                // TODO: change mode to POST_RECORD
                Snackbar.make(recordPreview, "Recording is finished, please wait for the result!", Snackbar.LENGTH_LONG).show();

                // queue finishing on main thread
                queueFinishRecording();
            }
            long dif = System.currentTimeMillis() - currentTime;
            currentTime = System.currentTimeMillis();
            Timber.d("imgRecievedAfter: " + dif);
        }

        @Override
        public void cameraOpened(CameraDevice device) {
            CameraManager cameraManager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
            try {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(device.getId());
                // initialize recorder
                size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                // Add some margin to the focal length, to avoid too short focal lengths.
                focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)[0] * 1.5f;

            } catch (CameraAccessException e) {
                Log.d("MARK", "CameraAccessException e" + e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void cameraClosed(CameraDevice device) {
        }
    };

    private void queueFinishRecording() {
        // see: http://stackoverflow.com/a/11125271/1176596
        // Get a handler that can be used to post to the main thread
        Handler mainHandler = new Handler(getActivity().getMainLooper());
        mainHandler.post(this::finishRecording);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        cache = Cache.open();
        this.mode = cache.getInt(Cache.CAMERA_MODE);
        View view = inflater.inflate(R.layout.fragment_record, container, false);

        // Create our Preview view and set it as the content of our activity.
        recordPreview = new RecorderPreviewView(getActivity());
        recordPreview.setPreviewListener(previewListener);
        recorderOverlayView = new RecorderOverlayView(getActivity());
        FrameLayout preview = (FrameLayout) view.findViewById(R.id.record_preview);
        preview.addView(recordPreview);
        preview.addView(recorderOverlayView);
        MixpanelHelper.trackViewCamera(getContext());
        return view;
    }


    @Override
    public void onResume() {
        super.onResume();
        if (!cache.getBoolean(Cache.MOTOR_ON)) {
            DefaultListeners.register();
        }

        recordPreview.onResume();
    }

    @Override
    public void onPause() {
        recordPreview.onPause();
        super.onPause();
        if (!cache.getBoolean(Cache.MOTOR_ON)) {
            DefaultListeners.unregister();
        }
        fromPause = true;
    }

    /**
     *
     */
    public void startRecording() {

        Timber.d("Initializing recorder with f: " + focalLength + " sx: " + size.getWidth() + " sy: " + size.getHeight());
        Recorder.initializeRecorder(CameraUtils.CACHE_PATH, size.getWidth(), size.getHeight(), focalLength, mode);
        // be shure for manual Mode:
        if (!cache.getBoolean(Cache.MOTOR_ON)) {
            DefaultListeners.register();
        } else {
            DefaultListeners.unregister();
        }

        setupSelectionPoints();
        isRecorderReady = true;
        Timber.v("Starting recording...");

        MixpanelHelper.trackCameraStartRecording(getContext());

        recorderOverlayView.getRecorderOverlayRenderer().startRendering();
        recordPreview.lockExposure();
        BluetoothEngineControlService bluetoothService = ((DscvrApp) getActivity().getApplicationContext()).getBluetoothService();
        boolean first = true;
        for (Float statingPoint : getStartingPoints()) {
            //FIXME to hacky
            Timber.d("startingPoints: " + statingPoint);
            if (first) {
                bluetoothService.goToDeg(statingPoint);
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        bluetoothService.goCompleteAround(BluetoothEngineControlService.SPEED);
                        Recorder.setIdle(false);
                    }
                }, 300);
                first = false;
            } else {
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        bluetoothService.move360withDeg(statingPoint);
                    }
                }, 1500);
            }
        }
        isRecording = true;
    }

    public ArrayList<Float> getStartingPoints() {
        float[] vector = {0, 0, 1, 0};
        ArrayList<Float> result = new ArrayList<>();
        SelectionPoint[] rawPoints = Recorder.getSelectionPoints();
        float[] extrinsics;
        float[] resultOfMultiply = new float[4];
        double valueInRad;
        float valueInDeg;
        for (SelectionPoint point : rawPoints) {
            extrinsics = point.getExtrinsics();
            Matrix.multiplyMV(resultOfMultiply, 0, extrinsics, 0, vector, 0);
            valueInRad = Math.atan(resultOfMultiply[1]);
            valueInDeg = (float) ((valueInRad * 180) / Math.PI);
            if (!result.contains(valueInDeg)) {
                result.add(valueInDeg);
            }

        }
        return result;
    }

    /**
     * show lines for recording
     */
    private void setupSelectionPoints() {
        SelectionPoint[] rawPoints = Recorder.getSelectionPoints();
        List<SelectionPoint> points = new LinkedList<>();
        List<SelectionPoint> points2 = new LinkedList<>();

        Log.d("MARK", "setupSelectionPoints rawPoints.length = " + rawPoints.length);
        for (SelectionPoint p : rawPoints) {
            points.add(p);
            points2.add(p);
        }

        points2.remove(0);

        for (int i = 0; i < points2.size(); ++i) {
            SelectionPoint a = points.get(i);
            SelectionPoint b = points2.get(i);
            if (a.getRingId() == b.getRingId()) {
                Edge edge = new Edge(a, b);
                float[] vector = {0, 0, 1, 0};
                float[] posA = new float[4];
                float[] posB = new float[4];
                Matrix.multiplyMV(posA, 0, a.getExtrinsics(), 0, vector, 0);
                Matrix.multiplyMV(posB, 0, b.getExtrinsics(), 0, vector, 0);

                LineNode edgeNode = new LineNode(posA, posB);
                edgeLineNodeMap.put(edge, edgeNode);
                edgeLineNodeGlobalIdMap.put(edge.getGlobalIds(), edgeNode);
                recorderOverlayView.addChildNode(edgeNode);
            }
        }
    }

    public void finishRecording() {

        MixpanelHelper.trackCameraFinishRecording(getContext());
        GlobalState.isAnyJobRunning = true;
        UUID id = UUID.randomUUID();

        recordPreview.setPreviewListener(null);
        recordPreview.stopPreviewFeed();

        // start a background thread to finish recorder
        DscvrApp.getInstance().getJobManager().addJobInBackground(new FinishRecorderJob(id));

//        ((MainActivityRedesign) getActivity()).backToFeed(false);//original
        ((RecorderActivity) getActivity()).startPreview(id);
    }

    public void cancelRecording() {
        recordPreview.setPreviewListener(null);
        recordPreview.stopPreviewFeed();

        // TODO: What is this pause call for?
        recordPreview.onPause();

        MixpanelHelper.trackCameraCancelRecording(getContext());
        GlobalState.isAnyJobRunning = true;

        // start background thread to cancel recorder
        DscvrApp.getInstance().getJobManager().addJobInBackground(new CancelRecorderJob());
    }

    private void updateBallPosition() {
        float[] vector = {0, 0, 0.9f, 0};
        float[] newPosition = new float[4];
        Matrix.multiplyMV(newPosition, 0, Recorder.getBallPosition(), 0, vector, 0);

        ballPosition = smoothenBall(ballPosition, newPosition);
//        ballPosition.set(newPosition[0], newPosition[1], newPosition[2]);

        // use ball position
        recorderOverlayView.getRecorderOverlayRenderer().setSpherePosition(ballPosition.x, ballPosition.y, ballPosition.z);

    }

    private Vector3 smoothenBall(Vector3 ballPos, float[] newPosition) {

        // Quick hack to limit expo duration in calculations, due to unexpected results of CACurrentMediaTime
        float exposureDuration = (float) Math.max(this.exposureDuration, 0.006);

        float ballSphereRadius = 0.9f; // Don't put it on 1, since it would overlap with the rings then.
        float movementPerFrameInPixels = 1500;

        Calendar calendar = Calendar.getInstance();
        long newTime = calendar.getTimeInMillis();

        Vector3 target = new Vector3(newPosition[0], newPosition[1], newPosition[2]);
        Vector3 ball = new Vector3(ballPos);

        float timeDiff = (float) (newTime - time) / 1000f;

        if (!Recorder.hasStarted() && ballPos.isZero()) {
            ballPos.set(newPosition[0], newPosition[1], newPosition[2]);
        } else {
            // Speed per second
            float maxRecordingSpeedInRadiants = sensorWidthInMeters * movementPerFrameInPixels / ((float) (captureWidth) * exposureDuration);

            float maxRecordingSpeed = ballSphereRadius * maxRecordingSpeedInRadiants;

            float maxSpeed = maxRecordingSpeed * timeDiff;

            float accelleration = (!Recorder.isIdle() ? (maxRecordingSpeed / 10) : (maxRecordingSpeed)) / 30;

            Vector3 newHeading = Vector3.subtract(target, ballPos);

            float dist = Vector3.length(newHeading);
            float curSpeed = Vector3.length(ballSpeed);

            // We have to actually break.
            if (Math.sqrt(dist / accelleration) >= dist / curSpeed)
                curSpeed -= accelleration;
            else curSpeed += accelleration;

            // Limit speed
            if (curSpeed < 0) curSpeed = 0;

            if (curSpeed > maxSpeed)
                curSpeed = Math.signum(curSpeed) * maxSpeed;

            if (curSpeed > dist)
                curSpeed = dist;

            if (newHeading.length() != 0)
                ballSpeed = Vector3.multiply(Vector3.normalize(newHeading), curSpeed);
            else
                ballSpeed = newHeading;

            ballPos = Vector3.add(ball, ballSpeed);
        }

        time = newTime;
        return ballPos;

    }

    private float[] moveViaMotor() {
        float[] coreMotionMatrix;
        long mediaTime = System.currentTimeMillis();
        long timeDiff = mediaTime - lastElapsedTime;
        double elapsedSec = timeDiff / 1000.0;

        int sessionRotateCount = 7200;
        int sessionBuffCount = 200;
        int PPS = 300;
        int rotatePlusBuff = sessionRotateCount + sessionBuffCount;

        double degreeIncrMicro = (0.036 / (rotatePlusBuff / PPS));
        double degreeIncr = (elapsedSec / 0.0001) * degreeIncrMicro;

        lastElapsedTime = System.currentTimeMillis();
        if (isRecording) {
            currentDegree += degreeIncr;
        }

//                float[] rotation = {(float) -Math.toDegrees(currentDegree), 0, 1, 0};
//                float[] curRotation = Maths.buildRotationMatrix(baseCorrection, rotation);

        if (((RecorderActivity) getActivity()).dataHasCome) {
            isRecording = true;
        }
        Log.d("MARK", "degreeIncr = " + degreeIncr);

        currentPhi = (float) Math.toRadians(currentDegree);

        if (currentPhi > (2 * Math.PI) - 0.001) {
            isRecording = false;
            if (currentTheta == 0) {
                currentTheta = (-DeviceName.getCurrentThetaValue());
            } else if (currentTheta < 0) {
                currentTheta = DeviceName.getCurrentThetaValue();
            } else if (currentTheta > 0) {
                currentTheta = 0;
            }
            currentDegree = 0;
        }
        Log.d("MARK", "currentDegree = " + currentDegree);

        customRotationMatrixSource = new CustomRotationMatrixSource(currentTheta, currentPhi);
        coreMotionMatrix = customRotationMatrixSource.getRotationMatrix();

        return coreMotionMatrix;
    }
}