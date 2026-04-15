using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Media.Imaging;
using ClipboardSync.App.Models;

namespace ClipboardSync.App.Clipboard;

public sealed class ClipboardWriter
{
    public async Task<bool> ApplyRemoteAsync(ClipboardEvent clipboardEvent, byte[]? imageBytes)
    {
        const int maxAttempts = 5;

        for (var attempt = 1; attempt <= maxAttempts; attempt++)
        {
            try
            {
                return await System.Windows.Application.Current.Dispatcher.InvokeAsync(() =>
                {
                    switch (clipboardEvent.ContentType)
                    {
                        case ContentType.TEXT:
                        case ContentType.URL:
                            if (clipboardEvent.TextPayload is null)
                            {
                                return false;
                            }

                            System.Windows.Clipboard.SetText(clipboardEvent.TextPayload, System.Windows.TextDataFormat.UnicodeText);
                            return true;

                        case ContentType.IMAGE:
                            if (imageBytes is null)
                            {
                                return false;
                            }

                            using (var stream = new MemoryStream(imageBytes))
                            {
                                var decoder = new PngBitmapDecoder(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
                                var frame = decoder.Frames.FirstOrDefault();
                                if (frame is null)
                                {
                                    return false;
                                }

                                var dataObject = new System.Windows.DataObject();
                                dataObject.SetImage(frame);
                                dataObject.SetData("PNG", new MemoryStream(imageBytes));
                                System.Windows.Clipboard.SetDataObject(dataObject, true);
                            }

                            return true;

                        default:
                            return false;
                    }
                });
            }
            catch (COMException exception) when ((uint)exception.HResult == 0x800401D0 && attempt < maxAttempts)
            {
                await Task.Delay(60);
            }
        }

        return false;
    }
}
