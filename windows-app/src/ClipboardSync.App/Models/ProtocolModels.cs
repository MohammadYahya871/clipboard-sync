using System.Text.Json;
using System.Text.Json.Serialization;
using ClipboardSync.App.Util;

namespace ClipboardSync.App.Models;

public enum ContentType
{
    TEXT,
    URL,
    IMAGE,
    MIXED_UNSUPPORTED
}

public enum TransferState
{
    QUEUED,
    SENDING,
    AWAITING_ACK,
    ACKED,
    FAILED,
    DEFERRED
}

public sealed record ImageMetadata(
    int Width,
    int Height,
    long ByteSize,
    string ChecksumSha256,
    string Encoding,
    string? TransferId = null
);

public sealed record ClipboardEvent(
    string EventId,
    string SourceDeviceId,
    string OriginatedAtUtc,
    ContentType ContentType,
    string MimeType,
    long PayloadSizeBytes,
    string ContentHashSha256,
    string DedupeKey,
    TransferState TransferState,
    string? TextPayload = null,
    ImageMetadata? Image = null
);

public sealed record TransferDescriptor(
    string TransferId,
    string EventId,
    int TotalChunks,
    long TotalBytes,
    string ChecksumSha256
);

public sealed record TransferChunk(
    string TransferId,
    int ChunkIndex,
    string Base64Payload
);

public sealed record ProtocolEnvelope(
    string Type,
    string TimestampUtc,
    string? SessionId = null,
    string? DeviceId = null,
    string? Challenge = null,
    string? Response = null,
    string? Status = null,
    string? Reason = null,
    ClipboardEvent? Event = null,
    TransferDescriptor? Transfer = null,
    TransferChunk? Chunk = null
);

public sealed record PairingPayload(
    string DeviceId,
    string DisplayName,
    string ServiceName,
    string Host,
    int Port,
    string PairingCode,
    string CertificateSha256
);

public sealed record DiscoveryMessage(
    string Type,
    string? DeviceId = null,
    string? DisplayName = null,
    string? ServiceName = null,
    string? Host = null,
    int? Port = null,
    string? CertificateSha256 = null)
{
    public const string DiscoverType = "clipboard_sync_discover";
    public const string ResponseType = "clipboard_sync_device";
}

public sealed record NormalizedClipboardItem(
    ClipboardEvent Event,
    byte[]? ImageBytes,
    string PreviewText,
    string? PreviewUri = null,
    bool FromRemote = false
);

public sealed class RecentClipboardItem
{
    public required string EventId { get; init; }
    public required ContentType ContentType { get; init; }
    public required string PreviewText { get; init; }
    public string? PreviewUri { get; init; }
    public required long PayloadSizeBytes { get; init; }
    public required string SyncedAtUtc { get; init; }
    public required string DirectionLabel { get; init; }
    public required TransferState TransferState { get; set; }
    public required string Status { get; set; }
}

public sealed class SavedDeviceItem : ObservableObject
{
    private bool _selected;
    private bool _available;
    private bool _connected;

    public required string DeviceId { get; init; }
    public required string DisplayName { get; set; }
    public required string LastSeenUtc { get; set; }

    public bool Selected
    {
        get => _selected;
        set => SetProperty(ref _selected, value);
    }

    public bool Available
    {
        get => _available;
        set => SetProperty(ref _available, value);
    }

    public bool Connected
    {
        get => _connected;
        set => SetProperty(ref _connected, value);
    }

    public string Status => Connected ? "Connected" : Available ? "Available" : Selected ? "Selected" : "Saved";
}

public static class ProtocolJson
{
    public static JsonSerializerOptions Options { get; } = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = false,
        Converters =
        {
            new JsonStringEnumConverter()
        }
    };
}
