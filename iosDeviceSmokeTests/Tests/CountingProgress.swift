import Foundation
import Kompressor

/// `onProgress` callback that counts how many times the compressor invokes it.
///
/// The Kotlin-exported progress callback is typed as `KotlinSuspendFunction1` in Swift;
/// each invocation is a separate call that either reports progress or signals cancellation.
/// This counter is used by the streaming stress test to assert that the pipeline produces
/// at least a handful of progress updates over a >100 MB input (i.e. that progress is
/// reported continuously during the stream, not only at the ends).
final class CountingProgress: NSObject, KotlinSuspendFunction1 {
    private var lock = os_unfair_lock_s()
    private var updates: [Float] = []

    func invoke(p1: Any?, completionHandler: @escaping (Any?, (any Error)?) -> Void) {
        if let fraction = (p1 as? NSNumber)?.floatValue {
            os_unfair_lock_lock(&lock)
            updates.append(fraction)
            os_unfair_lock_unlock(&lock)
        }
        completionHandler(nil, nil)
    }

    var count: Int {
        os_unfair_lock_lock(&lock)
        defer { os_unfair_lock_unlock(&lock) }
        return updates.count
    }

    var values: [Float] {
        os_unfair_lock_lock(&lock)
        defer { os_unfair_lock_unlock(&lock) }
        return updates
    }
}
