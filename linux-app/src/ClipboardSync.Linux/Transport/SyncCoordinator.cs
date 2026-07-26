using System.Collections.Concurrent;
using System.Collections.ObjectModel;
using System.Security.Cryptography.X509Certificates;
using Avalonia.Threading;
using ClipboardSync.Linux.Clipboard;
using ClipboardSync.Linux.Diagnostics;
using ClipboardSync.Linux.Models;
using ClipboardSync.Linux.Pairing;
using ClipboardSync.Linux.Services;
using ClipboardSync.Linux.Util;

namespace ClipboardSync.Linux.Transport;

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
    private readonly ConcurrentDictionary<string, NormalizedClipboardItem> _recentPayloads = new();
    private readonly ConcurrentDictionary<string, NormalizedClipboardItem> _deferredIncoming = new();
    private readonly object _outboundGate = new();
    private readonly Queue<PendingEvent> _outboundQueue = new();
    private readonly X509Certificate2 _certificate;
    private readonly LanServer _lanServer;
    private readonly LanDiscoveryResponder _lanDiscoveryResponder;

    private ClipboardMonitor? _clipboardMonitor;
    private string _currentChallenge = string.Empty;
    private string _currentSessionId = string.Empty;
    private string _pairedDeviceLabel = "Not paired";
    private string _connectionLabel = "Starting";
    private string _transportLabel = "Wi-Fi / LAN";
    private string _lastItemSummary = "No clipboard item synced yet.";
    private string? _connectedPeerDeviceId;
    private string? _inFlightOutboundEventId;
    private DateTimeOffset _lastLocalClipboardAt = DateTimeOffset.MinValue;
    private DateTimeOffset _lastRemoteImageAppliedAt = DateTimeOffset.MinValue;
    private DateTimeOffset _lastStateChangedAt = DateTimeOffset.MinValue;
    private int _lastRemoteImageByteCount;
    private int _remoteImageGeneration;
    private CancellationTokenSource? _maintainImageCts;
    private readonly SemaphoreSlim _clipboardApplyGate = new(1, 1);
    private byte[]? _lastGoodImageBytes;
    private DateTimeOffset _lastPngAsTextHealAt = DateTimeOffset.MinValue;
    private bool _authenticated;

    public SyncCoordinator(AppLogStore logStore)
    {
        _logStore = logStore;
        _settingsStore = new TrustedDeviceStore(logStore);
        _certificateManager = new CertificateManager(_settingsStore);
        _certificate = _certificateManager.GetOrCreateCertificate();
        _clipboardExtractor = new ClipboardExtractor(_settingsStore.Current.DeviceId);
        WaylandClipboard.Log = message => _logStore.InfoQuiet($"Clipboard: {message}");
        var certificateSha256 = CertificateManager.Sha256ThumbprintHex(_certificate);
        _lanServer = new LanServer(_certificate, _settingsStore.Current.Port, _logStore);
        _lanDiscoveryResponder = new LanDiscoveryResponder(
            _settingsStore,
            _logStore,
            () => CurrentLanAddress,
            certificateSha256);
        _lanServer.ConnectionStateChanged += (_, state) =>
        {
            _connectionLabel = state;
            if (state != "Connected")
            {
                _authenticated = false;
                _connectedPeerDeviceId = null;
            }

            // Only refresh the device list on connect — clearing ListBox on every
            // disconnect redraw made the window look like it was restarting.
            if (state == "Connected")
            {
                RefreshSavedDevices();
            }

            OnStateChanged();
        };
    }

    public ObservableCollection<RecentClipboardItem> RecentItems { get; } = [];

    public ObservableCollection<SavedDeviceItem> SavedDevices { get; } = [];

    public ObservableCollection<LogEntry> LogEntries => _logStore.Entries;

    public string LogFilePath => _logStore.FilePath;

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
            _logStore.Info($"Linux sync enabled set to {value}");
            OnStateChanged();
        }
    }

    public bool RunAtStartup
    {
        get => _settingsStore.Current.RunAtStartup;
        set
        {
            if (_settingsStore.Current.RunAtStartup == value)
            {
                return;
            }

            _settingsStore.Current.RunAtStartup = value;
            _settingsStore.Save();
            StartupRegistration.Apply(value);
            _logStore.Info($"Linux run at startup set to {value}");
            OnStateChanged();
        }
    }

    public bool AcceptNewPairing
    {
        get => _settingsStore.Current.AcceptNewPairing;
        set
        {
            if (_settingsStore.Current.AcceptNewPairing == value)
            {
                return;
            }

            _settingsStore.Current.AcceptNewPairing = value;
            _settingsStore.Save();
            _logStore.Info($"Linux accept new pairing set to {value}");
            OnStateChanged();
        }
    }

    public SyncMode SyncMode
    {
        get => _settingsStore.Current.SyncMode;
        set
        {
            if (_settingsStore.Current.SyncMode == value)
            {
                return;
            }

            _settingsStore.Current.SyncMode = value;
            _settingsStore.Save();
            _logStore.Info($"Linux sync mode set to {value}");
            OnStateChanged();
        }
    }

    public bool AllowTextSync
    {
        get => _settingsStore.Current.AllowTextSync;
        set => SetPolicyValue(_settingsStore.Current.AllowTextSync, value, next => _settingsStore.Current.AllowTextSync = next, "text");
    }

    public bool AllowUrlSync
    {
        get => _settingsStore.Current.AllowUrlSync;
        set => SetPolicyValue(_settingsStore.Current.AllowUrlSync, value, next => _settingsStore.Current.AllowUrlSync = next, "URL");
    }

    public bool AllowImageSync
    {
        get => _settingsStore.Current.AllowImageSync;
        set => SetPolicyValue(_settingsStore.Current.AllowImageSync, value, next => _settingsStore.Current.AllowImageSync = next, "image");
    }

    public int MaxImageSizeMb
    {
        get => _settingsStore.Current.MaxImageSizeMb;
        set
        {
            var next = Math.Clamp(value, 1, 200);
            if (_settingsStore.Current.MaxImageSizeMb == next)
            {
                return;
            }

            _settingsStore.Current.MaxImageSizeMb = next;
            _settingsStore.Save();
            _logStore.Info($"Linux max image size set to {next} MB");
            OnStateChanged();
        }
    }

    public string PairedDeviceLabel => _pairedDeviceLabel;

    public string ConnectionLabel => _connectionLabel;

    public string TransportLabel => _transportLabel;

    public string StatusSummary => $"{ConnectionLabel} on {TransportLabel}";

    public string GuidanceText =>
        "v2 rules: TEXT/URL auto-sync both ways. Images are not auto-sent (use Send clipboard now). Screenshots never auto. Phone background: tap notification Sync. Keep the phone notification on.";

    public string PairingPayload => _settingsStore.BuildPairingPayload(
        CurrentLanAddress,
        CertificateManager.Sha256ThumbprintHex(_certificate));

    public string LastItemSummary => _lastItemSummary;

    public string QueueSummary => $"Queued {_outboundQueue.Count + _pendingByEventId.Count} / Deferred {_deferredIncoming.Count}";

    public event EventHandler? StateChanged;

    public async Task InitializeAsync()
    {
        _logStore.Info("Initializing sync coordinator");
        // Text-oriented poll only: image MIME changes are fingerprinted by type list,
        // never by re-reading PNG bytes (that froze GNOME).
        _clipboardMonitor = new ClipboardMonitor(
            enabled: true,
            log: message => _logStore.Info(message),
            tryHealAsync: TryHealClipboardHistoryImageAsync);
        _clipboardMonitor.ClipboardUpdated += OnClipboardUpdated;
        _connectionLabel = "Listening";
        RefreshSavedDevices();
        ApplyStartupRegistration();
        OnStateChanged();
        await _lanServer.StartAsync(HandleEnvelopeAsync, _cts.Token);
        await _lanDiscoveryResponder.StartAsync(_cts.Token);
        _logStore.Info($"Selected LAN address {CurrentLanAddress} for pairing payloads");
        _logStore.Info("Sync coordinator initialized (type-poll monitor; Super+V image heal)");
    }

    public async Task SendCurrentClipboardNowAsync()
    {
        if (!CanSendOutbound("Manual send"))
        {
            return;
        }

        var normalized = await _clipboardExtractor.ExtractCurrentAsync();
        if (normalized is null)
        {
            _logStore.Info("Manual send ignored because no supported payload was found");
            return;
        }

        await QueueLocalItemAsync(normalized, "manual-send", force: true);
    }

    public async Task ResendRecentAsync(string eventId)
    {
        if (!CanSendOutbound("Resend"))
        {
            return;
        }

        if (!_recentPayloads.TryGetValue(eventId, out var item))
        {
            _logStore.Warn($"Recent item {eventId} is no longer available for resend");
            return;
        }

        var pending = new PendingEvent(item);
        lock (_outboundGate)
        {
            DropSupersededOutbound(item);
            _pendingByEventId[eventId] = pending;
            _outboundQueue.Enqueue(pending);
        }

        await UpdateRecentStatusAsync(eventId, TransferState.QUEUED, "Queued");
        OnStateChanged();
        await FlushQueueAsync();
    }

    public async Task RestoreRecentToClipboardAsync(string eventId)
    {
        if (!_recentPayloads.TryGetValue(eventId, out var item))
        {
            _logStore.Warn($"Recent item {eventId} is no longer available to restore");
            return;
        }

        if (await _clipboardWriter.ApplyRemoteAsync(item.Event, item.ImageBytes))
        {
            _loopGuard.MarkRemoteApplied(item.Event.ContentHashSha256);
            _logStore.Info($"Restored recent item {eventId} to Linux clipboard");
        }
    }

    public async Task ApplyDeferredIncomingAsync(string eventId)
    {
        if (!_deferredIncoming.TryRemove(eventId, out var item))
        {
            _logStore.Warn($"Deferred item {eventId} is no longer available");
            OnStateChanged();
            return;
        }

        if (await _clipboardWriter.ApplyRemoteAsync(item.Event, item.ImageBytes))
        {
            _loopGuard.MarkRemoteApplied(item.Event.ContentHashSha256);
            _loopGuard.RememberSeenEvent(item.Event.EventId);
            await UpdateRecentStatusAsync(eventId, TransferState.ACKED, "Applied");
            _logStore.Info($"Applied deferred item {eventId}");
        }

        OnStateChanged();
    }

    public async Task CopyPairingPayloadToClipboardAsync()
    {
        var payload = PairingPayload;
        try
        {
            await WaylandClipboard.SetTextAsync(payload);
            _loopGuard.MarkRemoteApplied(CryptoUtils.Sha256Hex(payload.Replace("\r\n", "\n")));
            _logStore.Info($"Copied Linux pairing payload to clipboard with host {CurrentLanAddress}");
        }
        catch (Exception exception)
        {
            _logStore.Error("Failed to copy Linux pairing payload to clipboard", exception);
        }
    }

    public void RegeneratePairingCode()
    {
        _settingsStore.RegeneratePairingCode();
        OnStateChanged();
    }

    public void ManualReconnect()
    {
        _connectionLabel = _lanServer.HasClient ? "Connected" : "Listening";
        RefreshSavedDevices();
        OnStateChanged();
    }

    public void SelectSavedDevice(SavedDeviceItem? device)
    {
        if (device is null)
        {
            return;
        }

        _settingsStore.SelectPeer(device.DeviceId);
        _pairedDeviceLabel = device.DisplayName;
        RefreshSavedDevices();
        OnStateChanged();
    }

    public void ClearLogs()
    {
        _logStore.Clear();
    }

    public async ValueTask DisposeAsync()
    {
        _clipboardMonitor?.Dispose();
        try { _maintainImageCts?.Cancel(); } catch { /* ignored */ }
        try { _maintainImageCts?.Dispose(); } catch { /* ignored */ }
        _cts.Cancel();
        await _lanDiscoveryResponder.DisposeAsync();
        await _lanServer.DisposeAsync();
        _cts.Dispose();
        _clipboardApplyGate.Dispose();
    }

    private void OnClipboardUpdated(object? sender, EventArgs args)
    {
        _logStore.Info("Local clipboard change detected");
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
        if (!CanMirrorClipboardChange())
        {
            _logStore.Info($"Clipboard update ignored in {SyncMode} mode");
            return;
        }

        // After a phone image lands, ignore local TEXT/URL echoes for a few seconds so
        // PNG-as-text sibling offers cannot bounce back to the phone (or re-enter as text).
        if (_lastRemoteImageAppliedAt > DateTimeOffset.UtcNow.Subtract(TimeSpan.FromSeconds(6)))
        {
            var peek = await _clipboardExtractor.ExtractCurrentAsync();
            if (peek?.Event.ContentType is ContentType.TEXT or ContentType.URL)
            {
                _logStore.Info("Clipboard update ignored (text right after remote image apply)");
                return;
            }
        }

        var normalized = await _clipboardExtractor.ExtractCurrentAsync();
        if (normalized is null)
        {
            _logStore.Info("Clipboard update ignored because no supported payload was found");
            return;
        }

        if (normalized.Event.ContentType is ContentType.MIXED_UNSUPPORTED)
        {
            _logStore.Info("Clipboard update ignored (unsupported mixed payload)");
            return;
        }

        await QueueLocalItemAsync(normalized, "clipboard-change", force: false);
    }

    private async Task QueueLocalItemAsync(NormalizedClipboardItem normalized, string trigger, bool force)
    {
        if (!CanSendOutbound("Outbound sync"))
        {
            return;
        }

        var rejection = PolicyRejectionReason(normalized);
        if (rejection is not null)
        {
            _logStore.Warn($"Skipped clipboard event {normalized.Event.EventId} from {trigger}: {rejection}");
            await AddRecentAsync(normalized with { Event = normalized.Event with { TransferState = TransferState.FAILED } }, "Linux -> Android", "Filtered");
            return;
        }

        _logStore.Info($"Detected local clipboard event {normalized.Event.EventId} ({normalized.Event.ContentType}) from {trigger}");
        if (_loopGuard.ShouldSuppressLocal(normalized.Event.ContentHashSha256))
        {
            _logStore.Info($"Suppressed clipboard echo for {normalized.Event.EventId}");
            return;
        }
        if (SyncMode == SyncMode.ASK && !force)
        {
            await AddRecentAsync(normalized, "Linux -> Android", "Staged");
            _logStore.Info($"Staged clipboard event {normalized.Event.EventId}; use Send from history to transfer");
            return;
        }

        _lastLocalClipboardAt = DateTimeOffset.UtcNow;
        _loopGuard.RememberSeenEvent(normalized.Event.EventId);
        // Treat our outbound hash as "already applied" so a peer echo cannot bounce back.
        _loopGuard.MarkRemoteApplied(normalized.Event.ContentHashSha256);
        var pending = new PendingEvent(normalized);
        lock (_outboundGate)
        {
            DropSupersededOutbound(normalized);
            _pendingByEventId[normalized.Event.EventId] = pending;
            _outboundQueue.Enqueue(pending);
        }

        await AddRecentAsync(normalized, "Linux -> Android", "Queued");
        await FlushQueueAsync();
    }

    private void DropSupersededOutbound(NormalizedClipboardItem incoming)
    {
        // Only the latest image matters — queued screenshot storms were killing the phone socket.
        if (incoming.Event.ContentType is not ContentType.IMAGE)
        {
            return;
        }

        var kept = new Queue<PendingEvent>();
        while (_outboundQueue.TryDequeue(out var existing))
        {
            if (existing.Item.Event.ContentType is ContentType.IMAGE)
            {
                _pendingByEventId.TryRemove(existing.Item.Event.EventId, out _);
                _logStore.Info($"Dropped superseded queued image {existing.Item.Event.EventId}");
                continue;
            }

            kept.Enqueue(existing);
        }

        while (kept.TryDequeue(out var item))
        {
            _outboundQueue.Enqueue(item);
        }

        if (_inFlightOutboundEventId is not null &&
            _pendingByEventId.TryGetValue(_inFlightOutboundEventId, out var inFlight) &&
            inFlight.Item.Event.ContentType is ContentType.IMAGE &&
            !string.Equals(_inFlightOutboundEventId, incoming.Event.EventId, StringComparison.Ordinal))
        {
            _pendingByEventId.TryRemove(_inFlightOutboundEventId, out _);
            _logStore.Info($"Cancelled in-flight image {_inFlightOutboundEventId}; newer image queued");
            _inFlightOutboundEventId = null;
        }
    }

    private async Task FlushQueueAsync()
    {
        if (!_authenticated || !_lanServer.HasClient || !SyncEnabled)
        {
            lock (_outboundGate)
            {
                if (_outboundQueue.Count > 0)
                {
                    _logStore.Info(
                        $"Holding {_outboundQueue.Count} outbound item(s); waiting for an authenticated Android client");
                }
            }

            return;
        }

        PendingEvent? pending = null;
        lock (_outboundGate)
        {
            if (_inFlightOutboundEventId is not null)
            {
                return;
            }

            if (!_outboundQueue.TryDequeue(out pending))
            {
                return;
            }

            _inFlightOutboundEventId = pending.Item.Event.EventId;
        }

        pending.Attempts++;
        pending.LastAttemptUtc = DateTimeOffset.UtcNow;
        var bytes = pending.Item.ImageBytes?.Length ?? 0;
        _logStore.Info(
            $"Sending outbound {pending.Item.Event.ContentType} {pending.Item.Event.EventId} " +
            $"({bytes} bytes, attempt {pending.Attempts})");
        await _lanServer.SendClipboardEventAsync(
            pending.Item.Event with { TransferState = TransferState.AWAITING_ACK },
            pending.Item.ImageBytes,
            _cts.Token);
        _ = TrackAckTimeoutAsync(pending.Item.Event.EventId, pending.Item.Event.ContentType);
        OnStateChanged();
    }

    private async Task TrackAckTimeoutAsync(string eventId, ContentType contentType)
    {
        var timeout = contentType is ContentType.IMAGE
            ? TimeSpan.FromSeconds(45)
            : TimeSpan.FromSeconds(8);
        try
        {
            await Task.Delay(timeout, _cts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        if (!_pendingByEventId.TryGetValue(eventId, out var pending))
        {
            return;
        }

        lock (_outboundGate)
        {
            if (string.Equals(_inFlightOutboundEventId, eventId, StringComparison.Ordinal))
            {
                _inFlightOutboundEventId = null;
            }
        }

        if (pending.Attempts >= 3)
        {
            await UpdateRecentStatusAsync(eventId, TransferState.FAILED, "Failed");
            _pendingByEventId.TryRemove(eventId, out _);
            _logStore.Warn($"Clipboard event {eventId} failed after retries");
            OnStateChanged();
            await FlushQueueAsync();
            return;
        }

        lock (_outboundGate)
        {
            _outboundQueue.Enqueue(pending);
        }

        _logStore.Warn($"Retrying clipboard event {eventId}");
        OnStateChanged();
        await FlushQueueAsync();
    }

    private async Task CompleteOutboundAsync(string eventId, TransferState state, string status)
    {
        _pendingByEventId.TryRemove(eventId, out _);
        lock (_outboundGate)
        {
            if (string.Equals(_inFlightOutboundEventId, eventId, StringComparison.Ordinal))
            {
                _inFlightOutboundEventId = null;
            }
        }

        await UpdateRecentStatusAsync(eventId, state, status);
        OnStateChanged();
        await FlushQueueAsync();
    }

    private async Task HandleEnvelopeAsync(ProtocolEnvelope envelope)
    {
        if (!string.Equals(envelope.Type, "transfer_chunk", StringComparison.Ordinal))
        {
            _logStore.Info($"Handling envelope {envelope.Type}");
        }

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
                    await CompleteOutboundAsync(
                        envelope.Event.EventId,
                        TransferState.ACKED,
                        envelope.Status ?? "Acked");
                }
                break;

            case "clipboard_reject":
                if (envelope.Event is not null)
                {
                    await CompleteOutboundAsync(
                        envelope.Event.EventId,
                        TransferState.FAILED,
                        envelope.Reason ?? "Rejected");
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
        _connectedPeerDeviceId = claimedDeviceId;
        _settingsStore.RememberPeer(claimedDeviceId, claimedDeviceId);
        RefreshSavedDevices();
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
            await SendClipboardAckAsync(clipboardEvent, "duplicate");
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
            case ContentType.MIXED_UNSUPPORTED:
                await SendClipboardRejectAsync(clipboardEvent, "Unsupported content type");
                break;
        }
    }

    private void HandleTransferBegin(TransferDescriptor descriptor)
    {
        if (_incomingTransfers.TryGetValue(descriptor.TransferId, out var incoming))
        {
            incoming.Descriptor = descriptor;
            _logStore.Info($"Incoming image transfer {descriptor.TransferId} started");
        }
    }

    private void HandleTransferChunk(TransferChunk chunk)
    {
        if (_incomingTransfers.TryGetValue(chunk.TransferId, out var incoming))
        {
            if (!incoming.ReceivedChunkIndexes.Add(chunk.ChunkIndex))
            {
                _logStore.Warn($"Ignored duplicate transfer chunk {chunk.ChunkIndex} for {chunk.TransferId}");
                return;
            }

            try
            {
                incoming.Output.Write(Convert.FromBase64String(chunk.Base64Payload));
            }
            catch (FormatException exception)
            {
                _logStore.Warn($"Ignored malformed transfer chunk {chunk.ChunkIndex} for {chunk.TransferId}: {exception.Message}");
            }
        }
    }

    private async Task HandleTransferCompleteAsync(TransferDescriptor descriptor)
    {
        if (!_incomingTransfers.TryRemove(descriptor.TransferId, out var incoming))
        {
            return;
        }

        var bytes = incoming.Output.ToArray();
        if (incoming.Descriptor is not null &&
            (incoming.ReceivedChunkIndexes.Count != incoming.Descriptor.TotalChunks || bytes.Length != incoming.Descriptor.TotalBytes))
        {
            _logStore.Warn($"Incomplete transfer {descriptor.TransferId}: received {incoming.ReceivedChunkIndexes.Count}/{incoming.Descriptor.TotalChunks} chunks and {bytes.Length}/{incoming.Descriptor.TotalBytes} bytes");
            await SendClipboardRejectAsync(incoming.Event, "Incomplete image transfer");
            return;
        }

        var checksum = CryptoUtils.Sha256Hex(bytes);
        if (!string.Equals(checksum, descriptor.ChecksumSha256, StringComparison.OrdinalIgnoreCase))
        {
            _logStore.Warn($"Checksum mismatch for transfer {descriptor.TransferId}");
            await SendClipboardRejectAsync(incoming.Event, "Image transfer checksum mismatch");
            return;
        }

        await ApplyRemoteEventAsync(incoming.Event, bytes);
    }

    private async Task ApplyRemoteEventAsync(ClipboardEvent clipboardEvent, byte[]? imageBytes)
    {
        if (SyncMode == SyncMode.SEND_ONLY)
        {
            _logStore.Warn($"Rejected incoming clipboard event {clipboardEvent.EventId} because send-only mode is enabled");
            await SendClipboardRejectAsync(clipboardEvent, "Receive disabled");
            return;
        }

        // Already on this side (echo of our outbound, or duplicate offer).
        if (_loopGuard.ShouldSuppressLocal(clipboardEvent.ContentHashSha256))
        {
            _logStore.Info($"Ignoring remote echo for {clipboardEvent.EventId}");
            _loopGuard.RememberSeenEvent(clipboardEvent.EventId);
            await SendClipboardAckAsync(clipboardEvent, "applied");
            return;
        }

        // Hash can differ across platforms for equivalent text; also compare payload.
        if (clipboardEvent.ContentType is ContentType.TEXT or ContentType.URL &&
            !string.IsNullOrEmpty(clipboardEvent.TextPayload))
        {
            var currentText = await WaylandClipboard.GetTextAsync();
            var remoteText = clipboardEvent.TextPayload.Replace("\r\n", "\n");
            if (string.Equals(currentText, remoteText, StringComparison.Ordinal))
            {
                _logStore.Info($"Ignoring remote text already on clipboard for {clipboardEvent.EventId}");
                _loopGuard.MarkRemoteApplied(clipboardEvent.ContentHashSha256);
                _loopGuard.RememberSeenEvent(clipboardEvent.EventId);
                await SendClipboardAckAsync(clipboardEvent, "applied");
                return;
            }
        }

        // Protect a fresh local IMAGE/manual copy briefly. TEXT/URL from the phone always apply
        // (the old 2.5s window was dropping real phone text during reconnect races).
        if (clipboardEvent.ContentType is ContentType.IMAGE &&
            _lastLocalClipboardAt > DateTimeOffset.UtcNow.Subtract(TimeSpan.FromMilliseconds(1500)))
        {
            _logStore.Info(
                $"Skipping remote image {clipboardEvent.EventId}; local clipboard changed recently");
            _loopGuard.RememberSeenEvent(clipboardEvent.EventId);
            await SendClipboardAckAsync(clipboardEvent, "skipped_local_newer");
            return;
        }

        // Never apply oversized / binary "text" — that is how PNG bytes become paste garbage.
        if (clipboardEvent.ContentType is ContentType.TEXT or ContentType.URL)
        {
            var payload = clipboardEvent.TextPayload ?? string.Empty;
            if (WaylandClipboard.IsForbiddenClipboardText(payload) ||
                clipboardEvent.PayloadSizeBytes > 64 * 1024)
            {
                _logStore.Warn(
                    $"Rejecting remote text {clipboardEvent.EventId}: forbidden/oversized " +
                    $"(len={payload.Length}, payloadSize={clipboardEvent.PayloadSizeBytes})");
                _loopGuard.RememberSeenEvent(clipboardEvent.EventId);
                await SendClipboardRejectAsync(clipboardEvent, "Text payload looks like binary/image");
                return;
            }

            // Phone Sync sometimes emits IMAGE then a garbage TEXT sibling (URI / PNG-as-text).
            // Never drop ordinary user text — that broke phone→Linux text after screenshots.
            if (_lastRemoteImageAppliedAt > DateTimeOffset.UtcNow.Subtract(TimeSpan.FromSeconds(4)) &&
                LooksLikePostImageTextSibling(payload))
            {
                var preview = payload.Replace("\r", "\\r").Replace("\n", "\\n");
                if (preview.Length > 40)
                {
                    preview = preview[..40];
                }

                _logStore.Warn(
                    $"Ignoring post-image text sibling {clipboardEvent.EventId} " +
                    $"(len={payload.Length}, preview={preview})");
                _loopGuard.RememberSeenEvent(clipboardEvent.EventId);
                await SendClipboardAckAsync(clipboardEvent, "skipped_after_image");
                return;
            }
        }

        // A leftover test/thumbnail IMAGE must not clobber a fresh full screenshot.
        if (clipboardEvent.ContentType is ContentType.IMAGE &&
            imageBytes is { Length: > 0 } &&
            _lastRemoteImageByteCount >= 50_000 &&
            imageBytes.Length < 4_096 &&
            _lastRemoteImageAppliedAt > DateTimeOffset.UtcNow.Subtract(TimeSpan.FromSeconds(45)))
        {
            _logStore.Warn(
                $"Ignoring tiny remote image {clipboardEvent.EventId} ({imageBytes.Length} bytes) " +
                $"after large image ({_lastRemoteImageByteCount} bytes)");
            _loopGuard.RememberSeenEvent(clipboardEvent.EventId);
            await SendClipboardAckAsync(clipboardEvent, "skipped_tiny_after_large");
            return;
        }

        // Suppress local monitor echo before writing, or the poller will bounce it back.
        _loopGuard.MarkRemoteApplied(clipboardEvent.ContentHashSha256);
        _loopGuard.RememberSeenEvent(clipboardEvent.EventId);

        // Arm the post-image text ignore window before SetPng so a concurrent TEXT
        // sibling from the phone cannot win the race during the write.
        if (clipboardEvent.ContentType is ContentType.IMAGE && imageBytes is { Length: > 0 })
        {
            _lastRemoteImageAppliedAt = DateTimeOffset.UtcNow;
            _clipboardMonitor?.PauseUntil(DateTimeOffset.UtcNow.AddSeconds(25));
        }

        await _clipboardApplyGate.WaitAsync(_cts.Token);
        bool applied;
        try
        {
            applied = await _clipboardWriter.ApplyRemoteAsync(clipboardEvent, imageBytes);
        }
        finally
        {
            _clipboardApplyGate.Release();
        }

        if (!applied)
        {
            _logStore.Warn($"Failed to apply remote clipboard event {clipboardEvent.EventId}");
            await SendClipboardRejectAsync(clipboardEvent, "Linux clipboard was unavailable");
            return;
        }

        if (clipboardEvent.ContentType is ContentType.IMAGE && imageBytes is { Length: > 0 })
        {
            _lastRemoteImageAppliedAt = DateTimeOffset.UtcNow;
            _lastRemoteImageByteCount = imageBytes.Length;
            _lastGoodImageBytes = imageBytes;
            _clipboardMonitor?.PauseUntil(DateTimeOffset.UtcNow.AddSeconds(25));
            var waylandTypes = string.Join(", ", await WaylandClipboard.ListWaylandTypesAsync());
            _logStore.Info($"Clipboard MIME after image apply: {waylandTypes}");
            if (!await WaylandClipboard.ClipboardHasRealImageAsync())
            {
                _logStore.Warn("Wayland clipboard is not a real image/png after apply; retrying SetPng");
                try
                {
                    await WaylandClipboard.SetPngAsync(imageBytes);
                    waylandTypes = string.Join(", ", await WaylandClipboard.ListWaylandTypesAsync());
                    _logStore.Info($"Clipboard MIME after image retry: {waylandTypes}");
                }
                catch (Exception exception)
                {
                    _logStore.Error("Image clipboard retry failed", exception);
                }
            }

            StartMaintainRemoteImage(imageBytes);
        }

        _logStore.Info($"Applied remote clipboard event {clipboardEvent.EventId}");
        await AddRecentAsync(
            new NormalizedClipboardItem(
                clipboardEvent with { TransferState = TransferState.ACKED },
                imageBytes,
                clipboardEvent.TextPayload ?? $"Image {clipboardEvent.Image?.Width}x{clipboardEvent.Image?.Height}",
                FromRemote: true),
            "Android -> Linux",
            "Applied");
        await SendClipboardAckAsync(clipboardEvent, "applied");
    }

    /// <summary>
    /// clipboard-history@alexsaveau.dev (Super+V) only supports text: it captures images with
    /// get_text and restores them with set_text, so paste becomes �PNG…. Never paste from that
    /// history item directly — we rewrite the clipboard back to a real image/png offer.
    /// </summary>
    private async Task TryHealClipboardHistoryImageAsync(CancellationToken cancellationToken)
    {
        // Long cooldown — healing SetPng fights clipboard-history and flickers the dock.
        if (_lastPngAsTextHealAt > DateTimeOffset.UtcNow.Subtract(TimeSpan.FromSeconds(20)))
        {
            return;
        }

        if (await WaylandClipboard.ClipboardHasRealImageAsync(cancellationToken))
        {
            return;
        }

        if (!await WaylandClipboard.ClipboardLooksLikeHistoryImageAsTextAsync(cancellationToken))
        {
            return;
        }

        var recovered = await WaylandClipboard.TryReadPngFromTextMimeAsync(cancellationToken);
        var bytes = recovered is { Length: > 0 } ? recovered : _lastGoodImageBytes;
        if (bytes is not { Length: > 0 })
        {
            return;
        }

        _lastPngAsTextHealAt = DateTimeOffset.UtcNow;
        _logStore.Warn(
            $"Healing Super+V/history PNG-as-text → image/png " +
            $"({bytes.Length} bytes{(recovered is null ? ", restored last good image" : ", recovered from text mime")})");

        _loopGuard.MarkRemoteApplied(CryptoUtils.Sha256Hex(bytes));
        _clipboardMonitor?.PauseUntil(DateTimeOffset.UtcNow.AddSeconds(15));
        await _clipboardApplyGate.WaitAsync(cancellationToken);
        try
        {
            await WaylandClipboard.SetPngAsync(bytes, cancellationToken);
            _lastGoodImageBytes = bytes;
        }
        finally
        {
            _clipboardApplyGate.Release();
        }
    }

    private void StartMaintainRemoteImage(byte[] imageBytes)
    {
        // Cancel any previous repair loop — stacked loops were re-applying an old
        // 64x64 test PNG on top of real phone screenshots.
        try { _maintainImageCts?.Cancel(); } catch { /* ignored */ }
        try { _maintainImageCts?.Dispose(); } catch { /* ignored */ }

        var linked = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token);
        _maintainImageCts = linked;
        var generation = Interlocked.Increment(ref _remoteImageGeneration);
        _ = MaintainRemoteImageAsync(imageBytes, generation, linked.Token);
    }

    /// <summary>
    /// GNOME/Xwayland sometimes replaces a good image/png offer with PNG-as-text/plain
    /// shortly after apply. Re-apply briefly if that happens — only for the latest image.
    /// </summary>
    private async Task MaintainRemoteImageAsync(byte[] imageBytes, int generation, CancellationToken token)
    {
        try
        {
            // Short, quiet repair: only if clipboard became PNG-as-text (not mere type flaps).
            for (var attempt = 0; attempt < 3; attempt++)
            {
                await Task.Delay(2000, token);
                if (generation != _remoteImageGeneration)
                {
                    return;
                }

                if (await WaylandClipboard.ClipboardHasRealImageAsync(token))
                {
                    return;
                }

                if (!await WaylandClipboard.ClipboardLooksLikeHistoryImageAsTextAsync(token))
                {
                    continue;
                }

                _logStore.Warn($"Clipboard became PNG-as-text (repair {attempt + 1}/3); re-applying");
                _loopGuard.MarkRemoteApplied(CryptoUtils.Sha256Hex(imageBytes));
                _clipboardMonitor?.PauseUntil(DateTimeOffset.UtcNow.AddSeconds(10));
                await _clipboardApplyGate.WaitAsync(token);
                try
                {
                    if (generation != _remoteImageGeneration)
                    {
                        return;
                    }

                    await WaylandClipboard.SetPngAsync(imageBytes, token);
                }
                finally
                {
                    _clipboardApplyGate.Release();
                }

                return;
            }
        }
        catch (OperationCanceledException)
        {
            // newer image or shutting down
        }
        catch (Exception exception)
        {
            _logStore.Error("MaintainRemoteImageAsync failed", exception);
        }
    }

    private Task SendClipboardAckAsync(ClipboardEvent clipboardEvent, string status)
    {
        return _lanServer.SendAsync(new ProtocolEnvelope(
            "clipboard_ack",
            DateTimeOffset.UtcNow.ToString("O"),
            Event: clipboardEvent,
            Status: status), _cts.Token);
    }

    private Task SendClipboardRejectAsync(ClipboardEvent clipboardEvent, string reason)
    {
        return _lanServer.SendAsync(new ProtocolEnvelope(
            "clipboard_reject",
            DateTimeOffset.UtcNow.ToString("O"),
            Event: clipboardEvent,
            Reason: reason), _cts.Token);
    }

    private async Task AddRecentAsync(NormalizedClipboardItem item, string direction, string status)
    {
        _recentPayloads[item.Event.EventId] = item;
        await Dispatcher.UIThread.InvokeAsync(() =>
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

    private bool CanSendOutbound(string action)
    {
        if (!SyncEnabled)
        {
            _logStore.Warn($"{action} skipped because sync is disabled");
            return false;
        }
        if (SyncMode == SyncMode.RECEIVE_ONLY)
        {
            _logStore.Warn($"{action} skipped because receive-only mode is enabled");
            return false;
        }
        return true;
    }

    private bool CanMirrorClipboardChange()
    {
        return SyncEnabled && SyncMode == SyncMode.MIRROR;
    }

    private string? PolicyRejectionReason(NormalizedClipboardItem item)
    {
        return item.Event.ContentType switch
        {
            ContentType.TEXT when !AllowTextSync => "text sync is disabled",
            ContentType.URL when !AllowUrlSync => "URL sync is disabled",
            ContentType.IMAGE when !AllowImageSync => "image sync is disabled",
            ContentType.IMAGE when item.Event.PayloadSizeBytes > MaxImageSizeMb * 1024L * 1024L => $"image is larger than {MaxImageSizeMb} MB",
            ContentType.MIXED_UNSUPPORTED => "mixed clipboard content is unsupported",
            _ => null
        };
    }

    /// <summary>
    /// HyperOS/Android often follows an IMAGE offer with a non-text sibling (content URI,
    /// file URI, or PNG bytes decoded as text). Real user strings must not match this.
    /// </summary>
    private static bool LooksLikePostImageTextSibling(string payload)
    {
        if (string.IsNullOrWhiteSpace(payload))
        {
            return true;
        }

        if (WaylandClipboard.IsForbiddenClipboardText(payload) ||
            WaylandClipboard.LooksLikePngAsText(payload) ||
            WaylandClipboard.LooksLikeBinaryText(payload))
        {
            return true;
        }

        var trimmed = payload.Trim();
        if (trimmed.StartsWith("content://", StringComparison.OrdinalIgnoreCase) ||
            trimmed.StartsWith("file://", StringComparison.OrdinalIgnoreCase) ||
            trimmed.StartsWith("file:", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        return false;
    }

    private void ApplyStartupRegistration()
    {
        if (_settingsStore.Current.RunAtStartup)
        {
            if (!StartupRegistration.IsEnabled())
            {
                StartupRegistration.Apply(true);
                _logStore.Info("Applied Linux startup registration");
            }

            return;
        }

        if (StartupRegistration.IsEnabled())
        {
            StartupRegistration.Apply(false);
            _logStore.Info("Removed Linux startup registration");
        }
    }

    private void SetPolicyValue(bool current, bool next, Action<bool> assign, string label)
    {
        if (current == next)
        {
            return;
        }

        assign(next);
        _settingsStore.Save();
        _logStore.Info($"Linux {label} sync set to {next}");
        OnStateChanged();
    }

    private async Task UpdateRecentStatusAsync(string eventId, TransferState transferState, string status)
    {
        await Dispatcher.UIThread.InvokeAsync(() =>
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
        var now = DateTimeOffset.UtcNow;
        if (now - _lastStateChangedAt < TimeSpan.FromMilliseconds(500))
        {
            return;
        }

        _lastStateChangedAt = now;
        StateChanged?.Invoke(this, EventArgs.Empty);
    }

    private void RefreshSavedDevices()
    {
        Dispatcher.UIThread.Post(() =>
        {
            SavedDevices.Clear();
            foreach (var peer in _settingsStore.Current.SavedPeers.OrderBy(peer => peer.DisplayName))
            {
                var connected = peer.DeviceId == _connectedPeerDeviceId && _authenticated;
                SavedDevices.Add(new SavedDeviceItem
                {
                    DeviceId = peer.DeviceId,
                    DisplayName = peer.DisplayName,
                    LastSeenUtc = peer.LastSeenUtc,
                    Selected = peer.DeviceId == _settingsStore.Current.SelectedPeerDeviceId,
                    Available = connected,
                    Connected = connected
                });
            }
        });
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

        public TransferDescriptor? Descriptor { get; set; }

        public HashSet<int> ReceivedChunkIndexes { get; } = [];

        public MemoryStream Output { get; } = new();
    }
}
