import Darwin
import Foundation

/// Thin wrapper around Mach's `task_info(TASK_VM_INFO)` used by the streaming
/// stress test to track peak `phys_footprint` while compression runs.
///
/// `phys_footprint` is the counter Apple recommends for "how much RAM is this
/// process using right now": it is the same value shown in Xcode's memory gauge
/// and in App Store jetsam telemetry.
enum MemoryProbe {
    /// Current physical footprint in bytes. Returns `0` if the Mach call fails.
    static func physFootprintBytes() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { infoPtr -> kern_return_t in
            infoPtr.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { intPtr in
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), intPtr, &count)
            }
        }
        return result == KERN_SUCCESS ? info.phys_footprint : 0
    }
}

/// Background sampler that records the peak `phys_footprint` over the sampler's lifetime.
///
/// Used by `IosLargeInputStreamingTests` to verify the pipeline streams rather than
/// loading the whole input into RAM. Sampling runs on a dispatch queue so we pick up
/// transient spikes while the async compression is running on the main actor.
final class PeakMemorySampler {
    private let queue = DispatchQueue(label: "co.crackn.kompressor.memprobe", qos: .userInitiated)
    private let intervalMs: Int
    private var isRunning = false
    private var lock = os_unfair_lock_s()
    private var peak: UInt64 = 0

    init(intervalMs: Int = 50) {
        self.intervalMs = intervalMs
    }

    func start() {
        os_unfair_lock_lock(&lock)
        isRunning = true
        peak = MemoryProbe.physFootprintBytes()
        os_unfair_lock_unlock(&lock)
        queue.async { [weak self] in
            self?.sampleLoop()
        }
    }

    func stop() -> UInt64 {
        os_unfair_lock_lock(&lock)
        isRunning = false
        let latest = MemoryProbe.physFootprintBytes()
        if latest > peak { peak = latest }
        let captured = peak
        os_unfair_lock_unlock(&lock)
        return captured
    }

    private func sampleLoop() {
        while true {
            os_unfair_lock_lock(&lock)
            let running = isRunning
            os_unfair_lock_unlock(&lock)
            if !running { return }

            let current = MemoryProbe.physFootprintBytes()
            os_unfair_lock_lock(&lock)
            if current > peak { peak = current }
            os_unfair_lock_unlock(&lock)

            Thread.sleep(forTimeInterval: Double(intervalMs) / 1000.0)
        }
    }
}
