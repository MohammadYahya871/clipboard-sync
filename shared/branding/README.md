# Branding Assets

The application icon and notification glyph are based on the web-hosted Microsoft Fluent System Icons asset:

- Asset: `Clipboard Arrow Right`
- Source repository: <https://github.com/microsoft/fluentui-system-icons>
- Asset URL: <https://raw.githubusercontent.com/microsoft/fluentui-system-icons/main/assets/Clipboard%20Arrow%20Right/SVG/ic_fluent_clipboard_arrow_right_24_filled.svg>
- License: MIT

Files in this folder:

- `ic_fluent_clipboard_arrow_right_24_filled.svg`
  The original downloaded source asset.
- `clipboard-arrow-right-metadata.json`
  The source metadata file from the Fluent icon repo.
- `LICENSE-fluentui-system-icons.txt`
  The upstream MIT license text copied from the source repo.
- `app-icon-preview.png`
  Generated preview image used in the repository README.
- `generate_branding_assets.py`
  Regenerates the Android launcher/notification assets and the Windows `.ico` from the downloaded source asset.

To regenerate app branding after changing the source icon:

```powershell
py -3 D:\work\tools\clipboard-sync\shared\branding\generate_branding_assets.py
```
