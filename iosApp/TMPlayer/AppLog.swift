import Foundation
import TMPlayerKit

/// A line in the app's log, as `NSLog` takes it (a format and its arguments), under `tag`. It goes
/// through the Kotlin side's Logger (`NativeLog`), so it reaches the system log as `NSLog` did and
/// the app's kept log too, which "Save logs" reads; `NSLog` alone left the Swift side out of it.
func tmLog(_ tag: String, _ format: String, _ args: CVarArg...) {
    NativeLog.shared.line(tag: tag, message: String(format: format, arguments: args))
}

/// The same, for something that went wrong.
func tmLogWarning(_ tag: String, _ format: String, _ args: CVarArg...) {
    NativeLog.shared.warning(tag: tag, message: String(format: format, arguments: args))
}
