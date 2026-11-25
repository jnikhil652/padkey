import Cocoa
import CoreGraphics
import IOKit
import IOKit.hidsystem

/// Turns protocol messages into CGEvents. All calls happen on the main queue.
final class Injector {
    private let src = CGEventSource(stateID: .hidSystemState)

    // Pointer state
    private var pos = CGPoint.zero
    private var havePos = false
    private var leftDown = false
    private var rightDown = false
    private var otherDown = false
    private var scrollRemX = 0.0
    private var scrollRemY = 0.0

    // Keyboard state
    private var heldKeys: [Int: CGEventFlags] = [:]
    private var heldFlags: CGEventFlags = []
    private var repeatTimer: DispatchSourceTimer?
    private var repeatCode: Int?

    // Caps Lock (keycode 57): toggled by a tap, kept as state and applied to every following event.
    private(set) var capsOn = CGEventSource.flagsState(.hidSystemState).contains(.maskAlphaShift)
    var onCaps: ((Bool) -> Void)?
    /// Flags applied to every event: held modifiers + Caps Lock.
    private var baseFlags: CGEventFlags { capsOn ? heldFlags.union(.maskAlphaShift) : heldFlags }

    // Cached display rects
    private var rects: [CGRect] = []
    private var rectsStamp = Date.distantPast

    // MARK: flags

    /// Message flag bits: shift 1, ctrl 2, alt 4, cmd 8, fn 16, caps 32.
    static func cgFlags(_ f: Int) -> CGEventFlags {
        var r: CGEventFlags = []
        if f & 1 != 0 { r.insert(.maskShift) }
        if f & 2 != 0 { r.insert(.maskControl) }
        if f & 4 != 0 { r.insert(.maskAlternate) }
        if f & 8 != 0 { r.insert(.maskCommand) }
        if f & 16 != 0 { r.insert(.maskSecondaryFn) }
        if f & 32 != 0 { r.insert(.maskAlphaShift) }
        return r
    }

    private static func modifierFlag(forKey code: Int) -> CGEventFlags? {
        switch code {
        case 54, 55: return .maskCommand
        case 56, 60: return .maskShift
        case 58, 61: return .maskAlternate
        case 59, 62: return .maskControl
        case 63: return .maskSecondaryFn
        default: return nil
        }
    }

    /// Keys that real keyboards report with the fn flag set (arrows, F-keys, nav cluster).
    private static let fnKeys: Set<Int> = [
        123, 124, 125, 126, 115, 119, 116, 121, 117,
        122, 120, 99, 118, 96, 97, 98, 100, 101, 109, 103, 111, 105, 107, 113, 106, 64, 79, 80, 90,
    ]

    // MARK: displays

