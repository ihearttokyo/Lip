import Foundation

/// Fixed safe messages: never quote an imported term, JSON excerpt or filename.
enum VocabularyFailure: Error, LocalizedError {
    case unavailable, invalidJSON, documentFields, format, unicodeContract, entryFields
    case duplicateProperty, duplicateEntry, invalidTerm, language, weight, source, size, count, mergeWork
    var errorDescription: String? {
        switch self {
        case .unavailable: return "Pinned Unicode16 vocabulary data is unavailable; original files were not changed."
        case .invalidJSON: return "Invalid vocabulary JSON."
        case .documentFields: return "Unknown vocabulary document fields."
        case .format: return "Unsupported vocabulary format/version."
        case .unicodeContract: return "Unsupported vocabulary Unicode contract."
        case .entryFields: return "Invalid vocabulary entry fields."
        case .duplicateProperty: return "Duplicate JSON property."
        case .duplicateEntry: return "Duplicate normalized vocabulary entry."
        case .invalidTerm: return "Vocabulary term is outside the Unicode, length or content limits."
        case .language: return "Unsupported vocabulary language."
        case .weight: return "Invalid vocabulary weight."
        case .source: return "Unsupported source category."
        case .size: return "Vocabulary file exceeds the size limit."
        case .count: return "Vocabulary entry count exceeds the limit."
        case .mergeWork: return "Vocabulary merge work exceeds the limit."
        }
    }
}

/// No provider/filesystem/history operations, speech biasing or neural model changes.
struct PortableVocabulary {
    struct Entry: Equatable {
        let term: String
        let language: String
        let weight: Int
        let source: String
        init(term: String, language: String = "en", weight: Int = 1, source: String = "manual") {
            self.term = term; self.language = language; self.weight = weight; self.source = source
        }
        static func == (a: Self, b: Self) -> Bool {
            a.term.utf8.elementsEqual(b.term.utf8) && a.language.utf8.elementsEqual(b.language.utf8) &&
                a.weight == b.weight && a.source.utf8.elementsEqual(b.source.utf8)
        }
    }
    static let format = "lip.learned-words", version = 1, unicodeVersion = "16.0.0"
    static let maxBytes = 16 * 1024 * 1024, maxEntries = 100_000
    private static let languages: Set<String> = ["en", "ja", "zh", "es", "und"]
    private static let sources: Set<String> = ["manual", "gmail", "outlook", "whatsapp", "import"]
    private let injectedUnicode: PinnedUnicode16?
    init(unicode: PinnedUnicode16? = nil) { self.injectedUnicode = unicode }
    private func unicode() throws -> PinnedUnicode16 { try injectedUnicode ?? PinnedUnicode16.bundled() }

