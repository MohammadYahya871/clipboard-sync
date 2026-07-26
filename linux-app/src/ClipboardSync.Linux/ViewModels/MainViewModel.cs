using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Threading;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using ClipboardSync.Linux.Diagnostics;
using ClipboardSync.Linux.Models;
using ClipboardSync.Linux.Transport;
using QRCoder;

namespace ClipboardSync.Linux.ViewModels;

public partial class MainViewModel : ViewModelBase
{
    private readonly SyncCoordinator _coordinator;
    private string? _cachedQrPayload;
    private Bitmap? _cachedQrImage;
    private int _statusRaiseQueued;
    private string _lastStatusFingerprint = string.Empty;

    [ObservableProperty]
    private SavedDeviceItem? _selectedSavedDevice;

    [ObservableProperty]
    private RecentClipboardItem? _selectedRecentItem;

    [ObservableProperty]
    private string _lastActionFeedback = "Ready";

    public MainViewModel(SyncCoordinator coordinator)
    {
        _coordinator = coordinator;
        _coordinator.StateChanged += (_, _) =>
        {
            if (Interlocked.Exchange(ref _statusRaiseQueued, 1) == 1)
            {
                return;
            }

            Dispatcher.UIThread.Post(() =>
            {
                Interlocked.Exchange(ref _statusRaiseQueued, 0);
                RaiseStatus();
            }, DispatcherPriority.Background);
        };
    }

    public ObservableCollection<RecentClipboardItem> RecentItems => _coordinator.RecentItems;

    public ObservableCollection<SavedDeviceItem> SavedDevices => _coordinator.SavedDevices;

    public ObservableCollection<LogEntry> LogEntries => _coordinator.LogEntries;

    public string LogFilePath => _coordinator.LogFilePath;

    public bool SyncEnabled
    {
        get => _coordinator.SyncEnabled;
        set
        {
            _coordinator.SyncEnabled = value;
            RaiseSettings();
        }
    }

    public bool RunAtStartup
    {
        get => _coordinator.RunAtStartup;
        set
        {
            _coordinator.RunAtStartup = value;
            RaiseSettings();
        }
    }

    public bool AcceptNewPairing
    {
        get => _coordinator.AcceptNewPairing;
        set
        {
            _coordinator.AcceptNewPairing = value;
            RaiseSettings();
        }
    }

    public SyncMode SyncMode
    {
        get => _coordinator.SyncMode;
        set
        {
            _coordinator.SyncMode = value;
            RaiseSettings();
        }
    }

    public bool AllowTextSync
    {
        get => _coordinator.AllowTextSync;
        set
        {
            _coordinator.AllowTextSync = value;
            RaiseSettings();
        }
    }

    public bool AllowUrlSync
    {
        get => _coordinator.AllowUrlSync;
        set
        {
            _coordinator.AllowUrlSync = value;
            RaiseSettings();
        }
    }

    public bool AllowImageSync
    {
        get => _coordinator.AllowImageSync;
        set
        {
            _coordinator.AllowImageSync = value;
            RaiseSettings();
        }
    }

    public int MaxImageSizeMb
    {
        get => _coordinator.MaxImageSizeMb;
        set
        {
            _coordinator.MaxImageSizeMb = value;
            RaiseSettings();
        }
    }

    public string StatusSummary => _coordinator.StatusSummary;
    public string GuidanceText => _coordinator.GuidanceText;
    public string PairedDeviceLabel => _coordinator.PairedDeviceLabel;
    public string ConnectionLabel => _coordinator.ConnectionLabel;
    public string LastItemSummary => _coordinator.LastItemSummary;
    public string QueueSummary => _coordinator.QueueSummary;
    public string PairingPayload => _coordinator.PairingPayload;
    public SyncMode[] SyncModes { get; } = Enum.GetValues<SyncMode>();

    public string ConnectionHealthLabel
    {
        get
        {
            if (!SyncEnabled)
            {
                return "Sync paused";
            }

            if (PairedDeviceLabel.Contains("Not paired", StringComparison.OrdinalIgnoreCase))
            {
                return "Pairing needed";
            }

            if (ConnectionLabel.Contains("Connected", StringComparison.OrdinalIgnoreCase))
            {
                return "Connected";
            }

            if (ConnectionLabel.Contains("Connecting", StringComparison.OrdinalIgnoreCase) ||
                ConnectionLabel.Contains("Searching", StringComparison.OrdinalIgnoreCase))
            {
                return "Searching";
            }

            return "Paired but offline";
        }
    }