    private func displayRects() -> [CGRect] {
        if Date().timeIntervalSince(rectsStamp) < 1.0, !rects.isEmpty { return rects }
        var count: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &count)
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        CGGetActiveDisplayList(count, &ids, &count)
        rects = ids.prefix(Int(count)).map { CGDisplayBounds($0) }
        if rects.isEmpty { rects = [CGDisplayBounds(CGMainDisplayID())] }
        rectsStamp = Date()
        return rects
    }

    private func clamp(_ p: CGPoint) -> CGPoint {
        let rs = displayRects()
        if rs.contains(where: { $0.contains(p) }) { return p }
        var best = p
        var bestD = CGFloat.infinity
        for r in rs {
            let c = CGPoint(x: min(max(p.x, r.minX), r.maxX - 1),
                            y: min(max(p.y, r.minY), r.maxY - 1))
            let d = hypot(c.x - p.x, c.y - p.y)
            if d < bestD { bestD = d; best = c }
        }
        return best
    }

    private func syncPos() {
        guard let cur = CGEvent(source: nil)?.location else { return }
        if !havePos || hypot(cur.x - pos.x, cur.y - pos.y) > 3 {
            pos = cur
            havePos = true
        }
    }

    // MARK: mouse

    func move(dx: Double, dy: Double, flags: Int = 0) {
        syncPos()
        let np = clamp(CGPoint(x: pos.x + dx, y: pos.y + dy))
        let type: CGEventType
        let btn: CGMouseButton
        if leftDown { type = .leftMouseDragged; btn = .left }
        else if rightDown { type = .rightMouseDragged; btn = .right }
        else if otherDown { type = .otherMouseDragged; btn = .center }
        else { type = .mouseMoved; btn = .left }
        guard let e = CGEvent(mouseEventSource: src, mouseType: type, mouseCursorPosition: np, mouseButton: btn) else { return }
        e.setIntegerValueField(.mouseEventDeltaX, value: Int64(dx.rounded()))
        e.setIntegerValueField(.mouseEventDeltaY, value: Int64(dy.rounded()))
        e.flags = baseFlags.union(Injector.cgFlags(flags))
        e.post(tap: .cghidEventTap)
        pos = np
    }

    /// Absolute move to a global (CG) point, e.g. a tap on the mirrored screen. Warps synchronously so an
    /// immediately following click lands in the right place.
    func moveTo(_ p: CGPoint, flags: Int = 0) {
        let np = clamp(p)
        let dx = havePos ? np.x - pos.x : 0, dy = havePos ? np.y - pos.y : 0
        let type: CGEventType
        let btn: CGMouseButton
        if leftDown { type = .leftMouseDragged; btn = .left }
        else if rightDown { type = .rightMouseDragged; btn = .right }
        else if otherDown { type = .otherMouseDragged; btn = .center }
        else { type = .mouseMoved; btn = .left }
        CGWarpMouseCursorPosition(np)
        if let e = CGEvent(mouseEventSource: src, mouseType: type, mouseCursorPosition: np, mouseButton: btn) {
            e.setIntegerValueField(.mouseEventDeltaX, value: Int64(dx.rounded()))
            e.setIntegerValueField(.mouseEventDeltaY, value: Int64(dy.rounded()))
            e.flags = baseFlags.union(Injector.cgFlags(flags))
            e.post(tap: .cghidEventTap)
        }
        pos = np
        havePos = true
    }

    func button(_ name: String, down: Bool, clickState: Int = 1, flags: Int = 0) {
        syncPos()
        let type: CGEventType
        let btn: CGMouseButton
        switch name {
        case "right":
            type = down ? .rightMouseDown : .rightMouseUp; btn = .right; rightDown = down
        case "middle":
            type = down ? .otherMouseDown : .otherMouseUp; btn = .center; otherDown = down
        default:
            type = down ? .leftMouseDown : .leftMouseUp; btn = .left; leftDown = down
        }
        let p = CGPoint(x: pos.x.rounded(.down), y: pos.y.rounded(.down))
        guard let e = CGEvent(mouseEventSource: src, mouseType: type, mouseCursorPosition: p, mouseButton: btn) else { return }
        e.setIntegerValueField(.mouseEventClickState, value: Int64(max(1, clickState)))
        e.flags = baseFlags.union(Injector.cgFlags(flags))
        e.post(tap: .cghidEventTap)
    }

    /// One down+up pair whose clickState is `count` (2 = the second click of a double click).
    func click(_ name: String, count: Int, flags: Int = 0) {
        button(name, down: true, clickState: count, flags: flags)
        button(name, down: false, clickState: count, flags: flags)
    }

    /// dx/dy are pixels the content should move (positive dy = content moves down).
    func scroll(dx: Double, dy: Double, flags: Int = 0) {
        scrollRemX += dx
        scrollRemY += dy
        let ix = Int32(scrollRemX.rounded(.towardZero))
        let iy = Int32(scrollRemY.rounded(.towardZero))
        scrollRemX -= Double(ix)
        scrollRemY -= Double(iy)
        if ix == 0 && iy == 0 { return }
        guard let e = CGEvent(scrollWheelEvent2Source: src, units: .pixel, wheelCount: 2, wheel1: iy, wheel2: ix, wheel3: 0) else { return }
        e.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
        e.flags = baseFlags.union(Injector.cgFlags(flags))
        e.post(tap: .cghidEventTap)
    }

    // MARK: keyboard

    private func postKey(_ code: Int, down: Bool, flags: CGEventFlags, autorepeat: Bool = false) {
        guard let e = CGEvent(keyboardEventSource: src, virtualKey: CGKeyCode(code), keyDown: down) else { return }
        var f = flags
        if Injector.fnKeys.contains(code) { f.insert(.maskSecondaryFn) }
        e.flags = f
        if Injector.modifierFlag(forKey: code) != nil { e.type = .flagsChanged }
        if autorepeat { e.setIntegerValueField(.keyboardEventAutorepeat, value: 1) }
        e.post(tap: .cghidEventTap)
    }

    func key(code: Int, down: Bool, flags: Int = 0) {
        if let mf = Injector.modifierFlag(forKey: code) {
            if down { heldFlags.insert(mf) } else { heldFlags.remove(mf) }
            postKey(code, down: down, flags: baseFlags)
            if down { heldKeys[code] = heldFlags } else { heldKeys[code] = nil }
            return
        }
        if code == 57 {   // Caps Lock: a tap toggles it
            if down { toggleCaps() }
            return
        }
        if down {
            stopRepeat()
            let f = baseFlags.union(Injector.cgFlags(flags))
            heldKeys[code] = f
            postKey(code, down: true, flags: f)
            startRepeat(code, f)
        } else {
            if repeatCode == code { stopRepeat() }
            let f = heldKeys[code] ?? baseFlags.union(Injector.cgFlags(flags))
            heldKeys[code] = nil
            postKey(code, down: false, flags: f)
        }
    }

    private func toggleCaps() {
        capsOn.toggle()
        // Also flip the real system Caps Lock state (LED, other apps); the flags below cover apps that read events.
        let h = NXOpenEventStatus()
        if h != 0 {
            _ = IOHIDSetModifierLockState(h, Int32(kIOHIDCapsLockState), capsOn)
            NXCloseEventStatus(h)
        }
        if let e = CGEvent(keyboardEventSource: src, virtualKey: 57, keyDown: true) {
            e.type = .flagsChanged
            e.flags = baseFlags
            e.post(tap: .cghidEventTap)
        }
        onCaps?(capsOn)
    }

    func tap(_ code: Int, flags: Int = 0) {
        key(code: code, down: true, flags: flags)
        key(code: code, down: false, flags: flags)
    }

    private func startRepeat(_ code: Int, _ f: CGEventFlags) {
        repeatCode = code
        let t = DispatchSource.makeTimerSource(queue: .main)
        t.schedule(deadline: .now() + 0.4, repeating: 0.035)
        t.setEventHandler { [weak self] in
            self?.postKey(code, down: true, flags: f, autorepeat: true)
        }
        t.resume()
        repeatTimer = t
    }

    private func stopRepeat() {
        repeatTimer?.cancel()
        repeatTimer = nil
        repeatCode = nil
    }

    /// Release everything that might be stuck (connection dropped).
    func releaseAll() {
        stopRepeat()
        for (code, f) in heldKeys where Injector.modifierFlag(forKey: code) == nil {
            postKey(code, down: false, flags: f)
        }
        for (code, _) in heldKeys where Injector.modifierFlag(forKey: code) != nil {
            if let mf = Injector.modifierFlag(forKey: code) { heldFlags.remove(mf) }
            postKey(code, down: false, flags: baseFlags)
        }
        heldKeys.removeAll()
        heldFlags = []
        if leftDown { button("left", down: false) }
        if rightDown { button("right", down: false) }
        if otherDown { button("middle", down: false) }
    }

    // MARK: text

    private enum TypeOp {
        case chunk([UniChar])
        case key(Int)
        case paste(String)
        case pause(TimeInterval)
    }
    private final class TypeJob {
        var ops: [TypeOp]
        let done: (() -> Void)?
        init(_ ops: [TypeOp], _ done: (() -> Void)?) { self.ops = ops; self.done = done }
    }
    private var typeJobs: [TypeJob] = []
    private var typeBusy = false

    /// Types a unicode string as CGEvent keystrokes (<=16 UTF-16 units per event, ~8 ms between events so apps keep up).
    /// Jobs run in order; `done` fires when this one has been fully posted.
    func text(_ s: String, enter: Bool = false, done: (() -> Void)? = nil) {
        var ops: [TypeOp] = []
        var buf: [UniChar] = []
        func flush() { if !buf.isEmpty { ops.append(.chunk(buf)); buf.removeAll() } }
        for ch in s {
            if ch.isNewline { flush(); ops.append(.key(36)) }
            else if ch == "\t" { flush(); ops.append(.key(48)) }
            else {
                let u = Array(String(ch).utf16)
                if buf.count + u.count > 16 { flush() }
                buf.append(contentsOf: u)
            }
        }
        flush()
        if enter { ops.append(.key(36)) }
        enqueue(ops, done)
    }

    /// Pastes `s` through the clipboard: saves every pasteboard item, sets the text, posts Cmd+V, restores the
    /// previous contents ~400 ms later (unless something else changed the clipboard meanwhile).
    func paste(_ s: String, enter: Bool = false, done: (() -> Void)? = nil) {
        var ops: [TypeOp] = [.paste(s), .pause(enter ? 0.14 : 0.05)]
        if enter { ops.append(.key(36)) }
        enqueue(ops, done)
    }

    private func enqueue(_ ops: [TypeOp], _ done: (() -> Void)?) {
        typeJobs.append(TypeJob(ops, done))
        if !typeBusy { typeBusy = true; stepType() }
    }

    private func stepType() {
        guard let job = typeJobs.first else { typeBusy = false; return }
        if job.ops.isEmpty {
            typeJobs.removeFirst()
            job.done?()
            stepType()
            return
        }
        var delay = 0.008
        switch job.ops.removeFirst() {
        case .chunk(let u): postUnicode(u)
        case .key(let c): tap(c)
        case .paste(let s): doPaste(s); delay = 0.0
        case .pause(let d): delay = d
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in self?.stepType() }
    }

    private func postUnicode(_ u: [UniChar]) {
        for down in [true, false] {
            guard let e = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: down) else { continue }
            e.flags = capsOn ? .maskAlphaShift : []
            var chars = u
            e.keyboardSetUnicodeString(stringLength: chars.count, unicodeString: &chars)
            e.post(tap: .cghidEventTap)
        }
    }

    private var savedBoard: [[(NSPasteboard.PasteboardType, Data)]]?
    private var restoreWork: DispatchWorkItem?
    private var ourChange = 0

    private func doPaste(_ s: String) {
        let pb = NSPasteboard.general
        if savedBoard == nil {   // keep the first snapshot while a restore is still pending
            savedBoard = (pb.pasteboardItems ?? []).map { item in
                item.types.compactMap { t in item.data(forType: t).map { (t, $0) } }
            }
        }
        restoreWork?.cancel()
        let item = NSPasteboardItem()
        item.setString(s, forType: .string)
        // Clipboard managers skip transient items.
        item.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.TransientType"))
        pb.clearContents()
        pb.writeObjects([item])
        ourChange = pb.changeCount
        if let v = CGEvent(keyboardEventSource: src, virtualKey: 9, keyDown: true),
           let u = CGEvent(keyboardEventSource: src, virtualKey: 9, keyDown: false) {
            for e in [v, u] { e.flags = .maskCommand; e.post(tap: .cghidEventTap) }
        }
        let w = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            let pb = NSPasteboard.general
            if pb.changeCount == self.ourChange, let saved = self.savedBoard {
                pb.clearContents()
                let items: [NSPasteboardItem] = saved.map { entries in
                    let it = NSPasteboardItem()
                    for (t, d) in entries { it.setData(d, forType: t) }
                    return it
                }
                if !items.isEmpty { pb.writeObjects(items) }
            }
            self.savedBoard = nil
        }
        restoreWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: w)
    }

    /// Puts a phone photo on the clipboard (PNG + TIFF) and pastes it: Ctrl+V in terminals (Claude Code and other
    /// TUIs read images from the clipboard on Ctrl+V), Cmd+V elsewhere. The image stays on the clipboard so it can be pasted again.
    func pasteImage(_ data: Data, done: (() -> Void)? = nil) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, let img = NSImage(data: data), let tiff = img.tiffRepresentation,
                  let rep = NSBitmapImageRep(data: tiff) else { done?(); return }
            self.restoreWork?.cancel(); self.restoreWork = nil; self.savedBoard = nil
            let pb = NSPasteboard.general
            pb.clearContents()
            let item = NSPasteboardItem()
            if let png = rep.representation(using: .png, properties: [:]) { item.setData(png, forType: .png) }
            item.setData(tiff, forType: .tiff)
            pb.writeObjects([item])
            let terminals: Set<String> = ["com.apple.Terminal", "com.googlecode.iterm2", "com.mitchellh.ghostty", "dev.warp.Warp-Stable",
                                          "net.kovidgoyal.kitty", "com.github.wez.wezterm", "org.alacritty", "co.zeit.hyper", "com.raphaelamorim.rio"]
            let front = NSWorkspace.shared.frontmostApplication?.bundleIdentifier ?? ""
            let flag: CGEventFlags = terminals.contains(front) ? .maskControl : .maskCommand
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
                if let v = CGEvent(keyboardEventSource: self.src, virtualKey: 9, keyDown: true),
                   let u = CGEvent(keyboardEventSource: self.src, virtualKey: 9, keyDown: false) {
                    for e in [v, u] { e.flags = flag; e.post(tap: .cghidEventTap) }
                }
                done?()
            }
        }
    }

    /// Posts Cmd+C and, once the clipboard changed (or after ~0.6 s), returns its text (nil if nothing was copied).
    func copySelection(_ done: @escaping (String?) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let pb = NSPasteboard.general
            let before = pb.changeCount
            if let v = CGEvent(keyboardEventSource: self.src, virtualKey: 8, keyDown: true),
               let u = CGEvent(keyboardEventSource: self.src, virtualKey: 8, keyDown: false) {
                for e in [v, u] { e.flags = .maskCommand; e.post(tap: .cghidEventTap) }
            }
            var tries = 0
            func check() {
                tries += 1
                if pb.changeCount != before { done(pb.string(forType: .string)); return }
                if tries >= 12 { done(nil); return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05, execute: check)
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05, execute: check)
        }
    }

    // MARK: media / system keys

    private static let mediaKeys: [String: Int32] = [
        "volup": 0, "voldown": 1, "brightup": 2, "brightdown": 3, "mute": 7,
        "play": 16, "next": 17, "prev": 18, "fast": 19, "rewind": 20,
        "kbup": 21, "kbdown": 22,
    ]

    func media(_ name: String) {
        guard let k = Injector.mediaKeys[name] else { return }
        for down in [true, false] {
            let flags: UInt = down ? 0xa00 : 0xb00
            let data1 = Int((k << 16) | ((down ? 0xa : 0xb) << 8))
            let ev = NSEvent.otherEvent(with: .systemDefined, location: .zero,
                                        modifierFlags: NSEvent.ModifierFlags(rawValue: flags),
                                        timestamp: 0, windowNumber: 0, context: nil,
                                        subtype: 8, data1: data1, data2: -1)
            ev?.cgEvent?.post(tap: .cghidEventTap)
        }
    }
}
