import CryptoKit
import Foundation

/// PadKey protocol v2 crypto (see ../../PROTOCOL.md): pairing, session keys and AES-256-GCM frames.
enum PK {
    static let version = 2
    static let maxControlFrame = 1_000_000
    static let maxVideoFrame = 8_000_000

    static func b64(_ d: Data) -> String { d.base64EncodedString() }
    static func unb64(_ s: Any?) -> Data? { (s as? String).flatMap { Data(base64Encoded: $0) } }

    static func random(_ n: Int) -> Data {
        var g = SystemRandomNumberGenerator()
        return Data((0..<n).map { _ in UInt8.random(in: 0...255, using: &g) })
    }

    static func hkdf(_ ikm: Data, salt: Data, info: String, count: Int = 32) -> Data {
        let k = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt,
                                       info: Data(info.utf8), outputByteCount: count)
        return k.withUnsafeBytes { Data($0) }
    }

    static func hmac(_ key: Data, _ msg: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: msg, using: SymmetricKey(data: key)))
    }

    static func macValid(_ mac: Data, key: Data, _ msg: Data) -> Bool {
        HMAC<SHA256>.isValidAuthenticationCode(mac, authenticating: msg, using: SymmetricKey(data: key))
    }

    /// Mac's proof that it holds the long-term secret `s` (checked by the phone before it sends anything).
    static func macAuth(secret s: Data, label: String, nc: Data, ns: Data) -> Data {
        hmac(s, Data("padkey v2 mac \(label)".utf8) + nc + ns)
    }

    // MARK: pairing (ECDH P-256 bound to the PIN)

    struct PairKeys {
        let confirmKey: Data   // for the two confirmation MACs
        let wrapKey: Data      // AES-GCM key that carries the long-term secret
        let transcript: Data
    }

    /// K = HKDF(ECDH shared secret || PIN, salt = SHA256(phonePub || macPub)).
    static func pairKeys(shared: Data, pin: String, phonePub: Data, macPub: Data) -> PairKeys {
        let transcript = Data("padkey v2 pair".utf8) + phonePub + macPub
        let salt = Data(SHA256.hash(data: phonePub + macPub))
        let k = hkdf(shared + Data(pin.utf8), salt: salt, info: "padkey v2 pair")
        return PairKeys(confirmKey: hkdf(k, salt: salt, info: "padkey v2 pair confirm"),
                        wrapKey: hkdf(k, salt: salt, info: "padkey v2 pair wrap"), transcript: transcript)
    }

    static func phoneConfirm(_ k: PairKeys) -> Data { hmac(k.confirmKey, Data("phone".utf8) + k.transcript) }
    static func macConfirm(_ k: PairKeys) -> Data { hmac(k.confirmKey, Data("mac".utf8) + k.transcript) }
}

/// One direction pair of AES-256-GCM frame keys for a single TCP connection.
/// Frame = 4-byte big-endian length of (ciphertext+tag) | ciphertext | 16-byte tag; nonce = 4 zero bytes + 8-byte
/// big-endian per-direction counter; AAD = the 4 length bytes. Keys are fresh per connection (HKDF over both nonces).
struct SecureChannel {
    private let sendKey: SymmetricKey
    private let recvKey: SymmetricKey
    private var sendCtr: UInt64 = 0
    private var recvCtr: UInt64 = 0

    /// The Mac is the server: it sends with the m2p key and receives with the p2m key.
    init(secret: Data, nc: Data, ns: Data, label: String) {
        let okm = PK.hkdf(secret, salt: nc + ns, info: "padkey v2 keys \(label)", count: 64)
        recvKey = SymmetricKey(data: okm.prefix(32))
        sendKey = SymmetricKey(data: okm.suffix(32))
    }

    private static func nonce(_ c: UInt64) -> AES.GCM.Nonce {
        var d = Data(count: 4)
        withUnsafeBytes(of: c.bigEndian) { d.append(contentsOf: $0) }
        return try! AES.GCM.Nonce(data: d)
    }

