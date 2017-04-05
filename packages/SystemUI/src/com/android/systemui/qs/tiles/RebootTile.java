/*
 * Copyright (C) 2013 Slimroms
 * Copyright (C) 2017 Xperia Open Source Project
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

package com.android.systemui.qs.tiles;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.ServiceManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;

public class RebootTile extends QSTileImpl<BooleanState> {

    private IStatusBarService mStatusBarService;

    //1 Normal Reboot
    //2 Reboot to Recovery
    //3 Soft Reboot
    //4 Reboot SystemUI
    //5 Shutdown
    private int mChoiceNumber = 1;

    public RebootTile(QSHost host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        if (mChoiceNumber == 5) {
            mChoiceNumber = 1;
            refreshState();
        } else {
            mChoiceNumber++;
            refreshState();
        }
    }

    private void doSystemUIReboot() {
        IStatusBarService statusBarService = IStatusBarService.Stub.asInterface(ServiceManager.checkService(Context.STATUS_BAR_SERVICE));
        if (statusBarService != null) {
            try {
                statusBarService.restartUI();
            } catch (RemoteException e) {
                // do nothing.
            }
        }
    }

    private void doSoftReboot() {
        final IActivityManager am = ActivityManagerNative.asInterface(ServiceManager.checkService("activity"));
        if (am != null) {
            try {
                am.restart();
            } catch (RemoteException e) {
                // Don't need anything from here
            }
        }
    }

    @Override
    protected void handleLongClick() {
        Handler handler = new Handler();
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mHost.collapsePanels();

        switch(mChoiceNumber) {
            case 1:
                handler.postDelayed(new Runnable() {
                    public void run() {
                        pm.reboot("");
                    }
                }, 500);
            break;
            case 2:
                handler.postDelayed(new Runnable() {
                    public void run() {
                        pm.rebootCustom(PowerManager.REBOOT_RECOVERY);
                    }
                }, 500);
            break;
            case 3:
                handler.postDelayed(new Runnable() {
                    public void run() {
                        doSoftReboot();
                    }
                }, 500);
            break;
            case 4:
                handler.postDelayed(new Runnable() {
                    public void run() {
                        doSystemUIReboot();
                    }
                }, 500);
            break;
            case 5:
                handler.postDelayed(new Runnable() {
                    public void run() {
                        pm.shutdown(false, pm.SHUTDOWN_USER_REQUESTED, false);
                    }
                }, 500);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_reboot_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.MAGICAL_WORLD;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        switch(mChoiceNumber) {
            case 1:
                state.label = mContext.getString(R.string.quick_settings_reboot_label);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_reboot);
                state.contentDescription =  mContext.getString(R.string.quick_settings_reboot_label);
            break;
            case 2:
                state.label = mContext.getString(R.string.quick_settings_reboot_recovery_label);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_reboot_recovery);
                state.contentDescription =  mContext.getString(R.string.quick_settings_reboot_recovery_label);
            break;
            case 3:
                state.label = mContext.getString(R.string.quick_settings_soft_reboot_label);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_reboot);
                state.contentDescription =  mContext.getString(R.string.quick_settings_soft_reboot_label);
            break;
            case 4:
                state.label = mContext.getString(R.string.quick_settings_systemui_reboot_label);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_reboot_systemui);
                state.contentDescription =  mContext.getString(R.string.quick_settings_systemui_reboot_label);
            break;
            case 5:
                state.label = mContext.getString(R.string.quick_settings_poweroff_label);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_poweroff);
                state.contentDescription =  mContext.getString(R.string.quick_settings_poweroff_label);
            break;
        }
    }

    @Override
    public void handleSetListening(boolean listening) {
    }
}
