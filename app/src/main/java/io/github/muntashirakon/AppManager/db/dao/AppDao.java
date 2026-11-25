// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.db.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

import io.github.muntashirakon.AppManager.db.entity.App;

@Dao
public interface AppDao {
    @Query("SELECT * FROM app")
    List<App> getAll();

    @Query("SELECT * FROM app WHERE is_installed = 1")
    List<App> getAllInstalled();

    @Query("SELECT * FROM app WHERE package_name = :packageName")
    List<App> getAll(String packageName);

    @Query("SELECT * FROM app WHERE package_name = :packageName AND user_id = :userId")
    List<App> getAll(String packageName, int userId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(List<App> apps);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(App app);

    @Update
    void update(App app);

    /**
     * Updates only the size and usage columns, so that a concurrent update of the other columns
     * (e.g. after a package change) is never overwritten with stale data.
     */
    @Query("UPDATE app SET code_size = :codeSize, data_size = :dataSize, mobile_data = :mobileData, "
            + "wifi_data = :wifiData, open_count = :openCount, screen_time = :screenTime, "
            + "last_usage_time = :lastUsageTime WHERE package_name = :packageName AND user_id = :userId")
    void updateSizeAndUsage(String packageName, int userId, long codeSize, long dataSize, long mobileData,
                            long wifiData, int openCount, long screenTime, long lastUsageTime);

    @Query("DELETE FROM app WHERE 1")
    void deleteAll();

    @Delete
    void delete(App app);

    @Delete
    void delete(List<App> apps);

    @Query("DELETE FROM app WHERE package_name = :packageName AND user_id = :userId")
    void delete(String packageName, int userId);
}
