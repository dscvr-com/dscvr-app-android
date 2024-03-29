package com.iam360.dscvr;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.support.multidex.MultiDex;
import android.util.Log;

import com.crashlytics.android.Crashlytics;
import com.iam360.dscvr.bluetooth.BluetoothConnector;
import com.iam360.dscvr.sensors.DefaultListeners;
import com.iam360.dscvr.sensors.RotationMatrixProvider;
import com.iam360.dscvr.util.Cache;
import com.path.android.jobqueue.JobManager;
import com.path.android.jobqueue.config.Configuration;
import com.path.android.jobqueue.log.CustomLogger;

import io.fabric.sdk.android.Fabric;
import timber.log.Timber;

/**
 * @author Nilan Marktanner
 * @date 2016-01-30
 */
public class DscvrApp extends Application {
    private static DscvrApp instance;
    private JobManager jobManager;
    private BluetoothConnector connector = null;



    public DscvrApp() {
        instance = this;
    }

    public BluetoothConnector getConnector(){
         if(connector == null){
            connector = new BluetoothConnector(BluetoothAdapter.getDefaultAdapter(), this.getApplicationContext());
        }
        return connector;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
            Fabric.with(this, new Crashlytics());
        } else {
            Fabric.with(this, new Crashlytics());
            Timber.plant(new CrashReportingTree());
        }

        configureJobManager();

    }

    public JobManager getJobManager() {
        return jobManager;
    }

    public static DscvrApp getInstance() {
        return instance;
    }

    private void configureJobManager() {
        Configuration configuration = new Configuration.Builder(this)
                .customLogger(new CustomLogger() {
                    private static final String TAG = "JOBS";

                    @Override
                    public boolean isDebugEnabled() {
                        return true;
                    }

                    @Override
                    public void d(String text, Object... args) {
                        Timber.d(String.format(text, args));
                    }

                    @Override
                    public void e(Throwable t, String text, Object... args) {
                        Timber.e(String.format(text, args), t);
                    }

                    @Override
                    public void e(String text, Object... args) {
                        Timber.e(String.format(text, args));
                    }
                })
                .minConsumerCount(1)//always keep at least one consumer alive
                .maxConsumerCount(3)//up to 3 consumers at a time
                .loadFactor(3)//3 jobs per consumer
                .consumerKeepAlive(120)//wait 2 minute
                .build();
        jobManager = new JobManager(this, configuration);
    }

    public RotationMatrixProvider getMatrixProvider(){
        if(Cache.getInstance().getBoolean(Cache.MOTOR_ON)) {
            return  connector.getBluetoothService().getBluetoothEngineMatrixProviderForGatt();
        } else {
            return DefaultListeners.getInstance();
        }
    }

    public boolean hasConnection() {
        return connector != null && connector.isConnected();
    }

    /**
     * A tree which logs important information for crash reporting.
     */
    private static class CrashReportingTree extends Timber.Tree {
        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            if (priority == Log.VERBOSE || priority == Log.DEBUG) {
                return;
            }

            // FakeCrashLibrary.log(priority, tag, message);

            if (t != null) {
                if (priority == Log.ERROR) {
                    // FakeCrashLibrary.logError(t);
                } else if (priority == Log.WARN) {
                    // FakeCrashLibrary.logWarning(t);
                }
            }
        }
    }
}