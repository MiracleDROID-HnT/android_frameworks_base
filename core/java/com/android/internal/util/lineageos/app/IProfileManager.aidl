package com.android.internal.util.lineageos.app;

import com.android.internal.util.lineageos.app.Profile;
import android.app.NotificationGroup;
import android.os.ParcelUuid;

/** {@hide} */
interface IProfileManager
{
    boolean setActiveProfile(in ParcelUuid profileParcelUuid);
    boolean setActiveProfileByName(String profileName);
    Profile getActiveProfile();

    boolean addProfile(in Profile profile);
    boolean removeProfile(in Profile profile);
    void updateProfile(in Profile profile);

    Profile getProfile(in ParcelUuid profileParcelUuid);
    Profile getProfileByName(String profileName);
    Profile[] getProfiles();
    boolean profileExists(in ParcelUuid profileUuid);
    boolean profileExistsByName(String profileName);
    boolean notificationGroupExistsByName(String notificationGroupName);

    NotificationGroup[] getNotificationGroups();
    void addNotificationGroup(in NotificationGroup group);
    void removeNotificationGroup(in NotificationGroup group);
    void updateNotificationGroup(in NotificationGroup group);
    NotificationGroup getNotificationGroupForPackage(in String pkg);
    NotificationGroup getNotificationGroup(in ParcelUuid groupParcelUuid);

    void resetAll();
    boolean isEnabled();
}
