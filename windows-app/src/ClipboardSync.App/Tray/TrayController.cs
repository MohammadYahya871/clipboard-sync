using System.Drawing;
using System.IO;
using Forms = System.Windows.Forms;

namespace ClipboardSync.App.Tray;

public sealed class TrayController : IDisposable
{
    private readonly Forms.NotifyIcon _notifyIcon;
    private readonly Forms.ToolStripMenuItem _syncToggleItem;

    public TrayController()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => OpenRequested?.Invoke(this, EventArgs.Empty));
        menu.Items.Add("Send Clipboard Now", null, (_, _) => SendClipboardRequested?.Invoke(this, EventArgs.Empty));
        _syncToggleItem = new Forms.ToolStripMenuItem("Pause Sync");
        _syncToggleItem.Click += (_, _) => ToggleSyncRequested?.Invoke(this, EventArgs.Empty);
        menu.Items.Add(_syncToggleItem);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => ExitRequested?.Invoke(this, EventArgs.Empty));

        _notifyIcon = new Forms.NotifyIcon
        {
            Text = "Clipboard Sync",
            Icon = LoadTrayIcon(),
            Visible = true,
            ContextMenuStrip = menu
        };
        _notifyIcon.DoubleClick += (_, _) => OpenRequested?.Invoke(this, EventArgs.Empty);
    }

    public event EventHandler? OpenRequested;

    public event EventHandler? SendClipboardRequested;

    public event EventHandler? ToggleSyncRequested;

    public event EventHandler? ExitRequested;

    public void SetSyncEnabled(bool enabled)
    {
        _syncToggleItem.Text = enabled ? "Pause Sync" : "Resume Sync";
    }

    public void Dispose()
    {
        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();
    }

    private static Icon LoadTrayIcon()
    {
        var iconPath = Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico");
        return File.Exists(iconPath)
            ? new Icon(iconPath)
            : SystemIcons.Application;
    }
}
