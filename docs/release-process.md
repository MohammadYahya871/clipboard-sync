# Release Process

Clipboard Sync uses one shared public app version and one shared build number.

## Version Files

- `VERSION`
  Public semantic version in `MAJOR.MINOR.PATCH` format.
- `VERSION_CODE`
  Monotonically increasing integer used by Android `versionCode` and iOS `CURRENT_PROJECT_VERSION`.

Windows reads `VERSION` through `Directory.Build.props`. Android reads both files from Gradle. iOS project settings are synchronized by `scripts/set-version.ps1`.

## Bump Version

```powershell
.\scripts\set-version.ps1 -Version 1.2.3 -VersionCode 12
```

Use SemVer:

- Increment `PATCH` for bug fixes.
- Increment `MINOR` for compatible features.
- Increment `MAJOR` for breaking protocol, pairing, storage, or user-visible compatibility changes.

Always increase `VERSION_CODE`, even for rebuilds of the same public version.

## Package Release

```powershell
.\scripts\package-release.ps1
```

Or bump and package in one step:

```powershell
.\scripts\package-release.ps1 -Version 1.2.3 -VersionCode 12
```

Artifacts are written to `dist/release/vVERSION/`:

- `ClipboardSync-VERSION-android-debug.apk`
- `ClipboardSync-VERSION-windows-x64.zip`
- `ClipboardSync-VERSION-windows-x64-setup.exe` (requires Inno Setup 6+ on the build machine)
- `SHA256SUMS.txt`

Generated release artifacts are not committed. Upload the files from the versioned release folder to GitHub Releases.
