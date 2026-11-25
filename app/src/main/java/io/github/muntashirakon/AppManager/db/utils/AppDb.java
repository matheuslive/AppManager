// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.db.utils;

import static io.github.muntashirakon.AppManager.compat.PackageManagerCompat.GET_SIGNING_CERTIFICATES;
import static io.github.muntashirakon.AppManager.compat.PackageManagerCompat.MATCH_DISABLED_COMPONENTS;
import static io.github.muntashirakon.AppManager.compat.PackageManagerCompat.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES;
import static io.github.muntashirakon.AppManager.compat.PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandleHidden;
import android.text.TextUtils;
import android.util.ArrayMap;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.muntashirakon.AppManager.backup.BackupUtils;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.db.AppsDb;
import io.github.muntashirakon.AppManager.db.dao.AppDao;
import io.github.muntashirakon.AppManager.db.dao.BackupDao;
import io.github.muntashirakon.AppManager.db.entity.App;
import io.github.muntashirakon.AppManager.db.entity.Backup;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.rules.compontents.ComponentsBlocker;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.ssaid.SsaidSettings;
import io.github.muntashirakon.AppManager.types.PackageSizeInfo;
import io.github.muntashirakon.AppManager.uri.UriManager;
import io.github.muntashirakon.AppManager.usage.AppUsageStatsManager;
import io.github.muntashirakon.AppManager.usage.PackageUsageInfo;
import io.github.muntashirakon.AppManager.usage.TimeInterval;
import io.github.muntashirakon.AppManager.usage.UsageDataAccess;
import io.github.muntashirakon.AppManager.usage.UsageUtils;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.BroadcastUtils;
import io.github.muntashirakon.AppManager.utils.ExUtils;
import io.github.muntashirakon.AppManager.utils.KeyStoreUtils;
import io.github.muntashirakon.AppManager.utils.PackageUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;

public class AppDb {
    public static final String TAG = AppDb.class.getSimpleName();

    /**
     * Guards reads and writes of the database. Must only be held for short database operations:
     * every reader (e.g. the backup/restore dialog) waits on it.
     */
    private static final Object sLock = new Object();
    /**
     * Serialises the updaters, which may run for a long time (one binder call per package).
     */
    private static final Object sUpdateLock = new Object();
    private static final AtomicBoolean sFullUpdateRunning = new AtomicBoolean(false);

    private final AppDao mAppDao;
    private final BackupDao mBackupDao;

    public AppDb() {
        mAppDao = AppsDb.getInstance().appDao();
        mBackupDao = AppsDb.getInstance().backupDao();
    }

    public List<App> getAllApplications() {
        synchronized (sLock) {
            return mAppDao.getAll();
        }
    }

    public List<App> getAllInstalledApplications() {
        synchronized (sLock) {
            return mAppDao.getAllInstalled();
        }
    }

    public List<App> getAllApplications(String packageName) {
        synchronized (sLock) {
            return mAppDao.getAll(packageName);
        }
    }

    public List<App> getAllApplications(String packageName, @UserIdInt int userId) {
        synchronized (sLock) {
            return mAppDao.getAll(packageName, userId);
        }
    }

    public List<Backup> getAllBackups() {
        synchronized (sLock) {
            return mBackupDao.getAll();
        }
    }

    public List<Backup> getAllBackups(String packageName) {
        synchronized (sLock) {
            return mBackupDao.get(packageName);
        }
    }

    /**
     * Fetch backups without a lock file. Necessary checks must be done to ensure that the backups actually exist.
     */
    public List<Backup> getAllBackupsNoLock(String packageName) {
        return mBackupDao.get(packageName);
    }

    public void insert(App app) {
        synchronized (sLock) {
            mAppDao.insert(app);
        }
    }

    public void insert(Backup backup) {
        synchronized (sLock) {
            mBackupDao.insert(backup);
        }
    }

    public void insertBackups(List<Backup> backups) {
        synchronized (sLock) {
            mBackupDao.insert(backups);
        }
    }

