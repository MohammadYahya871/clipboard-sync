using ClipboardSync.Linux.Models;

namespace ClipboardSync.Linux.Clipboard;

public sealed class ClipboardWriter
{
    public async Task<bool> ApplyRemoteAsync(ClipboardEvent clipboardEvent, byte[]? imageBytes)
    {
        try
        {
            switch (clipboardEvent.ContentType)
            {
                case ContentType.TEXT:
                case ContentType.URL:
                    if (string.IsNullOrWhiteSpace(clipboardEvent.TextPayload))
                    {
                        return false;
                    }

                    if (WaylandClipboard.LooksLikeBinaryText(clipboardEvent.TextPayload))
                    {
                        return false;
                    }

                    await WaylandClipboard.SetTextAsync(clipboardEvent.TextPayload);
                    return true;

                case ContentType.IMAGE:
                    if (imageBytes is null || imageBytes.Length == 0 || !PngUtil.LooksLikePng(imageBytes))
                    {
                        return false;
                    }

                    await WaylandClipboard.SetPngAsync(imageBytes);
                    return true;

                default:
                    return false;
            }
        }
        catch (Exception exception)
        {
            System.Diagnostics.Debug.WriteLine(exception);
            try
            {
                await File.AppendAllTextAsync(
                    Path.Combine(
                        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                        "ClipboardSync",
                        "logs",
                        "clipboard-apply-errors.log"),
                    $"{DateTimeOffset.UtcNow:O} {exception}\n");
            }
            catch
            {
                // ignored
            }

            return false;
        }
    }
}
