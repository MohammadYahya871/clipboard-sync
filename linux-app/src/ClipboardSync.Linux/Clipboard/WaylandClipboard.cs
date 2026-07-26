using System.Diagnostics;
using System.Text;

namespace ClipboardSync.Linux.Clipboard;

/// <summary>
/// Clipboard access for GNOME/Wayland.
/// Image writes use a long-lived <c>wl-copy --foreground</c> owner (never xclip text).
/// Reads prefer xclip to avoid Dash2Dock flicker from wl-paste polling.
/// </summary>
public static class WaylandClipboard
{
    private static readonly string Xclip = ResolveXclip();
    private static readonly object ImageOwnerGate = new();
    private static Process? _imageOwner;
    private static string? _imageOwnerFile;

    public static bool UsesXclip { get; } = !string.Equals(Xclip, "wl-fallback", StringComparison.Ordinal);

    /// <summary>Optional logger for refusals / image-owner lifecycle.</summary>
    public static Action<string>? Log { get; set; }

    public static async Task<string[]> ListTypesAsync(CancellationToken cancellationToken = default)
    {
        // Prefer xclip for routine reads — wl-paste --list-types every poll flickers Dash2Dock.
        var xclipTypes = await ListXclipTypesAsync(cancellationToken);
        if (xclipTypes.Length > 0)
        {
            return xclipTypes;
        }

        return await ListWaylandTypesAsync(cancellationToken);
    }

    /// <summary>Monitor hot path: xclip TARGETS only (never wl-paste).</summary>
    public static Task<string[]> ListTypesForMonitorAsync(CancellationToken cancellationToken = default)
    {
        if (UsesXclip)
        {
            return ListXclipTypesAsync(cancellationToken);
        }

        return ListWaylandTypesAsync(cancellationToken);
    }

    private static async Task<string[]> ListXclipTypesAsync(CancellationToken cancellationToken)
    {
        if (!UsesXclip)
        {
            return [];
        }

        var (exitCode, stdout, _) = await RunAsync(
            Xclip,
            ["-selection", "clipboard", "-t", "TARGETS", "-o"],
            cancellationToken);
        if (exitCode != 0 || string.IsNullOrWhiteSpace(stdout))
        {
            return [];
        }

        return stdout
            .Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(static t => !t.Equals("TARGETS", StringComparison.OrdinalIgnoreCase))
            .ToArray();
    }

    public static async Task<string[]> ListWaylandTypesAsync(CancellationToken cancellationToken = default)
    {
        var wlPaste = FindOnPath("wl-paste") ?? "/usr/bin/wl-paste";
        if (!File.Exists(wlPaste) && FindOnPath("wl-paste") is null)
        {
            return [];
        }

        var (exitCode, stdout, _) = await RunAsync(wlPaste, ["--list-types"], cancellationToken);
        if (exitCode != 0 || string.IsNullOrWhiteSpace(stdout))
        {
            return [];
        }

        return stdout.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
    }

    public static async Task<string?> GetTextAsync(CancellationToken cancellationToken = default)
    {
        var types = await ListTypesAsync(cancellationToken);
        // If an image is offered, never read text — GNOME/Xwayland often also exposes
        // the PNG bytes as text/plain, which is what pastes as "�PNG...".
        if (types.Any(t => t.Equals("image/png", StringComparison.OrdinalIgnoreCase)))
        {
            return null;
        }

        if (UsesXclip)
        {
            foreach (var target in new[] { "text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING" })
            {
                var (exitCode, bytes, _) = await RunBytesAsync(
                    Xclip,
                    ["-selection", "clipboard", "-t", target, "-o"],
                    cancellationToken,
                    timeout: TimeSpan.FromMilliseconds(600));
                if (exitCode != 0 || bytes.Length == 0)
                {
                    continue;
                }

                if (PngUtil.LooksLikePng(bytes) || LooksLikeBinary(bytes))
                {
                    continue;
                }

                var text = Encoding.UTF8.GetString(bytes).Replace("\r\n", "\n");
                if (string.IsNullOrWhiteSpace(text) || LooksLikeBinaryText(text))
                {
                    continue;
                }

                return text;
            }

            return null;
        }

        return await GetTextViaWlPasteAsync(cancellationToken);
    }

