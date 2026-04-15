using System.Collections.Concurrent;
using System.Collections.ObjectModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography.X509Certificates;
using System.Windows;
using ClipboardSync.App.Clipboard;
using ClipboardSync.App.Diagnostics;
using ClipboardSync.App.Models;
using ClipboardSync.App.Pairing;
using ClipboardSync.App.Util;

namespace ClipboardSync.App.Transport;

public sealed class SyncCoordinator : IAsyncDisposable
{
    private readonly AppLogStore _logStore;
    private readonly TrustedDeviceStore _settingsStore;
    private readonly CertificateManager _certificateManager;
    private readonly ClipboardExtractor _clipboardExtractor;
    private readonly ClipboardWriter _clipboardWriter = new();
    private readonly LoopGuard _loopGuard = new();
    private readonly CancellationTokenSource _cts = new();
    private readonly ConcurrentDictionary<string, PendingEvent> _pendingByEventId = new();
    private readonly ConcurrentDictionary<string, IncomingTransfer> _incomingTransfers = new();
    private readonly Queue<PendingEvent> _outboundQueue = new();
    private readonly X509Certificate2 _certificate;
    private readonly LanServer _lanServer;

    private ClipboardMonitor? _clipboardMonitor;
    private string _currentChallenge = string.Empty;
    private string _currentSessionId = string.Empty;
    private string _pairedDeviceLabel = "Not paired";
    private string _connectionLabel = "Starting";
    private string _transportLabel = "Wi-Fi / LAN";
    private string _lastItemSummary = "No clipboard item synced yet.";
    private DateTimeOffset _lastLocalClipboardAt = DateTimeOffset.MinValue;
    private bool _authenticated;

    public SyncCoordinator(AppLogStore logStore)
    {
        _logStore = logStore;
        _settingsStore = new TrustedDeviceStore(logStore);
        _certificateManager = new CertificateManager(_settingsStore);
        _certificate = _certificateManager.GetOrCreateCertificate();
        _clipboardExtractor = new ClipboardExtractor(_settingsStore.Current.DeviceId);
        _lanServer = new LanServer(_certificate, _settingsStore.Current.Port, _logStore);
        _lanServer.ConnectionStateChanged += (_, state) =>
        {
            _connectionLabel = state;
            if (state != "Connected")
            {
                _authenticated = false;
            }
            OnStateChanged();
        };
    }

    public ObservableCollection<RecentClipboardItem> RecentItems { get; } = [];

    public ObservableCollection<LogEntry> LogEntries => _logStore.Entries;

    public bool SyncEnabled
    {
        get => _settingsStore.Current.SyncEnabled;
        set
        {
            if (_settingsStore.Current.SyncEnabled == value)
            {
                return;
            }

            _settingsStore.Current.SyncEnabled = value;
            _settingsStore.Save();
            _logStore.Info($"Windows sync enabled set to {value}");
            OnStateChanged();
        }
    }

    public string PairedDeviceLabel => _pairedDeviceLabel;

    public string ConnectionLabel => _connectionLabel;

    public string TransportLabel => _transportLabel;

    public string StatusSummary => $"{ConnectionLabel} on {TransportLabel}";

    public string GuidanceText => "Windows stays available in the tray and can monitor the clipboard continuously. Android outbound clipboard sync remains foreground-limited by the OS.";

    public string PairingPayload => _settingsStore.BuildPairingPayload(
        CurrentLanAddress,
        CertificateManager.Sha256ThumbprintHex(_certificate));

    public string LastItemSummary => _lastItemSummary;

    public event EventHandler? StateChanged;

    public async Task InitializeAsync()
    {
        _logStore.Info("Initializing sync coordinator");
        _clipboardMonitor = new ClipboardMonitor();
        _clipboardMonitor.ClipboardUpdated += OnClipboardUpdated;
        _connectionLabel = "Listening";
        OnStateChanged();
        await _lanServer.StartAsync(HandleEnvelopeAsync, _cts.Token);
        _logStore.Info($"Selected LAN address {CurrentLanAddress} for pairing payloads");
        _logStore.Info("Sync coordinator initialized");
    }