    /**
     * Replace all backups in a single transaction, so that readers never see an empty table.
     */
    public void replaceAllBackups(List<Backup> backups) {
        synchronized (sLock) {
            AppsDb.getInstance().runInTransaction(() -> {
                mBackupDao.deleteAll();
                mBackupDao.insert(backups);
            });
        }
    }

    public void deleteApplication(String packageName, int userId) {
        synchronized (sLock) {
            mAppDao.delete(packageName, userId);
        }
    }

    public void deleteAllApplications() {
        synchronized (sLock) {
            mAppDao.deleteAll();
        }
    }

    public void deleteAllBackups() {
        synchronized (sLock) {
            mBackupDao.deleteAll();
        }
    }

    public void deleteBackup(Backup backup) {
        synchronized (sLock) {
            mBackupDao.delete(backup);
        }
    }

    /**
     * Load backups and installed apps into the database. Only one such load runs at a time in this
     * process: a request made while another one is running is dropped (it would only repeat the
     * same work, e.g. when a new MainActivity is created while the previous load is still running).
     */
    @WorkerThread
    public void loadInstalledOrBackedUpApplications(@NonNull Context context) {
        if (!sFullUpdateRunning.compareAndSet(false, true)) {
            Log.i(TAG, "An update of the app database is already running, skipping.");
            return;
        }
        try {
            getBackups(true);
            updateApplicationsInternal(context);
        } finally {
            sFullUpdateRunning.set(false);
        }
    }

    @WorkerThread
    public List<App> updateApplications(@NonNull Context context, @NonNull String[] packageNames) {
        synchronized (sUpdateLock) {
            List<App> toDelete = new ArrayList<>();
            List<App> appList = new ArrayList<>();
            for (String packageName : packageNames) {
                appList.addAll(updateApplicationInternal(context, packageName, toDelete));
            }
            // Update usage and others (outside sLock: may take a while)
            updateVariableData(context, appList, true);
            writeApps(toDelete, appList);
            return appList;
        }
    }

    @WorkerThread
    public List<App> updateApplication(@NonNull Context context, @NonNull String packageName) {
        synchronized (sUpdateLock) {
            List<App> toDelete = new ArrayList<>();
            List<App> appList = updateApplicationInternal(context, packageName, toDelete);
            // Update usage and others (outside sLock: may take a while)
            updateVariableData(context, appList, true);
            writeApps(toDelete, appList);
            return appList;
        }
    }

    /**
     * Delete and insert in a single short transaction under {@link #sLock}.
     */
    private void writeApps(@NonNull List<App> toDelete, @NonNull List<App> toInsert) {
        synchronized (sLock) {
            long start = SystemClock.elapsedRealtime();
            AppsDb.getInstance().runInTransaction(() -> {
                if (!toDelete.isEmpty()) mAppDao.delete(toDelete);
                if (!toInsert.isEmpty()) mAppDao.insert(toInsert);
            });
            Log.i(TAG, "Wrote %d apps, deleted %d (lock held for %d ms)", toInsert.size(), toDelete.size(),
                    SystemClock.elapsedRealtime() - start);
        }
    }

