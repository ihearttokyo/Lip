import Foundation

/// Explicit-user operation capability, not automatic-capture consent or proof of
/// a GUI click. No filesystem/provider/user code runs under this lock.
final class HistoryImportAuthorization {
    let id = UUID()
    private let lock = NSLock()
    private enum State { case live, claimed, canceled }
    private var state: State = .live
    func cancel() { lock.lock(); defer { lock.unlock() }; if state == .live { state = .canceled } }
    func checkLive() throws { lock.lock(); defer { lock.unlock() }; guard state == .live else { throw HistoryFailure.importRevoked } }
    func claim() throws { lock.lock(); defer { lock.unlock() }; guard state == .live else { throw HistoryFailure.importRevoked }; state = .claimed }
}
struct HistoryImportEvent {
    let operation: UUID
    let result: Result<HistoryPortableDisposition, Error>
}
