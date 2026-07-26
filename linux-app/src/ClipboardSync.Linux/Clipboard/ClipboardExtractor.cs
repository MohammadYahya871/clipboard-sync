using System.Text;
using ClipboardSync.Linux.Models;
using ClipboardSync.Linux.Util;

namespace ClipboardSync.Linux.Clipboard;

public sealed class ClipboardExtractor
{
    private readonly string _deviceId;

    public ClipboardExtractor(string deviceId)
    {
        _deviceId = deviceId;
    }

    public async Task<NormalizedClipboardItem?> ExtractCurrentAsync()
    {
        var types = await WaylandClipboard.ListTypesAsync();
        var hasPng = types.Any(type => type.Equals("image/png", StringComparison.OrdinalIgnoreCase));
        var hasText = types.Any(type =>
            type.StartsWith("text/", StringComparison.OrdinalIgnoreCase) ||
            type.Equals("TEXT", StringComparison.OrdinalIgnoreCase) ||
            type.Equals("STRING", StringComparison.OrdinalIgnoreCase) ||
            type.Equals("UTF8_STRING", StringComparison.OrdinalIgnoreCase));

        // Prefer image/png when present. GNOME/Xwayland often also exposes PNG bytes as
        // text/plain; text-first ordering then treats a screenshot as TEXT and syncs garbage.
        if (hasPng)
        {
            var bytes = await WaylandClipboard.GetPngAsync();
            if (bytes is { Length: > 0 })
            {
                return BuildImageItem(bytes);
            }
        }

        if (hasText || types.Length == 0)
        {
            var text = await WaylandClipboard.GetTextAsync();
            if (!string.IsNullOrWhiteSpace(text) &&
                !WaylandClipboard.LooksLikePngAsText(text) &&
                !WaylandClipboard.LooksLikeBinaryText(text) &&
                !LooksLikeLocalFileUri(text))
            {
                text = text.Replace("\r\n", "\n");
                var hash = CryptoUtils.Sha256Hex(text);
                var type = Uri.TryCreate(text.Trim(), UriKind.Absolute, out var uri) &&
                           (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps)
                    ? ContentType.URL
                    : ContentType.TEXT;

                return new NormalizedClipboardItem(
                    Event: new ClipboardEvent(
                        EventId: CryptoUtils.UuidV7(),
                        SourceDeviceId: _deviceId,
                        OriginatedAtUtc: DateTimeOffset.UtcNow.ToString("O"),
                        ContentType: type,
                        MimeType: "text/plain",
                        PayloadSizeBytes: Encoding.UTF8.GetByteCount(text),
                        ContentHashSha256: hash,
                        DedupeKey: $"{_deviceId}:{hash}",
                        TransferState: TransferState.QUEUED,
                        TextPayload: text
                    ),
                    ImageBytes: null,
                    PreviewText: text.Length <= 120 ? text : text[..120]
                );
            }
        }

        return null;
    }

    private NormalizedClipboardItem BuildImageItem(byte[] bytes)
    {
        var hash = CryptoUtils.Sha256Hex(bytes);
        var (width, height) = PngUtil.ReadSize(bytes);
        return new NormalizedClipboardItem(
            Event: new ClipboardEvent(
                EventId: CryptoUtils.UuidV7(),
                SourceDeviceId: _deviceId,
                OriginatedAtUtc: DateTimeOffset.UtcNow.ToString("O"),
                ContentType: ContentType.IMAGE,
                MimeType: "image/png",
                PayloadSizeBytes: bytes.Length,
                ContentHashSha256: hash,
                DedupeKey: $"{_deviceId}:{hash}",
                TransferState: TransferState.QUEUED,
                Image: new ImageMetadata(
                    Width: width,
                    Height: height,
                    ByteSize: bytes.Length,
                    ChecksumSha256: hash,
                    Encoding: "png",
                    TransferId: CryptoUtils.UuidV7())
            ),
            ImageBytes: bytes,
            PreviewText: $"Image {width}x{height}"
        );
    }

    private static bool LooksLikeLocalFileUri(string text)
    {
        var trimmed = text.Trim();
        return trimmed.StartsWith("file:", StringComparison.OrdinalIgnoreCase) ||
               trimmed.StartsWith("content:", StringComparison.OrdinalIgnoreCase);
    }
}
