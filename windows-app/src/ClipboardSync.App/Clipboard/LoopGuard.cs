namespace ClipboardSync.App.Clipboard;

public sealed class LoopGuard
{
    private readonly int _maxEntries;
    private readonly TimeSpan _suppressionWindow;
    private readonly Dictionary<string, DateTimeOffset> _eventIds = new();
    private readonly Dictionary<string, DateTimeOffset> _suppressedHashes = new();

    public LoopGuard(int maxEntries = 128, TimeSpan? suppressionWindow = null)
    {
        _maxEntries = maxEntries;
        _suppressionWindow = suppressionWindow ?? TimeSpan.FromSeconds(4);
    }

    public void RememberSeenEvent(string eventId)
    {
        _eventIds[eventId] = DateTimeOffset.UtcNow;
        Trim(_eventIds);
    }

    public bool HasSeenEvent(string eventId) => _eventIds.ContainsKey(eventId);

    public void MarkRemoteApplied(string hash)
    {
        _suppressedHashes[hash] = DateTimeOffset.UtcNow.Add(_suppressionWindow);
        Trim(_suppressedHashes);
    }

    public bool ShouldSuppressLocal(string hash)
    {
        if (!_suppressedHashes.TryGetValue(hash, out var until))
        {
            return false;
        }

        if (until < DateTimeOffset.UtcNow)
        {
            _suppressedHashes.Remove(hash);
            return false;
        }

        return true;
    }

    private void Trim(Dictionary<string, DateTimeOffset> dictionary)
    {
        if (dictionary.Count <= _maxEntries)
        {
            return;
        }

        foreach (var key in dictionary.OrderBy(pair => pair.Value).Take(dictionary.Count - _maxEntries).Select(pair => pair.Key).ToList())
        {
            dictionary.Remove(key);
        }
    }
}
