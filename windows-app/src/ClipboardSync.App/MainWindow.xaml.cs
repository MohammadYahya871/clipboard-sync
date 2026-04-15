using System.ComponentModel;
using System.IO;
using System.Windows.Media.Imaging;
using ClipboardSync.App.ViewModels;

namespace ClipboardSync.App;

public partial class MainWindow
{
    private readonly Func<bool> _isExplicitExitRequested;
    private readonly Action<string> _logInfo;

    public MainWindow(MainViewModel viewModel, Func<bool> isExplicitExitRequested, Action<string> logInfo)
    {
        InitializeComponent();
        DataContext = viewModel;
        _isExplicitExitRequested = isExplicitExitRequested;
        _logInfo = logInfo;
        ApplyWindowIcon();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!_isExplicitExitRequested())
        {
            e.Cancel = true;
            _logInfo("Main window close intercepted; hiding to tray");
            Hide();
            return;
        }

        _logInfo("Main window closing explicitly");
        base.OnClosing(e);
    }

    public void ShowFromTray()
    {
        _logInfo("Showing main window from tray");
        Show();
        WindowState = System.Windows.WindowState.Normal;
        Activate();
    }

    private void ApplyWindowIcon()
    {
        var iconPath = Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico");
        if (File.Exists(iconPath))
        {
            Icon = BitmapFrame.Create(new Uri(iconPath, UriKind.Absolute));
        }
    }
}