    @GuardedBy("sUpdateLock")
    @WorkerThread
    @NonNull
    private List<App> updateApplicationInternal(@NonNull Context context, @NonNull String packageName,
                                                @NonNull List<App> toDelete) {
        int[] userIds = Users.getUsersIds();
        List<App> oldApps;
        List<Backup> backups;
        synchronized (sLock) {
            oldApps = new ArrayList<>(mAppDao.getAll(packageName));
            backups = new ArrayList<>(mBackupDao.get(packageName));
        }
        List<App> appList = new ArrayList<>(userIds.length);
        for (int userId : userIds) {
            int oldAppIndex = findIndexOfApp(oldApps, packageName, userId);
            PackageInfo packageInfo = null;
            Backup backup = null;
            ListIterator<Backup> backupListIterator = backups.listIterator();
            while (backupListIterator.hasNext()) {
                Backup b = backupListIterator.next();
                if (b.userId == userId) {
                    backup = b;
                    backupListIterator.remove();
                    break;
                }
            }
            try {
                packageInfo = PackageManagerCompat.getPackageInfo(packageName,
                        PackageManager.GET_META_DATA | GET_SIGNING_CERTIFICATES | PackageManager.GET_ACTIVITIES
                                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                                | PackageManager.GET_SERVICES | MATCH_DISABLED_COMPONENTS | MATCH_UNINSTALLED_PACKAGES
                                | MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, userId);
            } catch (RemoteException | PackageManager.NameNotFoundException | SecurityException e) {
                // Package does not exist
            }
            if (backup == null && packageInfo == null) {
                // Neither backup nor package exist
                if (oldAppIndex >= 0) {
                    // Delete existing backup
                    toDelete.add(oldApps.get(oldAppIndex));
                }
                continue;
            }
            App oldApp = null;
            if (oldAppIndex >= 0) {
                // There's already existing app
                oldApp = oldApps.get(oldAppIndex);
                toDelete.add(oldApp);
                if ((packageInfo != null && isUpToDate(oldApp, packageInfo))
                        || (backup != null && isUpToDate(oldApp, backup))) {
                    // Up-to-date app
                    appList.add(oldApp);
                    oldApp.lastActionTime = System.currentTimeMillis();
                    continue;
                }
            }
            // New app
            App app = packageInfo != null ? App.fromPackageInfo(context, packageInfo) : App.fromBackup(backup);
            copySizeAndUsage(oldApp, app);
            appList.add(app);
        }

        // Add the rest of the backups if any
        for (Backup backup : backups) {
            appList.add(App.fromBackup(backup));
        }

        // Return the list instead of triggering broadcast
        return appList;
    }

    @WorkerThread
    public void updateApplications(@NonNull Context context) {
        if (!sFullUpdateRunning.compareAndSet(false, true)) {
            Log.i(TAG, "An update of the app database is already running, skipping.");
            return;
        }
        try {
            updateApplicationsInternal(context);
        } finally {
            sFullUpdateRunning.set(false);
        }
    }

