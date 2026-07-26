using System;
using Avalonia;
using Avalonia.X11;
using ClipboardSync.Linux.Services;

namespace ClipboardSync.Linux;

internal static class Program
{
    // Must match desktop file basename (ClipboardSync.Linux.desktop) for GNOME dock grouping.
    public const string AppId = "ClipboardSync.Linux";

    private static SingleInstanceGuard? _instance;

    [STAThread]
    public static int Main(string[] args)
    {
        _instance = SingleInstanceGuard.TryAcquire();
        if (_instance is null)
        {
            // Activate the already-running window; do not open a second Avalonia lifetime
            // (that is what makes a dock icon appear and vanish).
            if (SingleInstanceGuard.TryActivateExisting())
            {
                return 0;
            }

            Console.Error.WriteLine("Clipboard Sync is already running.");
            return 1;
        }

        try
        {
            BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
            return 0;
        }
        finally
        {
            _instance.Dispose();
            _instance = null;
        }
    }

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .With(new X11PlatformOptions
            {
                WmClass = AppId,
                // Avoid extra X11 helper windows that some docks briefly surface.
                EnableInputFocusProxy = false
            })
            .WithInterFont()
            .LogToTrace();

    internal static SingleInstanceGuard? Instance => _instance;
}
