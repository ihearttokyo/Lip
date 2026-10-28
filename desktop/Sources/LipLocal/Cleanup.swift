import Foundation

enum CleanupMode: String, CaseIterable {
    case verbatim, light, clean
    var title: String {
        switch self {
        case .verbatim: return "Bark native formatting (no rewriting)"
        case .light: return "Light formatting"
        case .clean: return "Filler cleanup (rules only)"
        }
    }
}

struct CleanupOptions {
    var mode: CleanupMode = .verbatim
    var capitalizeFirstWord = true
    var finalPeriod = true
    var removeHesitations = true
}

final class CleanupSettings {
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    var autoInsert: Bool {
        get { defaults.object(forKey: "insertion.enabled") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "insertion.enabled") }
    }
    var options: CleanupOptions {
        get {
            CleanupOptions(mode: defaults.string(forKey: "cleanup.mode").flatMap(CleanupMode.init(rawValue:)) ?? .verbatim,
                capitalizeFirstWord: defaults.object(forKey: "cleanup.capitalizeFirstWord") as? Bool ?? true,
                finalPeriod: defaults.object(forKey: "cleanup.finalPeriod") as? Bool ?? true,
                removeHesitations: defaults.object(forKey: "cleanup.removeHesitations") as? Bool ?? true)
        }
        set {
            defaults.set(newValue.mode.rawValue, forKey: "cleanup.mode")
            defaults.set(newValue.capitalizeFirstWord, forKey: "cleanup.capitalizeFirstWord")
            defaults.set(newValue.finalPeriod, forKey: "cleanup.finalPeriod")
            defaults.set(newValue.removeHesitations, forKey: "cleanup.removeHesitations")
        }
    }
}

struct CleanupResult {
    let text: String
    let note: String
}

