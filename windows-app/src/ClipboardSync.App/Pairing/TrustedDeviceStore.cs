using System.IO;
using System.Text;
using System.Text.Json;
using ClipboardSync.App.Diagnostics;
using ClipboardSync.App.Models;
using ClipboardSync.App.Util;

namespace ClipboardSync.App.Pairing;

public sealed class AppSettings
{
    public bool SyncEnabled { get; set; } = true;
    public string DeviceId { get; set; } = CryptoUtils.UuidV7();
    public string DisplayName { get; set; } = Environment.MachineName;
    public string ServiceName { get; set; } = $"{Environment.MachineName}-clipboard-sync";
    public int Port { get; set; } = 43871;
    public string PairingCode { get; set; } = CryptoUtils.RandomBase64(18);
    public PairingPayload? TrustedPeer { get; set; }
    public List<SavedPeer> SavedPeers { get; set; } = [];
    public string? SelectedPeerDeviceId { get; set; }
    public string CertificatePath { get; set; } = "server-cert.pfx";
    public string CertificatePassword { get; set; } = CryptoUtils.RandomBase64(18);
}

public sealed class SavedPeer
{
    public required string DeviceId { get; set; }
    public string DisplayName { get; set; } = "Android device";
    public string LastSeenUtc { get; set; } = DateTimeOffset.UtcNow.ToString("O");
}

public sealed class TrustedDeviceStore
{
    private readonly string _settingsPath;
    private readonly AppLogStore _logStore;

    public TrustedDeviceStore(AppLogStore logStore)
    {
        _logStore = logStore;
        DirectoryPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ClipboardSync");
        Directory.CreateDirectory(DirectoryPath);
        _settingsPath = Path.Combine(DirectoryPath, "settings.json");
        Current = Load();
    }

    public string DirectoryPath { get; }

    public AppSettings Current { get; private set; }

    public void Save()
    {
        var json = JsonSerializer.Serialize(Current, ProtocolJson.Options);
        File.WriteAllText(_settingsPath, json);
    }

    public string BuildPairingPayload(string host, string certificateSha256)
    {
        var payload = new PairingPayload(
            DeviceId: Current.DeviceId,
            DisplayName: Current.DisplayName,
            ServiceName: Current.ServiceName,
            Host: host,
            Port: Current.Port,
            PairingCode: Current.PairingCode,
            CertificateSha256: certificateSha256
        );
        return Convert.ToBase64String(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(payload, ProtocolJson.Options)))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
    }

    public void RegeneratePairingCode()
    {
        Current.PairingCode = CryptoUtils.RandomBase64(18);
        Save();
        _logStore.Warn("Regenerated Windows pairing code");
    }

    public void RememberPeer(string deviceId, string displayName)
    {
        var existing = Current.SavedPeers.FirstOrDefault(peer => peer.DeviceId == deviceId);
        if (existing is null)
        {
            Current.SavedPeers.Add(new SavedPeer
            {
                DeviceId = deviceId,
                DisplayName = displayName,
                LastSeenUtc = DateTimeOffset.UtcNow.ToString("O")
            });
            _logStore.Info($"Saved trusted connection for {displayName} ({deviceId})");
        }
        else
        {
            existing.DisplayName = displayName;
            existing.LastSeenUtc = DateTimeOffset.UtcNow.ToString("O");
        }

        Current.SelectedPeerDeviceId ??= deviceId;
        Save();
    }

    public void SelectPeer(string deviceId)
    {
        if (Current.SavedPeers.All(peer => peer.DeviceId != deviceId))
        {
            return;
        }

        Current.SelectedPeerDeviceId = deviceId;
        Save();
        _logStore.Info($"Selected saved device {deviceId}");
    }

    private AppSettings Load()
    {
        if (!File.Exists(_settingsPath))
        {
            var created = new AppSettings();
            File.WriteAllText(_settingsPath, JsonSerializer.Serialize(created, ProtocolJson.Options));
            return created;
        }

        var raw = File.ReadAllText(_settingsPath);
        return JsonSerializer.Deserialize<AppSettings>(raw, ProtocolJson.Options) ?? new AppSettings();
    }
}
