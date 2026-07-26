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
    private string _lastActionFeedback = "Ready";

    public MainViewModel(SyncCoordinator coordinator)
    {
        _coordinator = coordinator;
        _coordinator.StateChanged += (_, _) => RaiseAll();
        CopyPairingPayloadCommand = new RelayCommand(() =>
        {
            _coordinator.CopyPairingPayloadToClipboard();
            LastActionFeedback = "Pairing payload copied.";
        });
        RegeneratePairingCodeCommand = new RelayCommand(() =>
        {
            _coordinator.RegeneratePairingCode();
            LastActionFeedback = "New pairing code generated.";
            RaiseAll();
        });
        ReconnectCommand = new RelayCommand(() =>
        {
            _coordinator.ManualReconnect();
            LastActionFeedback = "Reconnecting...";
            RaiseAll();
        });
        ConnectSavedDeviceCommand = new RelayCommand(() =>
        {
            _coordinator.SelectSavedDevice(SelectedSavedDevice);
            LastActionFeedback = SelectedSavedDevice is null ? "Select a saved device first." : $"Connecting to {SelectedSavedDevice.DisplayName}...";
            RaiseAll();
        });
        ClearLogsCommand = new RelayCommand(() =>
        {
            _coordinator.ClearLogs();
            LastActionFeedback = "Diagnostics cleared.";
        });
        SendCurrentClipboardCommand = new RelayCommand(async () =>
        {
            LastActionFeedback = "Sending current clipboard...";
            await _coordinator.SendCurrentClipboardNowAsync();
            LastActionFeedback = "Sync request sent.";
        });
        ResendRecentCommand = new RelayCommand(async () =>
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
        });
        RestoreRecentCommand = new RelayCommand(async () =>
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
        });
        ApplyDeferredCommand = new RelayCommand(async () =>
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
        });
        NextSyncModeCommand = new RelayCommand(() =>
        {
            SyncMode = NextMode(SyncMode);
            LastActionFeedback = $"Sync mode set to {SyncModeLabel}.";
        });
        DecreaseImageLimitCommand = new RelayCommand(() =>
        {
            MaxImageSizeMb -= 5;
            LastActionFeedback = $"Image limit set to {MaxImageSizeMb} MB.";
        });
        IncreaseImageLimitCommand = new RelayCommand(() =>
        {
            MaxImageSizeMb += 5;
            LastActionFeedback = $"Image limit set to {MaxImageSizeMb} MB.";
        });
    }

    public SyncMode[] SyncModes { get; } = Enum.GetValues<SyncMode>();

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

    public bool RunAtStartup
    {
        get => _coordinator.RunAtStartup;
        set
        {
            _coordinator.RunAtStartup = value;
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

    public string LastActionFeedback
    {
        get => _lastActionFeedback;
        private set => SetProperty(ref _lastActionFeedback, value);
    }

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
            var content = $"Allowed: {AllowedContentSummary}.";
            return $"{pairing} {enabled} {content}";
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
        RaisePropertyChanged(nameof(RunAtStartup));
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
        RaisePropertyChanged(nameof(ConnectionHealthLabel));
        RaisePropertyChanged(nameof(ReadinessSummary));
        RaisePropertyChanged(nameof(SyncModeLabel));
        RaisePropertyChanged(nameof(SyncModeDescription));
        RaisePropertyChanged(nameof(AllowedContentSummary));
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
