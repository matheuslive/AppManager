// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.usage;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.muntashirakon.AppManager.ipc.LocalServices;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.misc.OsEnvironment;
import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.settings.Ops;
import io.github.muntashirakon.AppManager.types.PackageSizeInfo;
import io.github.muntashirakon.io.Path;

/**
 * Decides where app sizes, data usage and screen time come from.
 * <p>
 * Upstream only reads these when the "Usage access" feature is enabled. The services involved
 * ({@code storagestats}, {@code netstats} and {@code usagestats}) are already called through the
 * privileged server (see {@link io.github.muntashirakon.AppManager.ipc.ProxyBinder}). When that
 * server runs as root, system or shell, those services accept the call based on the server's
 * UID, regardless of the PACKAGE_USAGE_STATS app op of App Manager itself. So, with the feature
 * disabled, the same data is still read through the privileged server. Without a privileged
 * server (no-root mode), the data stays unavailable, as upstream.
 */
public final class UsageDataAccess {
    public static final String TAG = UsageDataAccess.class.getSimpleName();

    private UsageDataAccess() {
    }

    /**
     * Whether the privileged server is running as a UID that can query usage/storage/network stats
     * without App Manager holding the usage access app op.
     */
    @AnyThread
    public static boolean isPrivilegedSourceAvailable() {
        int uid = Ops.getWorkingUid();
        if (uid == Process.myUid()) {
            return false;
        }
        if (uid != Ops.ROOT_UID && uid != Ops.SYSTEM_UID && uid != Ops.SHELL_UID) {
            return false;
        }
        return LocalServices.alive();
    }

    /**
     * Whether app sizes, data usage and screen time can be read: either via the usage access
     * feature (upstream behaviour) or via the privileged server when the feature is disabled.
     */
    @AnyThread
    public static boolean canReadUsageData() {
        if (FeatureController.isUsageAccessEnabled()) {
            return SelfPermissions.checkUsageStatsPermission();
        }
        return isPrivilegedSourceAvailable() && SelfPermissions.checkUsageStatsPermission();
    }

    /**
     * Last-resort size calculation with {@code du} as root. Slow (walks the directories), so it is
     * only meant for a single package on demand, when {@code storagestats} fails.
     */
    @WorkerThread
    @Nullable
    public static PackageSizeInfo getPackageSizeInfoByDu(@NonNull ApplicationInfo info, int userId) {
        if (!Ops.isWorkingUidRoot() && !Ops.isDirectRoot()) {
            return null;
        }
        String packageName = info.packageName;
        Set<String> code = new LinkedHashSet<>();
        if (info.sourceDir != null) {
            if (info.sourceDir.startsWith("/data/app/")) {
                // Whole install dir: base.apk, splits, oat and lib
                int idx = info.sourceDir.lastIndexOf('/');
                code.add(idx > 0 ? info.sourceDir.substring(0, idx) : info.sourceDir);
            } else {
                code.add(info.sourceDir);
            }
        }
        Set<String> data = new LinkedHashSet<>();
        Set<String> cache = new LinkedHashSet<>();
        if (info.dataDir != null) {
            data.add(info.dataDir);
            cache.add(info.dataDir + "/cache");
            cache.add(info.dataDir + "/code_cache");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && info.deviceProtectedDataDir != null) {
            data.add(info.deviceProtectedDataDir);
            cache.add(info.deviceProtectedDataDir + "/cache");
            cache.add(info.deviceProtectedDataDir + "/code_cache");
        }
        Set<String> media = new LinkedHashSet<>();
        Set<String> obb = new LinkedHashSet<>();
        OsEnvironment.UserEnvironment ue = OsEnvironment.getUserEnvironment(userId);
        addPaths(data, ue.buildExternalStorageAppDataDirs(packageName));
        addPaths(cache, ue.buildExternalStorageAppCacheDirs(packageName));
        addPaths(media, ue.buildExternalStorageAppMediaDirs(packageName));
        addPaths(obb, ue.buildExternalStorageAppObbDirs(packageName));
        long codeSize = du(code);
        long dataSize = du(data);
        long cacheSize = du(cache);
        long mediaSize = du(media);
        long obbSize = du(obb);
        if (codeSize < 0 && dataSize < 0) {
            return null;
        }
        // PackageSizeInfo keeps data and cache apart
        dataSize = Math.max(0, Math.max(0, dataSize) - Math.max(0, cacheSize));
        return new PackageSizeInfo(packageName, Math.max(0, codeSize), dataSize, Math.max(0, cacheSize),
                Math.max(0, mediaSize), Math.max(0, obbSize));
    }

    private static void addPaths(@NonNull Set<String> out, @Nullable Path[] paths) {
        if (paths == null) return;
        for (Path path : paths) {
            String filePath = path.getFilePath();
            if (filePath != null) out.add(filePath);
        }
    }

    /**
     * @return Total size in bytes of the paths that exist, or -1 if none could be read.
     */
    @WorkerThread
    private static long du(@NonNull Set<String> paths) {
        if (paths.isEmpty()) return -1;
        List<String> cmd = new ArrayList<>(paths.size() + 2);
        cmd.add("du");
        cmd.add("-sk");
        cmd.addAll(paths);
        Runner.Result result = Runner.runCommand(cmd.toArray(new String[0]));
        long total = 0;
        boolean found = false;
        // du exits non-zero if some path does not exist; the others are still printed
        for (String line : result.getOutputAsList()) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String kb = line.substring(0, tab).trim();
            if (!TextUtils.isDigitsOnly(kb)) continue;
            try {
                total += Long.parseLong(kb) * 1024;
                found = true;
            } catch (NumberFormatException e) {
                Log.w(TAG, "Bad du output: %s", line);
            }
        }
        return found ? total : -1;
    }
}
