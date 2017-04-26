package com.iam360.dscvr.bluetooth;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.ParcelUuid;
import android.util.Log;

import com.iam360.dscvr.record.Recorder;
import com.iam360.dscvr.sensors.RotationMatrixProvider;
import com.iam360.dscvr.util.Maths;


import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import timber.log.Timber;

/**
 * Class to control Motor.
 * Can send MotorCommands to the motor
 * Created by Charlotte on 21.11.2016.
 */
public class BluetoothEngineControlService {

    public static final ParcelUuid SERVICE_UUID = ParcelUuid.fromString("69400001-B5A3-F393-E0A9-E50E24DCCA99");
    public static final UUID CHARACTERISTIC_UUID = UUID.fromString("69400002-B5A3-F393-E0A9-E50E24DCCA99");
    private static final String TAG = "MotorControl";
    private static final double STEPS_FOR_ONE_ROUND_X = 5111;
    private static final double STEPS_FOR_ONE_ROUND_Y = 15000;
    private static final int STEP_FOR_360 = (int) ((STEPS_FOR_ONE_ROUND_X / 360f) *380f);


    private BluetoothGattService bluetoothService;
    private BluetoothGatt gatt;
    private EngineCommandPoint movedSteps = new EngineCommandPoint(0, 0);
    private BluetoothEngineMatrixProvider providerInstanz;
    private double yTeta = 0;
    private long start360;
    public static final int SPEED = 500;
    private static final double SPEED_IN_RAD = (((float)SPEED)/ ((float)STEPS_FOR_ONE_ROUND_X)) * 2 * Math.PI;

    public BluetoothEngineControlService() {
    }

    public boolean setBluetoothGatt(BluetoothGatt gatt) {
        if (gatt == null && this.hasBluetoothService()) {
            stop();
        }

        if (gatt == null) {
            bluetoothService = null;
            this.gatt = null;
            return false;
        }

        // set: bluetoothService
        List<BluetoothGattService> services = gatt.getServices();
        Log.i("onServicesDiscovered: ", services.toString());
        BluetoothGattService correctService = null;
        for (BluetoothGattService service : services) {
            if (service.getUuid().equals(SERVICE_UUID.getUuid())) {
                correctService = service;
                break;
            }
        }
        if (correctService == null) {
            return false;
        } else {
            this.gatt = gatt;
            this.bluetoothService = correctService;
            return true;
        }
    }

    public boolean hasBluetoothService() {
        return bluetoothService != null && gatt != null;
    }

    private void sendCommand(EngineCommand command) {
        BluetoothGattCharacteristic characteristic = bluetoothService.getCharacteristic(CHARACTERISTIC_UUID);
        assert (((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) |
                (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) > 0);
        characteristic.setValue(command.getValue());
        gatt.writeCharacteristic(characteristic);
    }


    public void moveXY(EngineCommandPoint steps, EngineCommandPoint speed) {
        EngineCommand command = EngineCommand.moveXY(steps, speed);
        movedSteps.add(steps);
        sendCommand(command);

    }



    public void goCompleteAround(float speed) {

        moveXY(new EngineCommandPoint((float) STEP_FOR_360 * (-1), 0f), new EngineCommandPoint(speed, speed));
        start360 = System.currentTimeMillis();
    }

    public  void goToDeg(float deg) {
        float ySteps = (float) ((STEPS_FOR_ONE_ROUND_Y / 360) * deg);
        yTeta = deg + Math.toDegrees(Math.PI);
        moveXY(new EngineCommandPoint(0, ySteps), new EngineCommandPoint(0, SPEED));
    }

    private void stop() {
        sendCommand(EngineCommand.stop());
    }

    public BluetoothEngineMatrixProvider getBluetoothEngineMatrixProviderForGatt() {
        if (gatt != null) {
            if (providerInstanz == null) {
                providerInstanz = new BluetoothEngineMatrixProvider();
            }
            return providerInstanz;
        }
        return null;
    }

    public void move360withDeg(Float statingPoint) {
        goToDeg(statingPoint);
        //do we need to wait?
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                goCompleteAround(BluetoothEngineControlService.SPEED);
                Recorder.setIdle(false);
            }
        }, 300);
    }

    public class BluetoothEngineMatrixProvider extends RotationMatrixProvider {
        @Override
        public void getRotationMatrix(float[] target) {
            double xPhi =  (SPEED_IN_RAD * (System.currentTimeMillis() - start360)) / 1000f;
            Timber.d("xPhi: " + xPhi);
            float[] rotationX = {(float) yTeta+180, 1, 0, 0};
            float[] rotationY = {(float) -Math.toDegrees(xPhi), 0, 1, 0};
            float[] result = Maths.buildRotationMatrix(rotationY, rotationX);
            System.arraycopy(result, 0, target, 0, 16);
        }
    }
}