    private static func pythonSpace(_ cp: UInt32) -> Bool {
        (9...13).contains(cp) || (0x1c...0x20).contains(cp) || [0x85, 0xa0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000].contains(cp) || (0x2000...0x200a).contains(cp)
    }
    private func validated(_ entry: Entry, _ unicode: PinnedUnicode16) throws -> Entry {
        let input = Array(entry.term.unicodeScalars.prefix(257))
        guard input.count <= 256 else { throw VocabularyFailure.invalidTerm }
        let stripped = input.drop(while: { Self.pythonSpace($0.value) }).reversed().drop(while: { Self.pythonSpace($0.value) }).reversed()
        let term = try unicode.nfc(String(String.UnicodeScalarView(stripped)))
        guard (1...128).contains(term.unicodeScalars.count), term.utf8.count <= 512 else { throw VocabularyFailure.invalidTerm }
        var hasLetter = false
        for scalar in term.unicodeScalars {
            let category = unicode.category(scalar.value)
            guard category != .other, ![9, 10, 13].contains(scalar.value) else { throw VocabularyFailure.invalidTerm }
            hasLetter = hasLetter || category == .letter
        }
        let prefix = Array(term.utf8.prefix(4)).map { $0 >= 65 && $0 <= 90 ? $0 + 32 : $0 }
        guard hasLetter, !term.contains("@"), !term.contains("://"), prefix != Array("www.".utf8) else { throw VocabularyFailure.invalidTerm }
        guard Self.languages.contains(entry.language) else { throw VocabularyFailure.language }
        guard (1...1_000_000).contains(entry.weight) else { throw VocabularyFailure.weight }
        guard Self.sources.contains(entry.source) else { throw VocabularyFailure.source }
        return Entry(term: term, language: entry.language, weight: entry.weight, source: entry.source)
    }
    // Swift String equality/hash is canonical-equivalence based; portable identity
    // uses exact pinned-normalized scalar bytes instead, independently of OS tables.
    private struct Identity: Hashable {
        let language: Data
        let folded: Data
        func hash(into hasher: inout Hasher) {
            // Foundation Data hashing may sample a prefix; hash every byte so a
            // long common prefix does not make import/merge quadratic.
            hasher.combine(language.count)
            language.withUnsafeBytes { hasher.combine(bytes: $0) }
            hasher.combine(folded.count)
            folded.withUnsafeBytes { hasher.combine(bytes: $0) }
        }
    }
    private func identity(_ entry: Entry, _ unicode: PinnedUnicode16) throws -> Identity {
        Identity(language: Data(entry.language.utf8), folded: Data(try unicode.nfc(unicode.fullCaseFold(entry.term)).utf8))
    }
    func decode(_ data: Data) throws -> [Entry] {
        guard data.count <= Self.maxBytes else { throw VocabularyFailure.size }
        let unicode = try unicode()
        guard String(data: data, encoding: .utf8) != nil else { throw VocabularyFailure.invalidJSON }
        return try Parser(bytes: Array(data), codec: self, unicode: unicode).document()
    }
    func merge(existing: [Entry], incoming: [Entry]) throws -> [Entry] {
        let unicode = try unicode()
        guard existing.count <= Self.maxEntries * 2, incoming.count <= Self.maxEntries * 2 - existing.count else { throw VocabularyFailure.mergeWork }
        var rows: [Identity: Entry] = [:]
        for sequence in [existing, incoming] {
            for raw in sequence {
                let entry = try validated(raw, unicode), key = try identity(entry, unicode)
                if let previous = rows[key] {
                    if entry.weight > previous.weight { rows[key] = Entry(term: previous.term, language: previous.language, weight: entry.weight, source: previous.source) }
                } else { rows[key] = entry }
                guard rows.count <= Self.maxEntries else { throw VocabularyFailure.count }
            }
        }
        return rows.sorted {
            if $0.key.language != $1.key.language { return $0.key.language.lexicographicallyPrecedes($1.key.language) }
            // Valid UTF-8 byte order equals scalar/codepoint order, not UTF16 order.
            return $0.key.folded.lexicographicallyPrecedes($1.key.folded)
        }.map(\.value)
    }
    func encode(_ entries: [Entry]) throws -> Data {
        let rows = try merge(existing: [], incoming: entries)
        var result = "", size = 0
        func append(_ text: String) throws {
            size += text.utf8.count
            guard size <= Self.maxBytes else { throw VocabularyFailure.size }
            result.append(text)
        }
        func quote(_ value: String) -> String {
            "\"" + value.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") + "\""
        }
        try append("{\n  \"format\": \"\(Self.format)\",\n  \"version\": \(Self.version),\n  \"unicode_version\": \"\(Self.unicodeVersion)\",\n  \"entries\": [")
        if !rows.isEmpty { try append("\n") }
        for (index, row) in rows.enumerated() {
            try append("    {\n      \"term\": \(quote(row.term)),\n      \"language\": \(quote(row.language)),\n      \"weight\": \(row.weight),\n      \"source\": \(quote(row.source))\n    }")
            try append(index == rows.count - 1 ? "\n" : ",\n")
        }
        try append(rows.isEmpty ? "]\n}\n" : "  ]\n}\n")
        return Data(result.utf8)
    }

