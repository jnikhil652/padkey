import AppKit
import CoreGraphics
import CoreMedia
import Network
import ScreenCaptureKit
import VideoToolbox

/// Display helpers. A display is addressed by its index in `ids()` (main display first).
enum Displays {
    /// Index of the display the phone is mirroring / pointing at. Main queue only.
    static var selected = 0

    static func ids() -> [CGDirectDisplayID] {
        var count: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &count)
        var all = [CGDirectDisplayID](repeating: 0, count: Int(count))
        CGGetActiveDisplayList(count, &all, &count)
        all = Array(all.prefix(Int(count)))
        if all.isEmpty { all = [CGMainDisplayID()] }  // display asleep / headless
        let main = CGMainDisplayID()
        return all.sorted { a, b in (a == main ? 0 : 1, a) < (b == main ? 0 : 1, b) }
    }

    static func id(at index: Int) -> CGDirectDisplayID {
        let l = ids()
        return l.indices.contains(index) ? l[index] : CGMainDisplayID()
    }

    static func bounds(at index: Int) -> CGRect { CGDisplayBounds(id(at: index)) }

    /// Main queue only (NSScreen).
    static func list() -> [[String: Any]] {
        var names: [CGDirectDisplayID: String] = [:]
        for s in NSScreen.screens {
            if let n = s.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber {
                names[CGDirectDisplayID(n.uint32Value)] = s.localizedName
            }
        }
        return ids().enumerated().map { i, d in ["id": i, "name": names[d] ?? "Display \(i + 1)"] }
    }

    /// Encoded video size for a display: 1280 wide (or native if smaller), even height.
    static func videoSize(for r: CGRect) -> (Int, Int) {
        let w = min(1280, max(2, Int(r.width)))
        let h = max(2, Int((Double(w) * Double(r.height) / Double(max(r.width, 1)) / 2).rounded()) * 2)
        return (w & ~1, h)
    }
}

/// ScreenCaptureKit -> VideoToolbox H.264 -> TCP viewers on port 50506.
/// Wire format: after a one-line JSON hello from the viewer, frames of 4-byte big-endian length + Annex-B access unit.
final class ScreenStreamer: NSObject, SCStreamOutput, SCStreamDelegate {
    static let port: UInt16 = 50506
    static let shared = ScreenStreamer()

    /// Called on the main queue whenever capture starts, fails or stops (so the Mac can resend `screeninfo`).
    var onState: (() -> Void)?
    private(set) var running = false
    private(set) var failed = false
    private(set) var listenerError: String?

    private let q = DispatchQueue(label: "padkey.screen")
    private var listener: NWListener?
    private var viewers: [ObjectIdentifier: Viewer] = [:]
    private var requested = false
    private var wantIndex = 0

    private var gen = 0
    private var stream: SCStream?
    private var session: VTCompressionSession?
    private var curIndex = -1
    private var lastPB: CVPixelBuffer?
    private var needKeyframe = false
    private var pts = 0

    // MARK: listener