    /**
     * Full update of the app table, in three steps so that {@link #sLock} (which every reader, e.g.
     * the backup/restore dialog, needs) is only held for short database reads/writes:
     * <ol>
     *     <li>Collect the installed packages and cheap per-app data, without {@link #sLock}. Sizes
     *     and usage are carried over from the previous entry (the database is the cache).
     *     <li>Write the app table in one transaction under {@link #sLock}.
     *     <li>Refresh sizes, data usage and screen time without any lock, then write only those
     *     columns in one short transaction under {@link #sLock}.
     * </ol>
     * Updaters are serialised by {@link #sUpdateLock} (steps 1–2 only).
     */
    @WorkerThread
    private void updateApplicationsInternal(@NonNull Context context) {
        long start = SystemClock.elapsedRealtime();
        List<App> modifiedApps = new ArrayList<>();
        Set<String> newApps = new HashSet<>();
        Set<String> updatedApps = new HashSet<>();
        List<App> oldApps;
        synchronized (sUpdateLock) {
            Map<String, Backup> backups = getBackups(false);
            synchronized (sLock) {
                oldApps = new ArrayList<>(mAppDao.getAll());
            }
            Map<String, App> previousApps = new HashMap<>(oldApps.size());
            for (App oldApp : oldApps) {
                previousApps.put(oldApp.userId + ":" + oldApp.packageName, oldApp);
            }

            // Interrupt thread on request
            if (ThreadUtils.isInterrupted()) return;

            for (int userId : Users.getUsersIds()) {
                // Interrupt thread on request
                if (ThreadUtils.isInterrupted()) return;

                if (!SelfPermissions.checkCrossUserPermission(userId, false)) {
                    // No support for cross user
                    continue;
                }

                List<PackageInfo> packageInfoList = PackageManagerCompat.getInstalledPackages(
                        GET_SIGNING_CERTIFICATES | PackageManager.GET_ACTIVITIES
                                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                                | PackageManager.GET_SERVICES | MATCH_DISABLED_COMPONENTS
                                | MATCH_UNINSTALLED_PACKAGES | MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, userId);

                for (PackageInfo packageInfo : packageInfoList) {
                    // Interrupt thread on request
                    if (ThreadUtils.isInterrupted()) return;

                    int oldAppIndex = findIndexOfApp(oldApps, packageInfo.packageName, UserHandleHidden.getUserId(packageInfo.applicationInfo.uid));
                    if (oldAppIndex >= 0) {
                        // There's already existing app
                        App oldApp = oldApps.remove(oldAppIndex);
                        if (isUpToDate(oldApp, packageInfo)) {
                            // Up-to-date app
                            updatedApps.add(oldApp.packageName);
                            modifiedApps.add(oldApp);
                            backups.remove(packageInfo.packageName);
                            oldApp.lastActionTime = System.currentTimeMillis();
                            continue;
                        }
                    }
                    // New app
                    App app = App.fromPackageInfo(context, packageInfo);
                    copySizeAndUsage(previousApps.get(app.userId + ":" + app.packageName), app);
                    backups.remove(packageInfo.packageName);
                    newApps.add(app.packageName);
                    modifiedApps.add(app);
                }
            }

            // Update rules, keystore, SAF and SSAID. Sizes and usage are refreshed later.
            updateVariableData(context, modifiedApps, false);

            // Add rest of the backup items, i.e., items that aren't installed
            for (Backup backup : backups.values()) {
                if (backup == null) continue;
                // Interrupt thread on request
                if (ThreadUtils.isInterrupted()) return;

                int oldAppIndex = findIndexOfApp(oldApps, backup.packageName, backup.userId);
                if (oldAppIndex >= 0) {
                    // There's already existing app
                    App oldApp = oldApps.remove(oldAppIndex);
                    if (isUpToDate(oldApp, backup)) {
                        // Up-to-date app
                        updatedApps.add(oldApp.packageName);
                        modifiedApps.add(oldApp);
                        continue;
                    }
                }
                // New app
                App app = App.fromBackup(backup);
                newApps.add(app.packageName);
                modifiedApps.add(app);
            }
            // Add new data
            writeApps(oldApps, modifiedApps);
        }
        Log.i(TAG, "App list updated in %d ms", SystemClock.elapsedRealtime() - start);
        if (!oldApps.isEmpty()) {
            // Delete broadcast
            BroadcastUtils.sendDbPackageRemoved(context, getPackageNamesFromApps(oldApps));
        }
        if (!newApps.isEmpty()) {
            // New apps
            BroadcastUtils.sendDbPackageAdded(context, newApps.toArray(new String[0]));
        }
        if (!updatedApps.isEmpty()) {
            // Altered apps
            BroadcastUtils.sendDbPackageAltered(context, updatedApps.toArray(new String[0]));
        }
        // Refresh sizes and usage
        if (ThreadUtils.isInterrupted()) return;
        long sizeStart = SystemClock.elapsedRealtime();
        Set<String> changed = refreshSizeAndUsage(context, modifiedApps);
        Log.i(TAG, "Sizes and usage refreshed in %d ms (%d packages changed)",
                SystemClock.elapsedRealtime() - sizeStart, changed.size());
        if (!changed.isEmpty()) {
            BroadcastUtils.sendDbPackageAltered(context, changed.toArray(new String[0]));
        }
    }