    public string ReadinessSummary
    {
        get
        {
            var pairing = PairedDeviceLabel.Contains("Not paired", StringComparison.OrdinalIgnoreCase)
                ? "Pair a phone to start."
                : "Trusted device saved.";
            var enabled = SyncEnabled ? "Sync is enabled." : "Sync is paused.";
            return $"{pairing} {enabled} Allowed: {AllowedContentSummary}.";
        }
    }

    public string SyncModeLabel => SyncMode switch
    {
        SyncMode.MIRROR => "Mirror",
        SyncMode.MANUAL => "Manual",
        SyncMode.ASK => "Ask",
        SyncMode.RECEIVE_ONLY => "Receive only",
        SyncMode.SEND_ONLY => "Send only",
        _ => SyncMode.ToString()
    };

    public string SyncModeDescription => SyncMode switch
    {
        SyncMode.MIRROR => "Automatically mirrors supported clipboard changes when the platform allows it.",
        SyncMode.MANUAL => "Only sends when you use Sync now, tray actions, notification actions, or sharing.",
        SyncMode.ASK => "Stages detected items in history so you can choose what to send.",
        SyncMode.RECEIVE_ONLY => "Receives incoming items but does not send this computer's clipboard.",
        SyncMode.SEND_ONLY => "Sends outbound items but does not write incoming items to this clipboard.",
        _ => string.Empty
    };

    public string AllowedContentSummary
    {
        get
        {
            var allowed = new List<string>();
            if (AllowTextSync)
            {
                allowed.Add("text");
            }

            if (AllowUrlSync)
            {
                allowed.Add("URLs");
            }

            if (AllowImageSync)
            {
                allowed.Add($"images up to {MaxImageSizeMb} MB");
            }

            return allowed.Count == 0 ? "nothing" : string.Join(", ", allowed);
        }
    }

    public Bitmap PairingQrCodeImage
    {
        get
        {
            var payload = PairingPayload;
            if (_cachedQrImage is not null &&
                string.Equals(_cachedQrPayload, payload, StringComparison.Ordinal))
            {
                return _cachedQrImage;
            }

            _cachedQrImage?.Dispose();
            _cachedQrPayload = payload;
            _cachedQrImage = CreateQrCodeImage(payload);
            return _cachedQrImage;
        }
    }

    [RelayCommand]
    private async Task CopyPairingPayloadAsync()
    {
        await _coordinator.CopyPairingPayloadToClipboardAsync();
        LastActionFeedback = "Pairing payload copied.";
    }

    [RelayCommand]
    private void RegeneratePairingCode()
    {
        _coordinator.RegeneratePairingCode();
        LastActionFeedback = "New pairing code generated.";
        _cachedQrPayload = null;
        RaiseSettings();
        OnPropertyChanged(nameof(PairingQrCodeImage));
    }

    [RelayCommand]
    private void Reconnect()
    {
        _coordinator.ManualReconnect();
        LastActionFeedback = "Reconnecting...";
        RaiseStatus();
    }

    [RelayCommand]
    private void ConnectSavedDevice()
    {
        _coordinator.SelectSavedDevice(SelectedSavedDevice);
        LastActionFeedback = SelectedSavedDevice is null ? "Select a saved device first." : $"Connecting to {SelectedSavedDevice.DisplayName}...";
        RaiseStatus();
    }

    [RelayCommand]
    private void ClearLogs()
    {
        _coordinator.ClearLogs();
        LastActionFeedback = "Diagnostics cleared.";
    }

    [RelayCommand]
    private void OpenLogFile()
    {
        var path = LogFilePath;
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
        {
            return;
        }

        Process.Start(new ProcessStartInfo
        {
            FileName = path,
            UseShellExecute = true
        });
    }

    [RelayCommand]
    private async Task SendCurrentClipboardAsync()
    {
        LastActionFeedback = "Sending current clipboard...";
        await _coordinator.SendCurrentClipboardNowAsync();
        LastActionFeedback = "Sync request sent.";
    }

    [RelayCommand]
    private async Task ResendRecentAsync()
    {
        if (SelectedRecentItem is not null)
        {
            LastActionFeedback = "Sending selected history item...";
            await _coordinator.ResendRecentAsync(SelectedRecentItem.EventId);
            LastActionFeedback = "History item queued.";
        }
        else
        {
            LastActionFeedback = "Select a history item first.";
        }
    }

