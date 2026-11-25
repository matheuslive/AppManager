# Changelog (fork)

## 2026-11-24

- **23:10 Usage data without "Usage access":** sizes, data usage and screen time are read through the privileged server (root/ADB) when the feature is disabled; `du` fallback for sizes on the app info page; `storagestats` uses the shell package name in ADB mode.
- **23:10 App database lock:** the updater collects data without holding `AppDb.sLock` and writes in short transactions; sizes/usage are refreshed in a separate pass; concurrent full updates are skipped; backups table replaced atomically.
- **23:10 Docs:** "Fork notes" in the README and this changelog.
