import AppKit
import CoreGraphics
import CryptoKit
import Foundation
import Network

/// TCP server (protocol v2, see ../../PROTOCOL.md): a plaintext hello/pair phase, then AES-256-GCM frames.
final class Server {
    static let port: UInt16 = 50505

    let injector = Injector()
    var onChange: (() -> Void)?
    private(set) var listenerError: String?
    private(set) var listening = false
    private var listener: NWListener?
    private var conns: [ObjectIdentifier: Conn] = [:]
    private var activity: NSObjectProtocol?
    /// Ids of recently handled text/paste messages, so a resend after a reconnect is not typed twice.
    fileprivate var recentTextIds: [Int64] = []

    var clientNames: [String] { conns.values.filter { $0.authed }.map { $0.phoneName } }

    /// Per-connection screen-mirror requests (display index, quality); capture runs while any phone wants it.
    fileprivate var screenWanted: [ObjectIdentifier: (display: Int, quality: Int)] = [:]
    private var cursorTimer: DispatchSourceTimer?

    fileprivate func screenRequest(_ c: Conn, on: Bool, display: Int?, quality: Int?) {
        let id = ObjectIdentifier(c)
        if on {
            let old = screenWanted[id]
            screenWanted[id] = (display ?? old?.display ?? Displays.selected, quality ?? old?.quality ?? 0)
        } else { screenWanted[id] = nil }
        if on, let d = display { Displays.selected = d }
        applyScreenRequest()
        if on { c.sendScreenInfo() }
        updateCursorTimer()
    }

    private func applyScreenRequest() {
        let q = screenWanted.values.map { $0.quality }.max() ?? 0
        ScreenStreamer.shared.setRequest(on: !screenWanted.isEmpty, index: Displays.selected, quality: q)
    }

    private func updateCursorTimer() {
        if screenWanted.isEmpty { cursorTimer?.cancel(); cursorTimer = nil; return }
        if cursorTimer != nil { return }
        let t = DispatchSource.makeTimerSource(queue: .main)
        t.schedule(deadline: .now(), repeating: 1.0 / 15)
        var last = CGPoint(x: -1, y: -1)
        t.setEventHandler { [weak self] in
            guard let self = self, let loc = CGEvent(source: nil)?.location else { return }
            let b = Displays.bounds(at: Displays.selected)
            let p = CGPoint(x: (loc.x - b.minX) / max(b.width, 1), y: (loc.y - b.minY) / max(b.height, 1))
            if hypot(p.x - last.x, p.y - last.y) < 0.0005 { return }
            last = p
            for c in self.conns.values where c.authed && self.screenWanted[ObjectIdentifier(c)] != nil {
                c.send(["t": "cursor", "x": Double(p.x), "y": Double(p.y)])
            }
        }
        t.resume()
        cursorTimer = t
    }

    fileprivate func broadcastScreenInfo() {
        for c in conns.values where c.authed && screenWanted[ObjectIdentifier(c)] != nil { c.sendScreenInfo() }
    }

    func broadcast(_ obj: [String: Any]) {
        for c in conns.values where c.authed { c.send(obj) }
    }

    /// Drop every connection (used by "Forget all phones").
    func disconnectAll() {
        for c in Array(conns.values) { c.close() }
        ScreenStreamer.shared.dropAllViewers()
    }

