package com.iam360.dscvr.record;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import com.iam360.dscvr.DscvrApp;
import com.iam360.dscvr.opengl.Sphere;
import com.iam360.dscvr.sensors.CoreMotionListener;
import com.iam360.dscvr.sensors.DefaultListeners;

import timber.log.Timber;

/**
 * @author Nilan Marktanner
 * @date 2016-02-10
 */
public class RecorderOverlayRenderer implements GLSurfaceView.Renderer {
    private final DscvrApp appContext;
    private List<LineNode> lineNodes;

    // Map globalIds of the edge's selection points : LineNode
    private Map<String, LineNode> edgeLineNodeGlobalIdMap = new HashMap<>();

    private Sphere sphere;

    private static final float FIELD_OF_VIEW_Y = 95.0f;
    private static final float Z_NEAR = 0.1f;
    private static final float Z_FAR = 120.0f;

    private final float[] mvpMatrix = new float[16];
    private final float[] projection = new float[16];
    private final float[] camera = new float[16];
    private float[] rotationMatrix = new float[16];
    private float[] lastCmMatrix = new float[16];

    private boolean addedNewLineNode;

    private boolean shouldRender;


    public RecorderOverlayRenderer(DscvrApp appContext) {
        this.appContext = appContext;
        lineNodes = new LinkedList<>();
        addedNewLineNode = false;
        shouldRender = false;
        sphere = new Sphere(5, 5);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Timber.v("onSurfaceCreated");

        sphere.initializeProgram();
        setSpherePosition(0.9f, 0, 0);

        // Set the background frame color as transparent!
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClearDepthf(1.0f);

        // Set the camera position
        Matrix.setLookAtM(camera, 0,
                0.0f, 0.0f, -0.01f, // eye
                0.0f, 0.0f, 0.0f, // center
                0.0f, 1.0f, 0.0f); // up

    }

    public void setSpherePosition(float x, float y, float z) {
        sphere.setTransform(new float[]{
                0.01f, 0, 0, 0,
                0, 0.01f, 0, 0,
                0, 0, 0.01f, 0,
                x, y, z, 1
        });
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Timber.v("onSurfaceChanged");
        GLES20.glViewport(0, 0, width, height);
        float ratio = (float) width / height;

        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW_Y, ratio, Z_NEAR, Z_FAR);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        synchronized (lineNodes) {
            if (addedNewLineNode) {
                for (LineNode node : lineNodes) {
                    if (!node.isProgramInitialized()) {
                        node.initializeProgram();
                    }
                }
            }
        }

        if (!shouldRender) {
            return;
        }

        float[] view = new float[16];
        float[] cmDiff = new float[16];
        float[] smoothRotation = new float[16];

        //FIXME
        //inverse
        Matrix.multiplyMM(cmDiff, 0, appContext.getMatrixProvider().getRotationMatrixInverse(), 0, lastCmMatrix, 0);

        Matrix.multiplyMM(smoothRotation, 0, cmDiff, 0, rotationMatrix, 0);

        //Matrix.multiplyMM(view, 0, camera, 0, CoreMotionListener.getInstance().getRotationMatrix(), 0);
        //Matrix.multiplyMM(view, 0, camera, 0, rotationMatrix, 0);
        Matrix.multiplyMM(view, 0, camera, 0, smoothRotation, 0);
        /*Matrix.multiplyMM(view, 0, camera, 0, new float[] {
                0, 1, 0, 0,
                1, 0, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        }, 0);*/

        // Calculate the projection and view transformation
        Matrix.multiplyMM(mvpMatrix, 0, projection, 0, view, 0);


        // Set the background frame color as transparent!
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClearDepthf(1.0f);

        // Draw lines
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        synchronized (lineNodes) {
            for (int i = 0; i < lineNodes.size(); i++) {
                lineNodes.get(i).draw(mvpMatrix);
            }
        }

        sphere.draw(mvpMatrix);
    }

    public float[] getPointOnScreen(float[] point) {
        float[] res = new float[4];
        Matrix.multiplyMV(res, 0, mvpMatrix, 0, point, 0);
        return res;
    }

    public void addChildNode(LineNode edgeNode) {
        synchronized (lineNodes) {
            addedNewLineNode = true;
            lineNodes.add(edgeNode);
        }
    }

    public void colorChildNode(LineNode lineNode) {
        synchronized (lineNodes) {
            int index = lineNodes.indexOf(lineNode);
            if (index >= 0) lineNodes.get(index).isRecordedEdge(true);
        }
    }

    public void setRotationMatrix(float[] rotationMatrix) {
        //System.arraycopy(rotationMatrix, 0, this.rotationMatrix, 0, 16);
        Matrix.transposeM(this.rotationMatrix, 0, rotationMatrix, 0);
        appContext.getMatrixProvider().getRotationMatrix(this.lastCmMatrix);
        //Timber.w("Set rotation matrix: " + GeneralUtils.mToString(this.rotationMatrix));
    }

    public void startRendering() {
        shouldRender = true;
    }

    public void stopRendering() {
        shouldRender = false;
    }
}
