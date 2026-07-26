using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Threading;
using ClipboardSync.Linux.Diagnostics;
using ClipboardSync.Linux.Services;
using ClipboardSync.Linux.Transport;
using ClipboardSync.Linux.ViewModels;
using ClipboardSync.Linux.Views;

namespace ClipboardSync.Linux;

public partial class App : Application
{
    private SyncCoordinator? _coordinator;
    private AppLogStore? _logStore;
    private MainWindow? _mainWindow;
    private MainViewModel? _viewModel;
    private IClassicDesktopStyleApplicationLifetime? _desktop;
    private bool _exitRequested;
    private bool _destroyingWindow;

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }

    public override async void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            _desktop = desktop;
            // Important: do NOT assign MainWindow at startup. A mapped Avalonia window
            // (even Opacity=0 / ShowInTaskbar=false) stays in Dash2Dock and animates.
            desktop.ShutdownMode = ShutdownMode.OnExplicitShutdown;

            _logStore = new AppLogStore();
            _coordinator = new SyncCoordinator(_logStore);
            _viewModel = new MainViewModel(_coordinator);

            Program.Instance?.StartActivationServer(ShowMainWindow);

            desktop.Exit += async (_, _) =>
            {
                if (_coordinator is not null)
                {
                    await _coordinator.DisposeAsync();
                }
            };

            try
            {
                await _coordinator.InitializeAsync();
                StartupRegistration.InstallApplicationsEntry();
                if (_coordinator.RunAtStartup)
                {
                    StartupRegistration.Apply(true);
                }

                _logStore.Info("Running in background (tray). Open from the tray menu if needed.");
            }
            catch (Exception exception)
            {
                _logStore.Error("Failed to initialize Clipboard Sync", exception);
                ShowMainWindow();
            }
        }

        base.OnFrameworkInitializationCompleted();
    }

    private void OnOpenClicked(object? sender, EventArgs e) => ShowMainWindow();

    private async void OnSendClipboardClicked(object? sender, EventArgs e)
    {
        if (_coordinator is null)
        {
            return;
        }

        await _coordinator.SendCurrentClipboardNowAsync();
    }

    private void OnQuitClicked(object? sender, EventArgs e)
    {
        _exitRequested = true;
        _destroyingWindow = true;
        _mainWindow?.Close();
        _desktop?.Shutdown();
    }

    private void ShowMainWindow()
    {
        Dispatcher.UIThread.Post(() =>
        {
            if (_desktop is null || _viewModel is null)
            {
                return;
            }

            if (_mainWindow is null)
            {
                _mainWindow = new MainWindow
                {
                    DataContext = _viewModel,
                    ShowActivated = true,
                    ShowInTaskbar = true
                };
                _mainWindow.Closing += OnMainWindowClosing;
            }

            _desktop.MainWindow = _mainWindow;
            _mainWindow.Show();
            _mainWindow.WindowState = WindowState.Normal;
            _mainWindow.Activate();
        });
    }

    private void OnMainWindowClosing(object? sender, WindowClosingEventArgs e)
    {
        if (_exitRequested || _destroyingWindow)
        {
            return;
        }

        // Convert Close into "destroy window, keep process in tray".
        e.Cancel = true;
        Dispatcher.UIThread.Post(DestroyMainWindow);
    }

    private void DestroyMainWindow()
    {
        if (_mainWindow is null)
        {
            return;
        }

        _destroyingWindow = true;
        var window = _mainWindow;
        _mainWindow = null;
        if (_desktop is not null)
        {
            _desktop.MainWindow = null;
        }

        window.Closing -= OnMainWindowClosing;
        window.Close();
        _destroyingWindow = false;
    }
}
