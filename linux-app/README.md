# Clipboard Sync — Linux (Fedora / Wayland)

Native Linux desktop host that speaks the same LAN protocol as the Windows app and your Android client.

Supports:

- Text and URL sync
- PNG image sync (including GNOME screenshots copied to the clipboard)
- Secure pairing + TLS WebSocket
- Mirror / Manual / Ask / Receive only / Send only modes

## Requirements

- Fedora (or any Linux) with Wayland
- `wl-clipboard` (`wl-copy` / `wl-paste`)
- .NET 8 runtime (or the SDK if building from source)

```bash
sudo dnf install wl-clipboard
```

## Firewall

```bash
sudo firewall-cmd --permanent --add-port=43871/tcp
sudo firewall-cmd --permanent --add-port=43872/udp
sudo firewall-cmd --reload
```

## Run (published build)

```bash
export DOTNET_ROOT="$HOME/.dotnet"
export PATH="$HOME/.dotnet:$PATH"
/home/mohammadyahya/projects/tools/clipboard-sync/linux-app/dist/ClipboardSync.Linux
```

Or from this folder after publish:

```bash
./scripts/run.sh
```

## Pair with Android

1. Start the Linux app (leave **Accept new pairing** on)
2. On Android: **Scan QR**, or **Find nearby** and tap this PC
3. Payload paste remains only as a fallback
4. Keep the Android foreground notification on so copied text/images auto-sync; use **Sync** for screenshots

## Build / publish

```bash
export DOTNET_ROOT="$HOME/.dotnet"
export PATH="$HOME/.dotnet:$PATH"
cd linux-app/src/ClipboardSync.Linux
dotnet publish -c Release -r linux-x64 --self-contained false -o ../../dist
```

## Logs

`~/.local/share/ClipboardSync/logs/clipboard-sync-linux.log`

## Notes

- Uses the same ports as Windows: TCP `43871` (WebSocket) and UDP `43872` (discovery)
- Settings live in `~/.local/share/ClipboardSync/settings.json`
- Image sync prefers `image/png` on the Wayland clipboard so screenshots sync as images
