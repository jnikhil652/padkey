import AppKit
import ApplicationServices

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var item: NSStatusItem!
    private let server = Server()
    private var pollTimer: Timer?

    func applicationDidFinishLaunching(_ notification: Notification) {
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        let menu = NSMenu()
        menu.delegate = self
        item.menu = menu
        server.onChange = { [weak self] in self?.refreshIcon() }
        server.start()
        // Ask for Accessibility once at launch (shows the system dialog).
        _ = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary)
        refreshIcon()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in self?.refreshIcon() }
    }

    private var trusted: Bool { AXIsProcessTrusted() }

    private func refreshIcon() {
        guard let b = item.button else { return }
        let connected = !server.clientNames.isEmpty
        let name = connected ? "keyboard.fill" : "keyboard"
        if let img = NSImage(systemSymbolName: name, accessibilityDescription: "PadKey") {
            img.isTemplate = true
            b.image = img
            b.title = trusted ? "" : " !"
        } else {
            b.title = connected ? "PK*" : "PK"
        }
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let names = server.clientNames
        add(menu, names.isEmpty ? "Waiting for phone" : "Connected: \(names.joined(separator: ", "))")
        if let e = server.listenerError {
            add(menu, "Server error: \(e)")
        } else {
            for ip in Net.ipv4Addresses() { add(menu, "\(ip):\(Server.port)", action: #selector(copyAddr(_:)), obj: "\(ip):\(Server.port)") }
            add(menu, "Bonjour: _padkey._tcp")
        }
        menu.addItem(.separator())
        if trusted {
            add(menu, "Accessibility: granted")
        } else {
            add(menu, "Accessibility NOT granted - click to fix", action: #selector(openAccessibility))
            add(menu, "(System Settings > Privacy & Security > Accessibility > enable PadKey)")
        }
        add(menu, Server.token.isEmpty ? "Token: none (open to LAN)" : "Token: required (~/.config/padkey/token)")
        menu.addItem(.separator())
        let q = NSMenuItem(title: "Quit PadKey", action: #selector(quit), keyEquivalent: "q")
        q.target = self
        menu.addItem(q)
    }

    @discardableResult
    private func add(_ menu: NSMenu, _ title: String, action: Selector? = nil, obj: Any? = nil) -> NSMenuItem {
        let it = NSMenuItem(title: title, action: action, keyEquivalent: "")
        if action != nil { it.target = self; it.representedObject = obj } else { it.isEnabled = false }
        menu.addItem(it)
        return it
    }

    @objc private func copyAddr(_ s: NSMenuItem) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(s.representedObject as? String ?? "", forType: .string)
    }

    @objc private func openAccessibility() {
        _ = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary)
        if let u = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(u)
        }
    }

    @objc private func quit() {
        server.injector.releaseAll()
        NSApp.terminate(nil)
    }
}

enum Net {
    static func ipv4Addresses() -> [String] {
        var out: [String] = []
        var ifa: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifa) == 0, let first = ifa else { return out }
        defer { freeifaddrs(ifa) }
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let i = p.pointee
            guard let a = i.ifa_addr, a.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: i.ifa_name)
            guard name.hasPrefix("en") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(a, socklen_t(a.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            out.append(String(cString: host))
        }
        return out
    }
}
