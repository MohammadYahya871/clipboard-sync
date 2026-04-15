using ClipboardSync.App.Diagnostics;
using ClipboardSync.App.Tray;
using ClipboardSync.App.Transport;
using ClipboardSync.App.ViewModels;
using System.Threading;
using System.Windows;

namespace ClipboardSync.App;

public partial class App
{
    private const string MutexName = @"Local\ClipboardSync.App.Singleton";
    private const string ShowEventName = @"Local\ClipboardSync.App.ShowMainWindow";

    private readonly AppLogStore _logStore = new();
    private TrayController? _trayController;
    private SyncCoordinator? _coordinator;
    private MainWindow? _mainWindow;
    private Mutex? _singleInstanceMutex;
    private EventWaitHandle? _showMainWindowEvent;
    private CancellationTokenSource? _showWindowListenerCts;
    private bool _explicitExitRequested;
    private bool _ownsSingleInstanceMutex;

    protected override async void OnStartup(System.Windows.StartupEventArgs e)
    {
        base.OnStartup(e);
        ShutdownMode = ShutdownMode.OnExplicitShutdown;
        RegisterGlobalExceptionHandlers();

        _logStore.Info("App startup requested");

        if (!TryAcquireSingleInstance())
        {
            _logStore.Warn("Second instance detected; signaling existing instance and exiting");
            SignalExistingInstance();
            Shutdown();
            return;
        }

        try
        {
            _showMainWindowEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
            _showWindowListenerCts = new CancellationTokenSource();
            _ = ListenForShowRequestsAsync(_showWindowListenerCts.Token);

            _coordinator = new SyncCoordinator(_logStore);
            await _coordinator.InitializeAsync();
            var viewModel = new MainViewModel(_coordinator);
            _mainWindow = new MainWindow(viewModel, () => _explicitExitRequested, _logStore.Info);
            MainWindow = _mainWindow;
            _trayController = new TrayController();
            _trayController.OpenRequested += (_, _) =>
            {
                _logStore.Info("Tray requested main window open");
                _mainWindow.ShowFromTray();
            };
            _trayController.ExitRequested += async (_, _) =>
            {
                _logStore.Info("Tray requested app exit");
                _explicitExitRequested = true;
                await ShutdownApplicationAsync();
            };

            _mainWindow.Show();
            _logStore.Info("Main window shown");
        }
        catch (Exception exception)
        {
            _logStore.Error("Fatal startup failure", exception);
            System.Windows.MessageBox.Show(
                $"Clipboard Sync failed to start. See the log file for details:\n{_logStore.FilePath}",
                "Clipboard Sync Startup Error",
                System.Windows.MessageBoxButton.OK,
                System.Windows.MessageBoxImage.Error);
            _explicitExitRequested = true;
            await ShutdownApplicationAsync();
        }
    }

    protected override async void OnExit(System.Windows.ExitEventArgs e)
    {
        _logStore.Info("App exit requested");
        await DisposeRuntimeAsync();
        base.OnExit(e);
    }

    private bool TryAcquireSingleInstance()
    {
        _singleInstanceMutex = new Mutex(true, MutexName, out var createdNew);
        _ownsSingleInstanceMutex = createdNew;
        return createdNew;
    }

    private void SignalExistingInstance()
    {
        try
        {
            using var existingEvent = EventWaitHandle.OpenExisting(ShowEventName);
            existingEvent.Set();
        }
        catch
        {
            // If the event is not available yet, there is nothing else to signal.
        }
    }

    private async Task ListenForShowRequestsAsync(CancellationToken cancellationToken)
    {
        if (_showMainWindowEvent is null)
        {
            return;
        }

        await Task.Run(() =>
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                _showMainWindowEvent.WaitOne();
                if (cancellationToken.IsCancellationRequested)
                {
                    return;
                }

                Dispatcher.Invoke(() =>
                {
                    _logStore.Info("Received request to show main window from another launch");
                    _mainWindow?.ShowFromTray();
                });
            }
        }, cancellationToken);
    }

    private void RegisterGlobalExceptionHandlers()
    {
        DispatcherUnhandledException += (_, args) =>
        {
            _logStore.Error("Dispatcher unhandled exception", args.Exception);
            args.Handled = false;
        };

        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            _logStore.Error("AppDomain unhandled exception", args.ExceptionObject as Exception);
        };

        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            _logStore.Error("Unobserved task exception", args.Exception);
            args.SetObserved();
        };
    }

    private async Task ShutdownApplicationAsync()
    {
        await DisposeRuntimeAsync();
        _mainWindow?.Close();
        Shutdown();
    }

    private async Task DisposeRuntimeAsync()
    {
        _showWindowListenerCts?.Cancel();
        _showMainWindowEvent?.Set();
        _showMainWindowEvent?.Dispose();
        _showMainWindowEvent = null;
        _showWindowListenerCts?.Dispose();
        _showWindowListenerCts = null;
        _trayController?.Dispose();
        _trayController = null;

        if (_coordinator is not null)
        {
            await _coordinator.DisposeAsync();
            _coordinator = null;
        }

        if (_ownsSingleInstanceMutex && _singleInstanceMutex is not null)
        {
            _singleInstanceMutex.ReleaseMutex();
        }
        _singleInstanceMutex?.Dispose();
        _singleInstanceMutex = null;
        _ownsSingleInstanceMutex = false;
    }
}
