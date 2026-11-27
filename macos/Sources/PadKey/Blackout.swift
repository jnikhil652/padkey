import AppKit
import CoreGraphics
import IOKit.pwr_mgt

/// "Screen off" that PadKey can always undo.
///
/// `pmset displaysleepnow` puts the displays to real sleep, and with the lid closed (external monitor only)
/// macOS wakes them only on physical input: IOPMAssertionDeclareUserActivity, caffeinate -u, CGEvent posts
/// and IOHIDPostEvent all leave them dark. So instead the displays are blanked (gamma ramp to black) and
/// kept from idle-sleeping while a phone is connected. Gamma resets on its own if PadKey quits or crashes.
final class Blackout {
    static let shared = Blackout()
    /// Marks events PadKey injects, so only real local input lifts the blackout.
    static let injectedTag: Int64 = 0x5061644B   // "PadK"

    private(set) var on = false
    private var monitor: Any?
    private var noIdleSleep: IOPMAssertionID = 0

    private init() {
        CGDisplayRegisterReconfigurationCallback({ _, flags, _ in
            // A display was added / woke / changed mode: its gamma was reset, blank it again.
            if !flags.contains(.beginConfigurationFlag) {
                DispatchQueue.main.async { if Blackout.shared.on { Blackout.shared.apply() } }
            }
        }, nil)
    }

    func set(_ black: Bool) {
        if black == on { return }
        on = black
        if black {
            apply()
            // Someone at the Mac (real keyboard / mouse / trackpad) gets the screen back.
            monitor = NSEvent.addGlobalMonitorForEvents(matching: [.keyDown, .leftMouseDown, .rightMouseDown, .mouseMoved, .scrollWheel]) { e in
                if e.cgEvent?.getIntegerValueField(.eventSourceUserData) != Blackout.injectedTag { Blackout.shared.set(false) }
            }
        } else {
            CGDisplayRestoreColorSyncSettings()
            if let m = monitor { NSEvent.removeMonitor(m); monitor = nil }
            Blackout.wake()
        }
    }

    private func apply() {
        var ids = [CGDirectDisplayID](repeating: 0, count: 16)
        var n: UInt32 = 0
        CGGetOnlineDisplayList(16, &ids, &n)
        for d in ids.prefix(Int(n)) { CGSetDisplayTransferByFormula(d, 0, 0, 1, 0, 0, 1, 0, 0, 1) }   // max 0 = black
    }

    /// While a phone is connected, displays must not idle-sleep: with the lid closed PadKey could not wake them.
    func keepDisplayAwake(_ keep: Bool) {
        if keep, noIdleSleep == 0 {
            IOPMAssertionCreateWithName(kIOPMAssertionTypePreventUserIdleDisplaySleep as CFString,
                                        IOPMAssertionLevel(kIOPMAssertionLevelOn),
                                        "PadKey phone connected" as CFString, &noIdleSleep)
        } else if !keep, noIdleSleep != 0 {
            IOPMAssertionRelease(noIdleSleep)
            noIdleSleep = 0
            set(false)   // nobody left to press Wake
        }
    }

    /// Best effort for displays that are really asleep (works with the lid open; shows the lock screen if locked).
    static func wake() {
        var aid: IOPMAssertionID = 0
        if IOPMAssertionDeclareUserActivity("PadKey wake" as CFString, kIOPMUserActiveLocal, &aid) == kIOReturnSuccess {
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { IOPMAssertionRelease(aid) }
        }
    }

    /// Reported to phones in every pong as `dark`.
    var dark: Bool { on || CGDisplayIsAsleep(CGMainDisplayID()) != 0 }
}
