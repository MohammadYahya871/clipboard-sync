namespace ClipboardSync.Linux.Clipboard;

public static class PngUtil
{
    public static (int Width, int Height) ReadSize(byte[] png)
    {
        if (png.Length < 24)
        {
            return (0, 0);
        }

        // PNG signature (8) + IHDR length/type (8) + width/height (8)
        var width = (png[16] << 24) | (png[17] << 16) | (png[18] << 8) | png[19];
        var height = (png[20] << 24) | (png[21] << 16) | (png[22] << 8) | png[23];
        return (width, height);
    }

    public static bool LooksLikePng(byte[] bytes)
    {
        return bytes.Length >= 8 &&
               bytes[0] == 0x89 &&
               bytes[1] == 0x50 &&
               bytes[2] == 0x4E &&
               bytes[3] == 0x47 &&
               bytes[4] == 0x0D &&
               bytes[5] == 0x0A &&
               bytes[6] == 0x1A &&
               bytes[7] == 0x0A;
    }
}
