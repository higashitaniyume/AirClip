namespace AirClip.Platform.Windows.Interop;

/// <summary>Turns top-down straight-alpha BGRA pixels into an HICON via CreateIconIndirect.</summary>
internal static class IconFactory
{
    /// <summary>The caller owns the returned handle and must release it with DestroyIcon.</summary>
    internal static IntPtr CreateIcon(int width, int height, byte[] bgraTopDown)
    {
        if (width <= 0 || height <= 0 || bgraTopDown.Length < width * height * 4)
        {
            return IntPtr.Zero;
        }

        // CreateBitmap wants WORD-aligned scan lines; an all-zero monochrome mask means "fully
        // opaque", which is what the 32bpp colour bitmap's own alpha channel then overrides.
        int maskStride = ((width + 15) / 16) * 2;
        byte[] mask = new byte[maskStride * height];

        IntPtr colorBitmap = NativeMethods.CreateBitmap(width, height, 1, 32, bgraTopDown);
        IntPtr maskBitmap = NativeMethods.CreateBitmap(width, height, 1, 1, mask);

        try
        {
            if (colorBitmap == IntPtr.Zero || maskBitmap == IntPtr.Zero)
            {
                return IntPtr.Zero;
            }

            var info = new NativeMethods.ICONINFO
            {
                fIcon = 1,
                hbmColor = colorBitmap,
                hbmMask = maskBitmap,
            };

            return NativeMethods.CreateIconIndirect(ref info);
        }
        finally
        {
            // CreateIconIndirect copies the bitmaps, so they are ours to release either way.
            if (colorBitmap != IntPtr.Zero)
            {
                NativeMethods.DeleteObject(colorBitmap);
            }

            if (maskBitmap != IntPtr.Zero)
            {
                NativeMethods.DeleteObject(maskBitmap);
            }
        }
    }
}
