package com.iam360.dscvr.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by Mariel on 4/11/2016.
 */
public class Cache {

    public static final String CACHE_APPLICATION = "shared_prefs";

    private static Cache instance;
    private SharedPreferences openCache;
    private final SharedPreferences app;
    private final SharedPreferences user;
    private final Context context;

    public static boolean cacheInitialized = false;
    public static final String GCM_TOKEN = "GCM_TOKEN";
    public static final String USER_TOKEN = "USER_TOKEN";
    public static final String USER_ID = "USER_ID";
    public static final String USER_DISPLAY_NAME = "USER_DISPLAY_NAME";
    public static final String USER_NAME = "USER_NAME";
    public static final String USER_EMAIL = "USER_EMAIL";
    public static final String USER_FB_TOKEN = "USER_FB_TOKEN";
    public static final String USER_FB_ID = "USER_FB_ID";
    public static final String USER_FB_LOGGED_IN = "USER_FB_LOGGED_IN";
    public static final String USER_TWITTER_TOKEN = "USER_TWITTER_TOKEN";
    public static final String USER_TWITTER_SECRET = "USER_TWITTER_SECRET";
    public static final String USER_TWITTER_LOGGED_IN = "USER_TWITTER_LOGGED_IN";
    public static final String POST_OPTO_TO_FB = "POST_OPTO_TO_FB";
    public static final String POST_OPTO_TO_TWITTER = "POST_OPTO_TO_TWITTER";
    public static final String CAMERA_MODE = "CAMERA_MODE";
    public static final String CAMERA_CAPTURE_TYPE = "CAMERA_CAPTURE_TYPE";
    public static final String GYRO_ENABLE = "GYRO_ENABLE";
    public static final String LITTLE_PLANET_ENABLE = "LITTLE_PLANET_ENABLE";
    public static final String VR_3D_ENABLE = "VR_3D_ENABLE";
    public static final String GATE_CODE = "GATE_CODE";
    public static final String ONBOARDING_VERSION = "ONBOARDING_VERSION";
    public static final String NOTIF_COUNT = "NOTIF_COUNT";
    public static final String BLE_DEVICE_ADDRESS = "BLE_DEVICE_ADDRESS";
    public static final String BLE_ROT_COUNT = "BLE_ROT_COUNT";
    public static final String BLE_BOT_COUNT = "BLE_BOT_COUNT";
    public static final String BLE_TOP_COUNT = "BLE_TOP_COUNT";
    public static final String BLE_PPS_COUNT = "BLE_PPS_COUNT";
    public static final String BLE_BUF_COUNT = "BLE_BUF_COUNT";


    public static final String UPLOAD_ON_GOING = "UPLOAD_ON_GOING";
    public static final String BLE_DEVICE_NAME = "BLE_DEVICE_NAME";
    public static final String HORIZONTAL_VIEW_ANGLE = "BLE_DEVICE_NAME";
    public static final String MOTOR_ON = "MOTOR_ON";

    //<ID,<face,6faces(boolean)>>11
    public Map<String,Map<String,List<Boolean>>> upload = new HashMap<>();

    private Cache(Context context) {
        this.context = context;
        app = PreferenceManager.getDefaultSharedPreferences(context);
        user = context.getSharedPreferences(CACHE_APPLICATION, 0);
    }

    public static Cache getInstance(Context context, String name) {
        if (instance == null)
            instance = new Cache(context.getApplicationContext());
        cacheInitialized = true;
        instance.openCache = instance.app;
        return instance;
    }

    public static Cache open(String name) {
        if (instance == null)
            throw new IllegalStateException(
                    "Cache must be instantiated upon creation of Application.");
        if (Cache.CACHE_APPLICATION.equals(name))
            instance.openCache = instance.app;
        return instance;
    }

    public static Cache open() {
        return open(null);
    }

    public static Cache getInstance() {
        if (instance == null)
            throw new IllegalStateException(
                    "Cache must be instantiated upon creation of Application.");
        return instance;
    }

    public static Cache getInstance(Context context) {
        return getInstance(context, null);
    }

    public void save(String key, int value) {
        openCache.edit().putInt(key, value).commit();
    }

    public void save(String key, long value) {
        openCache.edit().putLong(key, value).commit();
    }

    public void save(String key, boolean value) {
        openCache.edit().putBoolean(key, value).commit();
    }

    public void save(String key, String value) {
        if (value == null)
            openCache.edit().remove(key).commit();
        else
            openCache.edit().putString(key, value).commit();
    }

    public void saveSet(String key, Set<String> value) {
        if (value == null)
            openCache.edit().remove(key).commit();
        else
            openCache.edit().putStringSet(key, value).commit();
    }

    public int getInt(String key) {
        return openCache.getInt(key, 0);
    }

    public long getLong(String key) {
        return openCache.getLong(key, 0);
    }

    public String getString(String key) {
        return openCache.getString(key, "");
    }

    public Set<String> getStringSet(String key) {
        return openCache.getStringSet(key, new HashSet<String>());
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return openCache.getBoolean(key, defaultValue);
    }

    public void clear() {
        openCache.edit().clear().commit();
    }

}
