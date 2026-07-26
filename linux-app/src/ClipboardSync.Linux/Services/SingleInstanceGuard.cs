using System.Net.Sockets;
using System.Text;

namespace ClipboardSync.Linux.Services;

/// <summary>
/// Named mutex + Unix socket so a second launch activates the existing window
/// instead of flashing a short-lived dock icon and exiting.
/// </summary>
public sealed class SingleInstanceGuard : IDisposable
{
    // Global\ so Cursor/agent shells and the GNOME session share one lock.
    public const string MutexName = @"Global\ClipboardSync.Linux.SingleInstance";

    private readonly Mutex _mutex;
    private CancellationTokenSource? _serverCts;
    private Socket? _listener;

    private SingleInstanceGuard(Mutex mutex)
    {
        _mutex = mutex;
    }

    public static string SocketPath =>
        Path.Combine(
            Environment.GetEnvironmentVariable("XDG_RUNTIME_DIR")
            ?? Path.GetTempPath(),
            "clipboard-sync.sock");

    public static SingleInstanceGuard? TryAcquire()
    {
        var mutex = new Mutex(initiallyOwned: true, name: MutexName, out var createdNew);
        if (!createdNew)
        {
            mutex.Dispose();
            return null;
        }

        return new SingleInstanceGuard(mutex);
    }

    /// <summary>Ask the running instance to focus its window. Returns true if signaled.</summary>
    public static bool TryActivateExisting()
    {
        try
        {
            using var client = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
            client.Connect(new UnixDomainSocketEndPoint(SocketPath));
            client.Send(Encoding.UTF8.GetBytes("activate\n"));
            return true;
        }
        catch
        {
            return false;
        }
    }

    public void StartActivationServer(Action onActivate)
    {
        try
        {
            if (File.Exists(SocketPath))
            {
                File.Delete(SocketPath);
            }
        }
        catch
        {
            // ignored
        }

        _listener = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
        _listener.Bind(new UnixDomainSocketEndPoint(SocketPath));
        _listener.Listen(2);
        _serverCts = new CancellationTokenSource();
        var token = _serverCts.Token;
        _ = Task.Run(() => AcceptLoopAsync(onActivate, token), token);
    }

    private async Task AcceptLoopAsync(Action onActivate, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested && _listener is not null)
        {
            try
            {
                using var client = await _listener.AcceptAsync(cancellationToken);
                var buffer = new byte[64];
                _ = await client.ReceiveAsync(buffer, cancellationToken);
                onActivate();
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch
            {
                // Keep listening; transient peer disconnects are fine.
            }
        }
    }

    public void Dispose()
    {
        try
        {
            _serverCts?.Cancel();
        }
        catch
        {
            // ignored
        }

        try
        {
            _listener?.Dispose();
        }
        catch
        {
            // ignored
        }

        try
        {
            if (File.Exists(SocketPath))
            {
                File.Delete(SocketPath);
            }
        }
        catch
        {
            // ignored
        }

        try
        {
            _mutex.ReleaseMutex();
        }
        catch
        {
            // ignored
        }

        _mutex.Dispose();
        _serverCts?.Dispose();
    }
}