    public static async Task<byte[]?> GetPngAsync(CancellationToken cancellationToken = default)
    {
        // Prefer wl-paste (source of truth for paste targets).
        var (wlCode, wlBytes, _) = await RunBytesAsync(
            FindOnPath("wl-paste") ?? "wl-paste",
            ["--type", "image/png"],
            cancellationToken,
            timeout: TimeSpan.FromSeconds(8));
        if (wlCode == 0 && wlBytes.Length > 0 && PngUtil.LooksLikePng(wlBytes))
        {
            return wlBytes;
        }

        if (!UsesXclip)
        {
            return null;
        }

        var (exitCode, bytes, _) = await RunBytesAsync(
            Xclip,
            ["-selection", "clipboard", "-t", "image/png", "-o"],
            cancellationToken,
            timeout: TimeSpan.FromSeconds(8));
        if (exitCode != 0 || bytes.Length == 0 || !PngUtil.LooksLikePng(bytes))
        {
            return null;
        }

        return bytes;
    }

    public static async Task SetTextAsync(string text, CancellationToken cancellationToken = default)
    {
        if (IsForbiddenClipboardText(text))
        {
            Log?.Invoke($"Refused SetText (len={text.Length}, looks like binary/PNG/oversized)");
            throw new InvalidOperationException("Refusing to set clipboard text that looks like binary/PNG.");
        }

        var utf8 = Encoding.UTF8.GetBytes(text);
        // Always use wl-copy for text writes. xclip's text/plain;charset=utf-8 path is
        // exactly what produced "PNG bytes as text" on this GNOME setup.
        var wlCopy = FindOnPath("wl-copy") ?? "/usr/bin/wl-copy";
        StopImageOwner();
        // No --clear: it flickers Dash2Dock; a new wl-copy offer replaces the previous one.
        await RunWithStdinAsync(
            wlCopy,
            ["--type", "text/plain;charset=utf-8"],
            utf8,
            cancellationToken,
            timeout: TimeSpan.FromSeconds(8));
    }

    public static async Task SetPngAsync(byte[] pngBytes, CancellationToken cancellationToken = default)
    {
        if (!PngUtil.LooksLikePng(pngBytes))
        {
            throw new InvalidOperationException("Refusing to set clipboard image that is not a PNG.");
        }

        var imageDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ClipboardSync",
            "clipboard-images");
        Directory.CreateDirectory(imageDir);
        CleanupOldImageFiles(imageDir);

        var tempFile = Path.Combine(imageDir, $"{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}-{Guid.NewGuid():N}.png");
        await File.WriteAllBytesAsync(tempFile, pngBytes, cancellationToken);

        var wlCopy = FindOnPath("wl-copy") ?? "/usr/bin/wl-copy";
        if (!File.Exists(wlCopy))
        {
            throw new InvalidOperationException("wl-copy not found; cannot set image/png on Wayland.");
        }

        // Do not wl-copy --clear here — that flickers Dash2Dock. Replacing the owner is enough.
        StartImageOwner(wlCopy, tempFile);

        if (!await WaitForRealImageAsync(TimeSpan.FromSeconds(10), cancellationToken))
        {
            var types = string.Join(", ", await ListWaylandTypesAsync(cancellationToken));
            throw new InvalidOperationException(
                $"Failed to place image/png on the clipboard (types after write: {types}).");
        }

        // Peek only the PNG magic — never pull the full multi‑MB payload into the process
        // (that path has frozen GNOME when combined with the clipboard monitor).
        if (!await PeekPngMagicAsync(cancellationToken))
        {
            throw new InvalidOperationException(
                "wl-copy advertised image/png but paste did not return a PNG signature.");
        }