    [RelayCommand]
    private async Task RestoreRecentAsync()
    {
        if (SelectedRecentItem is not null)
        {
            LastActionFeedback = "Copying selected item here...";
            await _coordinator.RestoreRecentToClipboardAsync(SelectedRecentItem.EventId);
            LastActionFeedback = "Selected item copied here.";
        }
        else
        {
            LastActionFeedback = "Select a history item first.";
        }
    }

    [RelayCommand]
    private async Task ApplyDeferredAsync()
    {
        if (SelectedRecentItem is not null)
        {
            LastActionFeedback = "Applying deferred item...";
            await _coordinator.ApplyDeferredIncomingAsync(SelectedRecentItem.EventId);
            LastActionFeedback = "Deferred item applied.";
        }
        else
        {
            LastActionFeedback = "Select a deferred history item first.";
        }
    }

    [RelayCommand]
    private void NextSyncMode()
    {
        SyncMode = NextMode(SyncMode);
        LastActionFeedback = $"Sync mode set to {SyncModeLabel}.";
    }

    [RelayCommand]
    private void DecreaseImageLimit()
    {
        MaxImageSizeMb -= 5;
        LastActionFeedback = $"Image limit set to {MaxImageSizeMb} MB.";
    }

    [RelayCommand]
    private void IncreaseImageLimit()
    {
        MaxImageSizeMb += 5;
        LastActionFeedback = $"Image limit set to {MaxImageSizeMb} MB.";
    }

    /// <summary>Status/connection/last-item only — never rebinds the QR image.</summary>
    private void RaiseStatus()
    {
        var fingerprint =
            $"{_coordinator.StatusSummary}|{_coordinator.ConnectionLabel}|{_coordinator.PairedDeviceLabel}|{_coordinator.LastItemSummary}|{_coordinator.QueueSummary}";
        if (string.Equals(fingerprint, _lastStatusFingerprint, StringComparison.Ordinal))
        {
            return;
        }

        _lastStatusFingerprint = fingerprint;
        OnPropertyChanged(nameof(StatusSummary));
        OnPropertyChanged(nameof(PairedDeviceLabel));
        OnPropertyChanged(nameof(ConnectionLabel));
        OnPropertyChanged(nameof(LastItemSummary));
        OnPropertyChanged(nameof(QueueSummary));
        OnPropertyChanged(nameof(GuidanceText));
        OnPropertyChanged(nameof(ConnectionHealthLabel));
        OnPropertyChanged(nameof(ReadinessSummary));
    }

    private void RaiseSettings()
    {
        OnPropertyChanged(nameof(SyncEnabled));
        OnPropertyChanged(nameof(RunAtStartup));
        OnPropertyChanged(nameof(AcceptNewPairing));
        OnPropertyChanged(nameof(SyncMode));
        OnPropertyChanged(nameof(AllowTextSync));
        OnPropertyChanged(nameof(AllowUrlSync));
        OnPropertyChanged(nameof(AllowImageSync));
        OnPropertyChanged(nameof(MaxImageSizeMb));
        OnPropertyChanged(nameof(GuidanceText));
        OnPropertyChanged(nameof(PairingPayload));
        OnPropertyChanged(nameof(ConnectionHealthLabel));
        OnPropertyChanged(nameof(ReadinessSummary));
        OnPropertyChanged(nameof(SyncModeLabel));
        OnPropertyChanged(nameof(SyncModeDescription));
        OnPropertyChanged(nameof(AllowedContentSummary));
        RaiseStatus();
    }

    private static SyncMode NextMode(SyncMode current) => current switch
    {
        SyncMode.MIRROR => SyncMode.MANUAL,
        SyncMode.MANUAL => SyncMode.ASK,
        SyncMode.ASK => SyncMode.RECEIVE_ONLY,
        SyncMode.RECEIVE_ONLY => SyncMode.SEND_ONLY,
        _ => SyncMode.MIRROR
    };

    private static Bitmap CreateQrCodeImage(string payload)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var qrCode = new PngByteQRCode(data);
        var bytes = qrCode.GetGraphic(12);
        using var stream = new MemoryStream(bytes);
        return new Bitmap(stream);
    }
}
