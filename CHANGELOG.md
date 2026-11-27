# Changelog (fork)

## 2026-11-27

- **16:08 Backup rename fix:** the cleanup after the swap deleted the new metadata/checksum files (`Path#moveTo` retargets the source); tested on the S20: backup without prompt, rename, restore with checksum verification. ([e3a88b5](https://github.com/matheuslive/AppManager/commit/e3a88b514))
- **12:01 Backup names:** with "Back up multiple" the backup is created without the name prompt, named after the current date-time; existing backups can be renamed from the restore dialog (⋮ → Rename), rewriting the v5 metadata and its checksum with the backup's crypto (v4: directory rename); database entry updated. ([c712524](https://github.com/matheuslive/AppManager/commit/c71252450))
- **00:09 Backup/restore entry:** exported `BackupRestoreActivity` for `app-manager://backup?id=<packageName>&user=<userId>` (`VIEW`, `DEFAULT`/`BROWSABLE`) opens the backup/restore dialog directly; finishes when the dialog closes. The dialog now checks the package manager when a package is not in the app database yet, and listens for completion on the application context. ([40158642](https://github.com/matheuslive/AppManager/commit/40158642e), [ac1ba12](https://github.com/matheuslive/AppManager/commit/ac1ba12d9))

## 2026-11-25

- **12:35 Release signing:** release builds are signed with the in-repo dev key, so the fork's release APK can be installed directly. ([4c7448a](https://github.com/matheuslive/AppManager/commit/4c7448a00))
- **12:08 Original logo:** the v1 App Manager logo (gear) is back for the launcher (adaptive, monochrome and legacy icons), TV banner, store/README icons, notifications and the Files launcher icon (folder with the v1 gear). ([30646a7](https://github.com/matheuslive/AppManager/commit/30646a748), [6f285d4](https://github.com/matheuslive/AppManager/commit/6f285d4ea))

## 2026-11-24

- **23:10 Usage data without "Usage access":** sizes, data usage and screen time are read through the privileged server (root/ADB) when the feature is disabled; `du` fallback for sizes on the app info page; `storagestats` uses the shell package name in ADB mode. ([09f4866](https://github.com/matheuslive/AppManager/commit/09f48669a))
- **23:10 App database lock:** the updater collects data without holding `AppDb.sLock` and writes in short transactions; sizes/usage are refreshed in a separate pass; concurrent full updates are skipped; backups table replaced atomically. ([2b18cbe](https://github.com/matheuslive/AppManager/commit/2b18cbe78))
- **23:10 Docs:** "Fork notes" in the README and this changelog. ([edca1cf](https://github.com/matheuslive/AppManager/commit/edca1cf38))