    public void CopyPairingPayloadToClipboard()
    {
        var payload = PairingPayload;
        const int maxAttempts = 5;

        for (var attempt = 1; attempt <= maxAttempts; attempt++)
        {
            try
            {
                System.Windows.Application.Current.Dispatcher.Invoke(() =>
                {
                    System.Windows.Clipboard.SetText(payload);
                });
                _loopGuard.MarkRemoteApplied(CryptoUtils.Sha256Hex(payload.Replace("\r\n", "\n")));
                _logStore.Info($"Copied Windows pairing payload to clipboard with host {CurrentLanAddress}");
                return;
            }
            catch (COMException exception) when ((uint)exception.HResult == 0x800401D0 && attempt < maxAttempts)
            {
                _logStore.Warn($"Clipboard busy while copying pairing payload, retrying attempt {attempt}");
                Thread.Sleep(60);
            }
            catch (Exception exception)
            {
                _logStore.Error("Failed to copy Windows pairing payload to clipboard", exception);
                return;
            }
        }

        _logStore.Warn("Failed to copy Windows pairing payload because the clipboard stayed busy");
    }

    public void RegeneratePairingCode()
    {
        _settingsStore.RegeneratePairingCode();
        OnStateChanged();
    }

    public void ManualReconnect()
    {
        _connectionLabel = _lanServer.HasClient ? "Connected" : "Listening";
        OnStateChanged();
    }

    public void ClearLogs()
    {
        _logStore.Clear();
    }

    public async ValueTask DisposeAsync()
    {
        _clipboardMonitor?.Dispose();
        _cts.Cancel();
        await _lanServer.DisposeAsync();
        _cts.Dispose();
    }

    private void OnClipboardUpdated(object? sender, EventArgs args)
    {
        _ = Task.Run(async () =>
        {
            try
            {
                await HandleLocalClipboardChangedAsync();
            }
            catch (Exception exception)
            {
                _logStore.Error("Unhandled clipboard update processing error", exception);
            }
        });
    }

    private async Task HandleLocalClipboardChangedAsync()
    {
        if (!SyncEnabled)
        {
            _logStore.Info("Clipboard update ignored because sync is disabled");
            return;
        }

        var normalized = await _clipboardExtractor.ExtractCurrentAsync();
        if (normalized is null)
        {
            _logStore.Info("Clipboard update ignored because no supported payload was found");
            return;
        }

        _logStore.Info($"Detected local clipboard event {normalized.Event.EventId} ({normalized.Event.ContentType})");
        if (_loopGuard.ShouldSuppressLocal(normalized.Event.ContentHashSha256))
        {
            _logStore.Info($"Suppressed clipboard echo for {normalized.Event.EventId}");
            return;
        }

        _lastLocalClipboardAt = DateTimeOffset.UtcNow;
        _loopGuard.RememberSeenEvent(normalized.Event.EventId);
        var pending = new PendingEvent(normalized);
        _pendingByEventId[normalized.Event.EventId] = pending;
        _outboundQueue.Enqueue(pending);
        await AddRecentAsync(normalized, "Windows -> Android", "Queued");
        await FlushQueueAsync();
    }

    private async Task FlushQueueAsync()
    {
        if (!_authenticated || !_lanServer.HasClient || !SyncEnabled)
        {
            return;
        }

        while (_outboundQueue.TryDequeue(out var pending))
        {
            pending.Attempts++;
            pending.LastAttemptUtc = DateTimeOffset.UtcNow;
            await _lanServer.SendClipboardEventAsync(
                pending.Item.Event with { TransferState = TransferState.AWAITING_ACK },
                pending.Item.ImageBytes,
                _cts.Token);
            _ = TrackAckTimeoutAsync(pending.Item.Event.EventId);
        }
    }

    private async Task TrackAckTimeoutAsync(string eventId)
    {
        await Task.Delay(TimeSpan.FromSeconds(5), _cts.Token).ContinueWith(_ => { }, TaskScheduler.Default);
        if (!_pendingByEventId.TryGetValue(eventId, out var pending))
        {
            return;
        }

        if (pending.Attempts >= 3)
        {
            await UpdateRecentStatusAsync(eventId, TransferState.FAILED, "Failed");
            _pendingByEventId.TryRemove(eventId, out _);
            _logStore.Warn($"Clipboard event {eventId} failed after retries");
            return;
        }

        _outboundQueue.Enqueue(pending);
        _logStore.Warn($"Retrying clipboard event {eventId}");
        await FlushQueueAsync();
    }