enum LocalCleanup {
    private static func replace(_ pattern: String, in text: String, with replacement: String) -> String {
        text.replacingOccurrences(of: pattern, with: replacement, options: .regularExpression)
    }
    private static func matches(_ pattern: String, _ text: String) -> Bool {
        text.range(of: pattern, options: .regularExpression) != nil
    }
    // Conservative whole-transcript refusal: do not alter code, URLs, emails, paths,
    // quoted literals or mixed-case identifiers. This is a safeguard, not a parser.
    static func protected(_ text: String) -> Bool {
        if matches(#"(?:^|\W)'[^'\n]+'|‘[^’\n]*’"#, text) { return true }
        return matches("https?://|www\\.|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|`|[{}=]|(?:^|\\s)[/~][^\\s]+|\\b\\w*_\\w*\\b|\\b[a-z]+[A-Z]\\w*\\b|[\\\"“”]", text)
    }
    static func apply(_ raw: String, options: CleanupOptions) -> CleanupResult {
        guard options.mode != .verbatim else { return CleanupResult(text: raw, note: "Bark output unchanged.") }
        guard !protected(raw) else {
            return CleanupResult(text: raw, note: "Cleanup skipped to preserve possible code, links, paths, identifiers or quoted text.")
        }
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return CleanupResult(text: raw, note: "No text to format.") }
        if options.mode == .clean && options.removeHesitations {
            // Never remove meaningful cues such as 'like', 'well', 'you know', negations
            // or repeated words. Uppercase UM/UH/ERM and numeric-unit uses stay intact.
            let regex = try! NSRegularExpression(pattern: #"(?:,[ \t]*)?\b(?:[Uu]m|[Uu]h|[Ee]rm)\b(?![.!?]),?[ \t]*"#)
            let original = text as NSString
            let edits = regex.matches(in: text, range: NSRange(location: 0, length: original.length)).filter { match in
                let prefix = original.substring(to: match.range.location)
                let suffix = original.substring(from: NSMaxRange(match.range))
                return !matches(#"\b\d+(?:\.\d+)?[ \t]*$"#, prefix) && !matches(#"^[ \t]*\d"#, suffix)
            }
            for match in edits.reversed() {
                text = (text as NSString).replacingCharacters(in: match.range, with: " ")
            }
            // If everything was hesitation, preserve the raw result rather than erase it.
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return CleanupResult(text: raw, note: "All text looked like hesitation; original preserved for review.")
            }
        }
        text = replace(#"[ \t]+"#, in: text, with: " ")
        text = replace(#"[ \t]+([,.;:!?])"#, in: text, with: "$1")
        text = replace(#"^[ \t]+|[ \t]+$"#, in: text, with: "")
        if options.capitalizeFirstWord, let first = text.first, first.isLowercase {
            // Existing mixed-case names are caught by protected(); only initial case changes.
            text.replaceSubrange(text.startIndex...text.startIndex, with: String(first).uppercased())
        }
        if options.finalPeriod, let last = text.last, last.isLetter || last.isNumber {
            text.append(".")
        }
        return CleanupResult(text: text, note: options.mode == .clean && options.removeHesitations
            ? "Local rules: spacing, optional initial capital/final period, and um/uh/erm removal. Review against original."
            : "Local rules: spacing and optional initial capital/final period. No word removal or contextual rewriting.")
    }
}

func testCleanup() {
    var count = 0
    func check(_ value: Bool, _ name: String) { precondition(value, name); count += 1 }
    var options = CleanupOptions()
    options.mode = .verbatim
    check(LocalCleanup.apply("  um, hello  \n", options: options).text == "  um, hello  \n", "verbatim must be byte-for-byte")
    options.mode = .light
    check(LocalCleanup.apply("  hello   there ! ", options: options).text == "Hello there!", "light spacing and capitalization")
    check(LocalCleanup.apply("um, please send the report", options: options).text == "Um, please send the report.", "light must not remove fillers")
    check(LocalCleanup.apply("do not send 25 mg", options: options).text == "Do not send 25 mg.", "numbers units and negation retained")
    check(LocalCleanup.apply("what I want is a report", options: options).text == "What I want is a report.", "must not infer a question from what")
    check(LocalCleanup.apply("Is it ready?", options: options).text == "Is it ready?", "existing punctuation retained")
    options.capitalizeFirstWord = false; options.finalPeriod = false
    check(LocalCleanup.apply("hello   there", options: options).text == "hello there", "format toggles apply")
    options = CleanupOptions(mode: .clean)
    check(LocalCleanup.apply("um, please send the report", options: options).text == "Please send the report.", "remove initial hesitation")
    check(LocalCleanup.apply("I, um, need the report", options: options).text == "I need the report.", "remove parenthetic hesitation")
    check(LocalCleanup.apply("uh I do not want 25 mg", options: options).text == "I do not want 25 mg.", "filler cleanup preserves content")
    check(LocalCleanup.apply("I like it well enough", options: options).text == "I like it well enough.", "meaningful filler-like words retained")
    check(LocalCleanup.apply("I had had the report", options: options).text == "I had had the report.", "repetitions retained")
    check(LocalCleanup.apply("UM is a university", options: options).text == "UM is a university.", "uppercase acronyms retained")
    check(LocalCleanup.apply("10 um", options: options).text == "10 um.", "numeric units retained")
    check(LocalCleanup.apply("um 10", options: options).text == "Um 10.", "ambiguous number context retained")
    check(LocalCleanup.apply("um", options: options).text == "um", "do not erase entire transcript")
    for raw in ["https://example.com/a", "send a@b.com", "getUser returns nil", "user_id is 2", "let x = 2", "say \"um hello\"", "/Users/example/file", "`um`", "say 'um hello'", "say ‘um hello’"] {
        check(LocalCleanup.apply(raw, options: options).text == raw, "protected content preserved: \(raw)")
    }
    check(LocalCleanup.apply("Um! Hello", options: options).text == "Um! Hello.", "do not strip emphatic words leaving orphan punctuation")
    options.removeHesitations = false
    check(LocalCleanup.apply("um, hello", options: options).text == "Um, hello.", "hesitation toggle applies")
    let suite = "dev.lip.local.cleanup-tests.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let settings = CleanupSettings(defaults: defaults)
    check(settings.options.mode == .verbatim, "default preserves native model output")
    check(settings.autoInsert, "direct insertion enabled by default as requested")
    settings.autoInsert = false
    check(!CleanupSettings(defaults: defaults).autoInsert, "insertion preference persists")
    options = CleanupOptions(mode: .clean, capitalizeFirstWord: false, finalPeriod: false, removeHesitations: false)
    settings.options = options
    let restored = CleanupSettings(defaults: defaults).options
    check(restored.mode == .clean && !restored.capitalizeFirstWord && !restored.finalPeriod && !restored.removeHesitations, "settings persist")
    defaults.set("unknown-future-mode", forKey: "cleanup.mode")
    check(settings.options.mode == .verbatim, "unknown settings mode preserves native output")
    print("Cleanup and settings tests passed (\(count) assertions).")
}
