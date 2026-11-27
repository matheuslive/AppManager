// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup.dialog;

import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandleHidden;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import java.util.Collections;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.backup.BackupUtils;
import io.github.muntashirakon.AppManager.compat.ApplicationInfoCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.self.SelfUriManager;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.PackageUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;

/**
 * Exported entry point that opens the backup/restore dialog of a single package directly, without
 * the app details page.
 * <p>
 * Format: {@code app-manager://backup?id=<package>&user=<user_id>} ({@code user} is optional and
 * defaults to the current user). Only the package and the user are read from the intent: the
 * operation and its flags are always chosen by the user in the dialog (flags come from the current
 * preferences), so nothing is executed by the intent alone. The activity finishes once the dialog
 * is dismissed; a started operation continues in {@code BatchOpsService}.
 */
public class BackupRestoreActivity extends BaseActivity {
    public static final String TAG = BackupRestoreActivity.class.getSimpleName();

    public static final String BACKUP_HOST = "backup";

    private final FragmentManager.FragmentLifecycleCallbacks mDialogCallbacks =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentDestroyed(@NonNull FragmentManager fm, @NonNull Fragment f) {
                    if (f instanceof BackupRestoreDialogFragment && !isChangingConfigurations()) {
                        // Dismissed, cancelled or closed after starting an operation
                        finish();
                    }
                }
            };

    @Override
    public boolean getTransparentBackground() {
        return true;
    }

    @Override
    public void finishAndRemoveTask() {
        // BaseActivity calls this when authentication fails or is cancelled. This activity may run
        // in the caller's task (e.g. Settings), which must not be removed.
        if (isTaskRoot()) {
            super.finishAndRemoveTask();
        } else {
            finish();
        }
    }

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(mDialogCallbacks, false);
        if (getSupportFragmentManager().findFragmentByTag(BackupRestoreDialogFragment.TAG) != null) {
            // Restored after a configuration change
            return;
        }
        UserPackagePair pair = parse(getIntent().getData());
        if (pair == null) {
            finish();
            return;
        }
        ThreadUtils.postOnBackgroundThread(() -> {
            // Users.getUsersIds() may need the privileged server: not on the main thread
            boolean validUser = ArrayUtils.contains(Users.getUsersIds(), pair.getUserId());
            boolean available = validUser && isInstalledOrBackedUp(pair);
            ThreadUtils.postOnMainThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!validUser) {
                    UIUtils.displayLongToast(R.string.backup_intent_invalid_user, String.valueOf(pair.getUserId()));
                    finish();
                    return;
                }
                if (!available) {
                    UIUtils.displayLongToast(R.string.backup_intent_no_app_or_backup, pair.getPackageName());
                    finish();
                    return;
                }
                if (getSupportFragmentManager().isStateSaved()) {
                    // Not in the foreground anymore
                    finish();
                    return;
                }
                BackupRestoreDialogFragment fragment = BackupRestoreDialogFragment.getInstanceWithPref(
                        Collections.singletonList(pair), pair.getUserId());
                fragment.show(getSupportFragmentManager(), BackupRestoreDialogFragment.TAG);
            });
        });
    }

    /**
     * Parse {@code app-manager://backup?id=<package>&user=<user_id>}. Shows a toast and returns
     * {@code null} if it is invalid.
     */
    @Nullable
    private static UserPackagePair parse(@Nullable Uri uri) {
        if (uri == null || !SelfUriManager.APP_MANAGER_SCHEME.equals(uri.getScheme())
                || !BACKUP_HOST.equals(uri.getHost())) {
            Log.w(TAG, "Invalid URI: %s", uri);
            return null;
        }
        String pkg = uri.getQueryParameter("id");
        if (pkg == null || !PackageUtils.validateName(pkg.trim())) {
            UIUtils.displayLongToast(R.string.backup_intent_invalid_package);
            return null;
        }
        pkg = pkg.trim();
        String userIdStr = uri.getQueryParameter("user");
        int userId;
        if (userIdStr == null) {
            userId = UserHandleHidden.myUserId();
        } else {
            userIdStr = userIdStr.trim();
            // Membership in Users.getUsersIds() is checked later, in the background
            if (userIdStr.isEmpty() || userIdStr.length() > 9 || !TextUtils.isDigitsOnly(userIdStr)) {
                UIUtils.displayLongToast(R.string.backup_intent_invalid_user, userIdStr);
                return null;
            }
            userId = Integer.parseInt(userIdStr);
        }
        return new UserPackagePair(pkg, userId);
    }

    @WorkerThread
    private static boolean isInstalledOrBackedUp(@NonNull UserPackagePair pair) {
        try {
            ApplicationInfo info = PackageManagerCompat.getApplicationInfo(pair.getPackageName(),
                    PackageManagerCompat.MATCH_UNINSTALLED_PACKAGES, pair.getUserId());
            if (ApplicationInfoCompat.isInstalled(info)) {
                return true;
            }
        } catch (Throwable th) {
            Log.i(TAG, "%s is not installed in user %d", pair.getPackageName(), pair.getUserId());
        }
        // Read without AppDb's lock, so that a running app database update does not block us
        return !BackupUtils.getBackupMetadataFromDbNoLockValidate(pair.getPackageName()).isEmpty();
    }
}
