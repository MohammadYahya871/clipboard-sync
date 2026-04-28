import Foundation
import Network

@MainActor
final class PeerDiscovery: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private let logger: AppLogger
    private var browser: NetServiceBrowser?
    private var services: [NetService] = []
    private(set) var resolved: [ResolvedPeerEndpoint] = []

    init(logger: AppLogger) {
        self.logger = logger
    }

    func startBonjour() {
        guard browser == nil else { return }
        let browser = NetServiceBrowser()
        browser.delegate = self
        browser.searchForServices(ofType: "_clipboardsync._tcp.", inDomain: "local.")
        self.browser = browser
        logger.info("Bonjour discovery started for _clipboardsync._tcp.")
    }

    func stopBonjour() {
        browser?.stop()
        browser = nil
        services.removeAll()
        resolved.removeAll()
    }

    func knownHost(for serviceName: String) -> ResolvedPeerEndpoint? {
        resolved.first { $0.serviceName == serviceName }
    }

    func discoverTrustedPeer(_ peer: TrustedPeer, timeoutMillis: Int = 900) async -> TrustedPeer? {
        if let bonjour = knownHost(for: peer.serviceName) {
            var updated = peer
            updated.host = bonjour.host
            updated.port = bonjour.port
            return updated
        }

        if let udp = await discoverViaUdp(peer: peer, timeoutMillis: timeoutMillis) {
            return udp
        }
        return nil
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        services.append(service)
        service.delegate = self
        service.resolve(withTimeout: 2)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        services.removeAll { $0 == service }
        resolved.removeAll { $0.serviceName == service.name }
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let host = sender.hostName else { return }
        let endpoint = ResolvedPeerEndpoint(serviceName: sender.name, host: host, port: sender.port)
        resolved.removeAll { $0.serviceName == sender.name }
        resolved.append(endpoint)
        logger.info("Resolved \(sender.name) to \(host):\(sender.port)")
    }

    private func discoverViaUdp(peer: TrustedPeer, timeoutMillis: Int) async -> TrustedPeer? {
        await withCheckedContinuation { continuation in
            var resumed = false
            func finish(_ value: TrustedPeer?) {
                guard !resumed else { return }
                resumed = true
                continuation.resume(returning: value)
            }

            let request = DiscoveryMessage(type: DiscoveryMessage.discoverType)
            let payload = try? ProtocolJSON.encoder.encode(request)
            let connection = NWConnection(host: "255.255.255.255", port: 43872, using: .udp)
            connection.start(queue: .global())
            connection.receiveMessage { data, _, _, _ in
                guard let data,
                      let message = try? ProtocolJSON.decoder.decode(DiscoveryMessage.self, from: data),
                      message.type == DiscoveryMessage.responseType,
                      message.deviceId == peer.deviceId || message.serviceName == peer.serviceName,
                      let host = message.host,
                      let port = message.port else {
                    return
                }
                var updated = peer
                updated.host = host
                updated.port = port
                finish(updated)
            }
            connection.send(content: payload, completion: .contentProcessed { _ in })

            DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(timeoutMillis)) {
                connection.cancel()
                finish(nil)
            }
        }
    }
}

private extension NWEndpoint.Port {
    init(_ int: Int) {
        self = NWEndpoint.Port(rawValue: UInt16(int))!
    }
}
