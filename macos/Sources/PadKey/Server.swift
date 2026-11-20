import Foundation
import Network

/// TCP server speaking newline-delimited JSON (see ../PROTOCOL.md).
final class Server {
    static let port: UInt16 = 50505

    let injector = Injector()
    var onChange: (() -> Void)?
    private(set) var listenerError: String?
    private(set) var listening = false
    private var listener: NWListener?
    private var conns: [ObjectIdentifier: Conn] = [:]

    /// Optional shared token: ~/.config/padkey/token (one line). Empty/missing = open.
    static var token: String {
        let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".config/padkey/token")
        let s = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var clientNames: [String] { conns.values.filter { $0.authed }.map { $0.remote } }

    func start() {
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

    fileprivate func closed(_ c: Conn) {
        conns[ObjectIdentifier(c)] = nil
        if conns.isEmpty { injector.releaseAll() }
        onChange?()
    }
}

private final class Conn {
    let nw: NWConnection
    unowned let server: Server
    var buf = Data()
    var authed: Bool
    var lastRx = Date()
    var timer: DispatchSourceTimer?
    var done = false
    let remote: String

    init(_ nw: NWConnection, server: Server) {
        self.nw = nw
        self.server = server
        self.authed = Server.token.isEmpty
        if case let .hostPort(host, _) = nw.endpoint {
            remote = "\(host)".components(separatedBy: "%").first ?? "\(host)"
        } else { remote = "phone" }
    }

    func start() {
        nw.stateUpdateHandler = { [weak self] st in
            switch st {
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        nw.start(queue: .main)
        // Drop dead peers (phone sends a ping every ~3 s).
        let t = DispatchSource.makeTimerSource(queue: .main)
        t.schedule(deadline: .now() + 5, repeating: 5)
        t.setEventHandler { [weak self] in
            guard let self = self else { return }
            if Date().timeIntervalSince(self.lastRx) > 12 { self.close() }
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
        while let nl = buf.firstIndex(of: 10) {
            let line = buf.subdata(in: buf.startIndex..<nl)
            buf.removeSubrange(buf.startIndex...nl)
            if !line.isEmpty { handle(line) }
        }
        if buf.count > 1_000_000 { close() }
    }

    func send(_ obj: [String: Any]) {
        guard var d = try? JSONSerialization.data(withJSONObject: obj) else { return }
        d.append(10)
        nw.send(content: d, completion: .contentProcessed { _ in })
    }

    func handle(_ line: Data) {
        guard let o = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any],
              let t = o["t"] as? String else { return }
        func num(_ k: String) -> Double { (o[k] as? NSNumber)?.doubleValue ?? 0 }
        func int(_ k: String, _ def: Int = 0) -> Int { (o[k] as? NSNumber)?.intValue ?? def }
        let inj = server.injector

        if t == "hello" {
            let want = Server.token
            if want.isEmpty || (o["token"] as? String) == want {
                authed = true
                send(["t": "hello", "ok": true, "name": Host.current().localizedName ?? "Mac", "v": 1])
                server.onChange?()
            } else {
                send(["t": "error", "msg": "bad token"])
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in self?.close() }
            }
            return
        }
        if t == "ping" { send(["t": "pong", "id": o["id"] ?? 0]); return }
        guard authed else { return }

        switch t {
        case "move": inj.move(dx: num("dx"), dy: num("dy"), flags: int("f"))
        case "scroll": inj.scroll(dx: num("dx"), dy: num("dy"), flags: int("f"))
        case "btn": inj.button(o["b"] as? String ?? "left", down: (o["d"] as? Bool) ?? ((o["d"] as? NSNumber)?.boolValue ?? true), clickState: int("n", 1), flags: int("f"))
        case "click": inj.click(o["b"] as? String ?? "left", count: int("n", 1), flags: int("f"))
        case "key": inj.key(code: int("c"), down: (o["d"] as? NSNumber)?.boolValue ?? true, flags: int("f"))
        case "text": if let s = o["s"] as? String { inj.text(s) }
        case "media": if let k = o["k"] as? String { inj.media(k) }
        case "release": inj.releaseAll()
        default: break
        }
    }

    func close() {
        if done { return }
        done = true
        timer?.cancel()
        nw.cancel()
        server.closed(self)
    }
}
