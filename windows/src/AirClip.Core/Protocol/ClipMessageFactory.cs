using AirClip.Core.Clipboard;

namespace AirClip.Core.Protocol;

public static class ClipMessageFactory
{
    public static ClipMessage Create(ClipboardContent content, DeviceIdentity device, TimeProvider? timeProvider = null)
    {
        ArgumentNullException.ThrowIfNull(content);
        ArgumentNullException.ThrowIfNull(device);

        ClipPayload payload = content.Kind switch
        {
            ClipboardContentKind.Text => new ClipPayload
            {
                Content = content.Text!,
                MimeType = ProtocolConstants.TextMimeType,
                Encoding = ProtocolConstants.Utf8Encoding,
            },
            _ => new ClipPayload
            {
                Content = Convert.ToBase64String(content.Image!.Png),
                MimeType = ProtocolConstants.ImageMimeType,
                Encoding = ProtocolConstants.Base64Encoding,
                Width = content.Image.Width,
                Height = content.Image.Height,
            },
        };

        return new ClipMessage
        {
            DeviceId = device.Id,
            DeviceName = device.Name,
            Timestamp = (timeProvider ?? TimeProvider.System).GetUtcNow().ToUnixTimeSeconds(),
            Type = content.Kind == ClipboardContentKind.Text ? ClipMessageType.Text : ClipMessageType.Image,
            Hash = content.Hash,
            Payload = payload,
        };
    }

    public static bool TryReadContent(ClipMessage message, out ClipboardContent? content, out string? error)
    {
        ArgumentNullException.ThrowIfNull(message);
        content = null;
        error = null;

        if (message.Payload is null)
        {
            error = "payload is missing";
            return false;
        }

        switch (message.Type)
        {
            case ClipMessageType.Text:
                ClipboardContent text = ClipboardContent.FromText(message.Payload.Content);
                if (!string.IsNullOrEmpty(message.Hash) && !string.Equals(text.Hash, message.Hash, StringComparison.OrdinalIgnoreCase))
                {
                    error = "text hash mismatch";
                    return false;
                }

                content = text;
                return true;

            case ClipMessageType.Image:
                if (string.IsNullOrEmpty(message.Hash))
                {
                    error = "image hash is required";
                    return false;
                }

                if (message.Payload.Width is not > 0 || message.Payload.Height is not > 0)
                {
                    error = "image dimensions are missing";
                    return false;
                }

                byte[] png;
                try
                {
                    png = Convert.FromBase64String(message.Payload.Content);
                }
                catch (FormatException)
                {
                    error = "image payload is not valid base64";
                    return false;
                }

                content = ClipboardContent.FromImage(
                    new ClipboardImage(message.Payload.Width.Value, message.Payload.Height.Value, png, message.Hash));
                return true;

            default:
                error = $"{message.Type} carries no clipboard content";
                return false;
        }
    }
}