        Log?.Invoke($"SetPng ok ({pngBytes.Length} bytes) types={string.Join(',', await ListWaylandTypesAsync(cancellationToken))}");
    }

    public static async Task<bool> ClipboardHasRealImageAsync(CancellationToken cancellationToken = default)
    {
        // Use xclip when possible so maintain/heal loops do not call wl-paste on a timer.
        var types = await ListTypesForMonitorAsync(cancellationToken);
        return types.Any(t => t.Equals("image/png", StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>
    /// True when the clipboard offers PNG bytes only as text (the bad paste-as-�PNG state).
    /// Common with Super+V history managers that only call get_text/set_text.
    /// </summary>
    public static async Task<bool> ClipboardIsPngAsTextOnlyAsync(CancellationToken cancellationToken = default)
    {
        var mime = await FindPngAsTextMimeAsync(cancellationToken);
        return mime is not null;
    }

    /// <summary>
    /// True when clipboard-history (Super+V) likely rewrote an image as text — either raw
    /// PNG bytes on a text MIME, or UTF-8-mangled PNG (IHDR + replacement chars).
    /// </summary>
    public static async Task<bool> ClipboardLooksLikeHistoryImageAsTextAsync(
        CancellationToken cancellationToken = default)
    {
        var types = await ListTypesForMonitorAsync(cancellationToken);
        if (types.Length == 0 ||
            types.Any(t => t.Equals("image/png", StringComparison.OrdinalIgnoreCase)))
        {
            return false;
        }

        var hasText = types.Any(t =>
            t.StartsWith("text/", StringComparison.OrdinalIgnoreCase) ||
            t.Equals("UTF8_STRING", StringComparison.OrdinalIgnoreCase) ||
            t.Equals("STRING", StringComparison.OrdinalIgnoreCase) ||
            t.Equals("TEXT", StringComparison.OrdinalIgnoreCase));
        if (!hasText)
        {
            return false;
        }

        // Prefer xclip peeks in the monitor path (no wl-paste).
        var sample = await PeekTextMimePrefixAsync(64, cancellationToken);
        if (sample.Length == 0)
        {
            return false;
        }

        if (PngUtil.LooksLikePng(sample) || LooksLikeBinary(sample))
        {
            return true;
        }

        var asUtf8 = Encoding.UTF8.GetString(sample);
        return asUtf8.Contains("IHDR", StringComparison.Ordinal) &&
               (asUtf8.Contains("PNG", StringComparison.OrdinalIgnoreCase) ||
                asUtf8.Contains('\uFFFD'));
    }

    /// <summary>
    /// If a history manager rewrote a PNG as text/plain, read those bytes back (binary).
    /// </summary>
    public static async Task<byte[]?> TryReadPngFromTextMimeAsync(CancellationToken cancellationToken = default)
    {
        var mime = await FindPngAsTextMimeAsync(cancellationToken);
        if (mime is null)
        {
            return null;
        }

        var wlPaste = FindOnPath("wl-paste") ?? "/usr/bin/wl-paste";
        var (exitCode, bytes, _) = await RunBytesAsync(
            wlPaste,
            ["--type", mime],
            cancellationToken,
            timeout: TimeSpan.FromSeconds(15));
        if (exitCode == 0 && PngUtil.LooksLikePng(bytes))
        {
            return bytes;
        }

        return null;
    }

    private static async Task<string?> FindPngAsTextMimeAsync(CancellationToken cancellationToken)
    {
        var types = await ListTypesForMonitorAsync(cancellationToken);
        if (types.Any(t => t.Equals("image/png", StringComparison.OrdinalIgnoreCase)))
        {
            return null;
        }

        foreach (var mime in new[] { "text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING" })
        {
            if (!types.Any(t => t.Equals(mime, StringComparison.OrdinalIgnoreCase)))
            {
                continue;
            }

            var head = await PeekTextMimePrefixAsync(8, cancellationToken, mime);
            if (PngUtil.LooksLikePng(head))
            {
                return mime;
            }
        }

        return null;
    }

    private static async Task<byte[]> PeekTextMimePrefixAsync(
        int count,
        CancellationToken cancellationToken,
        string? mime = null)
    {
        var types = mime is null ? await ListTypesForMonitorAsync(cancellationToken) : null;
        var mimes = mime is null
            ? new[] { "text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING" }
                .Where(m => types!.Any(t => t.Equals(m, StringComparison.OrdinalIgnoreCase)))
            : [mime];

        foreach (var candidate in mimes)
        {
            if (UsesXclip)
            {
                var (exitCode, bytes, _) = await RunBytesAsync(
                    "/bin/bash",
                    [
                        "-c",
                        "exec \"$1\" -selection clipboard -t \"$2\" -o 2>/dev/null | head -c \"$3\"",
                        "_",
                        Xclip,
                        candidate,
                        count.ToString()
                    ],
                    cancellationToken,
                    timeout: TimeSpan.FromSeconds(2));
                if (exitCode == 0 && bytes.Length > 0)
                {
                    return bytes;
                }

                continue;
            }

            var wlPaste = FindOnPath("wl-paste") ?? "/usr/bin/wl-paste";
            var (wlCode, wlBytes, _) = await RunBytesAsync(
                "/bin/bash",
                [
                    "-c",
                    "exec \"$1\" --type \"$2\" 2>/dev/null | head -c \"$3\"",
                    "_",
                    wlPaste,
                    candidate,
                    count.ToString()
                ],
                cancellationToken,
                timeout: TimeSpan.FromSeconds(2));
            if (wlCode == 0 && wlBytes.Length > 0)
            {
                return wlBytes;
            }
        }

        return [];
    }

    public static bool IsForbiddenClipboardText(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return false;
        }

        // Normal clipboard text is small. Phone screenshots as "text" are hundreds of KB+.
        if (text.Length > 64 * 1024)
        {
            return true;
        }

        var utf8 = Encoding.UTF8.GetBytes(text);
        var latin1 = Encoding.Latin1.GetBytes(text);
        if (PngUtil.LooksLikePng(utf8) || PngUtil.LooksLikePng(latin1))
        {
            return true;
        }

        if (LooksLikeBinaryText(text) || LooksLikeBinary(utf8) || LooksLikeBinary(latin1))
        {
            return true;
        }

        var replacement = 0;
        var sample = Math.Min(text.Length, 4096);
        for (var i = 0; i < sample; i++)
        {
            if (text[i] == '\uFFFD')
            {
                replacement++;
            }
        }

        return replacement > sample / 50;
    }

    public static bool LooksLikePngAsText(string text) => IsForbiddenClipboardText(text) ||
        (!string.IsNullOrEmpty(text) && text.Contains("IHDR", StringComparison.Ordinal) &&
         (text.Contains("PNG", StringComparison.OrdinalIgnoreCase) || text.Contains('\uFFFD')));

    public static bool LooksLikeBinaryText(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return false;
        }

        if (text.Contains('\0'))
        {
            return true;
        }

        if (text.Contains("IHDR", StringComparison.Ordinal) &&
            (text.Contains("PNG", StringComparison.OrdinalIgnoreCase) || text.Contains('\uFFFD')))
        {
            return true;
        }

        if (text.Contains("PNG", StringComparison.Ordinal) &&
            (text.Contains('\uFFFD') ||
             text.Contains("sBIT", StringComparison.Ordinal) ||
             text.Contains("IDAT", StringComparison.Ordinal)))
        {
            return true;
        }

        try
        {
            var prefix = text.Length <= 16 ? text : text[..16];
            if (PngUtil.LooksLikePng(Encoding.Latin1.GetBytes(prefix)))
            {
                return true;
            }
        }
        catch
        {
            // ignored
        }

        return LooksLikeBinary(Encoding.UTF8.GetBytes(text));
    }

    private static void StartImageOwner(string wlCopyPath, string pngFile)
    {
        lock (ImageOwnerGate)
        {
            StopImageOwner_NoLock();

            // Use shell redirect so wl-copy owns a real file-backed stdin. Feeding
            // redirected StandardInput from .NET has been flaky on this GNOME setup.
            var psi = new ProcessStartInfo
            {
                FileName = "/bin/bash",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = false,
                RedirectStandardOutput = false,
                RedirectStandardError = false
            };
            // --foreground keeps this process as the clipboard data source until replaced.
            psi.ArgumentList.Add("-c");
            psi.ArgumentList.Add(
                "exec \"$1\" --type image/png --foreground < \"$2\"");
            psi.ArgumentList.Add("_");
            psi.ArgumentList.Add(wlCopyPath);
            psi.ArgumentList.Add(pngFile);

            var process = Process.Start(psi) ?? throw new InvalidOperationException("Failed to start wl-copy");
            // Give wl-copy a moment to claim the selection before we consider it live.
            Thread.Sleep(80);
            if (process.HasExited)
            {
                throw new InvalidOperationException(
                    $"wl-copy image owner exited immediately (code={process.ExitCode}).");
            }

            _imageOwner = process;
            _imageOwnerFile = pngFile;
            Log?.Invoke($"Started wl-copy image owner pid={process.Id} file={pngFile}");
        }
    }

    private static void StopImageOwner()
    {
        lock (ImageOwnerGate)
        {
            StopImageOwner_NoLock();
        }
    }

    private static void StopImageOwner_NoLock()
    {
        if (_imageOwner is null)
        {
            return;
        }

        try
        {
            if (!_imageOwner.HasExited)
            {
                _imageOwner.Kill(entireProcessTree: false);
            }
        }
        catch
        {
            // ignored
        }
        finally
        {
            try { _imageOwner.Dispose(); } catch { /* ignored */ }
            _imageOwner = null;
        }
    }

    private static async Task<bool> WaitForRealImageAsync(TimeSpan timeout, CancellationToken cancellationToken)
    {
        var deadline = DateTimeOffset.UtcNow + timeout;
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (await ClipboardHasRealImageAsync(cancellationToken))
            {
                return true;
            }

            await Task.Delay(100, cancellationToken);
        }

        return await ClipboardHasRealImageAsync(cancellationToken);
    }

    private static async Task<bool> PeekPngMagicAsync(CancellationToken cancellationToken)
    {
        // bash + head so we never buffer the whole screenshot in this process.
        var wlPaste = FindOnPath("wl-paste") ?? "/usr/bin/wl-paste";
        var (exitCode, bytes, _) = await RunBytesAsync(
            "/bin/bash",
            ["-c", "exec \"$1\" --type image/png 2>/dev/null | head -c 8", "_", wlPaste],
            cancellationToken,
            timeout: TimeSpan.FromSeconds(3));
        return exitCode == 0 && PngUtil.LooksLikePng(bytes);
    }

    private static async Task<string?> GetTextViaWlPasteAsync(CancellationToken cancellationToken)
    {
        var types = await ListWaylandTypesAsync(cancellationToken);
        if (types.Any(t => t.Equals("image/png", StringComparison.OrdinalIgnoreCase)))
        {
            return null;
        }

        foreach (var mime in new[] { "text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT" }
                     .Where(mime => types.Any(t => t.Equals(mime, StringComparison.OrdinalIgnoreCase))))
        {
            var (exitCode, bytes, _) = await RunBytesAsync(
                "wl-paste",
                ["--type", mime, "--no-newline"],
                cancellationToken,
                timeout: TimeSpan.FromMilliseconds(750));
            if (exitCode != 0 || bytes.Length == 0 || PngUtil.LooksLikePng(bytes) || LooksLikeBinary(bytes))
            {
                continue;
            }

            var text = Encoding.UTF8.GetString(bytes).Replace("\r\n", "\n");
            if (!string.IsNullOrWhiteSpace(text) && !LooksLikeBinaryText(text))
            {
                return text;
            }
        }

        return null;
    }

    private static void CleanupOldImageFiles(string imageDir)
    {
        try
        {
            var cutoff = DateTime.UtcNow.AddHours(-2);
            foreach (var file in Directory.EnumerateFiles(imageDir, "*.png"))
            {
                if (_imageOwnerFile is not null &&
                    string.Equals(file, _imageOwnerFile, StringComparison.Ordinal))
                {
                    continue;
                }

                if (File.GetLastWriteTimeUtc(file) < cutoff)
                {
                    try { File.Delete(file); } catch { /* ignored */ }
                }
            }
        }
        catch
        {
            // ignored
        }
    }

    private static string ResolveXclip()
    {
        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "xclip"),
            Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "tools", "xclip")),
            "/home/mohammadyahya/projects/tools/clipboard-sync/linux-app/tools/xclip",
            "/usr/bin/xclip"
        };

        foreach (var candidate in candidates)
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }

        return FindOnPath("xclip") ?? "wl-fallback";
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

    private static bool LooksLikeBinary(byte[] bytes)
    {
        var sample = Math.Min(bytes.Length, 512);
        if (sample == 0)
        {
            return false;
        }

        var control = 0;
        for (var i = 0; i < sample; i++)
        {
            var b = bytes[i];
            if (b == 0)
            {
                return true;
            }

            if (b < 9 || (b > 13 && b < 32))
            {
                control++;
            }
        }

        return control > sample / 10;
    }

    private static async Task<(int ExitCode, string Stdout, string Stderr)> RunAsync(
        string fileName,
        IEnumerable<string> args,
        CancellationToken cancellationToken)
    {
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutCts.CancelAfter(TimeSpan.FromMilliseconds(900));

        var psi = new ProcessStartInfo
        {
            FileName = fileName,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        foreach (var arg in args)
        {
            psi.ArgumentList.Add(arg);
        }

        using var process = Process.Start(psi) ?? throw new InvalidOperationException($"Failed to start {fileName}");
        try
        {
            var stdoutTask = process.StandardOutput.ReadToEndAsync(timeoutCts.Token);
            var stderrTask = process.StandardError.ReadToEndAsync(timeoutCts.Token);
            await process.WaitForExitAsync(timeoutCts.Token);
            return (process.ExitCode, await stdoutTask, await stderrTask);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { /* ignored */ }
            return (-1, string.Empty, "timeout");
        }
    }

    private static async Task<(int ExitCode, byte[] Bytes, string Stderr)> RunBytesAsync(
        string fileName,
        IEnumerable<string> args,
        CancellationToken cancellationToken,
        TimeSpan? timeout = null)
    {
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutCts.CancelAfter(timeout ?? TimeSpan.FromSeconds(3));

        var psi = new ProcessStartInfo
        {
            FileName = fileName,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        foreach (var arg in args)
        {
            psi.ArgumentList.Add(arg);
        }

        using var process = Process.Start(psi) ?? throw new InvalidOperationException($"Failed to start {fileName}");
        try
        {
            await using var memory = new MemoryStream();
            await process.StandardOutput.BaseStream.CopyToAsync(memory, timeoutCts.Token);
            var stderr = await process.StandardError.ReadToEndAsync(timeoutCts.Token);
            await process.WaitForExitAsync(timeoutCts.Token);
            return (process.ExitCode, memory.ToArray(), stderr);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { /* ignored */ }
            return (-1, [], "timeout");
        }
    }

    private static async Task RunWithStdinAsync(
        string fileName,
        IEnumerable<string> args,
        byte[] stdin,
        CancellationToken cancellationToken,
        TimeSpan? timeout = null)
    {
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutCts.CancelAfter(timeout ?? TimeSpan.FromSeconds(8));

        var psi = new ProcessStartInfo
        {
            FileName = fileName,
            RedirectStandardInput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        foreach (var arg in args)
        {
            psi.ArgumentList.Add(arg);
        }

        using var process = Process.Start(psi) ?? throw new InvalidOperationException($"Failed to start {fileName}");
        try
        {
            await process.StandardInput.BaseStream.WriteAsync(stdin, timeoutCts.Token);
            await process.StandardInput.DisposeAsync();
            await process.WaitForExitAsync(timeoutCts.Token);
            if (process.ExitCode != 0)
            {
                var stderr = await process.StandardError.ReadToEndAsync(CancellationToken.None);
                throw new InvalidOperationException($"{fileName} failed: {stderr}");
            }
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { /* ignored */ }
            throw new TimeoutException($"{fileName} timed out writing clipboard data.");
        }
    }
}
