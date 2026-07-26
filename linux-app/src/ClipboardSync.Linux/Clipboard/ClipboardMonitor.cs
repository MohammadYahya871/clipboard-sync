using System.Security.Cryptography;
using System.Text;

namespace ClipboardSync.Linux.Clipboard;

/// <summary>
/// Watches clipboard changes via xclip TARGETS polling only.
/// Never calls wl-paste on a timer — that flickers Dash2Dock / can freeze GNOME.
/// Super+V PNG-as-text healing runs at most once per detected transition.
/// </summary>
public sealed class ClipboardMonitor : IDisposable
{
    private readonly CancellationTokenSource _cts = new();
    private readonly object _gate = new();
    private Task? _pollTask;
    private string? _lastFingerprint;
    private DateTimeOffset _lastRaise = DateTimeOffset.MinValue;
    private DateTimeOffset _pausedUntil = DateTimeOffset.MinValue;
    private readonly bool _enabled;
    private readonly Func<CancellationToken, Task>? _tryHealAsync;
    private bool _lastWasPngAsText;

    public ClipboardMonitor(
        bool enabled = true,
        Action<string>? log = null,
        Func<CancellationToken, Task>? tryHealAsync = null)
    {
        _enabled = enabled;
        _tryHealAsync = tryHealAsync;
        if (!_enabled)
        {
            return;
        }

        if (!WaylandClipboard.UsesXclip &&
            !File.Exists("/usr/bin/wl-paste") &&
            string.IsNullOrEmpty(FindOnPath("wl-paste")))
        {
            throw new InvalidOperationException("Neither xclip nor wl-paste is available.");
        }

        log?.Invoke(
            WaylandClipboard.UsesXclip
                ? "Clipboard monitor polling via xclip TARGETS only (no wl-paste)"
                : "Clipboard monitor polling via wl-paste (may flicker on GNOME dock)");
        _pollTask = Task.Run(() => PollLoopAsync(_cts.Token));
    }

    public event EventHandler? ClipboardUpdated;

    /// <summary>Ignore clipboard changes until the given time (e.g. after applying a remote image).</summary>
    public void PauseUntil(DateTimeOffset untilUtc) => _pausedUntil = untilUtc;

    public void Dispose()
    {
        _cts.Cancel();
        _cts.Dispose();
    }

    private async Task PollLoopAsync(CancellationToken cancellationToken)
    {
        _lastFingerprint = await BuildFingerprintAsync(cancellationToken);

        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                // Slow poll: frequent wl-paste/xclip was flickering the dock.
                await Task.Delay(2000, cancellationToken);

                if (DateTimeOffset.UtcNow < _pausedUntil)
                {
                    _lastFingerprint = await BuildFingerprintAsync(cancellationToken) ?? _lastFingerprint;
                    continue;
                }

                var fingerprint = await BuildFingerprintAsync(cancellationToken);
                if (fingerprint is null)
                {
                    continue;
                }

                var isPngAsText = fingerprint.StartsWith("png-as-text:", StringComparison.Ordinal);
                // Heal only on transition into PNG-as-text (e.g. Super+V history select).
                if (isPngAsText && !_lastWasPngAsText && _tryHealAsync is not null)
                {
                    try
                    {
                        await _tryHealAsync(cancellationToken);
                    }
                    catch
                    {
                        // best-effort
                    }
                }

                _lastWasPngAsText = isPngAsText;

                if (fingerprint == _lastFingerprint)
                {
                    continue;
                }

                _lastFingerprint = fingerprint;
                // Do not raise outbound sync for png-as-text garbage.
                if (!isPngAsText)
                {
                    RaiseThrottled();
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch
            {
                // Keep polling through transient clipboard busy errors.
            }
        }
    }

    private async Task<string?> BuildFingerprintAsync(CancellationToken cancellationToken)
    {
        // Prefer xclip TARGETS so we never touch wl-paste in the hot loop.
        var types = await WaylandClipboard.ListTypesForMonitorAsync(cancellationToken);
        if (types.Length == 0)
        {
            return "empty";
        }

        var typesKey = string.Join('|', types.OrderBy(static t => t, StringComparer.OrdinalIgnoreCase));
        var hasPng = types.Any(t => t.Equals("image/png", StringComparison.OrdinalIgnoreCase));
        var hasText = types.Any(t =>
            t.StartsWith("text/", StringComparison.OrdinalIgnoreCase) ||
            t.Equals("UTF8_STRING", StringComparison.OrdinalIgnoreCase) ||
            t.Equals("STRING", StringComparison.OrdinalIgnoreCase) ||
            t.Equals("TEXT", StringComparison.OrdinalIgnoreCase));

        if (hasPng)
        {
            return $"image:{typesKey}";
        }

        if (hasText)
        {
            // Cheap 8-byte peek only (xclip), not a full paste.
            if (await WaylandClipboard.ClipboardLooksLikeHistoryImageAsTextAsync(cancellationToken))
            {
                return $"png-as-text:{typesKey}";
            }

            var text = await WaylandClipboard.GetTextAsync(cancellationToken);
            if (!string.IsNullOrEmpty(text) &&
                !WaylandClipboard.LooksLikePngAsText(text) &&
                !WaylandClipboard.LooksLikeBinaryText(text) &&
                !LooksLikeLocalFileUri(text))
            {
                var textHash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text)));
                return $"text:{textHash}";
            }
        }

        return $"types:{typesKey}";
    }

    private void RaiseThrottled()
    {
        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow;
            if (now - _lastRaise < TimeSpan.FromMilliseconds(800))
            {
                return;
            }

            _lastRaise = now;
        }

        ClipboardUpdated?.Invoke(this, EventArgs.Empty);
    }

    private static bool LooksLikeLocalFileUri(string text)
    {
        var trimmed = text.Trim();
        return trimmed.StartsWith("file:", StringComparison.OrdinalIgnoreCase) ||
               trimmed.StartsWith("content:", StringComparison.OrdinalIgnoreCase);
    }

    private static string? FindOnPath(string name)
    {
        var path = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrWhiteSpace(path))
        {
            return null;
        }

        foreach (var directory in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            var candidate = Path.Combine(directory, name);
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return null;
    }
}