    private async Task HandleEnvelopeAsync(ProtocolEnvelope envelope)
    {
        _logStore.Info($"Handling envelope {envelope.Type}");
        switch (envelope.Type)
        {
            case "hello":
                _currentSessionId = string.IsNullOrWhiteSpace(envelope.SessionId) ? CryptoUtils.UuidV7() : envelope.SessionId!;
                _currentChallenge = CryptoUtils.RandomBase64(18);
                _pairedDeviceLabel = envelope.DeviceId ?? "Android device";
                await _lanServer.SendAsync(new ProtocolEnvelope(
                    Type: "auth_challenge",
                    TimestampUtc: DateTimeOffset.UtcNow.ToString("O"),
                    SessionId: _currentSessionId,
                    Challenge: _currentChallenge), _cts.Token);
                OnStateChanged();
                break;

            case "auth_response":
                await HandleAuthResponseAsync(envelope);
                break;

            case "clipboard_offer":
                if (envelope.Event is not null)
                {
                    await HandleClipboardOfferAsync(envelope.Event);
                }
                break;

            case "transfer_begin":
                if (envelope.Transfer is not null)
                {
                    HandleTransferBegin(envelope.Transfer);
                }
                break;

            case "transfer_chunk":
                if (envelope.Chunk is not null)
                {
                    HandleTransferChunk(envelope.Chunk);
                }
                break;

            case "transfer_complete":
                if (envelope.Transfer is not null)
                {
                    await HandleTransferCompleteAsync(envelope.Transfer);
                }
                break;

            case "clipboard_ack":
                if (envelope.Event is not null)
                {
                    _pendingByEventId.TryRemove(envelope.Event.EventId, out _);
                    await UpdateRecentStatusAsync(envelope.Event.EventId, TransferState.ACKED, envelope.Status ?? "Acked");
                }
                break;

            case "ping":
                await _lanServer.SendAsync(new ProtocolEnvelope("pong", DateTimeOffset.UtcNow.ToString("O")), _cts.Token);
                break;
        }
    }

    private async Task HandleAuthResponseAsync(ProtocolEnvelope envelope)
    {
        var claimedDeviceId = envelope.DeviceId ?? "android-device";
        var expected = CryptoUtils.HmacSha256Base64(
            _settingsStore.Current.PairingCode,
            $"{_currentChallenge}:{_currentSessionId}:{claimedDeviceId}");
        if (!string.Equals(expected, envelope.Response, StringComparison.Ordinal))
        {
            await _lanServer.SendAsync(new ProtocolEnvelope(
                "clipboard_reject",
                DateTimeOffset.UtcNow.ToString("O"),
                Reason: "Authentication failed"), _cts.Token);
            _logStore.Warn("Rejected Android auth response");
            return;
        }

        _authenticated = true;
        _pairedDeviceLabel = claimedDeviceId;
        _connectionLabel = "Connected";
        await _lanServer.SendAsync(new ProtocolEnvelope(
            "peer_status",
            DateTimeOffset.UtcNow.ToString("O"),
            Status: "ready",
            DeviceId: _settingsStore.Current.DeviceId), _cts.Token);
        _logStore.Info($"Authenticated peer {claimedDeviceId}");
        OnStateChanged();
        await FlushQueueAsync();
    }

    private async Task HandleClipboardOfferAsync(ClipboardEvent clipboardEvent)
    {
        if (!SyncEnabled)
        {
            await _lanServer.SendAsync(new ProtocolEnvelope(
                "clipboard_reject",
                DateTimeOffset.UtcNow.ToString("O"),
                Event: clipboardEvent,
                Reason: "Sync disabled"), _cts.Token);
            return;
        }

        if (_loopGuard.HasSeenEvent(clipboardEvent.EventId))
        {
            _logStore.Info($"Ignoring already seen remote event {clipboardEvent.EventId}");
            return;
        }

        _logStore.Info($"Processing remote clipboard offer {clipboardEvent.EventId} ({clipboardEvent.ContentType})");
        switch (clipboardEvent.ContentType)
        {
            case ContentType.TEXT:
            case ContentType.URL:
                await ApplyRemoteEventAsync(clipboardEvent, null);
                break;
            case ContentType.IMAGE:
                var transferId = clipboardEvent.Image?.TransferId ?? clipboardEvent.EventId;
                _incomingTransfers[transferId] = new IncomingTransfer(clipboardEvent, transferId);
                break;
        }
    }

    private void HandleTransferBegin(TransferDescriptor descriptor)
    {
        if (_incomingTransfers.ContainsKey(descriptor.TransferId))
        {
            _logStore.Info($"Incoming image transfer {descriptor.TransferId} started");
        }
    }

    private void HandleTransferChunk(TransferChunk chunk)
    {
        if (_incomingTransfers.TryGetValue(chunk.TransferId, out var incoming))
        {
            incoming.Output.Write(Convert.FromBase64String(chunk.Base64Payload));
        }
    }

    private async Task HandleTransferCompleteAsync(TransferDescriptor descriptor)
    {
        if (!_incomingTransfers.TryRemove(descriptor.TransferId, out var incoming))
        {
            return;
        }

        var bytes = incoming.Output.ToArray();
        var checksum = CryptoUtils.Sha256Hex(bytes);
        if (!string.Equals(checksum, descriptor.ChecksumSha256, StringComparison.OrdinalIgnoreCase))
        {
            _logStore.Warn($"Checksum mismatch for transfer {descriptor.TransferId}");
            return;
        }

        await ApplyRemoteEventAsync(incoming.Event, bytes);
    }