    /**
     * Compute sizes, data usage and screen time without any lock, then store only those columns.
     *
     * @return Packages whose values changed
     */
    @WorkerThread
    @NonNull
    private Set<String> refreshSizeAndUsage(@NonNull Context context, @NonNull List<App> apps) {
        Set<String> changed = new HashSet<>();
        List<App> updated = new ArrayList<>();
        Map<String, PackageUsageInfo> usageInfoMap = UsageDataAccess.canReadUsageData()
                ? getUsageInfoMap() : Collections.emptyMap();
        for (App app : apps) {
            if (ThreadUtils.isInterrupted()) return changed;
            if (!app.isInstalled && !app.isSystemApp()) {
                continue;
            }
            long codeSize = app.codeSize, dataSize = app.dataSize, mobile = app.mobileDataUsage,
                    wifi = app.wifiDataUsage, screenTime = app.screenTime, lastUsage = app.lastUsageTime;
            int openCount = app.openCount;
            setSizeAndUsage(context, app, usageInfoMap);
            if (codeSize != app.codeSize || dataSize != app.dataSize || mobile != app.mobileDataUsage
                    || wifi != app.wifiDataUsage || screenTime != app.screenTime
                    || lastUsage != app.lastUsageTime || openCount != app.openCount) {
                updated.add(app);
                changed.add(app.packageName);
            }
        }
        if (updated.isEmpty()) {
            return changed;
        }
        synchronized (sLock) {
            long start = SystemClock.elapsedRealtime();
            AppsDb.getInstance().runInTransaction(() -> {
                for (App app : updated) {
                    mAppDao.updateSizeAndUsage(app.packageName, app.userId, app.codeSize, app.dataSize,
                            app.mobileDataUsage, app.wifiDataUsage, app.openCount, app.screenTime,
                            app.lastUsageTime);
                }
            });
            Log.i(TAG, "Updated sizes/usage of %d apps (lock held for %d ms)", updated.size(),
                    SystemClock.elapsedRealtime() - start);
        }
        return changed;
    }

    @WorkerThread
    @NonNull
    public Map<String, Backup> getBackups(boolean loadBackups) {
        if (loadBackups) {
            // Very long operation
            return BackupUtils.storeAllAndGetLatestBackupMetadata();
        } else {
            return BackupUtils.getAllLatestBackupMetadataFromDb();
        }
    }

    private static void copySizeAndUsage(@Nullable App from, @NonNull App to) {
        if (from == null) return;
        to.codeSize = from.codeSize;
        to.dataSize = from.dataSize;
        to.mobileDataUsage = from.mobileDataUsage;
        to.wifiDataUsage = from.wifiDataUsage;
        to.openCount = from.openCount;
        to.screenTime = from.screenTime;
        to.lastUsageTime = from.lastUsageTime;
    }

    @WorkerThread
    @NonNull
    private static Map<String, PackageUsageInfo> getUsageInfoMap() {
        Map<String, PackageUsageInfo> usageInfoMap = new HashMap<>();
        TimeInterval interval = UsageUtils.getLastWeek();
        for (int userId : Users.getUsersIds()) {
            if (ThreadUtils.isInterrupted()) break;
            List<PackageUsageInfo> usageInfoList = ExUtils.exceptionAsNull(() ->
                    AppUsageStatsManager.getInstance().getUsageStats(interval, userId));
            if (usageInfoList != null) {
                for (PackageUsageInfo usageInfo : usageInfoList) {
                    usageInfoMap.put(usageInfo.userId + ":" + usageInfo.packageName, usageInfo);
                }
            }
        }
        return usageInfoMap;
    }

    /**
     * Set sizes, data usage and screen time. Values are zeroed if they cannot be read (upstream
     * behaviour).
     */
    @WorkerThread
    private static void setSizeAndUsage(@NonNull Context context, @NonNull App app,
                                        @NonNull Map<String, PackageUsageInfo> usageInfoMap) {
        boolean hasUsageAccess = UsageDataAccess.canReadUsageData();
        app.codeSize = app.dataSize = 0;
        if (hasUsageAccess) {
            PackageSizeInfo sizeInfo = PackageUtils.getPackageSizeInfo(context, app.packageName, app.userId, null);
            if (sizeInfo != null) {
                app.codeSize = sizeInfo.codeSize + sizeInfo.obbSize;
                app.dataSize = sizeInfo.dataSize + sizeInfo.mediaSize + sizeInfo.cacheSize;
            }
        }
        if (!app.isInstalled) {
            return;
        }
        PackageUsageInfo usageInfo = usageInfoMap.get(app.userId + ":" + app.packageName);
        if (usageInfo != null) {
            app.mobileDataUsage = usageInfo.mobileData != null ? usageInfo.mobileData.getTotal() : 0;
            app.wifiDataUsage = usageInfo.wifiData != null ? usageInfo.wifiData.getTotal() : 0;
            app.openCount = usageInfo.timesOpened;
            app.screenTime = usageInfo.screenTime;
            app.lastUsageTime = usageInfo.lastUsageTime;
        } else {
            app.mobileDataUsage = app.wifiDataUsage = app.screenTime = app.lastUsageTime = 0;
            app.openCount = 0;
        }
    }

