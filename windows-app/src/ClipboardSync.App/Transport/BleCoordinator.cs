using ClipboardSync.App.Diagnostics;

namespace ClipboardSync.App.Transport;

public sealed class BleCoordinator
{
    private readonly AppLogStore _logStore;

    public BleCoordinator(AppLogStore logStore)
    {
        _logStore = logStore;
    }

    public bool IsAvailable => false;

    public void Refresh()
    {
        _logStore.Info("BLE coordinator is scaffolded for Phase 3 transport work and is inactive in the current MVP");
    }
}
