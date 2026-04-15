# Test Matrix

## MVP

- Windows text copied, Android app active, text appears in Android clipboard
- Android text copied while app is foreground-active, text appears in Windows clipboard
- Windows image copied, image appears in Android clipboard as app-cached URI
- Android image copied from a content provider, image appears in Windows clipboard as pasteable bitmap
- remote-apply loop suppression prevents resend on both platforms
- LAN disconnect during transfer results in retry and visible diagnostics
- pairing rejection from either side blocks connection
- trusted device persists across app restart

## Follow-up

- BLE small-text fallback after LAN loss
- large image retry / resume
- richer history thumbnails and resend actions

