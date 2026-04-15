using System.IO;
using System.Net.WebSockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using ClipboardSync.App.Diagnostics;
using ClipboardSync.App.Models;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace ClipboardSync.App.Transport;

public sealed class LanServer : IAsyncDisposable
{
    private readonly X509Certificate2 _certificate;
    private readonly int _port;
    private readonly AppLogStore _logStore;
    private WebApplication? _app;
    private WebSocket? _socket;

    public LanServer(
        X509Certificate2 certificate,
        int port,
        AppLogStore logStore)
    {
        _certificate = certificate;
        _port = port;
        _logStore = logStore;
    }

    public bool HasClient => _socket?.State == WebSocketState.Open;

    public event EventHandler<string>? ConnectionStateChanged;

    public async Task StartAsync(Func<ProtocolEnvelope, Task> handler, CancellationToken cancellationToken)
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            ContentRootPath = AppContext.BaseDirectory
        });

        builder.Logging.ClearProviders();
        builder.WebHost.UseKestrel(options =>
        {
            options.ListenAnyIP(_port, listenOptions =>
            {
                listenOptions.UseHttps(_certificate);
            });
        });

        var app = builder.Build();
        app.UseWebSockets();
        app.Map("/ws", async context =>
        {
            if (!context.WebSockets.IsWebSocketRequest)
            {
                context.Response.StatusCode = StatusCodes.Status400BadRequest;
                return;
            }

            using var socket = await context.WebSockets.AcceptWebSocketAsync();
            _socket = socket;
            ConnectionStateChanged?.Invoke(this, "Connected");
            _logStore.Info("Android client connected to LAN WebSocket");
            await ReceiveLoopAsync(socket, handler, cancellationToken);
            _socket = null;
            ConnectionStateChanged?.Invoke(this, "Disconnected");
            _logStore.Warn("Android client disconnected from LAN WebSocket");
        });

        _app = app;
        await app.StartAsync(cancellationToken);
        ConnectionStateChanged?.Invoke(this, "Listening");
        _logStore.Info($"LAN server listening on port {_port}");
    }

    public async Task SendAsync(ProtocolEnvelope envelope, CancellationToken cancellationToken = default)
    {
        if (_socket?.State != WebSocketState.Open)
        {
            _logStore.Warn($"Skipped sending {envelope.Type} because no WebSocket client is connected");
            return;
        }

        _logStore.Info($"Sending envelope {envelope.Type}");
        var payload = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(envelope, ProtocolJson.Options));
        await _socket.SendAsync(payload, WebSocketMessageType.Text, true, cancellationToken);
    }

    public async Task SendClipboardEventAsync(ClipboardEvent clipboardEvent, byte[]? imageBytes, CancellationToken cancellationToken = default)
    {
        await SendAsync(new ProtocolEnvelope(
            Type: "clipboard_offer",
            TimestampUtc: DateTimeOffset.UtcNow.ToString("O"),
            Event: clipboardEvent), cancellationToken);

        if (clipboardEvent.Image is null || imageBytes is null)
        {
            return;
        }

        const int chunkSize = 32 * 1024;
        var totalChunks = (imageBytes.Length + chunkSize - 1) / chunkSize;
        var transferId = clipboardEvent.Image.TransferId ?? clipboardEvent.EventId;
        var descriptor = new TransferDescriptor(
            TransferId: transferId,
            EventId: clipboardEvent.EventId,
            TotalChunks: totalChunks,
            TotalBytes: imageBytes.Length,
            ChecksumSha256: clipboardEvent.Image.ChecksumSha256);

        await SendAsync(new ProtocolEnvelope("transfer_begin", DateTimeOffset.UtcNow.ToString("O"), Transfer: descriptor), cancellationToken);

        for (var index = 0; index < totalChunks; index++)
        {
            var start = index * chunkSize;
            var end = Math.Min(imageBytes.Length, start + chunkSize);
            var chunk = new TransferChunk(
                TransferId: transferId,
                ChunkIndex: index,
                Base64Payload: Convert.ToBase64String(imageBytes[start..end]));
            await SendAsync(new ProtocolEnvelope("transfer_chunk", DateTimeOffset.UtcNow.ToString("O"), Chunk: chunk), cancellationToken);
        }

        await SendAsync(new ProtocolEnvelope("transfer_complete", DateTimeOffset.UtcNow.ToString("O"), Transfer: descriptor), cancellationToken);
    }

    public async ValueTask DisposeAsync()
    {
        if (_app is not null)
        {
            await _app.StopAsync();
            await _app.DisposeAsync();
        }
    }

    private async Task ReceiveLoopAsync(
        WebSocket socket,
        Func<ProtocolEnvelope, Task> handler,
        CancellationToken cancellationToken)
    {
        var buffer = new byte[64 * 1024];
        while (socket.State == WebSocketState.Open && !cancellationToken.IsCancellationRequested)
        {
            using var stream = new MemoryStream();
            WebSocketReceiveResult result;
            do
            {
                result = await socket.ReceiveAsync(buffer, cancellationToken);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "closed", cancellationToken);
                    return;
                }

                stream.Write(buffer, 0, result.Count);
            }
            while (!result.EndOfMessage);

            if (result.MessageType != WebSocketMessageType.Text)
            {
                continue;
            }

            var json = Encoding.UTF8.GetString(stream.ToArray());
            var envelope = JsonSerializer.Deserialize<ProtocolEnvelope>(json, ProtocolJson.Options);
            if (envelope is not null)
            {
                _logStore.Info($"Received envelope {envelope.Type}");
                await handler(envelope);
            }
        }
    }
}