    func startListener() {
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
            let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: ScreenStreamer.port)!)
            l.stateUpdateHandler = { [weak self] st in
                if case .failed(let e) = st { self?.listenerError = "\(e)" }
            }
            l.newConnectionHandler = { [weak self] c in self?.accept(c) }
            l.start(queue: q)
            listener = l
        } catch {
            listenerError = "\(error)"
        }
    }

    private func accept(_ nw: NWConnection) {
        let v = Viewer(nw)
        viewers[ObjectIdentifier(v)] = v
        nw.stateUpdateHandler = { [weak self, weak v] st in
            guard let v = v else { return }
            switch st {
            case .failed, .cancelled: self?.drop(v)
            default: break
            }
        }
        nw.start(queue: q)
        // First line must be {"t":"hello","token":"..."}; give the phone 5 s.
        q.asyncAfter(deadline: .now() + 5) { [weak self, weak v] in
            if let v = v, !v.authed { self?.drop(v) }
        }
        readHello(v)
    }

    private func readHello(_ v: Viewer) {
        v.nw.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self, weak v] data, _, complete, error in
            guard let self = self, let v = v else { return }
            if let d = data { v.buf.append(d) }
            if let nl = v.buf.firstIndex(of: 10) {
                let line = v.buf.subdata(in: v.buf.startIndex..<nl)
                let o = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any]
                let want = Server.token
                if want.isEmpty || (o?["token"] as? String) == want {
                    v.authed = true
                    v.needKey = true
                    self.requestKeyframe()
                    self.reconcile()
                    self.drain(v)   // ignore anything else
                } else {
                    self.drop(v)
                }
                return
            }
            if complete || error != nil || v.buf.count > 4096 { self.drop(v); return }
            self.readHello(v)
        }
    }

    private func drain(_ v: Viewer) {
        v.nw.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self, weak v] _, _, complete, error in
            guard let self = self, let v = v else { return }
            if complete || error != nil { self.drop(v); return }
            self.drain(v)
        }
    }

    private func drop(_ v: Viewer) {
        v.nw.cancel()
        if viewers.removeValue(forKey: ObjectIdentifier(v)) != nil { reconcile() }
    }

    // MARK: control (called from Server on main)

    /// `on` = at least one phone asked for the mirror; `index` = display index.
    func setRequest(on: Bool, index: Int) {
        q.async {
            self.requested = on
            self.wantIndex = index
            self.reconcile()
        }
    }

    // MARK: capture lifecycle (q)

    private func reconcile() {
        let wantRun = requested && !viewers.values.filter { $0.authed }.isEmpty
        if wantRun {
            if stream == nil && !starting || (curIndex != wantIndex && !starting) { start() }
        } else if stream != nil || starting {
            stop()
        }
    }

    private var starting = false

    private func start() {
        stop()
        gen += 1
        let g = gen
        let index = wantIndex
        starting = true
        failed = false
        for v in viewers.values { v.needKey = true }
        Task {
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                let did = Displays.id(at: index)
                guard let disp = content.displays.first(where: { $0.displayID == did }) ?? content.displays.first else {
                    throw NSError(domain: "padkey", code: 1, userInfo: [NSLocalizedDescriptionKey: "no display"])
                }
                let (w, h) = Displays.videoSize(for: Displays.bounds(at: index))
                let cfg = SCStreamConfiguration()
                cfg.width = w
                cfg.height = h
                cfg.minimumFrameInterval = CMTime(value: 1, timescale: 30)
                cfg.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
                cfg.showsCursor = true
                cfg.queueDepth = 3
                let filter = SCContentFilter(display: disp, excludingWindows: [])
                let st = SCStream(filter: filter, configuration: cfg, delegate: self)
                try st.addStreamOutput(self, type: .screen, sampleHandlerQueue: DispatchQueue(label: "padkey.screen.out"))
                let sess = try self.makeSession(w, h)
                try await st.startCapture()
                self.q.async {
                    if g != self.gen { // superseded or stopped while starting
                        Task { try? await st.stopCapture() }
                        VTCompressionSessionInvalidate(sess)
                        return
                    }
                    self.stream = st
                    self.session = sess
                    self.curIndex = index
                    self.starting = false
                    self.running = true
                    self.needKeyframe = true
                    self.notify()
                }
            } catch {
                NSLog("PadKey screen start failed: \(error)")
                self.q.async {
                    if g == self.gen { self.starting = false; self.failed = true; self.running = false }
                    self.notify()
                }
            }
        }
    }

    private func stop() {
        gen += 1
        starting = false
        if let st = stream { Task { try? await st.stopCapture() } }
        if let s = session { VTCompressionSessionInvalidate(s) }
        stream = nil
        session = nil
        lastPB = nil
        curIndex = -1
        let was = running
        running = false
        if was { notify() }
    }

    private func notify() { DispatchQueue.main.async { self.onState?() } }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        NSLog("PadKey screen stream stopped: \(error)")
        q.async {
            if stream === self.stream {
                self.stream = nil
                if let s = self.session { VTCompressionSessionInvalidate(s) }
                self.session = nil
                self.running = false
                self.failed = true
                self.notify()
            }
        }
    }

    // MARK: encode

    private func makeSession(_ w: Int, _ h: Int) throws -> VTCompressionSession {
        var s: VTCompressionSession?
        let spec: [CFString: Any] = [kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: true]
        let st = VTCompressionSessionCreate(allocator: nil, width: Int32(w), height: Int32(h),
                                            codecType: kCMVideoCodecType_H264,
                                            encoderSpecification: spec as CFDictionary,
                                            imageBufferAttributes: nil, compressedDataAllocator: nil,
                                            outputCallback: nil, refcon: nil, compressionSessionOut: &s)
        guard st == noErr, let sess = s else {
            throw NSError(domain: "padkey", code: Int(st), userInfo: [NSLocalizedDescriptionKey: "VTCompressionSessionCreate \(st)"])
        }
        func set(_ k: CFString, _ v: CFTypeRef) { VTSessionSetProperty(sess, key: k, value: v) }
        set(kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
        set(kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_Main_AutoLevel)
        set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
        set(kVTCompressionPropertyKey_AverageBitRate, 4_000_000 as CFNumber)
        set(kVTCompressionPropertyKey_DataRateLimits, [500_000 * 2, 1] as CFArray)  // bytes per second cap (~8 Mbps peak)
        set(kVTCompressionPropertyKey_ExpectedFrameRate, 30 as CFNumber)
        set(kVTCompressionPropertyKey_MaxKeyFrameInterval, 60 as CFNumber)
        set(kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, 2 as CFNumber)
        VTCompressionSessionPrepareToEncodeFrames(sess)
        return sess
    }

    private func requestKeyframe() {
        needKeyframe = true
        // Static screens produce no new frames: re-encode the last one as a keyframe for the new viewer.
        if let pb = lastPB, session != nil { encode(pb) }
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sb.isValid,
              let arr = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = arr.first?[.status] as? Int, SCFrameStatus(rawValue: raw) == .complete,
              let pb = CMSampleBufferGetImageBuffer(sb) else { return }
        q.async {
            guard stream === self.stream || self.starting else { return }
            self.lastPB = pb
            self.encode(pb)
        }
    }

    private func encode(_ pb: CVPixelBuffer) {
        guard let sess = session else { return }
        guard viewers.values.contains(where: { $0.authed }) else { return }
        var props: CFDictionary?
        if needKeyframe {
            props = [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary
            needKeyframe = false
        }
        pts += 1
        let t = CMTime(value: CMTimeValue(pts), timescale: 30)
        VTCompressionSessionEncodeFrame(sess, imageBuffer: pb, presentationTimeStamp: t, duration: .invalid,
                                        frameProperties: props, infoFlagsOut: nil) { [weak self] status, _, sample in
            guard status == noErr, let sample = sample, let self = self else { return }
            self.q.async { self.emit(sample) }
        }
    }

    private func emit(_ sample: CMSampleBuffer) {
        guard let au = ScreenStreamer.annexB(sample) else { return }
        var frame = Data(capacity: au.data.count + 4)
        var len = UInt32(au.data.count).bigEndian
        withUnsafeBytes(of: &len) { frame.append(contentsOf: $0) }
        frame.append(au.data)
        for v in viewers.values where v.authed {
            if v.needKey {
                if !au.key { continue }
                v.needKey = false
            }
            if v.inflight > 8 { // viewer can't keep up: drop, resync on next keyframe
                v.needKey = true
                needKeyframe = true
                continue
            }
            v.inflight += 1
            v.nw.send(content: frame, completion: .contentProcessed { [weak self, weak v] _ in
                self?.q.async { v?.inflight -= 1 }
            })
        }
    }

    /// AVCC sample -> Annex-B; keyframes get SPS/PPS prepended.
    private static func annexB(_ sample: CMSampleBuffer) -> (data: Data, key: Bool)? {
        guard let block = CMSampleBufferGetDataBuffer(sample) else { return nil }
        var key = true
        if let a = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]],
           let first = a.first, (first[kCMSampleAttachmentKey_NotSync] as? Bool) == true { key = false }
        let start: [UInt8] = [0, 0, 0, 1]
        var out = Data()
        if key, let fd = CMSampleBufferGetFormatDescription(sample) {
            var i = 0
            while true {
                var p: UnsafePointer<UInt8>?
                var size = 0
                var count = 0
                let st = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, parameterSetIndex: i, parameterSetPointerOut: &p, parameterSetSizeOut: &size, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
                if st != noErr || p == nil { break }
                out.append(contentsOf: start)
                out.append(p!, count: size)
                i += 1
                if i >= count { break }
            }
        }
        var total = 0
        var ptr: UnsafeMutablePointer<CChar>?
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &total, dataPointerOut: &ptr) == noErr,
              let base = ptr else { return nil }
        var off = 0
        while off + 4 <= total {
            var n: UInt32 = 0
            memcpy(&n, base + off, 4)
            let l = Int(UInt32(bigEndian: n))
            off += 4
            if l <= 0 || off + l > total { break }
            out.append(contentsOf: start)
            out.append(Data(bytes: base + off, count: l))
            off += l
        }
        return out.isEmpty ? nil : (out, key)
    }
}

private final class Viewer {
    let nw: NWConnection
    var buf = Data()
    var authed = false
    var needKey = true
    var inflight = 0
    init(_ nw: NWConnection) { self.nw = nw }
}
