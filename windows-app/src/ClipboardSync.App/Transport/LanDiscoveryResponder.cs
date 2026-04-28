using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using ClipboardSync.App.Diagnostics;
using ClipboardSync.App.Models;
using ClipboardSync.App.Pairing;

namespace ClipboardSync.App.Transport;

public sealed class LanDiscoveryResponder : IAsyncDisposable
{
    public const int DiscoveryPort = 43872;

    private readonly TrustedDeviceStore _settingsStore;
    private readonly AppLogStore _logStore;
    private readonly Func<string> _hostProvider;
    private readonly string _certificateSha256;
    private UdpClient? _udpClient;
    private Task? _receiveTask;

    public LanDiscoveryResponder(
        TrustedDeviceStore settingsStore,
        AppLogStore logStore,
        Func<string> hostProvider,
        string certificateSha256)
    {
        _settingsStore = settingsStore;
        _logStore = logStore;
        _hostProvider = hostProvider;
        _certificateSha256 = certificateSha256;
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        _udpClient = new UdpClient(AddressFamily.InterNetwork)
        {
            EnableBroadcast = true
        };
        _udpClient.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udpClient.Client.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));
        _receiveTask = Task.Run(() => ReceiveLoopAsync(cancellationToken), cancellationToken);
        _logStore.Info($"LAN discovery responder listening on UDP {DiscoveryPort}");
        return Task.CompletedTask;
    }

    public async ValueTask DisposeAsync()
    {
        _udpClient?.Dispose();
        if (_receiveTask is not null)
        {
            await _receiveTask.ContinueWith(_ => { }, TaskScheduler.Default);
        }
    }

    private async Task ReceiveLoopAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested && _udpClient is not null)
        {
            UdpReceiveResult result;
            try
            {
                result = await _udpClient.ReceiveAsync(cancellationToken);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (Exception exception)
            {
                _logStore.Error("LAN discovery receive failed", exception);
                continue;
            }

            await HandleRequestAsync(result, cancellationToken);
        }
    }

    private async Task HandleRequestAsync(UdpReceiveResult result, CancellationToken cancellationToken)
    {
        var json = Encoding.UTF8.GetString(result.Buffer);
        DiscoveryMessage? request;
        try
        {
            request = JsonSerializer.Deserialize<DiscoveryMessage>(json, ProtocolJson.Options);
        }
        catch (JsonException)
        {
            return;
        }

        if (request?.Type != DiscoveryMessage.DiscoverType)
        {
            return;
        }

        var response = new DiscoveryMessage(
            Type: DiscoveryMessage.ResponseType,
            DeviceId: _settingsStore.Current.DeviceId,
            DisplayName: _settingsStore.Current.DisplayName,
            ServiceName: _settingsStore.Current.ServiceName,
            Host: _hostProvider(),
            Port: _settingsStore.Current.Port,
            CertificateSha256: _certificateSha256);
        var payload = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(response, ProtocolJson.Options));
        await _udpClient!.SendAsync(payload, result.RemoteEndPoint, cancellationToken);
        _logStore.Info($"Answered LAN discovery probe from {result.RemoteEndPoint}");
    }
}
