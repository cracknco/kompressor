import Foundation
import Kompressor

final class NoOpProgress: NSObject, KompressorKotlinSuspendFunction1 {
    func invoke(p1: Any?, completionHandler: @escaping (Any?, (any Error)?) -> Void) {
        completionHandler(KotlinUnit(), nil)
    }
}
