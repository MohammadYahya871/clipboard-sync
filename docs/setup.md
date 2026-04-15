# Setup Notes

## Android

1. Open `android-app/` in Android Studio.
2. Confirm `local.properties` points to the Android SDK.
3. Build with:

   ```powershell
   .\gradlew.bat assembleDebug
   ```

4. Install the generated APK from:

   - `android-app/app/build/outputs/apk/debug/app-debug.apk`

## Windows

1. Install the .NET 8 SDK, or use the local SDK placed in `clipboard-sync/.dotnet`.
2. Build with:

   ```powershell
   .\.dotnet\dotnet.exe build windows-app\src\ClipboardSync.App\ClipboardSync.App.csproj
   ```

3. Run with:

   ```powershell
   .\.dotnet\dotnet.exe run --project windows-app\src\ClipboardSync.App\ClipboardSync.App.csproj
   ```

## Pairing flow

1. Start the Windows app.
2. Copy the pairing payload from the Windows UI.
3. Paste it into the Android app and confirm pairing.
4. Keep the Android app visible for outbound Android clipboard detection.

## Current implementation notes

- LAN sync is implemented for text, URLs, and images.
- BLE is scaffolded as an extension point and transport-selection hook, but not yet wired to an active payload path.
- Windows serves a self-signed TLS endpoint and Android pins the certificate fingerprint received during pairing.
- Windows development log file:
  - `%LOCALAPPDATA%\ClipboardSync\logs\clipboard-sync-dev.log`
  - the app appends every session to this file and keeps logging even if you clear the in-app diagnostics list
