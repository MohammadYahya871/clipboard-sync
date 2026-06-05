# Release Packaging Workspace

This folder is the local packaging workspace used to produce GitHub Release artifacts.

Generated packages are intentionally not committed. Build a release with:

```powershell
.\scripts\package-release.ps1
```

To bump and package in one step:

```powershell
.\scripts\package-release.ps1 -Version 1.2.3 -VersionCode 12
```

The script writes artifacts to:

- `dist/release/vVERSION/ClipboardSync-VERSION-android-debug.apk`
- `dist/release/vVERSION/ClipboardSync-VERSION-windows-x64.zip`
- `dist/release/vVERSION/SHA256SUMS.txt`

## Notes

- `VERSION` is the shared semantic version (`MAJOR.MINOR.PATCH`).
- `VERSION_CODE` is the monotonically increasing Android/iOS build number.
- The Android package is debug-signed for testing.
- The Windows package is a self-contained preview build for `win-x64`.
