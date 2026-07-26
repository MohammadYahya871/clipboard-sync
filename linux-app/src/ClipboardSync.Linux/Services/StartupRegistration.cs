namespace ClipboardSync.Linux.Services;

public static class StartupRegistration
{
    private static string AutostartDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "autostart");

    // Basename must match Wayland/X11 app id / WM_CLASS (ClipboardSync.Linux).
    private static string DesktopFilePath =>
        Path.Combine(AutostartDirectory, "ClipboardSync.Linux.desktop");

    private static string LegacyDesktopFilePath =>
        Path.Combine(AutostartDirectory, "clipboard-sync.desktop");

    public static bool IsEnabled() => File.Exists(DesktopFilePath) || File.Exists(LegacyDesktopFilePath);

    public static void Apply(bool enabled)
    {
        // Always remove the legacy mismatched launcher that caused dock flicker.
        TryDelete(LegacyDesktopFilePath);

        if (!enabled)
        {
            TryDelete(DesktopFilePath);
            return;
        }

        Directory.CreateDirectory(AutostartDirectory);
        WriteIfChanged(DesktopFilePath, BuildDesktopEntry(ResolveExecutableCommand(), autostart: true));
    }

    public static void InstallApplicationsEntry()
    {
        var appsDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "applications");
        Directory.CreateDirectory(appsDir);

        TryDelete(Path.Combine(appsDir, "clipboard-sync.desktop"));
        WriteIfChanged(
            Path.Combine(appsDir, "ClipboardSync.Linux.desktop"),
            BuildDesktopEntry(ResolveExecutableCommand(), autostart: false));
    }

    private static void WriteIfChanged(string path, string contents)
    {
        if (File.Exists(path))
        {
            var existing = File.ReadAllText(path);
            if (string.Equals(existing, contents, StringComparison.Ordinal))
            {
                return;
            }
        }

        File.WriteAllText(path, contents);
    }

    private static string BuildDesktopEntry(string exec, bool autostart)
    {
        var extra = autostart
            ? """
              X-GNOME-Autostart-enabled=true
              X-GNOME-Autostart-Delay=3
              """
            : string.Empty;

        return
            $"""
             [Desktop Entry]
             Type=Application
             Name=Clipboard Sync
             Comment=Sync clipboard with your Android phone
             Exec={exec}
             Icon={ResolveIconPath()}
             Terminal=false
             Categories=Utility;Network;
             StartupNotify=false
             StartupWMClass={Program.AppId}
             {extra}
             """;
    }

    private static string ResolveExecutableCommand()
    {
        var processPath = Environment.ProcessPath;
        var dotnetRoot = Environment.GetEnvironmentVariable("DOTNET_ROOT");
        if (string.IsNullOrWhiteSpace(dotnetRoot))
        {
            dotnetRoot = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                ".dotnet");
        }

        if (!string.IsNullOrWhiteSpace(processPath) && File.Exists(processPath))
        {
            // Point Exec at the real binary (not run.sh) so GNOME matches the process to this desktop file.
            return $"env DOTNET_ROOT={Quote(dotnetRoot)} {Quote(processPath)}";
        }

        var runSh = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "scripts", "run.sh"));
        if (File.Exists(runSh))
        {
            return Quote(runSh);
        }

        return "dotnet ClipboardSync.Linux.dll";
    }

    private static string ResolveIconPath()
    {
        var candidates = new[]
        {
            Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.png")),
            Path.GetFullPath(Path.Combine(
                AppContext.BaseDirectory,
                "..",
                "..",
                "..",
                "Assets",
                "AppIcon.png")),
            "/home/mohammadyahya/projects/tools/clipboard-sync/linux-app/src/ClipboardSync.Linux/Assets/AppIcon.png"
        };

        foreach (var candidate in candidates)
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return "clipboard-sync";
    }

    private static string Quote(string value) =>
        value.Contains(' ', StringComparison.Ordinal) ? $"\"{value}\"" : value;

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch
        {
            // ignored
        }
    }
}
