using Microsoft.Win32;

namespace ClipboardSync.App.Util;

public static class StartupRegistration
{
    public const string RunValueName = "ClipboardSync";

    private static string RunKeyPath =>
        @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static bool IsEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: false);
        var value = key?.GetValue(RunValueName) as string;
        return !string.IsNullOrWhiteSpace(value);
    }

    public static void Apply(bool enabled, string? executablePath = null)
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true)
            ?? Registry.CurrentUser.CreateSubKey(RunKeyPath, writable: true);

        if (!enabled)
        {
            key.DeleteValue(RunValueName, throwOnMissingValue: false);
            return;
        }

        var path = executablePath ?? Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        key.SetValue(RunValueName, $"\"{path}\"");
    }
}
