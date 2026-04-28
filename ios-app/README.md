# Clipboard Sync for iOS

Native SwiftUI iOS client for Clipboard Sync.

This source tree targets iOS 17+ and Xcode 15+. It mirrors the Android app's LAN-first protocol while using iOS equivalents for platform-specific behavior:

- foreground pasteboard sync instead of hidden clipboard monitoring
- Shortcuts/App Intent actions instead of Android Quick Settings tiles
- Share Extension input instead of background clipboard reads
- explicit "latest screenshot" sync instead of Android MediaStore observation

The app interoperates with the existing Windows server and Android protocol without wire-format changes.

## Open In Xcode

Open:

```text
ios-app/ClipboardSyncIOS.xcodeproj
```

Select the `ClipboardSync` scheme and run on an iOS 17+ device. Local network, camera, and photo library permissions are requested only when the related features are used.

Final App Store/TestFlight packaging requires macOS, Xcode signing configuration, and an Apple Developer account.

