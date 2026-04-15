using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Media.Imaging;
using ClipboardSync.App.Models;
using ClipboardSync.App.Util;

namespace ClipboardSync.App.Clipboard;

public sealed class ClipboardExtractor
{
    private readonly string _deviceId;

    public ClipboardExtractor(string deviceId)
    {
        _deviceId = deviceId;
    }

    public async Task<NormalizedClipboardItem?> ExtractCurrentAsync()
    {
        const int maxAttempts = 5;

        for (var attempt = 1; attempt <= maxAttempts; attempt++)
        {
            try
            {
                return await System.Windows.Application.Current.Dispatcher.InvokeAsync(() =>
                {
                    if (System.Windows.Clipboard.ContainsText(System.Windows.TextDataFormat.UnicodeText))
                    {
                        var text = System.Windows.Clipboard.GetText(System.Windows.TextDataFormat.UnicodeText).Replace("\r\n", "\n");
                        if (string.IsNullOrWhiteSpace(text))
                        {
                            return null;
                        }

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
                                PayloadSizeBytes: System.Text.Encoding.UTF8.GetByteCount(text),
                                ContentHashSha256: hash,
                                DedupeKey: $"{_deviceId}:{hash}",
                                TransferState: TransferState.QUEUED,
                                TextPayload: text
                            ),
                            ImageBytes: null,
                            PreviewText: text.Length <= 120 ? text : text[..120]
                        );
                    }

                    if (System.Windows.Clipboard.ContainsImage())
                    {
                        var bitmap = System.Windows.Clipboard.GetImage();
                        if (bitmap is null)
                        {
                            return null;
                        }

                        var bytes = EncodePng(bitmap);
                        var hash = CryptoUtils.Sha256Hex(bytes);
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
                                    Width: bitmap.PixelWidth,
                                    Height: bitmap.PixelHeight,
                                    ByteSize: bytes.Length,
                                    ChecksumSha256: hash,
                                    Encoding: "png",
                                    TransferId: CryptoUtils.UuidV7())
                            ),
                            ImageBytes: bytes,
                            PreviewText: $"Image {bitmap.PixelWidth}x{bitmap.PixelHeight}"
                        );
                    }

                    return null;
                });
            }
            catch (COMException exception) when ((uint)exception.HResult == 0x800401D0 && attempt < maxAttempts)
            {
                await Task.Delay(60);
            }
        }

        return null;
    }

    private static byte[] EncodePng(BitmapSource bitmap)
    {
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }
}