    /// App Nap throttles timers and network for idle accessory apps; hold an activity while a phone is connected.
    fileprivate func updateActivity() {
        let any = conns.values.contains { $0.authed }
        if any, activity == nil {
            activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .latencyCritical],
                                                             reason: "PadKey phone connected")
        } else if !any, let a = activity {
            ProcessInfo.processInfo.endActivity(a)
            activity = nil
        }
    }

    func start() {
        ScreenStreamer.shared.startListener()
        ScreenStreamer.shared.onState = { [weak self] in self?.broadcastScreenInfo() }
        injector.onCaps = { [weak self] on in self?.broadcast(["t": "caps", "on": on]) }
        let params = NWParameters.tcp
        if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcp.noDelay = true
            tcp.enableKeepalive = true
            tcp.keepaliveIdle = 5
            tcp.keepaliveInterval = 2
            tcp.keepaliveCount = 3
        }
        params.allowLocalEndpointReuse = true
        do {
            let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: Server.port)!)
            l.service = NWListener.Service(name: Host.current().localizedName ?? "Mac", type: "_padkey._tcp")
            l.stateUpdateHandler = { [weak self] st in
                guard let self = self else { return }
                switch st {
                case .ready: self.listening = true; self.listenerError = nil
                case .failed(let e): self.listening = false; self.listenerError = "\(e)"
                default: break
                }
                self.onChange?()
            }
            l.newConnectionHandler = { [weak self] c in self?.accept(c) }
            l.start(queue: .main)
            listener = l
        } catch {
            listenerError = "\(error)"
            onChange?()
        }
    }

    private func accept(_ nw: NWConnection) {
        let c = Conn(nw, server: self)
        conns[ObjectIdentifier(c)] = c
        c.start()
        onChange?()
    }

    fileprivate func authenticated(_ c: Conn) {
        updateActivity()
        onChange?()
    }

    fileprivate func closed(_ c: Conn) {
        conns[ObjectIdentifier(c)] = nil
        if screenWanted.removeValue(forKey: ObjectIdentifier(c)) != nil {
            applyScreenRequest()
            updateCursorTimer()
        }
        if !conns.values.contains(where: { $0.authed }) { injector.releaseAll() }
        updateActivity()
        onChange?()
    }
}

private final class Conn {
    let nw: NWConnection
    unowned let server: Server
    var buf = Data()
    /// True after the first frame decrypted with the session key (proves the phone holds the long-term secret).
    var authed = false
    var channel: SecureChannel?
    var lastRx = Date()
    let born = Date()
    var timer: DispatchSourceTimer?
    var done = false
    var phoneName = "phone"
    // pairing in progress
    var pair: (keys: PK.PairKeys, id: String, name: String, shared: Data)?

    init(_ nw: NWConnection, server: Server) {
        self.nw = nw
        self.server = server
    }

