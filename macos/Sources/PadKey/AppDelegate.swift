import AppKit
import ApplicationServices
import ServiceManagement

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var item: NSStatusItem!
    private let server = Server()
    private var pollTimer: Timer?
    private var pairWindow: PairWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        let menu = NSMenu()
        menu.delegate = self
        item.menu = menu
        server.onChange = { [weak self] in self?.refreshIcon() }
        server.start()
        // Ask for Accessibility once at launch (shows the system dialog).
        _ = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary)
        if ProcessInfo.processInfo.environment["PADKEY_TEST_PIN"] != nil { _ = Pairing.shared.open() }   // headless test pairing
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
            for ip in Net.ipv4Addresses() {
                let label = Net.isTailscale(ip) ? "\(ip):\(Server.port)  (Tailscale)" : "\(ip):\(Server.port)"
                add(menu, label, action: #selector(copyAddr(_:)), obj: "\(ip):\(Server.port)")
            }
            add(menu, "Bonjour: _padkey._tcp")
        }
        menu.addItem(.separator())
        if trusted {
            add(menu, "Accessibility: granted")
        } else {
            add(menu, "Accessibility NOT granted - click to fix", action: #selector(openAccessibility))
            add(menu, "(System Settings > Privacy & Security > Accessibility > enable PadKey)")
        }
        if CGPreflightScreenCaptureAccess() {
            switch ScreenStreamer.shared.reason {
            case "needs_relaunch":
                add(menu, "Relaunch PadKey to enable Screen Recording", action: #selector(relaunch))
            case "failed":
                add(menu, "Screen Recording: granted, but capture failed")
            default:
                add(menu, "Screen Recording: granted" + (ScreenStreamer.shared.running ? " (mirroring)" : ""))
            }
        } else {
            add(menu, "Screen Recording NOT granted - click to fix", action: #selector(openScreenRecording))
            add(menu, "(System Settings > Privacy & Security > Screen Recording > enable PadKey)")
        }
        menu.addItem(.separator())
        add(menu, "Pair new phone…", action: #selector(pairNew))
        let phones = PairStore.shared.phones
        if phones.isEmpty {
            add(menu, "No phones paired")
        } else {
            add(menu, "Paired phones:")
            for p in phones { add(menu, "   \(p.name)") }
            add(menu, "Forget all phones", action: #selector(forgetAll))
        }
        let login = add(menu, "Launch at login", action: #selector(toggleLogin))
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
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

    @objc private func openScreenRecording() {
        _ = CGRequestScreenCaptureAccess()
        if let u = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
            NSWorkspace.shared.open(u)
        }
    }

    @objc private func pairNew() {
        _ = Pairing.shared.open()
        if pairWindow == nil { pairWindow = PairWindow() }
        pairWindow?.show()
    }

    @objc private func forgetAll() {
        let a = NSAlert()
        a.messageText = "Forget all paired phones?"
        a.informativeText = "Every phone will have to be paired again with a new PIN."
        a.addButton(withTitle: "Forget all")
        a.addButton(withTitle: "Cancel")
        NSApp.activate(ignoringOtherApps: true)
        if a.runModal() == .alertFirstButtonReturn {
            PairStore.shared.forgetAll()
            server.disconnectAll()
        }
    }

    @objc private func toggleLogin() {
        do {
            if SMAppService.mainApp.status == .enabled { try SMAppService.mainApp.unregister() }
            else { try SMAppService.mainApp.register() }
        } catch {
            let a = NSAlert()
            a.messageText = "Could not change Launch at login"
            a.informativeText = "\(error.localizedDescription)\nTry it from ~/Applications/PadKey.app (./build.sh --install), or add it in System Settings > General > Login Items."
            NSApp.activate(ignoringOtherApps: true)
            a.runModal()
        }
    }

    @objc private func relaunch() {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", "sleep 1; /usr/bin/open -n \"$0\"", Bundle.main.bundlePath]
        try? p.run()
        server.injector.releaseAll()
        NSApp.terminate(nil)
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
            guard name.hasPrefix("en") || name.hasPrefix("utun") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(a, socklen_t(a.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            let ip = String(cString: host)
            if name.hasPrefix("utun") && !isTailscale(ip) { continue } // other VPN tunnels
            out.append(ip)
        }
        return out
    }

    /// Tailscale hands out addresses from the CGNAT range 100.64.0.0/10.
    static func isTailscale(_ ip: String) -> Bool {
        let p = ip.split(separator: ".").compactMap { Int($0) }
        return p.count == 4 && p[0] == 100 && (64...127).contains(p[1])
    }
}

/// Small floating window showing the pairing PIN with a countdown. Closes itself when pairing finishes or expires.
final class PairWindow {
    private let panel: NSPanel
    private let pinLabel = NSTextField(labelWithString: "")
    private let info = NSTextField(labelWithString: "")
    private var timer: Timer?

    init() {
        panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 340, height: 200),
                        styleMask: [.titled, .closable], backing: .buffered, defer: false)
        panel.title = "Pair new phone"
        panel.level = .floating
        panel.isReleasedWhenClosed = false
        let head = NSTextField(labelWithString: "Open PadKey on the phone and enter this PIN:")
        pinLabel.font = NSFont.monospacedSystemFont(ofSize: 48, weight: .semibold)
        pinLabel.alignment = .center
        info.textColor = .secondaryLabelColor
        info.alignment = .center
        let cancel = NSButton(title: "Cancel", target: self, action: #selector(cancel))
        let stack = NSStackView(views: [head, pinLabel, info, cancel])
        stack.orientation = .vertical
        stack.spacing = 12
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 20, right: 20)
        panel.contentView = stack
        Pairing.shared.onChange = { [weak self] why in self?.changed(why) }
    }

    func show() {
        refresh()
        panel.center()
        NSApp.activate(ignoringOtherApps: true)
        panel.makeKeyAndOrderFront(nil)
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.refresh() }
    }

    private func refresh() {
        guard let pin = Pairing.shared.pin else { return }
        let left = Int(Pairing.shared.expires.timeIntervalSinceNow.rounded(.up))
        if left <= 0 { Pairing.shared.close("expired"); return }
        pinLabel.stringValue = String(pin.prefix(3)) + " " + String(pin.suffix(3))
        info.stringValue = "Valid for \(left / 60):" + String(format: "%02d", left % 60)
    }

    private func changed(_ why: String) {
        if why.hasPrefix("paired:") {
            pinLabel.stringValue = "Paired"
            info.stringValue = String(why.dropFirst(7))
            timer?.invalidate()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in self?.panel.orderOut(nil) }
        } else if why == "wrong PIN" {
            info.stringValue = "Wrong PIN entered on a phone. Try again."
        } else if why == "open" {
            refresh()
        } else {
            timer?.invalidate()
            pinLabel.stringValue = "-"
            info.stringValue = why == "closed" ? "Pairing cancelled." : "Pairing closed (\(why))."
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.panel.orderOut(nil) }
        }
    }

    @objc private func cancel() {
        timer?.invalidate()
        Pairing.shared.close()
        panel.orderOut(nil)
    }
}
