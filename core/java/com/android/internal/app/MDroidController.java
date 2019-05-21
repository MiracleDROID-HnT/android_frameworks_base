/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.app;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings.Secure;
import android.provider.Settings.System;
import android.util.Slog;

import com.android.internal.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Controller for managing theme settings.
 */
public final class MDroidController {

    private static final String TAG = "MDroidController";
    private static final boolean DEBUG = false;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ AUTO_MODE_CUSTOM, AUTO_MODE_TWILIGHT })
    public @interface AutoMode {}

    /**
     * Auto mode value to automatically activate Dark theme at a specific start and end time.
     *
     * @see #setAutoMode(int)
     * @see #setCustomStartTime(LocalTime)
     * @see #setCustomEndTime(LocalTime)
     */
    public static final int AUTO_MODE_CUSTOM = 0;
    /**
     * Auto mode value to automatically activate Dark theme from sunset to sunrise.
     *
     * @see #setAutoMode(int)
     */
    public static final int AUTO_MODE_TWILIGHT = 1;

    private final Context mContext;
    private final int mUserId;

    private final ContentObserver mContentObserver;

    private Callback mCallback;

    public MDroidController(@NonNull Context context) {
        this(context, ActivityManager.getCurrentUser());
    }

    public MDroidController(@NonNull Context context, int userId) {
        mContext = context.getApplicationContext();
        mUserId = userId;

        mContentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);

                final String setting = uri == null ? null : uri.getLastPathSegment();
                if (setting != null) {
                    onSettingChanged(setting);
                }
            }
        };
    }

    /**
     * Returns {@code true} when Dark theme is activated (the display is tinted red).
     */
    public boolean isActivated() {
        return Secure.getIntForUser(mContext.getContentResolver(),
                Secure.TWILIGHT_STATE, 0, mUserId) == 1;
    }

    /**
     * Sets whether Dark theme should be activated. This also sets the last activated time.
     *
     * @param activated {@code true} if Dark theme should be activated
     * @return {@code true} if the activated value was set successfully
     */
    public boolean setActivated(boolean activated) {
        if (isActivated() != activated) {
            Secure.putStringForUser(mContext.getContentResolver(),
                    Secure.THEME_LAST_ACTIVATED_TIME,
                    LocalDateTime.now().toString(),
                    mUserId);
        }
        return Secure.putIntForUser(mContext.getContentResolver(),
                Secure.TWILIGHT_STATE, activated ? 1 : 0, mUserId);
    }

    /**
     * Returns the time when Dark theme activation state last changed, or {@code null} if it
     * has never been changed.
     */
    public LocalDateTime getLastActivatedTime() {
        final ContentResolver cr = mContext.getContentResolver();
        final String lastActivatedTime = Secure.getStringForUser(
                cr, Secure.THEME_LAST_ACTIVATED_TIME, mUserId);
        if (lastActivatedTime != null) {
            try {
                return LocalDateTime.parse(lastActivatedTime);
            } catch (DateTimeParseException ignored) {}
            // Uses the old epoch time.
            try {
                return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(Long.parseLong(lastActivatedTime)),
                    ZoneId.systemDefault());
            } catch (DateTimeException|NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * Returns the current auto mode value controlling when Dark theme will be automatically
     * activated. One of {@link #AUTO_MODE_CUSTOM}, or {@link #AUTO_MODE_TWILIGHT}.
     */
    public @AutoMode int getAutoMode() {
        int autoMode = Secure.getIntForUser(mContext.getContentResolver(),
                Secure.THEME_AUTO_MODE, -1, mUserId);
        if (autoMode == -1) {
            if (DEBUG) {
                Slog.d(TAG, "Using default value for setting: " + Secure.THEME_AUTO_MODE);
            }
            autoMode = mContext.getResources().getInteger(
                    R.integer.config_defaultThemeAutoMode);
        }

        if (autoMode != AUTO_MODE_CUSTOM
                && autoMode != AUTO_MODE_TWILIGHT) {
            Slog.e(TAG, "Invalid autoMode: " + autoMode);
            autoMode = AUTO_MODE_TWILIGHT;
        }

        return autoMode;
    }

    /**
     * Sets the current auto mode value controlling when Dark theme will be automatically
     * activated. One of {@link #AUTO_MODE_CUSTOM}, or {@link #AUTO_MODE_TWILIGHT}.
     *
     * @param autoMode the new auto mode to use
     * @return {@code true} if new auto mode was set successfully
     */
    public boolean setAutoMode(@AutoMode int autoMode) {
        if (autoMode != AUTO_MODE_CUSTOM
                && autoMode != AUTO_MODE_TWILIGHT) {
            throw new IllegalArgumentException("Invalid autoMode: " + autoMode);
        }

        if (getAutoMode() != autoMode) {
            Secure.putStringForUser(mContext.getContentResolver(),
                    Secure.THEME_LAST_ACTIVATED_TIME,
                    null,
                    mUserId);
        }
        return Secure.putIntForUser(mContext.getContentResolver(),
                Secure.THEME_AUTO_MODE, autoMode, mUserId);
    }

    /**
     * Returns the local time when Dark theme will be automatically activated when using
     * {@link #AUTO_MODE_CUSTOM}.
     */
    public @NonNull LocalTime getCustomStartTime() {
        int startTimeValue = Secure.getIntForUser(mContext.getContentResolver(),
                Secure.THEME_CUSTOM_START_TIME, -1, mUserId);
        if (startTimeValue == -1) {
            if (DEBUG) {
                Slog.d(TAG, "Using default value for setting: "
                        + Secure.THEME_CUSTOM_START_TIME);
            }
            startTimeValue = mContext.getResources().getInteger(
                    R.integer.config_defaultThemeCustomStartTime);
        }

        return LocalTime.ofSecondOfDay(startTimeValue / 1000);
    }

    /**
     * Sets the local time when Dark theme will be automatically activated when using
     * {@link #AUTO_MODE_CUSTOM}.
     *
     * @param startTime the local time to automatically activate Dark theme
     * @return {@code true} if the new custom start time was set successfully
     */
    public boolean setCustomStartTime(@NonNull LocalTime startTime) {
        if (startTime == null) {
            throw new IllegalArgumentException("startTime cannot be null");
        }
        return Secure.putIntForUser(mContext.getContentResolver(),
                Secure.THEME_CUSTOM_START_TIME, startTime.toSecondOfDay() * 1000, mUserId);
    }

    /**
     * Returns the local time when Dark theme will be automatically deactivated when using
     * {@link #AUTO_MODE_CUSTOM}.
     */
    public @NonNull LocalTime getCustomEndTime() {
        int endTimeValue = Secure.getIntForUser(mContext.getContentResolver(),
                Secure.THEME_CUSTOM_END_TIME, -1, mUserId);
        if (endTimeValue == -1) {
            if (DEBUG) {
                Slog.d(TAG, "Using default value for setting: "
                        + Secure.THEME_CUSTOM_END_TIME);
            }
            endTimeValue = mContext.getResources().getInteger(
                    R.integer.config_defaultThemeCustomEndTime);
        }

        return LocalTime.ofSecondOfDay(endTimeValue / 1000);
    }

    /**
     * Sets the local time when Dark theme will be automatically deactivated when using
     * {@link #AUTO_MODE_CUSTOM}.
     *
     * @param endTime the local time to automatically deactivate Dark theme
     * @return {@code true} if the new custom end time was set successfully
     */
    public boolean setCustomEndTime(@NonNull LocalTime endTime) {
        if (endTime == null) {
            throw new IllegalArgumentException("endTime cannot be null");
        }
        return Secure.putIntForUser(mContext.getContentResolver(),
                Secure.THEME_CUSTOM_END_TIME, endTime.toSecondOfDay() * 1000, mUserId);
    }

    private void onSettingChanged(@NonNull String setting) {
        if (DEBUG) {
            Slog.d(TAG, "onSettingChanged: " + setting);
        }

        if (mCallback != null) {
            switch (setting) {
                case Secure.TWILIGHT_STATE:
                    mCallback.onActivated(isActivated());
                    break;
                case Secure.THEME_AUTO_MODE:
                    mCallback.onAutoModeChanged(getAutoMode());
                    break;
                case Secure.THEME_CUSTOM_START_TIME:
                    mCallback.onCustomStartTimeChanged(getCustomStartTime());
                    break;
                case Secure.THEME_CUSTOM_END_TIME:
                    mCallback.onCustomEndTimeChanged(getCustomEndTime());
                    break;
            }
        }
    }

    /**
     * Register a callback to be invoked whenever the theme settings are changed.
     */
    public void setListener(Callback callback) {
        final Callback oldCallback = mCallback;
        if (oldCallback != callback) {
            mCallback = callback;

            if (callback == null) {
                // Stop listening for changes now that there IS NOT a listener.
                mContext.getContentResolver().unregisterContentObserver(mContentObserver);
            } else if (oldCallback == null) {
                // Start listening for changes now that there IS a listener.
                final ContentResolver cr = mContext.getContentResolver();
                cr.registerContentObserver(Secure.getUriFor(Secure.TWILIGHT_STATE),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(Secure.getUriFor(Secure.THEME_AUTO_MODE),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(Secure.getUriFor(Secure.THEME_CUSTOM_START_TIME),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(Secure.getUriFor(Secure.THEME_CUSTOM_END_TIME),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
            }
        }
    }

    /**
     * Callback invoked whenever the theme settings are changed.
     */
    public interface Callback {
        /**
         * Callback invoked when the activated state changes.
         *
         * @param activated {@code true} if Dark theme is activated
         */
        default void onActivated(boolean activated) {}
        /**
         * Callback invoked when the auto mode changes.
         *
         * @param autoMode the auto mode to use
         */
        default void onAutoModeChanged(int autoMode) {}
        /**
         * Callback invoked when the time to automatically activate Dark theme changes.
         *
         * @param startTime the local time to automatically activate Dark theme
         */
        default void onCustomStartTimeChanged(LocalTime startTime) {}
        /**
         * Callback invoked when the time to automatically deactivate Dark theme changes.
         *
         * @param endTime the local time to automatically deactivate Dark theme
         */
        default void onCustomEndTimeChanged(LocalTime endTime) {}
    }
}
