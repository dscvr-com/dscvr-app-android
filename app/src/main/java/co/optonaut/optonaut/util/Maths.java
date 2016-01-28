package co.optonaut.optonaut.util;

import android.opengl.Matrix;

/**
 * @author Nilan Marktanner
 * @date 2015-12-18
 */
// source: http://www.jimscosmos.com/code/android-open-gl-texture-mapped-spheres/
public class Maths {
    /**
     * 180 in radians.
     */
    public static final double ONE_EIGHTY_DEGREES = Math.PI;

    /**
     * 360 in radians.
     */
    public static final double THREE_SIXTY_DEGREES = ONE_EIGHTY_DEGREES * 2;

    /**
     * 120 in radians.
     */
    public static final double ONE_TWENTY_DEGREES = THREE_SIXTY_DEGREES / 3;

    /**
     * 90 degrees, North pole.
     */
    public static final double NINETY_DEGREES = Math.PI / 2;

    /**
     * Used by power.
     */
    private static final long POWER_CLAMP = 0x00000000ffffffffL;

    /**
     * Constructor, although not used at the moment.
     */
    private Maths() {
    }

    /**
     * Quick integer power function.
     *
     * @param base  number to raise.
     * @param raise to this power.
     * @return base ^ raise.
     */
    public static int power(final int base, final int raise) {
        int p = 1;
        long b = raise & POWER_CLAMP;

        // bits in b correspond to values of powerN
        // so start with p=1, and for each set bit in b, multiply corresponding
        // table entry
        long powerN = base;

        while (b != 0) {
            if ((b & 1) != 0) {
                p *= powerN;
            }
            b >>>= 1;
            powerN = powerN * powerN;
        }

        return p;
    }

    public static float[] computeTransform(float[] translationMatrix, float[] rotationMatrix, float[] scaleMatrix) {
        // R*S
        float[] rsMatrix = new float[16];
        Matrix.multiplyMM(rsMatrix, 0, rotationMatrix, 0, scaleMatrix, 0);

        // T*R*S
        float[] trsMatrix = new float[16];
        Matrix.multiplyMM(trsMatrix, 0, translationMatrix, 0, rsMatrix, 0);

        return trsMatrix;
    }

    public static float[] buildTranslationMatrix(float[] translation) {
        float[] translationMatrix = new float[16];
        Matrix.setIdentityM(translationMatrix, 0);
        Matrix.translateM(translationMatrix, 0, translation[0], translation[1], translation[2]);
        return translationMatrix;
    }

    public static float[] buildRotationMatrix(float[] rotation) {
        float[] rotationMatrix = new float[16];
        Matrix.setRotateM(rotationMatrix, 0, rotation[0], rotation[1], rotation[2], rotation[3]);
        return rotationMatrix;
    }

    public static float[] buildRotationMatrix(float[] rotation_l, float[] rotation_r) {
        float[] L = buildRotationMatrix(rotation_l);
        float[] R = buildRotationMatrix(rotation_r);
        float[] rotationMatrix = new float[16];
        Matrix.multiplyMM(rotationMatrix, 0, L, 0, R, 0);
        return rotationMatrix;
    }

    public static float[] buildScaleMatrix(float scale) {
        float[] scaleMatrix = new float[16];
        Matrix.setIdentityM(scaleMatrix, 0);
        Matrix.scaleM(scaleMatrix, 0, scale, scale, scale);
        return scaleMatrix;
    }
}