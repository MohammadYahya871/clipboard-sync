using System.Collections.ObjectModel;
using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ClipboardSync.App.Diagnostics;
using ClipboardSync.App.Models;
using ClipboardSync.App.Transport;
using ClipboardSync.App.Util;
using QRCoder;

namespace ClipboardSync.App.ViewModels;

public sealed class MainViewModel : ObservableObject
{
    private readonly SyncCoordinator _coordinator;
    private SavedDeviceItem? _selectedSavedDevice;
    private RecentClipboardItem? _selectedRecentItem;

    public MainViewModel(SyncCoordinator coordinator)
    {
        _coordinator = coordinator;
        _coordinator.StateChanged += (_, _) => RaiseAll();
        CopyPairingPayloadCommand = new RelayCommand(_coordinator.CopyPairingPayloadToClipboard);
        RegeneratePairingCodeCommand = new RelayCommand(() =>
        {
            _coordinator.RegeneratePairingCode();
            RaiseAll();
        });
        ReconnectCommand = new RelayCommand(() =>
        {
            _coordinator.ManualReconnect();
            RaiseAll();
        });
        ConnectSavedDeviceCommand = new RelayCommand(() =>
        {
            _coordinator.SelectSavedDevice(SelectedSavedDevice);
            RaiseAll();
        });
        ClearLogsCommand = new RelayCommand(_coordinator.ClearLogs);
        SendCurrentClipboardCommand = new RelayCommand(async () => await _coordinator.SendCurrentClipboardNowAsync());
        ResendRecentCommand = new RelayCommand(async () =>
        {
            if (SelectedRecentItem is not null)
            {
                await _coordinator.ResendRecentAsync(SelectedRecentItem.EventId);
            }
        });
        RestoreRecentCommand = new RelayCommand(async () =>
        {
            if (SelectedRecentItem is not null)
            {
                await _coordinator.RestoreRecentToClipboardAsync(SelectedRecentItem.EventId);
            }
        });
        ApplyDeferredCommand = new RelayCommand(async () =>
        {
            if (SelectedRecentItem is not null)
            {
                await _coordinator.ApplyDeferredIncomingAsync(SelectedRecentItem.EventId);
            }
        });
        NextSyncModeCommand = new RelayCommand(() => SyncMode = NextMode(SyncMode));
        DecreaseImageLimitCommand = new RelayCommand(() => MaxImageSizeMb -= 5);
        IncreaseImageLimitCommand = new RelayCommand(() => MaxImageSizeMb += 5);
    }

    public ObservableCollection<RecentClipboardItem> RecentItems => _coordinator.RecentItems;

    public ObservableCollection<SavedDeviceItem> SavedDevices => _coordinator.SavedDevices;

    public ObservableCollection<LogEntry> LogEntries => _coordinator.LogEntries;

    public bool SyncEnabled
    {
        get => _coordinator.SyncEnabled;
        set
        {
            _coordinator.SyncEnabled = value;
            RaiseAll();
        }
    }

    public SyncMode SyncMode
    {
        get => _coordinator.SyncMode;
        set
        {
            _coordinator.SyncMode = value;
            RaiseAll();
        }
    }

    public bool AllowTextSync
    {
        get => _coordinator.AllowTextSync;
        set
        {
            _coordinator.AllowTextSync = value;
            RaiseAll();
        }
    }

    public bool AllowUrlSync
    {
        get => _coordinator.AllowUrlSync;
        set
        {
            _coordinator.AllowUrlSync = value;
            RaiseAll();
        }
    }

    public bool AllowImageSync
    {
        get => _coordinator.AllowImageSync;
        set
        {
            _coordinator.AllowImageSync = value;
            RaiseAll();
        }
    }

    public int MaxImageSizeMb
    {
        get => _coordinator.MaxImageSizeMb;
        set
        {
            _coordinator.MaxImageSizeMb = value;
            RaiseAll();
        }
    }

    public string StatusSummary => _coordinator.StatusSummary;

    public string GuidanceText => _coordinator.GuidanceText;

    public string PairedDeviceLabel => _coordinator.PairedDeviceLabel;

    public string ConnectionLabel => _coordinator.ConnectionLabel;

    public string TransportLabel => _coordinator.TransportLabel;

    public string PairingPayload => _coordinator.PairingPayload;

    public ImageSource PairingQrCodeImage => CreateQrCodeImage(PairingPayload);

    public string LastItemSummary => _coordinator.LastItemSummary;

    public string QueueSummary => _coordinator.QueueSummary;

    public SavedDeviceItem? SelectedSavedDevice
    {
        get => _selectedSavedDevice;
        set => SetProperty(ref _selectedSavedDevice, value);
    }

    public RecentClipboardItem? SelectedRecentItem
    {
        get => _selectedRecentItem;
        set => SetProperty(ref _selectedRecentItem, value);
    }

    public RelayCommand CopyPairingPayloadCommand { get; }

    public RelayCommand RegeneratePairingCodeCommand { get; }

    public RelayCommand ReconnectCommand { get; }

    public RelayCommand ConnectSavedDeviceCommand { get; }

    public RelayCommand ClearLogsCommand { get; }

    public RelayCommand SendCurrentClipboardCommand { get; }

    public RelayCommand ResendRecentCommand { get; }

    public RelayCommand RestoreRecentCommand { get; }

    public RelayCommand ApplyDeferredCommand { get; }

    public RelayCommand NextSyncModeCommand { get; }

    public RelayCommand DecreaseImageLimitCommand { get; }

    public RelayCommand IncreaseImageLimitCommand { get; }

    private void RaiseAll()
    {
        RaisePropertyChanged(nameof(SyncEnabled));
        RaisePropertyChanged(nameof(SyncMode));
        RaisePropertyChanged(nameof(AllowTextSync));
        RaisePropertyChanged(nameof(AllowUrlSync));
        RaisePropertyChanged(nameof(AllowImageSync));
        RaisePropertyChanged(nameof(MaxImageSizeMb));
        RaisePropertyChanged(nameof(StatusSummary));
        RaisePropertyChanged(nameof(GuidanceText));
        RaisePropertyChanged(nameof(PairedDeviceLabel));
        RaisePropertyChanged(nameof(ConnectionLabel));
        RaisePropertyChanged(nameof(TransportLabel));
        RaisePropertyChanged(nameof(PairingPayload));
        RaisePropertyChanged(nameof(PairingQrCodeImage));
        RaisePropertyChanged(nameof(LastItemSummary));
        RaisePropertyChanged(nameof(QueueSummary));
        RaisePropertyChanged(nameof(SavedDevices));
    }

    private static SyncMode NextMode(SyncMode mode)
    {
        var values = Enum.GetValues<SyncMode>();
        return values[(Array.IndexOf(values, mode) + 1) % values.Length];
    }

    private static ImageSource CreateQrCodeImage(string payload)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var qrCode = new PngByteQRCode(data);
        var bytes = qrCode.GetGraphic(14);

        using var stream = new MemoryStream(bytes);
        var image = new BitmapImage();
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        image.Freeze();
        return image;
    }
}