    private static func header(_ n: Int) -> Data {
        var h = UInt32(n).bigEndian
        return withUnsafeBytes(of: &h) { Data($0) }
    }

    mutating func seal(_ plain: Data) -> Data? {
        let hdr = SecureChannel.header(plain.count + 16)
        guard let box = try? AES.GCM.seal(plain, using: sendKey, nonce: SecureChannel.nonce(sendCtr), authenticating: hdr) else { return nil }
        sendCtr += 1
        return hdr + box.ciphertext + box.tag
    }

    /// `body` = ciphertext || tag; `hdr` = the 4 length bytes that preceded it.
    mutating func open(_ body: Data, hdr: Data) -> Data? {
        guard body.count >= 16 else { return nil }
        let ct = body.prefix(body.count - 16), tag = body.suffix(16)
        guard let box = try? AES.GCM.SealedBox(nonce: SecureChannel.nonce(recvCtr), ciphertext: ct, tag: tag),
              let plain = try? AES.GCM.open(box, using: recvKey, authenticating: hdr) else { return nil }
        recvCtr += 1
        return plain
    }
}

// MARK: paired phones

struct PairedPhone: Codable {
    var id: String
    var name: String
    var secret: String   // base64, 32 bytes
    var added: String
}

/// ~/.config/padkey/paired.json (0600). Long-term per-phone secrets; unpaired clients get nothing.
final class PairStore {
    static let shared = PairStore()
    private struct File: Codable { var macId: String; var phones: [PairedPhone] }

    private(set) var macId: String
    private(set) var phones: [PairedPhone]
    private let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".config/padkey")
    private var url: URL { dir.appendingPathComponent("paired.json") }

    private init() {
        macId = PK.random(8).map { String(format: "%02x", $0) }.joined()
        phones = []
        if let d = try? Data(contentsOf: url), let f = try? JSONDecoder().decode(File.self, from: d) {
            macId = f.macId; phones = f.phones
        } else {
            save()
        }
    }

    func secret(for id: String) -> Data? {
        phones.first { $0.id == id }.flatMap { Data(base64Encoded: $0.secret) }
    }

    func add(id: String, name: String, secret: Data) {
        phones.removeAll { $0.id == id }
        phones.append(PairedPhone(id: id, name: name, secret: secret.base64EncodedString(),
                                  added: ISO8601DateFormatter().string(from: Date())))
        save()
    }

    func forgetAll() { phones = []; save() }

    private func save() {
        guard let data = try? JSONEncoder().encode(File(macId: macId, phones: phones)) else { return }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let tmp = dir.appendingPathComponent("paired.json.tmp")
        try? FileManager.default.removeItem(at: tmp)
        FileManager.default.createFile(atPath: tmp.path, contents: data, attributes: [.posixPermissions: 0o600])
        _ = try? FileManager.default.replaceItemAt(url, withItemAt: tmp)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}

/// The 6-digit PIN window opened by "Pair new phone...". Valid 2 minutes, dies after 3 wrong confirmations or one success.
final class Pairing {
    static let shared = Pairing()
    static let lifetime: TimeInterval = 120
    static let maxFailures = 3

    private(set) var pin: String?
    private(set) var expires = Date.distantPast
    private var failures = 0
    /// Main queue. Called when state changes (opened, paired, failed, expired).
    var onChange: ((String) -> Void)?

    var active: Bool { pin != nil && Date() < expires }

    func open() -> String {
        // PADKEY_TEST_PIN is for tools/test_client.py only (headless pairing); never set it in normal use.
        let p = ProcessInfo.processInfo.environment["PADKEY_TEST_PIN"] ?? String(format: "%06d", Int.random(in: 0...999_999))
        pin = p; expires = Date().addingTimeInterval(Pairing.lifetime); failures = 0
        onChange?("open")
        return p
    }

    func close(_ why: String = "closed") { pin = nil; onChange?(why) }

    func failed() {
        failures += 1
        if failures >= Pairing.maxFailures { close("too many wrong PINs") } else { onChange?("wrong PIN") }
    }
}
