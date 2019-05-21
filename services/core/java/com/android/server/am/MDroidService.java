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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
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

import com.android.internal.app.MDroidController;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.TimeZone;

public class MDroidService extends SystemService
        implements MDroidController.Callback {

    private static final String TAG = "MDroidService";

    private static final boolean DEBUG = false;

    private final Context mContext;
    private Constants mConstants;

    final MDroidHandler mHandler;
    final MDroidHandlerThread mHandlerThread;

    private int mCurrentUser = UserHandle.USER_NULL;
    private ContentObserver mUserSetupObserver;
    private boolean mBootCompleted;

    private MDroidController mController;
    private Boolean mIsActivated;
    private AutoMode mAutoMode;

    private boolean mHallSensorServiceEnabled = false;
    private int mHallSensorType;
    private int mLidState;
    private boolean mTwilightState;

    private HallSensorService mHallSensorService;
    private BinderService mBinderService;

    private PowerManager mPowerManager;
    private SensorManager mSensorManager;

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

        if (phase >= PHASE_BOOT_COMPLETED) {

            synchronized(this) {
                mBootCompleted = true;

                mConstants = new Constants(mHandler, getContext().getContentResolver());

                mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
                if (DEBUG) {
                    Slog.i(TAG, "SensorManager initialized");
                }
    
                mPowerManager = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);

                mHallSensorService = new HallSensorService();
                mHallSensorService.Initialize();

                mConstants.updateConstantsLocked();

                // Register listeners now that boot is complete.
                if (mCurrentUser != UserHandle.USER_NULL && mUserSetupObserver == null) {
                    setUp();
                }
            }
        }
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

    @Override
    public void onStartUser(int userHandle) {
        super.onStartUser(userHandle);

        if (mCurrentUser == UserHandle.USER_NULL) {
            onUserChanged(userHandle);
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);

        onUserChanged(userHandle);
    }

    @Override
    public void onStopUser(int userHandle) {
        super.onStopUser(userHandle);

        if (mCurrentUser == userHandle) {
            onUserChanged(UserHandle.USER_NULL);
        }
    }

    private void onUserChanged(int userHandle) {
        final ContentResolver cr = getContext().getContentResolver();

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (mUserSetupObserver != null) {
                cr.unregisterContentObserver(mUserSetupObserver);
                mUserSetupObserver = null;
            } else if (mBootCompleted) {
                tearDown();
            }
        }

        mCurrentUser = userHandle;

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (!isUserSetupCompleted(cr, mCurrentUser)) {
                mUserSetupObserver = new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        if (isUserSetupCompleted(cr, mCurrentUser)) {
                            cr.unregisterContentObserver(this);
                            mUserSetupObserver = null;

                            if (mBootCompleted) {
                                setUp();
                            }
                        }
                    }
                };
                cr.registerContentObserver(Secure.getUriFor(Secure.USER_SETUP_COMPLETE),
                        false /* notifyForDescendents */, mUserSetupObserver, mCurrentUser);
            } else if (mBootCompleted) {
                setUp();
            }
        }
    }

    private static boolean isUserSetupCompleted(ContentResolver cr, int userHandle) {
        return Secure.getIntForUser(cr, Secure.USER_SETUP_COMPLETE, 0, userHandle) == 1;
    }

    private void setUp() {
        Slog.d(TAG, "setUp: currentUser=" + mCurrentUser);

        // Create a new controller for the current user and start listening for changes.
        mController = new MDroidController(getContext(), mCurrentUser);
        mController.setListener(this);

        // Initialize the current auto mode.
        onAutoModeChanged(mController.getAutoMode());

        // Force the initialization current activated state.
        if (mIsActivated == null) {
            onActivated(mController.isActivated());
        }
    }

    private void tearDown() {
        Slog.d(TAG, "tearDown: currentUser=" + mCurrentUser);

        if (mController != null) {
            mController.setListener(null);
            mController = null;
        }

        if (mAutoMode != null) {
            mAutoMode.onStop();
            mAutoMode = null;
        }

        mIsActivated = null;
    }

    @Override
    public void onActivated(boolean activated) {
        if (mIsActivated == null || mIsActivated != activated) {
            Slog.i(TAG, activated ? "Turning on Dark theme" : "Turning on Light theme");

            mIsActivated = activated;

            if (mAutoMode != null) {
                mAutoMode.onActivated(activated);
            }
        }
    }

    @Override
    public void onAutoModeChanged(int autoMode) {
        Slog.d(TAG, "onAutoModeChanged: autoMode=" + autoMode);

        if (mAutoMode != null) {
            mAutoMode.onStop();
            mAutoMode = null;
        }

        if (autoMode == MDroidController.AUTO_MODE_CUSTOM) {
            mAutoMode = new CustomAutoMode();
        } else if (autoMode == MDroidController.AUTO_MODE_TWILIGHT) {
            mAutoMode = new TwilightAutoMode();
        }

        if (mAutoMode != null) {
            mAutoMode.onStart();
        }
    }

    @Override
    public void onCustomStartTimeChanged(LocalTime startTime) {
        Slog.d(TAG, "onCustomStartTimeChanged: startTime=" + startTime);

        if (mAutoMode != null) {
            mAutoMode.onCustomStartTimeChanged(startTime);
        }
    }

    @Override
    public void onCustomEndTimeChanged(LocalTime endTime) {
        Slog.d(TAG, "onCustomEndTimeChanged: endTime=" + endTime);

        if (mAutoMode != null) {
            mAutoMode.onCustomEndTimeChanged(endTime);
        }
    }

    /**
     * Returns the first date time corresponding to the local time that occurs before the
     * provided date time.
     *
     * @param compareTime the LocalDateTime to compare against
     * @return the prior LocalDateTime corresponding to this local time
     */
    public static LocalDateTime getDateTimeBefore(LocalTime localTime, LocalDateTime compareTime) {
        final LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(),
                compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());

        // Check if the local time has passed, if so return the same time yesterday.
        return ldt.isAfter(compareTime) ? ldt.minusDays(1) : ldt;
    }

    /**
     * Returns the first date time corresponding to this local time that occurs after the
     * provided date time.
     *
     * @param compareTime the LocalDateTime to compare against
     * @return the next LocalDateTime corresponding to this local time
     */
    public static LocalDateTime getDateTimeAfter(LocalTime localTime, LocalDateTime compareTime) {
        final LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(),
                compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());

        // Check if the local time has passed, if so return the same time tomorrow.
        return ldt.isBefore(compareTime) ? ldt.plusDays(1) : ldt;
    }

    private abstract class AutoMode implements MDroidController.Callback {
        public abstract void onStart();

        public abstract void onStop();
    }

    private class CustomAutoMode extends AutoMode implements AlarmManager.OnAlarmListener {

        private final AlarmManager mAlarmManager;
        private final BroadcastReceiver mTimeChangedReceiver;

        private LocalTime mStartTime;
        private LocalTime mEndTime;

        private LocalDateTime mLastActivatedTime;

        CustomAutoMode() {
            mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            mTimeChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateActivated();
                }
            };
        }

        private void updateActivated() {
            final LocalDateTime now = LocalDateTime.now();
            final LocalDateTime start = getDateTimeBefore(mStartTime, now);
            final LocalDateTime end = getDateTimeAfter(mEndTime, start);
            boolean activate = now.isBefore(end);

            if (mLastActivatedTime != null) {
                // Maintain the existing activated state if within the current period.
                if (mLastActivatedTime.isBefore(now) && mLastActivatedTime.isAfter(start)
                        && (mLastActivatedTime.isAfter(end) || now.isBefore(end))) {
                    activate = mController.isActivated();
                }
            }

            if (mIsActivated == null || mIsActivated != activate) {
                mController.setActivated(activate);
            }

            updateNextAlarm(mIsActivated, now);
        }

        private void updateNextAlarm(@Nullable Boolean activated, @NonNull LocalDateTime now) {
            if (activated != null) {
                final LocalDateTime next = activated ? getDateTimeAfter(mEndTime, now)
                        : getDateTimeAfter(mStartTime, now);
                final long millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                mAlarmManager.setExact(AlarmManager.RTC, millis, TAG, this, null);
            }
        }

        @Override
        public void onStart() {
            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(mTimeChangedReceiver, intentFilter);

            mStartTime = mController.getCustomStartTime();
            mEndTime = mController.getCustomEndTime();

            mLastActivatedTime = mController.getLastActivatedTime();

            // Force an update to initialize state.
            updateActivated();
        }

        @Override
        public void onStop() {
            getContext().unregisterReceiver(mTimeChangedReceiver);

            mAlarmManager.cancel(this);
            mLastActivatedTime = null;
        }

        @Override
        public void onActivated(boolean activated) {
            mLastActivatedTime = mController.getLastActivatedTime();
            updateNextAlarm(activated, LocalDateTime.now());
        }

        @Override
        public void onCustomStartTimeChanged(LocalTime startTime) {
            mStartTime = startTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onCustomEndTimeChanged(LocalTime endTime) {
            mEndTime = endTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onAlarm() {
            Slog.d(TAG, "onAlarm");
            updateActivated();
        }
    }

    private class TwilightAutoMode extends AutoMode implements TwilightListener {

        private final TwilightManager mTwilightManager;

        TwilightAutoMode() {
            mTwilightManager = getLocalService(TwilightManager.class);
        }

        private void updateActivated(TwilightState state) {
            if (state == null) {
                // If there isn't a valid TwilightState then just keep the current activated
                // state.
                return;
            }

            boolean activate = state.isNight();
            final LocalDateTime lastActivatedTime = mController.getLastActivatedTime();
            if (lastActivatedTime != null) {
                final LocalDateTime now = LocalDateTime.now();
                final LocalDateTime sunrise = state.sunrise();
                final LocalDateTime sunset = state.sunset();
                // Maintain the existing activated state if within the current period.
                if (lastActivatedTime.isBefore(now) && (lastActivatedTime.isBefore(sunrise)
                        ^ lastActivatedTime.isBefore(sunset))) {
                    activate = mController.isActivated();
                }
            }

            if (mIsActivated == null || mIsActivated != activate) {
                mController.setActivated(activate);
            }
        }

        @Override
        public void onStart() {
            mTwilightManager.registerListener(this, mHandler);

            // Force an update to initialize state.
            updateActivated(mTwilightManager.getLastTwilightState());
        }

        @Override
        public void onStop() {
            mTwilightManager.unregisterListener(this);
        }

        @Override
        public void onActivated(boolean activated) {
        }

        @Override
        public void onTwilightStateChanged(@Nullable TwilightState state) {
            Slog.d(TAG, "onTwilightStateChanged: isNight="
                    + (state == null ? null : state.isNight()));
            updateActivated(state);
        }
    }
}
