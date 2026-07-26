using System.Collections.ObjectModel;
using System.Diagnostics;
using Avalonia.Threading;

namespace ClipboardSync.Linux.Diagnostics;

public sealed record LogEntry(string TimestampUtc, string Level, string Message);

public sealed class AppLogStore
{
    private readonly object _fileLock = new();
    private readonly object _uiGate = new();
    private readonly Queue<LogEntry> _pendingUi = new();
    private readonly string _logDirectory;
    private bool _uiFlushScheduled;
    private DateTimeOffset _lastUiFlush = DateTimeOffset.MinValue;

    public AppLogStore()
    {
        _logDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ClipboardSync",
            "logs");
        Directory.CreateDirectory(_logDirectory);
        FilePath = Path.Combine(_logDirectory, "clipboard-sync-linux.log");
        AppendRaw(string.Empty);
        AppendRaw($"===== Session Started {DateTimeOffset.UtcNow:O} | PID {Environment.ProcessId} =====");
    }

    public ObservableCollection<LogEntry> Entries { get; } = [];

    public string FilePath { get; }

    public void Info(string message) => Append("INFO", message, showInUi: !IsNoisy(message));

    /// <summary>Write to the log file only — never touch the Avalonia list (avoids window flicker).</summary>
    public void InfoQuiet(string message) => Append("INFO", message, showInUi: false);

    public void Warn(string message) => Append("WARN", message, showInUi: true);

    public void Error(string message, Exception? exception = null)
    {
        var suffix = exception is null ? string.Empty : $": {exception.Message}";
        Append("ERROR", message + suffix, showInUi: true);
    }

    public void Clear()
    {
        lock (_uiGate)
        {
            _pendingUi.Clear();
        }

        void ClearUi()
        {
            Entries.Clear();
        }

        if (Dispatcher.UIThread.CheckAccess())
        {
            ClearUi();
        }
        else
        {
            Dispatcher.UIThread.Post(ClearUi);
        }

        Append("INFO", "Cleared in-memory diagnostics view", showInUi: true);
    }

    private static bool IsNoisy(string message) =>
        message.Contains("discovery probe", StringComparison.OrdinalIgnoreCase) ||
        message.Contains("transfer_chunk", StringComparison.OrdinalIgnoreCase) ||
        message.Contains("Answered LAN discovery", StringComparison.OrdinalIgnoreCase) ||
        message.Contains("Sending envelope transfer_chunk", StringComparison.OrdinalIgnoreCase) ||
        message.Contains("Handling envelope transfer_chunk", StringComparison.OrdinalIgnoreCase) ||
        message.Contains("Received envelope transfer_chunk", StringComparison.OrdinalIgnoreCase);

    private void Append(string level, string message, bool showInUi)
    {
        var entry = new LogEntry(DateTimeOffset.UtcNow.ToString("O"), level, message);
        AppendRaw($"{entry.TimestampUtc} [{entry.Level}] {entry.Message}");
        Debug.WriteLine($"{entry.TimestampUtc} [{entry.Level}] {entry.Message}");

        if (!showInUi)
        {
            return;
        }

        lock (_uiGate)
        {
            _pendingUi.Enqueue(entry);
            if (_uiFlushScheduled)
            {
                return;
            }

            _uiFlushScheduled = true;
        }

        // Batch UI inserts so the diagnostics ListBox does not redraw on every UDP probe.
        Dispatcher.UIThread.Post(FlushPendingUi, DispatcherPriority.Background);
    }

    private void FlushPendingUi()
    {
        List<LogEntry> batch;
        lock (_uiGate)
        {
            batch = _pendingUi.ToList();
            _pendingUi.Clear();
        }

        if (batch.Count == 0)
        {
            lock (_uiGate)
            {
                _uiFlushScheduled = false;
            }

            return;
        }

        var now = DateTimeOffset.UtcNow;
        var elapsed = now - _lastUiFlush;
        if (elapsed < TimeSpan.FromMilliseconds(750))
        {
            var delay = TimeSpan.FromMilliseconds(750) - elapsed;
            Dispatcher.UIThread.Post(async () =>
            {
                await Task.Delay(delay);
                FlushPendingUi();
            }, DispatcherPriority.Background);
            lock (_uiGate)
            {
                foreach (var item in batch)
                {
                    _pendingUi.Enqueue(item);
                }
            }

            return;
        }

        _lastUiFlush = now;
        foreach (var entry in batch)
        {
            Entries.Insert(0, entry);
        }

        while (Entries.Count > 60)
        {
            Entries.RemoveAt(Entries.Count - 1);
        }

        lock (_uiGate)
        {
            _uiFlushScheduled = _pendingUi.Count > 0;
            if (_uiFlushScheduled)
            {
                Dispatcher.UIThread.Post(FlushPendingUi, DispatcherPriority.Background);
            }
        }
    }

    private void AppendRaw(string line)
    {
        lock (_fileLock)
        {
            File.AppendAllText(FilePath, line + Environment.NewLine);
        }
    }
}
