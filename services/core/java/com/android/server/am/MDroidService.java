/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.am;

import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.Slog;
import com.android.server.SystemService;

import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

public class MDroidService extends SystemService {

    private static final String TAG = "MDroidService";

    private static final boolean DEBUG = false;

    private final Context mContext;
    private Constants mConstants;

    final MDroidHandler mHandler;
    final MDroidHandlerThread mHandlerThread;

    private boolean mHallSensorServiceEnabled = false;
    private int mHallSensorType;
    private int mLidState;
    private boolean mTwilightState;

    private HallSensorService mHallSensorService;
    private BinderService mBinderService;

    private PowerManager mPowerManager;
    private SensorManager mSensorManager;
    private TwilightManager mTwilightManager;

    public MDroidService(Context context) {
        super(context);
        mContext = context;
        if (DEBUG) {
            Slog.i(TAG, "MDroidService()");
        }

        mHandlerThread = new MDroidHandlerThread();
        mHandlerThread.start();
        mHandler = new MDroidHandler(mHandlerThread.getLooper());

        mHallSensorType = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_hallSensorType);
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.i(TAG, "onStart()");
        }

        mBinderService = new BinderService();
        publishBinderService("mdroidservice", mBinderService);
        publishLocalService(MDroidService.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (DEBUG) {
            Slog.i(TAG, "onBootPhase(" + phase + ")");
        }

        if (phase == PHASE_BOOT_COMPLETED) {

            synchronized(this) {
                mConstants = new Constants(mHandler, getContext().getContentResolver());

                mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
                if (DEBUG) {
                    Slog.i(TAG, "SensorManager initialized");
                }
    
                mPowerManager = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);

                mHallSensorService = new HallSensorService();
                mHallSensorService.Initialize();

                mConstants.updateConstantsLocked();

                mTwilightManager = getLocalService(TwilightManager.class);
                mTwilightManager.registerListener(mTwilightListener, mHandler);
            }
        }
    }

    private final TwilightListener mTwilightListener = new TwilightListener() {
        @Override
        public void onTwilightStateChanged(TwilightState state) {
            if (DEBUG) {
                Slog.i(TAG, "onTwilightStateChanged");
            }
            synchronized (MDroidService.this) {
                updateTwilightStateLocked();
            }
        }
    };

    private void updateTwilightStateLocked() {
        if (mTwilightManager != null) {
            TwilightState state = mTwilightManager.getLastTwilightState();
            if (state != null) {
                mTwilightState = state.isNight();
            }
        }
        if (DEBUG) {
            Slog.i(TAG, "updateTwilightStateLocked: mTwilightState = " + mTwilightState);
        }
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.TWILIGHT_STATE, mTwilightState ? 1 : 0,
                UserHandle.USER_CURRENT);
    }

    final class MDroidHandler extends Handler {
        MDroidHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            // Nothing here
        }
    }

    private final class Constants extends ContentObserver {

        private final ContentResolver mResolver;

        public Constants(Handler handler, ContentResolver resolver) {
            super(handler);
            mResolver = resolver;

            try {
            resolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.HALL_SENSOR_ENABLED),
                    false, this);
            } catch (Exception e) {
                // Nothing here
            }

            updateConstants();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateConstants();
        }

        private void updateConstants() {
            synchronized (MDroidService.this) {
                updateConstantsLocked();
            }
        }

        public void updateConstantsLocked() {
            try {
                mHallSensorServiceEnabled = Settings.Global.getInt(mResolver, 
                        Settings.Global.HALL_SENSOR_ENABLED) == 1;

                if (mHallSensorService != null) {
                    mHallSensorService.setHallSensorEnabled(mHallSensorServiceEnabled);
                }
            } catch (Exception e) {
                // Failed to parse the settings string, log this and move on
                // with defaults.
                Slog.e(TAG, "Bad MDroidService settings", e);
            }

            if (DEBUG) {
                Slog.d(TAG, "updateConstantsLocked: mHallSensorServiceEnabled = " + mHallSensorServiceEnabled);
            }
        }
    }

    void goToSleep() {
        if (mPowerManager != null) {
            mPowerManager.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_LID_SWITCH,
                    PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
        }
    }

    void wakeUp() {
        if (mPowerManager != null) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), "android.policy:LID");
        }
    }

    final class HallSensorService {
        // The hall sensor, or null if not available or needed.
        private Sensor mHallSensor;
        private boolean mHallSensorEnabled;

        private final SensorEventListener mHallSensorListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (mHallSensorEnabled) {
                    final long time = SystemClock.uptimeMillis();
                    final float distance = event.values[0];
                    boolean positive = distance > 0.0f;
                    handleHallSensorEvent(time, positive);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Not used.
            }
        };

        void Initialize() {
            mHallSensor = mSensorManager.getDefaultSensor(mHallSensorType, true);
            if (DEBUG) {
                Slog.i(TAG,"Hall Initialize: sensor = " + mHallSensor);
            }
        }

        void setHallSensorEnabled(boolean enable) {
            if (DEBUG) {
                Slog.i(TAG, "setHallSensorEnabled: enable = " + enable);
            }
            if (mHallSensor == null) return; 
            if (enable) {
                if (!mHallSensorEnabled) {
                    mHallSensorEnabled = true;
                    mSensorManager.registerListener(mHallSensorListener, mHallSensor,
                        SensorManager.SENSOR_DELAY_FASTEST, 1000000);
                }
            } else {
                if (mHallSensorEnabled) {
                    mHallSensorEnabled = false;
                    mSensorManager.unregisterListener(mHallSensorListener);
                }
            }
        }

        private void handleHallSensorEvent(long time, boolean positive) {
            if (mHallSensorEnabled) {
                if (DEBUG) {
                    Slog.i(TAG, "handleHallSensorEvent: value=" + positive + ", time = " + time);
                }
                handleWakeup(!positive);
            }
        }

        void handleWakeup(boolean wakeup) {
            if (DEBUG) {
                Slog.i(TAG, "handleHallSensorWakeup()");
            }
            if (wakeup) {
                wakeUp();
                mLidState = 1;
            } else {
                goToSleep();
                mLidState = 0;
            }
            sendLidChangeBroadcast();
        }
    }

    private void sendLidChangeBroadcast() {
        if (DEBUG) {
            Slog.i(TAG, "Sending cover change broadcast, mLidState = " + mLidState);
        }
        Intent intent = new Intent(com.android.internal.util.mdroid.content.Intent.ACTION_LID_STATE_CHANGED);
        intent.putExtra(com.android.internal.util.mdroid.content.Intent.EXTRA_LID_STATE, mLidState);
        intent.setFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
    }

    private class MDroidHandlerThread extends HandlerThread {

        Handler handler;

        public MDroidHandlerThread() {
            super("mdroid.handler", android.os.Process.THREAD_PRIORITY_FOREGROUND);
        }
    }

    private final class BinderService extends Binder {
        // Nothing here
    }
}
