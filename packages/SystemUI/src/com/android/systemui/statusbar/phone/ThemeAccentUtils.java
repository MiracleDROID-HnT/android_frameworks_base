/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ThemeAccentUtils {
    public static final String TAG = "ThemeAccentUtils";

    // Notification themes
    private static final String NOTIFICATION_DARK_THEME = "mx.mdroid.system.notification.dark";
    private static final String NOTIFICATION_BLACK_THEME = "mx.mdroid.system.notification.black";

    private static final String[] QS_TILE_THEMES = {
        "default", // 0
        "mx.mdroid.systemui.qstile.circle", // 1
        "mx.mdroid.systemui.qstile.circletrim", // 2
        "mx.mdroid.systemui.qstile.dualtonecircletrim", // 3
        "mx.mdroid.systemui.qstile.squircle", // 4
        "mx.mdroid.systemui.qstile.squircletrim", // 5
        "mx.mdroid.systemui.qstile.teardrop", // 6
    };

    private static final String[] getClocks(Context ctx) {
        final String list = ctx.getResources().getString(R.string.custom_clock_styles);
        return list.split(",");
    }

    // Check for the dark system theme
    public static boolean isUsingDarkTheme(IOverlayManager om, int userId) {
        OverlayInfo themeInfo = null;
        try {
            themeInfo = om.getOverlayInfo("mx.mdroid.system.theme.dark",
                    userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return (themeInfo != null && themeInfo.isEnabled());
    }

    // Check for the black system theme
    public static boolean isUsingBlackTheme(IOverlayManager om, int userId) {
        OverlayInfo themeInfo = null;
        try {
            themeInfo = om.getOverlayInfo("mx.mdroid.system.theme.black",
                    userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return (themeInfo != null && themeInfo.isEnabled());
    }

    public static void handleThemeStates(IOverlayManager om, int userId, boolean useBlackTheme, boolean useDarkTheme, boolean themeNeedsRefresh) {
        if (!useDarkTheme || !useBlackTheme) {
            setLightThemeState(om, userId, !useDarkTheme || !useBlackTheme);
        }
        if (themeNeedsRefresh || ((isUsingBlackTheme(om, userId) != useBlackTheme) ||
                (isUsingDarkTheme(om, userId) != useDarkTheme))) {
            setDarkThemeState(om, userId, useDarkTheme);
            setBlackThemeState(om, userId, useBlackTheme);
            setCommonThemeState(om, userId, useDarkTheme || useBlackTheme);
        }
    }

    private static List<OverlayInfo> getAllOverlays(IOverlayManager om, int userId) {
        Map<String, List<OverlayInfo>> allOverlaysMap = null;
        List<OverlayInfo> allOverlays = new ArrayList<OverlayInfo>();
        try {
            allOverlaysMap = om.getAllOverlays(
                    userId);
            for (String key : allOverlaysMap.keySet()) {
                List<OverlayInfo> stuff = allOverlaysMap.get(key);
                allOverlays.addAll(stuff);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return allOverlays;
    }

    private static List<OverlayInfo> getOverlayInfosForCategory(IOverlayManager om, int userId, String category) {
        List<OverlayInfo> allOverlays = getAllOverlays(om, userId);
        List<OverlayInfo> ret = new ArrayList<OverlayInfo>();
        for (OverlayInfo oi : allOverlays) {
            if (category.equals(oi.category)) {
                ret.add(oi);
            }
        }
        return ret;
    }

    private static List<String> getThemePkgs(IOverlayManager om, int userId, String category) {
        List<String> pkgs = new ArrayList<>();
        List<OverlayInfo> oi = getOverlayInfosForCategory(om, userId, category);
        for (int i = 0; i < oi.size(); i++)
            pkgs.add(oi.get(i).packageName);
        return pkgs;
    }

    private static void setThemeStateFromList(IOverlayManager om, int userId, boolean enable, List<String> pkgs) {
        try {
            for (String pkg : pkgs) {
                om.setEnabled(pkg, enable, userId);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Can't set dark themes", e);
        }
    }

    private static void setDarkThemeState(IOverlayManager om, int userId, boolean enable) {
        setThemeStateFromList(om, userId, enable, getThemePkgs(om, userId, "android.theme.dark"));
    }

    private static void setBlackThemeState(IOverlayManager om, int userId, boolean enable) {
        setThemeStateFromList(om, userId, enable, getThemePkgs(om, userId, "android.theme.black"));
    }

    private static void setLightThemeState(IOverlayManager om, int userId, boolean enable) {
        setThemeStateFromList(om, userId, enable, getThemePkgs(om, userId, "android.theme.light"));
    }

    private static void setCommonThemeState(IOverlayManager om, int userId, boolean enable) {
        setThemeStateFromList(om, userId, enable, getThemePkgs(om, userId, "android.theme.common"));
    }

    // Unloads dark notification theme
    private static void unloadDarkNotificationTheme(IOverlayManager om, int userId) {
        try {
            om.setEnabled(NOTIFICATION_DARK_THEME, false, userId);
        } catch (RemoteException e) {
        }
    }

    // Unloads black notification theme
    private static void unloadBlackNotificationTheme(IOverlayManager om, int userId) {
        try {
            om.setEnabled(NOTIFICATION_BLACK_THEME, false, userId);
        } catch (RemoteException e) {
        }
    }

    // Check for the dark notification theme
    public static boolean isUsingDarkNotificationTheme(IOverlayManager om, int userId) {
        OverlayInfo themeInfo = null;
        try {
            themeInfo = om.getOverlayInfo(NOTIFICATION_DARK_THEME, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return themeInfo != null && themeInfo.isEnabled();
    }

    // Check for the black notification theme
    public static boolean isUsingBlackNotificationTheme(IOverlayManager om, int userId) {
        OverlayInfo themeInfo = null;
        try {
            themeInfo = om.getOverlayInfo(NOTIFICATION_BLACK_THEME, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return themeInfo != null && themeInfo.isEnabled();
    }

    // Set light / dark notification theme
    public static void setNotificationTheme(IOverlayManager om, int userId,
               boolean useBlackTheme,  boolean useDarkTheme, int notificationStyle) {
        if (notificationStyle == 1 || (notificationStyle == 0 && !useDarkTheme && !useBlackTheme)) {
            unloadDarkNotificationTheme(om, userId);
            unloadBlackNotificationTheme(om, userId);
        } else if (notificationStyle == 2 || (notificationStyle == 0 && useDarkTheme && !useBlackTheme)) {
            unloadBlackNotificationTheme(om, userId);
            try {
                om.setEnabled(NOTIFICATION_DARK_THEME, true, userId);
            } catch (RemoteException e) {
            }
        } else if (notificationStyle == 3 || (notificationStyle == 0 && !useDarkTheme && useBlackTheme)) {
            unloadDarkNotificationTheme(om, userId);
            try {
                om.setEnabled(NOTIFICATION_BLACK_THEME, true, userId);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Switches qs tile style.
     */
    public static void updateTileStyle(IOverlayManager om, int userId, int qsTileStyle) {
        if (qsTileStyle == 0) {
            unlockQsTileStyles(om, userId);
        } else {
            try {
                om.setEnabled(QS_TILE_THEMES[qsTileStyle],
                        true, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change qs tile style", e);
            }
        }
    }

    // Unload all the qs tile styles
    public static void unlockQsTileStyles(IOverlayManager om, int userId) {
        // skip index 0
        for (int i = 1; i < QS_TILE_THEMES.length; i++) {
            String qstiletheme = QS_TILE_THEMES[i];
            try {
                om.setEnabled(qstiletheme,
                        false /*disable*/, userId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    // Switches the analog clock from one to another or back to stock
    public static void updateClocks(IOverlayManager om, int userId, int clockSetting, Context ctx) {
        // all clock already unloaded due to StatusBar observer unloadClocks call
        // set the custom analog clock overlay
        if (clockSetting > 4) {
            try {
                final String[] clocks = getClocks(ctx);
                om.setEnabled(clocks[clockSetting],
                        true, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't change analog clocks", e);
            }
        }
    }

    // Unload all the analog overlays
    public static void unloadClocks(IOverlayManager om, int userId, Context ctx) {
        // skip index 0
        final String[] clocks = getClocks(ctx);
        for (int i = 1; i < clocks.length; i++) {
            String clock = clocks[i];
            try {
                om.setEnabled(clock,
                        false /*disable*/, userId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}
