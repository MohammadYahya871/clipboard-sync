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
    private async Task CopyPairingPayloadAsync() => await _coordinator.CopyPairingPayloadToClipboardAsync();

    [RelayCommand]
    private void RegeneratePairingCode()
    {
        _coordinator.RegeneratePairingCode();
        _cachedQrPayload = null;
        RaiseSettings();
        OnPropertyChanged(nameof(PairingQrCodeImage));
    }

    [RelayCommand]
    private void Reconnect()
    {
        _coordinator.ManualReconnect();
        RaiseStatus();
    }

    [RelayCommand]
    private void ConnectSavedDevice()
    {
        _coordinator.SelectSavedDevice(SelectedSavedDevice);
        RaiseStatus();
    }

    [RelayCommand]
    private void ClearLogs() => _coordinator.ClearLogs();

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
    private async Task SendCurrentClipboardAsync() => await _coordinator.SendCurrentClipboardNowAsync();

    [RelayCommand]
    private async Task ResendRecentAsync()
    {
        if (SelectedRecentItem is not null)
        {
            await _coordinator.ResendRecentAsync(SelectedRecentItem.EventId);
        }
    }

    [RelayCommand]
    private async Task RestoreRecentAsync()
    {
        if (SelectedRecentItem is not null)
        {
            await _coordinator.RestoreRecentToClipboardAsync(SelectedRecentItem.EventId);
        }
    }

    [RelayCommand]
    private async Task ApplyDeferredAsync()
    {
        if (SelectedRecentItem is not null)
        {
            await _coordinator.ApplyDeferredIncomingAsync(SelectedRecentItem.EventId);
        }
    }

    [RelayCommand]
    private void NextSyncMode()
    {
        SyncMode = NextMode(SyncMode);
    }

    [RelayCommand]
    private void DecreaseImageLimit() => MaxImageSizeMb -= 5;

    [RelayCommand]
    private void IncreaseImageLimit() => MaxImageSizeMb += 5;

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
