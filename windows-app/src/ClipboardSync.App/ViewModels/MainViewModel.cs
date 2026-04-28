using System.Collections.ObjectModel;
using ClipboardSync.App.Diagnostics;
using ClipboardSync.App.Models;
using ClipboardSync.App.Transport;
using ClipboardSync.App.Util;

namespace ClipboardSync.App.ViewModels;

public sealed class MainViewModel : ObservableObject
{
    private readonly SyncCoordinator _coordinator;
    private SavedDeviceItem? _selectedSavedDevice;

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

    public string StatusSummary => _coordinator.StatusSummary;

    public string GuidanceText => _coordinator.GuidanceText;

    public string PairedDeviceLabel => _coordinator.PairedDeviceLabel;

    public string ConnectionLabel => _coordinator.ConnectionLabel;

    public string TransportLabel => _coordinator.TransportLabel;

    public string PairingPayload => _coordinator.PairingPayload;

    public string LastItemSummary => _coordinator.LastItemSummary;

    public SavedDeviceItem? SelectedSavedDevice
    {
        get => _selectedSavedDevice;
        set => SetProperty(ref _selectedSavedDevice, value);
    }

    public RelayCommand CopyPairingPayloadCommand { get; }

    public RelayCommand RegeneratePairingCodeCommand { get; }

    public RelayCommand ReconnectCommand { get; }

    public RelayCommand ConnectSavedDeviceCommand { get; }

    public RelayCommand ClearLogsCommand { get; }

    private void RaiseAll()
    {
        RaisePropertyChanged(nameof(SyncEnabled));
        RaisePropertyChanged(nameof(StatusSummary));
        RaisePropertyChanged(nameof(GuidanceText));
        RaisePropertyChanged(nameof(PairedDeviceLabel));
        RaisePropertyChanged(nameof(ConnectionLabel));
        RaisePropertyChanged(nameof(TransportLabel));
        RaisePropertyChanged(nameof(PairingPayload));
        RaisePropertyChanged(nameof(LastItemSummary));
        RaisePropertyChanged(nameof(SavedDevices));
    }
}