    private async Task ApplyRemoteEventAsync(ClipboardEvent clipboardEvent, byte[]? imageBytes)
    {
        if (_lastLocalClipboardAt > DateTimeOffset.UtcNow.Subtract(TimeSpan.FromMilliseconds(1500)))
        {
            _logStore.Warn($"Deferring remote clipboard event {clipboardEvent.EventId} because a newer local change exists");
            await AddRecentAsync(
                new NormalizedClipboardItem(clipboardEvent with { TransferState = TransferState.DEFERRED }, imageBytes, clipboardEvent.TextPayload ?? "Deferred image", FromRemote: true),
                "Android -> Windows",
                "Deferred");
            await _lanServer.SendAsync(new ProtocolEnvelope(
                "clipboard_ack",
                DateTimeOffset.UtcNow.ToString("O"),
                Event: clipboardEvent,
                Status: "deferred"), _cts.Token);
            return;
        }

        var applied = await _clipboardWriter.ApplyRemoteAsync(clipboardEvent, imageBytes);
        if (!applied)
        {
            _logStore.Warn($"Failed to apply remote clipboard event {clipboardEvent.EventId}");
            return;
        }

        _logStore.Info($"Applied remote clipboard event {clipboardEvent.EventId}");
        _loopGuard.MarkRemoteApplied(clipboardEvent.ContentHashSha256);
        _loopGuard.RememberSeenEvent(clipboardEvent.EventId);
        await AddRecentAsync(
            new NormalizedClipboardItem(
                clipboardEvent with { TransferState = TransferState.ACKED },
                imageBytes,
                clipboardEvent.TextPayload ?? $"Image {clipboardEvent.Image?.Width}x{clipboardEvent.Image?.Height}",
                FromRemote: true),
            "Android -> Windows",
            "Applied");
        await _lanServer.SendAsync(new ProtocolEnvelope(
            "clipboard_ack",
            DateTimeOffset.UtcNow.ToString("O"),
            Event: clipboardEvent,
            Status: "applied"), _cts.Token);
    }

    private async Task AddRecentAsync(NormalizedClipboardItem item, string direction, string status)
    {
        await System.Windows.Application.Current.Dispatcher.InvokeAsync(() =>
        {
            var recent = new RecentClipboardItem
            {
                EventId = item.Event.EventId,
                ContentType = item.Event.ContentType,
                PreviewText = item.PreviewText,
                PreviewUri = item.PreviewUri,
                PayloadSizeBytes = item.Event.PayloadSizeBytes,
                SyncedAtUtc = DateTimeOffset.UtcNow.ToString("O"),
                DirectionLabel = direction,
                TransferState = item.Event.TransferState,
                Status = status
            };
            RecentItems.Insert(0, recent);
            while (RecentItems.Count > 20)
            {
                RecentItems.RemoveAt(RecentItems.Count - 1);
            }
            _lastItemSummary = $"{recent.DirectionLabel}: {recent.PreviewText} ({recent.Status})";
            OnStateChanged();
        });
    }

    private async Task UpdateRecentStatusAsync(string eventId, TransferState transferState, string status)
    {
        await System.Windows.Application.Current.Dispatcher.InvokeAsync(() =>
        {
            var item = RecentItems.FirstOrDefault(entry => entry.EventId == eventId);
            if (item is null)
            {
                return;
            }

            item.TransferState = transferState;
            item.Status = status;
            _lastItemSummary = $"{item.DirectionLabel}: {item.PreviewText} ({item.Status})";
            OnStateChanged();
        });
    }

    private void OnStateChanged()
    {
        StateChanged?.Invoke(this, EventArgs.Empty);
    }

    private string CurrentLanAddress => CertificateManager.GetPreferredLanAddress();

    private sealed class PendingEvent
    {
        public PendingEvent(NormalizedClipboardItem item)
        {
            Item = item;
        }

        public NormalizedClipboardItem Item { get; }

        public int Attempts { get; set; }

        public DateTimeOffset LastAttemptUtc { get; set; }
    }

    private sealed class IncomingTransfer
    {
        public IncomingTransfer(ClipboardEvent @event, string transferId)
        {
            Event = @event;
            TransferId = transferId;
        }

        public ClipboardEvent Event { get; }

        public string TransferId { get; }

        public MemoryStream Output { get; } = new();
    }
}
