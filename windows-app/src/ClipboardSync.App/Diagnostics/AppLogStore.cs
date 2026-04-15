using System.Diagnostics;
using System.IO;
using System.Collections.ObjectModel;

namespace ClipboardSync.App.Diagnostics;

public sealed record LogEntry(string TimestampUtc, string Level, string Message);

public sealed class AppLogStore
{
    private readonly object _fileLock = new();
    private readonly string _logDirectory;

    public AppLogStore()
    {
        _logDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ClipboardSync",
            "logs");
        Directory.CreateDirectory(_logDirectory);
        FilePath = Path.Combine(_logDirectory, "clipboard-sync-dev.log");
        AppendRaw(string.Empty);
        AppendRaw($"===== Session Started {DateTimeOffset.UtcNow:O} | PID {Environment.ProcessId} =====");
    }

    public ObservableCollection<LogEntry> Entries { get; } = [];

    public string FilePath { get; }

    public void Info(string message) => Append("INFO", message);

    public void Warn(string message) => Append("WARN", message);

    public void Error(string message, Exception? exception = null)
    {
        var suffix = exception is null ? string.Empty : $": {exception.Message}";
        Append("ERROR", message + suffix);
    }

    public void Clear()
    {
        Entries.Clear();
        Append("INFO", "Cleared in-memory diagnostics view");
    }

    private void Append(string level, string message)
    {
        var entry = new LogEntry(DateTimeOffset.UtcNow.ToString("O"), level, message);
        AppendRaw($"{entry.TimestampUtc} [{entry.Level}] {entry.Message}");
        Debug.WriteLine($"{entry.TimestampUtc} [{entry.Level}] {entry.Message}");

        var dispatcher = App.Current?.Dispatcher;
        if (dispatcher is null)
        {
            return;
        }

        dispatcher.Invoke(() =>
        {
            Entries.Insert(0, entry);
            while (Entries.Count > 300)
            {
                Entries.RemoveAt(Entries.Count - 1);
            }
        });
    }

    private void AppendRaw(string line)
    {
        lock (_fileLock)
        {
            File.AppendAllText(FilePath, line + Environment.NewLine);
        }
    }
}
