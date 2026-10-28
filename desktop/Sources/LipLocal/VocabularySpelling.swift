import AppKit

/// Review-only acceptance of explicitly imported English terms. Never learns
/// globally, changes transcript characters or calls a speech/model API.
@MainActor final class VocabularySpellingBinding {
    private let views: [NSTextView]
    private let store: VocabularyStore
    private let worker = DispatchQueue(label: "dev.lip.vocabulary-review", qos: .utility)
    private var observer: NSObjectProtocol?
    private var request = UUID()
    private var closed = false

    init(views: [NSTextView], store: VocabularyStore = .shared) {
        self.views = views; self.store = store
        for view in views {
            view.isAutomaticSpellingCorrectionEnabled = false
            view.isGrammarCheckingEnabled = false
            view.isAutomaticTextReplacementEnabled = false
            view.isAutomaticDataDetectionEnabled = false
            view.isContinuousSpellCheckingEnabled = true
        }
        observer = NotificationCenter.default.addObserver(forName: VocabularyStore.didChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in self?.refresh() }
        }
    }
    /// Called only for an explicitly opened Review window, not app startup or
    /// field discovery. A missing store never asks Keychain for a key.
    func refresh() {
        guard !closed else { return }
        let ticket = UUID(); request = ticket
        worker.async { [weak self, store] in
            let result = Result { try store.read().activeEnglishWords }
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.closed, self.request == ticket else { return }
                switch result {
                case .success(let words):
                    for view in self.views {
                        // NSTextView owns a private spelling document tag; no
                        // global learnWord/unlearnWord or system list is used.
                        NSSpellChecker.shared.setIgnoredWords(words, inSpellDocumentWithTag: view.spellCheckerDocumentTag)
                        view.setAccessibilityHelp("Portable dictionary: \(words.count) supported words in Lip Review only. Transcript unchanged.")
                    }
                case .failure:
                    // Preserve previously approved document-local words and the
                    // transcript. Never try a different key/store or rewrite text.
                    for view in self.views {
                        view.setAccessibilityHelp("Portable dictionary unavailable. Existing transcript and local spelling acceptance were preserved.")
                    }
                }
            }
        }
    }
    func close() {
        guard !closed else { return }; closed = true; request = UUID()
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
        for view in views {
            NSSpellChecker.shared.setIgnoredWords([], inSpellDocumentWithTag: view.spellCheckerDocumentTag)
        }
    }
    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        // NSTextView owns/closes its spelling document. Never close someone
        // else's/global spelling session from a background deinitializer.
    }
}
