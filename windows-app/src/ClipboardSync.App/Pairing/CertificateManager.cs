using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using ClipboardSync.App.Util;

namespace ClipboardSync.App.Pairing;

public sealed class CertificateManager
{
    private static readonly string[] VirtualAdapterMarkers =
    [
        "vmware",
        "virtualbox",
        "hyper-v",
        "hyperv",
        "vethernet",
        "virtual",
        "loopback",
        "npcap",
        "vpn"
    ];

    private readonly TrustedDeviceStore _settingsStore;

    public CertificateManager(TrustedDeviceStore settingsStore)
    {
        _settingsStore = settingsStore;
    }

    public X509Certificate2 GetOrCreateCertificate()
    {
        var fullPath = Path.Combine(_settingsStore.DirectoryPath, _settingsStore.Current.CertificatePath);
        if (File.Exists(fullPath))
        {
            return new X509Certificate2(File.ReadAllBytes(fullPath), _settingsStore.Current.CertificatePassword, X509KeyStorageFlags.Exportable);
        }

        using var ecdsa = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest(
            $"CN={_settingsStore.Current.DisplayName}",
            ecdsa,
            HashAlgorithmName.SHA256);

        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, false));
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));

        var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(2));
        var export = cert.Export(X509ContentType.Pfx, _settingsStore.Current.CertificatePassword);
        File.WriteAllBytes(fullPath, export);
        return new X509Certificate2(export, _settingsStore.Current.CertificatePassword, X509KeyStorageFlags.Exportable);
    }

    public static string Sha256ThumbprintHex(X509Certificate2 certificate) => CryptoUtils.Sha256Hex(certificate.RawData);

    public static string GetPreferredLanAddress()
    {
        var candidate = NetworkInterface.GetAllNetworkInterfaces()
            .Where(nic => nic.OperationalStatus == OperationalStatus.Up)
            .Where(nic => nic.NetworkInterfaceType is not NetworkInterfaceType.Loopback and not NetworkInterfaceType.Tunnel)
            .SelectMany(nic => nic.GetIPProperties().UnicastAddresses.Select(address => new
            {
                Nic = nic,
                Address = address,
                Properties = nic.GetIPProperties(),
                Score = ScoreCandidate(nic, address)
            }))
            .Where(candidate => candidate.Address.Address.AddressFamily == AddressFamily.InterNetwork)
            .Where(candidate => !IPAddress.IsLoopback(candidate.Address.Address))
            .Where(candidate => !candidate.Address.Address.ToString().StartsWith("169.254.", StringComparison.Ordinal))
            .OrderByDescending(candidate => candidate.Score)
            .ThenBy(candidate => candidate.Nic.Name, StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault();

        return candidate?.Address.Address.ToString() ?? "127.0.0.1";
    }

    private static int ScoreCandidate(NetworkInterface nic, UnicastIPAddressInformation address)
    {
        var score = 0;
        var properties = nic.GetIPProperties();

        if (properties.GatewayAddresses.Any(gateway =>
                gateway.Address.AddressFamily == AddressFamily.InterNetwork &&
                !IPAddress.Any.Equals(gateway.Address) &&
                !IPAddress.None.Equals(gateway.Address)))
        {
            score += 50;
        }

        score += nic.NetworkInterfaceType switch
        {
            NetworkInterfaceType.Wireless80211 => 40,
            NetworkInterfaceType.Ethernet => 35,
            NetworkInterfaceType.GigabitEthernet => 35,
            NetworkInterfaceType.FastEthernetFx => 30,
            NetworkInterfaceType.FastEthernetT => 30,
            _ => 10
        };

        if (address.IsDnsEligible)
        {
            score += 5;
        }

        if (!LooksVirtualOrVpn(nic))
        {
            score += 20;
        }
        else
        {
            score -= 80;
        }

        return score;
    }

    private static bool LooksVirtualOrVpn(NetworkInterface nic)
    {
        var combined = $"{nic.Name} {nic.Description}";
        return VirtualAdapterMarkers.Any(marker =>
            combined.Contains(marker, StringComparison.OrdinalIgnoreCase));
    }
}