    /**
     * Must be called without {@link #sLock}.
     *
     * @param withSizeAndUsage Also refresh sizes, data usage and screen time (slow: one binder call
     *                         per package). Otherwise, the existing values are left untouched.
     */
    @WorkerThread
    private static void updateVariableData(@NonNull Context context, @NonNull List<App> modifiedApps,
                                           boolean withSizeAndUsage) {
        UriManager uriManager = new UriManager();
        ArrayMap<Integer, SsaidSettings> userIdSsaidSettingsMap = new ArrayMap<>();
        Map<String, PackageUsageInfo> usageInfoMap = withSizeAndUsage && UsageDataAccess.canReadUsageData()
                ? getUsageInfoMap() : Collections.emptyMap();
        for (int userId : Users.getUsersIds()) {
            // Interrupt thread on request
            if (ThreadUtils.isInterrupted()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    userIdSsaidSettingsMap.put(userId, new SsaidSettings(userId));
                } catch (IOException e) {
                    Log.w(TAG, "Error: " + e.getMessage());
                }
            }
        }
        for (App app : modifiedApps) {
            if (!app.isInstalled && !app.isSystemApp()) {
                continue;
            }
            int userId = app.userId;
            try (ComponentsBlocker cb = ComponentsBlocker.getInstance(app.packageName, userId, false)) {
                app.rulesCount = cb.entryCount();
            }
            if (withSizeAndUsage) {
                setSizeAndUsage(context, app, usageInfoMap);
            }
            // Interrupt thread on request
            if (ThreadUtils.isInterrupted()) return;
            if (!app.isInstalled) {
                continue;
            }
            app.hasKeystore = KeyStoreUtils.hasKeyStore(app.uid);
            app.usesSaf = uriManager.getGrantedUris(app.packageName) != null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                SsaidSettings ssaidSettings = userIdSsaidSettingsMap.get(userId);
                if (ssaidSettings != null) {
                    String ssaid = ssaidSettings.getSsaid(app.packageName, app.uid);
                    app.ssaid = TextUtils.isEmpty(ssaid) ? null : ssaid;
                } else {
                    app.ssaid = null;
                }
            }
        }
    }

    private static int findIndexOfApp(@NonNull List<App> appList, @NonNull String packageName, @UserIdInt int userId) {
        for (int i = 0; i < appList.size(); ++i) {
            App app = appList.get(i);
            if (app.userId == userId && app.packageName.equals(packageName)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isUpToDate(@NonNull App currentApp, @NonNull PackageInfo installedPackageInfo) {
        if (!currentApp.isInstalled) {
            // The app was not installed earlier
            return false;
        }
        // App was installed
        return currentApp.lastUpdateTime == installedPackageInfo.lastUpdateTime
                && currentApp.flags == installedPackageInfo.applicationInfo.flags;
    }

    private static boolean isUpToDate(@NonNull App currentApp, @NonNull Backup backup) {
        if (currentApp.isInstalled) {
            // The app was installed earlier
            return false;
        }
        // App was not installed
        if (currentApp.sdk != 0) {
            // The app is a system app
            return true;
        }
        // The app is a backed up app
        return currentApp.lastUpdateTime == backup.backupTime;
    }

    @NonNull
    private static String[] getPackageNamesFromApps(@NonNull List<App> apps) {
        HashSet<String> packages = new HashSet<>(apps.size());
        for (App app : apps) {
            packages.add(app.packageName);
        }
        return packages.toArray(new String[0]);
    }
}