    func start() {
        nw.stateUpdateHandler = { [weak self] st in
            switch st {
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        nw.start(queue: .main)
        // Drop dead peers (phone pings every ~2.5 s) and clients that never get through hello/pairing.
        let t = DispatchSource.makeTimerSource(queue: .main)
        t.schedule(deadline: .now() + 5, repeating: 5)
        t.setEventHandler { [weak self] in
            guard let self = self else { return }
            if Date().timeIntervalSince(self.lastRx) > 12 { self.close() }
            if !self.authed && Date().timeIntervalSince(self.born) > 30 { self.close() }
        }
        t.resume()
        timer = t
        receive()
    }

    func receive() {
        nw.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, complete, error in
            guard let self = self, !self.done else { return }
            if let d = data, !d.isEmpty {
                self.lastRx = Date()
                self.buf.append(d)
                self.drain()
            }
            if complete || error != nil { self.close(); return }
            self.receive()
        }
    }

    func drain() {
        while !done {
            if channel == nil {
                // plaintext phase: one JSON object per line
                guard let nl = buf.firstIndex(of: 10) else { break }
                let line = buf.subdata(in: buf.startIndex..<nl)
                buf.removeSubrange(buf.startIndex...nl)
                if !line.isEmpty { handleLine(line) }
            } else {
                guard buf.count >= 4 else { break }
                let hdr = Data(buf.prefix(4))
                let n = Int(hdr.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self).bigEndian })
                if n < 16 || n > PK.maxControlFrame { close(); return }
                guard buf.count >= 4 + n else { break }
                let body = Data(buf.subdata(in: buf.startIndex + 4..<buf.startIndex + 4 + n))
                buf.removeSubrange(buf.startIndex..<buf.startIndex + 4 + n)
                guard let plain = channel?.open(body, hdr: hdr) else { close(); return }   // bad tag / replay / forgery
                if !authed { authed = true; server.authenticated(self) }
                handle(plain)
            }
        }
        if buf.count > 2_000_000 { close() }
    }

    /// Plaintext send (handshake replies) or, once the session is secure, an encrypted frame.
    func send(_ obj: [String: Any]) {
        guard var d = try? JSONSerialization.data(withJSONObject: obj) else { return }
        if channel != nil {
            guard let framed = channel?.seal(d) else { return }
            nw.send(content: framed, completion: .contentProcessed { _ in })
        } else {
            d.append(10)
            nw.send(content: d, completion: .contentProcessed { _ in })
        }
    }

    func sendScreenInfo() {
        let b = Displays.bounds(at: Displays.selected)
        let st = ScreenStreamer.shared
        let perm = CGPreflightScreenCaptureAccess()
        send(["t": "screeninfo", "w": Int(b.width), "h": Int(b.height), "sel": Displays.selected,
              "displays": Displays.list(), "ok": st.reason == nil, "perm": perm, "reason": st.reason ?? "ok"])
    }

    private func fail(_ msg: String, code: String? = nil) {
        var o: [String: Any] = ["t": "error", "msg": msg]
        if let c = code { o["code"] = c }
        send(o)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in self?.close() }
    }

    // MARK: plaintext phase: hello / pair

    func handleLine(_ line: Data) {
        guard let o = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any],
              let t = o["t"] as? String else { return }
        switch t {
        case "hello":
            guard (o["v"] as? NSNumber)?.intValue ?? 1 >= PK.version else {
                return fail("PadKey protocol \(PK.version) needed: update the phone app", code: "version")
            }
            guard let id = o["id"] as? String, let secret = PairStore.shared.secret(for: id) else {
                return fail("not paired", code: "not_paired")
            }
            guard let nc = PK.unb64(o["n"]), nc.count >= 16, nc.count <= 64 else { return fail("bad hello") }
            let ns = PK.random(16)
            phoneName = PairStore.shared.phones.first { $0.id == id }?.name ?? "phone"
            send(["t": "hello", "ok": true, "v": PK.version, "name": Host.current().localizedName ?? "Mac",
                  "mid": PairStore.shared.macId, "n": PK.b64(ns),
                  "auth": PK.b64(PK.macAuth(secret: secret, label: "ctl", nc: nc, ns: ns))])
            channel = SecureChannel(secret: secret, nc: nc, ns: ns, label: "ctl")
        case "pair":
            guard Pairing.shared.active, let pin = Pairing.shared.pin else {
                return fail("pairing is not open: choose \"Pair new phone\" in the Mac menu", code: "pair_closed")
            }
            guard let pubData = PK.unb64(o["pub"]), pubData.count == 65, let id = o["id"] as? String, !id.isEmpty,
                  let phonePub = try? P256.KeyAgreement.PublicKey(x963Representation: pubData) else { return fail("bad pair") }
            let priv = P256.KeyAgreement.PrivateKey()
            guard let ss = try? priv.sharedSecretFromKeyAgreement(with: phonePub) else { return fail("bad pair") }
            let shared = ss.withUnsafeBytes { Data($0) }
            let macPub = priv.publicKey.x963Representation
            let keys = PK.pairKeys(shared: shared, pin: pin, phonePub: pubData, macPub: macPub)
            pair = (keys, id, String((o["name"] as? String ?? "phone").prefix(40)), shared)
            send(["t": "pair_pub", "pub": PK.b64(macPub)])
        case "pair_confirm":
            guard let p = pair, Pairing.shared.active else { return fail("pairing expired", code: "pair_closed") }
            guard let m = PK.unb64(o["m"]), PK.macValid(m, key: p.keys.confirmKey, Data("phone".utf8) + p.keys.transcript) else {
                Pairing.shared.failed()
                return fail("wrong PIN", code: "wrong_pin")
            }
            let secret = PK.random(32)
            let nonce = AES.GCM.Nonce()
            guard let box = try? AES.GCM.seal(secret, using: SymmetricKey(data: p.keys.wrapKey), nonce: nonce,
                                              authenticating: Data(p.id.utf8)) else { return fail("pair failed") }
            PairStore.shared.add(id: p.id, name: p.name, secret: secret)
            send(["t": "pair_ok", "mid": PairStore.shared.macId, "name": Host.current().localizedName ?? "Mac",
                  "m": PK.b64(PK.macConfirm(p.keys)), "iv": PK.b64(Data(nonce)), "ct": PK.b64(box.ciphertext + box.tag)])
            pair = nil
            Pairing.shared.close("paired:\(p.name)")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.close() }
        default: break   // unpaired clients may only hello / pair
        }
    }

    // MARK: secure phase

    func handle(_ plain: Data) {
        guard let o = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any],
              let t = o["t"] as? String else { return }
        func num(_ k: String) -> Double { (o[k] as? NSNumber)?.doubleValue ?? 0 }
        func int(_ k: String, _ def: Int = 0) -> Int { (o[k] as? NSNumber)?.intValue ?? def }
        let inj = server.injector

        switch t {
        case "ping":
            send(["t": "pong", "id": o["id"] ?? 0])
            if !sentCaps { sentCaps = true; send(["t": "caps", "on": inj.capsOn]) }
        case "move": inj.move(dx: num("dx"), dy: num("dy"), flags: int("f"))
        case "scroll": inj.scroll(dx: num("dx"), dy: num("dy"), flags: int("f"))
        case "btn": inj.button(o["b"] as? String ?? "left", down: (o["d"] as? NSNumber)?.boolValue ?? true, clickState: int("n", 1), flags: int("f"))
        case "click": inj.click(o["b"] as? String ?? "left", count: int("n", 1), flags: int("f"))
        case "key": inj.key(code: int("c"), down: (o["d"] as? NSNumber)?.boolValue ?? true, flags: int("f"))
        case "text", "paste":
            guard let s = o["s"] as? String else { return }
            let id = (o["id"] as? NSNumber)?.int64Value
            let enter = (o["enter"] as? NSNumber)?.boolValue ?? false
            if let id = id, server.recentTextIds.contains(id) { send(["t": "ack", "id": id]); return }  // resend after reconnect
            if let id = id { server.recentTextIds.append(id); if server.recentTextIds.count > 64 { server.recentTextIds.removeFirst() } }
            let ack: () -> Void = { [weak self] in if let id = id { self?.send(["t": "ack", "id": id]) } }
            if t == "paste" { inj.paste(s, enter: enter, done: ack) } else { inj.text(s, enter: enter, done: ack) }
        case "media": if let k = o["k"] as? String { inj.media(k) }
        case "release": inj.releaseAll()
        case "screen":
            let on = (o["on"] as? NSNumber)?.boolValue ?? true
            if on && !CGPreflightScreenCaptureAccess() { _ = CGRequestScreenCaptureAccess() }
            server.screenRequest(self, on: on, display: (o["display"] as? NSNumber)?.intValue,
                                 quality: (o["q"] as? NSNumber)?.intValue)
        case "moveto":
            let b = Displays.bounds(at: Displays.selected)
            let x = min(max(num("x"), 0), 1), y = min(max(num("y"), 0), 1)
            inj.moveTo(CGPoint(x: b.minX + x * (b.width - 1), y: b.minY + y * (b.height - 1)), flags: int("f"))
        default: break
        }
    }
    private var sentCaps = false

    func close() {
        if done { return }
        done = true
        timer?.cancel()
        nw.cancel()
        server.closed(self)
    }
}