    /// Schema-shaped strict parser, with no arbitrary nested allocations/recursion.
    private final class Parser {
        let bytes: [UInt8], codec: PortableVocabulary, unicode: PinnedUnicode16
        var at = 0
        init(bytes: [UInt8], codec: PortableVocabulary, unicode: PinnedUnicode16) { self.bytes = bytes; self.codec = codec; self.unicode = unicode }
        func whitespace() { while at < bytes.count && [9, 10, 13, 32].contains(bytes[at]) { at += 1 } }
        func take(_ c: UInt8) -> Bool { if at < bytes.count && bytes[at] == c { at += 1; return true }; return false }
        func expect(_ c: UInt8) throws { guard take(c) else { throw VocabularyFailure.invalidJSON } }
        func object(_ consume: (String) throws -> Void) throws -> Set<String> {
            whitespace(); try expect(123); whitespace()
            var keys: Set<String> = []
            if take(125) { return keys }
            while true {
                whitespace(); let key = try string(limit: 256)
                guard keys.insert(key).inserted else { throw VocabularyFailure.duplicateProperty }
                whitespace(); try expect(58); whitespace(); try consume(key); whitespace()
                if take(125) { return keys }; try expect(44)
            }
        }
        func document() throws -> [Entry] {
            var format: String?, version: Int?, unicodeVersion: String?, entries: [Entry]?
            let fields = try object { key in
                switch key {
                case "format": format = try string(limit: 256)
                case "version": version = try integer()
                case "unicode_version": unicodeVersion = try string(limit: 256)
                case "entries": entries = try entryArray()
                default: throw VocabularyFailure.documentFields
                }
            }
            whitespace(); guard at == bytes.count else { throw VocabularyFailure.invalidJSON }
            guard fields == ["format", "version", "unicode_version", "entries"] else { throw VocabularyFailure.documentFields }
            guard format == PortableVocabulary.format, version == PortableVocabulary.version else { throw VocabularyFailure.format }
            guard unicodeVersion == PortableVocabulary.unicodeVersion else { throw VocabularyFailure.unicodeContract }
            guard let entries else { throw VocabularyFailure.invalidJSON }; return entries
        }
        func entryArray() throws -> [Entry] {
            try expect(91); whitespace()
            var rows: [Entry] = [], seen: Set<Identity> = []
            if take(93) { return rows }
            while true {
                guard rows.count < PortableVocabulary.maxEntries else { throw VocabularyFailure.count }
                var term: String?, language: String?, weight: Int?, source: String?
                let fields = try object { key in
                    switch key {
                    case "term": term = try string(limit: 1024)
                    case "language": language = try string(limit: 256)
                    case "weight": weight = try integer()
                    case "source": source = try string(limit: 256)
                    default: throw VocabularyFailure.entryFields
                    }
                }
                guard fields == ["term", "language", "weight", "source"], let term, let language, let weight, let source else { throw VocabularyFailure.entryFields }
                let entry = try codec.validated(Entry(term: term, language: language, weight: weight, source: source), unicode)
                guard seen.insert(try codec.identity(entry, unicode)).inserted else { throw VocabularyFailure.duplicateEntry }
                rows.append(entry); whitespace(); if take(93) { return rows }; try expect(44); whitespace()
            }
        }
        func integer() throws -> Int {
            let start = at; _ = take(45)
            guard at < bytes.count else { throw VocabularyFailure.invalidJSON }
            if take(48) { if at < bytes.count && (48...57).contains(bytes[at]) { throw VocabularyFailure.invalidJSON } }
            else {
                guard (49...57).contains(bytes[at]) else { throw VocabularyFailure.invalidJSON }
                while at < bytes.count && (48...57).contains(bytes[at]) {
                    guard at - start <= 20 else { throw VocabularyFailure.invalidJSON }; at += 1
                }
            }
            if at < bytes.count && [46, 69, 101].contains(bytes[at]) { throw VocabularyFailure.invalidJSON }
            guard let value = Int(String(decoding: bytes[start..<at], as: UTF8.self)) else { throw VocabularyFailure.invalidJSON }; return value
        }
        func hex() throws -> UInt32 {
            guard at <= bytes.count - 4 else { throw VocabularyFailure.invalidJSON }
            var value: UInt32 = 0
            for _ in 0..<4 {
                let c = bytes[at]; at += 1
                let digit: UInt32
                switch c { case 48...57: digit = UInt32(c - 48); case 65...70: digit = UInt32(c - 65 + 10); case 97...102: digit = UInt32(c - 97 + 10); default: throw VocabularyFailure.invalidJSON }
                value = value << 4 | digit
            }
            return value
        }
        func string(limit: Int) throws -> String {
            try expect(34); var result: [UInt8] = []
            while at < bytes.count {
                let c = bytes[at]; at += 1
                if c == 34 { guard let value = String(bytes: result, encoding: .utf8) else { throw VocabularyFailure.invalidJSON }; return value }
                guard c >= 32 else { throw VocabularyFailure.invalidJSON }
                if c != 92 { result.append(c) }
                else {
                    guard at < bytes.count else { throw VocabularyFailure.invalidJSON }
                    let escaped = bytes[at]; at += 1
                    switch escaped {
                    case 34, 47, 92: result.append(escaped)
                    case 98: result.append(8); case 102: result.append(12); case 110: result.append(10); case 114: result.append(13); case 116: result.append(9)
                    case 117:
                        var cp = try hex()
                        if (0xd800...0xdbff).contains(cp) {
                            try expect(92); try expect(117); let low = try hex()
                            guard (0xdc00...0xdfff).contains(low) else { throw VocabularyFailure.invalidJSON }
                            cp = 0x10000 + (cp - 0xd800) * 1024 + low - 0xdc00
                        } else if (0xdc00...0xdfff).contains(cp) { throw VocabularyFailure.invalidJSON }
                        guard let scalar = Unicode.Scalar(cp) else { throw VocabularyFailure.invalidJSON }
                        result.append(contentsOf: String(scalar).utf8)
                    default: throw VocabularyFailure.invalidJSON
                    }
                }
                guard result.count <= limit else { throw VocabularyFailure.invalidJSON }
            }
            throw VocabularyFailure.invalidJSON
        }
    }
}
