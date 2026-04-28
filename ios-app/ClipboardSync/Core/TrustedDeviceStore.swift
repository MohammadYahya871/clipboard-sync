import Foundation
import Security
import UIKit

struct TrustedPeer: Codable, Equatable, Identifiable {
    var id: String { deviceId }

    var deviceId: String
    var displayName: String
    var serviceName: String
    var host: String
    var port: Int
    var pairingCode: String
    var certificateSha256: String

    init(payload: PairingPayload) {
        self.deviceId = payload.deviceId
        self.displayName = payload.displayName
        self.serviceName = payload.serviceName
        self.host = payload.host
        self.port = payload.port
        self.pairingCode = payload.pairingCode
        self.certificateSha256 = payload.certificateSha256
    }

    func payload() -> PairingPayload {
        PairingPayload(
            deviceId: deviceId,
            displayName: displayName,
            serviceName: serviceName,
            host: host,
            port: port,
            pairingCode: pairingCode,
            certificateSha256: certificateSha256
        )
    }
}

final class TrustedDeviceStore {
    private let defaults: UserDefaults
    private let keychain = KeychainStore(service: "com.clipboardsync.ios")

    private let peersKey = "trusted_peers"
    private let selectedDeviceKey = "selected_device_id"
    private let localDeviceKey = "local_device_id"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var localDeviceId: String {
        if let existing = try? keychain.readString(account: localDeviceKey), !existing.isEmpty {
            return existing
        }
        let created = "ios-\(UIDevice.current.identifierForVendor?.uuidString.lowercased() ?? CryptoUtils.uuidV7())"
        try? keychain.writeString(created, account: localDeviceKey)
        return created
    }

    func trustedPeers() -> [TrustedPeer] {
        guard let data = defaults.data(forKey: peersKey),
              let peers = try? ProtocolJSON.decoder.decode([TrustedPeer].self, from: data) else {
            return []
        }
        return peers
    }

    func selectedPeer() -> TrustedPeer? {
        let peers = trustedPeers()
        let selectedId = defaults.string(forKey: selectedDeviceKey)
        return peers.first { $0.deviceId == selectedId } ?? peers.first
    }

    func savePairingPayload(_ payload: PairingPayload) {
        let peer = TrustedPeer(payload: payload)
        let next = trustedPeers().filter { $0.deviceId != peer.deviceId } + [peer]
        save(peers: next)
        defaults.set(peer.deviceId, forKey: selectedDeviceKey)
    }

    func selectPeer(deviceId: String) {
        guard trustedPeers().contains(where: { $0.deviceId == deviceId }) else { return }
        defaults.set(deviceId, forKey: selectedDeviceKey)
    }

    func updateEndpoint(_ peer: TrustedPeer, host: String, port: Int) {
        var updated = peer
        updated.host = host
        updated.port = port
        let next = trustedPeers().filter { $0.deviceId != peer.deviceId } + [updated]
        save(peers: next)
    }

    private func save(peers: [TrustedPeer]) {
        if let data = try? ProtocolJSON.encoder.encode(peers) {
            defaults.set(data, forKey: peersKey)
        }
    }
}

final class KeychainStore {
    private let service: String

    init(service: String) {
        self.service = service
    }

    func readString(account: String) throws -> String? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status != errSecItemNotFound else { return nil }
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func writeString(_ value: String, account: String) throws {
        let data = Data(value.utf8)
        let query = baseQuery(account: account)
        let update: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var create = query
            create[kSecValueData as String] = data
            SecItemAdd(create as CFDictionary, nil)
        }
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
    }
}

