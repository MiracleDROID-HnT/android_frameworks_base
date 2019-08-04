/*
 * Copyright (C) 2018 The Dirty Unicorns Project
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

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import com.android.systemui.Dependency;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSDetailItems;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSDetailItemsList;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ThemeTile extends QSTileImpl<BooleanState> {

    private final ActivityStarter mActivityStarter;

    private static final Intent COLOR_PICKER = new Intent().setComponent(new ComponentName(
            "com.android.settings", "mx.mdroid.magicalworld.fragments.style.colorpicker.ColorPickerActivity"));

    static final List<ThemeTileItem> sThemeItems = new ArrayList<ThemeTileItem>();
    static {
        sThemeItems.add(new ThemeTileItem(R.string.quick_settings_theme_tile_title));
    }

    static final List<ThemeTileItem> sStyleItems = new ArrayList<ThemeTileItem>();
    static {
        sStyleItems.add(new ThemeTileItem(0, R.string.systemui_theme_style_auto));
        sStyleItems.add(new ThemeTileItem(1, R.string.systemui_theme_style_time_based));
        sStyleItems.add(new ThemeTileItem(2, R.string.systemui_theme_style_light));
        sStyleItems.add(new ThemeTileItem(3, R.string.systemui_theme_style_dark));
        sStyleItems.add(new ThemeTileItem(4, R.string.systemui_theme_style_black));
    }

    private enum Mode {
        ACCENT, STYLE
    }

    private IOverlayManager mOverlayManager;
    protected int mCurrentUserId = 0;
    private Mode mMode;

    public ThemeTile(QSHost host) {
        super(host);
        mActivityStarter = Dependency.get(ActivityStarter.class);
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
        mCurrentUserId = ActivityManager.getCurrentUser();
        // Get enabled mode
        String userChoice = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.THEME_TILE_ENABLED_MODE,
                UserHandle.USER_CURRENT);
        mMode = userChoice != null ? Mode.valueOf(userChoice) : Mode.ACCENT;
    }

    private static class ThemeTileItem {
        int settingsValStyle;
        final int labelRes;

        public ThemeTileItem(int labelRes) {
            this.labelRes = labelRes;
        }

        public ThemeTileItem(int settingsValStyle, int labelRes) {
			this.settingsValStyle = settingsValStyle;
            this.labelRes = labelRes;
        }

        public String getLabel(Context context) {
            return context.getString(labelRes);
        }

        public void commit_style(Context context) {
            Settings.System.putIntForUser(context.getContentResolver(),
                    Settings.System.THEME_GLOBAL_STYLE, settingsValStyle, UserHandle.USER_CURRENT);
        }
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return new ThemeDetailAdapter();
    }

    private class ThemeDetailAdapter
            implements DetailAdapter, AdapterView.OnItemClickListener {
        private QSDetailItemsList mItemsList;
        private QSDetailItemsList.QSDetailListAdapter mAdapter;
        private List<Item> mThemeItems = new ArrayList<>();

        @Override
        public CharSequence getTitle() {
            return mContext.getString(mMode == Mode.ACCENT ?
                    R.string.quick_settings_theme_tile_accent_detail_title :
                    R.string.quick_settings_theme_tile_style_detail_title);
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mItemsList = QSDetailItemsList.convertOrInflate(context, convertView, parent);
            mAdapter = new QSDetailItemsList.QSDetailListAdapter(context, mThemeItems);
            ListView listView = mItemsList.getListView();
            listView.setDivider(null);
            listView.setOnItemClickListener(this);
            listView.setAdapter(mAdapter);
            updateItems();
            return mItemsList;
        }

        private void updateItems() {
            if (mAdapter == null)
                return;
            mThemeItems.clear();
            if (mMode == Mode.ACCENT) {
                mThemeItems.addAll(getAccentItems());
            } else {
                mThemeItems.addAll(getStyleItems());
            }
            mAdapter.notifyDataSetChanged();
        }

        private List<Item> getAccentItems() {
            List<Item> items = new ArrayList<Item>();
            for (ThemeTileItem themeTileItem : sThemeItems) {
                Item item = new Item();
                item.tag = themeTileItem;
                item.doDisableFocus = true;
                item.icon = R.drawable.ic_qs_style_list;
                item.line1 = themeTileItem.getLabel(mContext);
                items.add(item);
            }
            return items;
        }

        private List<Item> getStyleItems() {
            List<Item> items = new ArrayList<Item>();
            for (ThemeTileItem styleItem : sStyleItems) {
                Item item = new Item();
                item.tag = styleItem;
                item.doDisableFocus = true;
                item.icon = R.drawable.ic_qs_style_list;
                item.line1 = styleItem.getLabel(mContext);
                items.add(item);
            }
            return items;
        }

        @Override
        public Intent getSettingsIntent() {
        return new Intent().setComponent(new ComponentName(
                "com.android.settings", "com.android.settings.Settings$StylePreferencesActivity"));
        }

        @Override
        public void setToggleState(boolean state) {
        }

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.MAGICAL_WORLD;
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            Item item = (Item) parent.getItemAtPosition(position);
            if (item == null || item.tag == null)
                return;
            ThemeTileItem themeItem = (ThemeTileItem) item.tag;
            showDetail(false);
            if (mMode == Mode.ACCENT) {
                mActivityStarter.postStartActivityDismissingKeyguard(COLOR_PICKER, 0);
            } else {
                themeItem.commit_style(mContext);
            }
        }
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (Prefs.getBoolean(mContext, Prefs.Key.QS_THEME_DIALOG_SHOWN, false)) {
            showDetail(true);
            return;
        }
        SystemUIDialog dialog = new SystemUIDialog(mContext);
        dialog.setTitle(R.string.theme_info_title);
        dialog.setMessage(R.string.theme_info_message);
        dialog.setPositiveButton(com.android.internal.R.string.ok,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showDetail(true);
                        Prefs.putBoolean(mContext, Prefs.Key.QS_THEME_DIALOG_SHOWN, true);
                    }
                });
        dialog.setShowForAllUsers(true);
        dialog.show();
    }

    @Override
    protected void handleLongClick() {
        mMode = mMode == Mode.ACCENT ? Mode.STYLE : Mode.ACCENT;
        Settings.System.putStringForUser(mContext.getContentResolver(),
                Settings.System.THEME_TILE_ENABLED_MODE, mMode.name(),
                UserHandle.USER_CURRENT);
        refreshState();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(mMode == Mode.ACCENT
                ? R.string.quick_settings_theme_tile_title : R.string.systemui_theme_style_title);
        state.icon = ResourceIcon.get(mMode == Mode.ACCENT
                ? R.drawable.ic_qs_accent : R.drawable.ic_qs_style);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EXTENSIONS;
    }

    @Override
    public Intent getLongClickIntent() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected void handleSetListening(boolean listening) {
        // TODO Auto-generated method stub
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_theme_tile_title);
    }
}
