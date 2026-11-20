import Cocoa
import CoreGraphics

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
        e.flags = heldFlags.union(Injector.cgFlags(flags))
        e.post(tap: .cghidEventTap)
        pos = np
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
        e.flags = heldFlags.union(Injector.cgFlags(flags))
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
        e.flags = heldFlags.union(Injector.cgFlags(flags))
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
            postKey(code, down: down, flags: heldFlags)
            if down { heldKeys[code] = heldFlags } else { heldKeys[code] = nil }
            return
        }
        if down {
            stopRepeat()
            let f = heldFlags.union(Injector.cgFlags(flags))
            heldKeys[code] = f
            postKey(code, down: true, flags: f)
            startRepeat(code, f)
        } else {
            if repeatCode == code { stopRepeat() }
            let f = heldKeys[code] ?? heldFlags.union(Injector.cgFlags(flags))
            heldKeys[code] = nil
            postKey(code, down: false, flags: f)
        }
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
            postKey(code, down: false, flags: heldFlags)
        }
        heldKeys.removeAll()
        heldFlags = []
        if leftDown { button("left", down: false) }
        if rightDown { button("right", down: false) }
        if otherDown { button("middle", down: false) }
    }

    // MARK: text

    func text(_ s: String) {
        var buf: [UniChar] = []
        func flush() {
            if buf.isEmpty { return }
            for down in [true, false] {
                guard let e = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: down) else { continue }
                e.flags = []
                e.keyboardSetUnicodeString(stringLength: buf.count, unicodeString: buf)
                e.post(tap: .cghidEventTap)
            }
            buf.removeAll(keepingCapacity: true)
        }
        for ch in s {
            if ch.isNewline { flush(); tap(36) }
            else if ch == "\t" { flush(); tap(48) }
            else {
                let u = Array(String(ch).utf16)
                if buf.count + u.count > 16 { flush() }
                buf.append(contentsOf: u)
            }
        }
        flush()
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
