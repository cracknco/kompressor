import Foundation
import Kompressor

final class NoOpProgress: NSObject, KotlinSuspendFunction1 {
    func invoke(p1: Any?, completionHandler: @escaping (Any?, (any Error)?) -> Void) {
        completionHandler(nil, nil)
    }
}
