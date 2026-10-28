import Foundation
import CryptoKit

/// Unicode16 data, not the operating system's normalization/case/category tables.
struct PinnedUnicode16 {
    private struct Tables: Decodable {
        let unicode_version: String
        let letter_ranges: [[UInt32]]
        let other_ranges: [[UInt32]]
        let casefold: [String: [UInt32]]
        let ccc: [String: Int]
        let decomposition: [String: [UInt32]]
        let composition: [String: UInt32]
    }
    private let letters: [[UInt32]]
    private let others: [[UInt32]]
    private let folds: [UInt32: [UInt32]]
    private let classes: [UInt32: Int]
    private let decompositions: [UInt32: [UInt32]]
    private let compositions: [UInt64: UInt32]
    // Exact parent-generated CPython3.14.6 / Unicode16 source-table binding.
    static let expectedSHA256 = "e0a6fe7b32371dff4a582331bdd3591d2854ef7df9e1527fd0b5e8e669f1615c"
    static let unicodeVersion = "16.0.0"

    private static let bundledResult: Result<PinnedUnicode16, Error> = Result {
        #if SWIFT_PACKAGE
        let url = Bundle.module.url(forResource: "unicode16", withExtension: "json", subdirectory: "VocabularyData")
        #else
        let url: URL? = nil // Standalone probes explicitly inject the exact pinned table.
        #endif
        guard let url, let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { throw VocabularyFailure.unavailable }
        return try PinnedUnicode16(data: data)
    }
    static func bundled() throws -> PinnedUnicode16 { try bundledResult.get() }

    init(data: Data) throws {
        guard data.count <= 2 * 1024 * 1024,
              SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == Self.expectedSHA256,
              let table = try? JSONDecoder().decode(Tables.self, from: data),
              table.unicode_version == Self.unicodeVersion else { throw VocabularyFailure.unavailable }
        func scalar(_ cp: UInt32) -> Bool { Unicode.Scalar(cp) != nil }
        func ranges(_ rows: [[UInt32]]) throws {
            var last: UInt32? = nil
            for row in rows {
                guard row.count == 2, row[0] <= row[1], row[1] <= 0x10ffff,
                      last.map({ row[0] > $0 }) ?? true else { throw VocabularyFailure.unavailable }
                last = row[1]
            }
        }
        try ranges(table.letter_ranges); try ranges(table.other_ranges)
        func mapping(_ rows: [String: [UInt32]]) throws -> [UInt32: [UInt32]] {
            var result: [UInt32: [UInt32]] = [:]
            for (key, value) in rows {
                guard let cp = UInt32(key), scalar(cp), !value.isEmpty, value.count <= 32,
                      value.allSatisfy(scalar), result[cp] == nil else { throw VocabularyFailure.unavailable }
                result[cp] = value
            }
            return result
        }
        self.letters = table.letter_ranges
        self.others = table.other_ranges
        self.folds = try mapping(table.casefold)
        self.decompositions = try mapping(table.decomposition)
        var classes: [UInt32: Int] = [:]
        for (key, value) in table.ccc {
            guard let cp = UInt32(key), scalar(cp), (1...255).contains(value), classes[cp] == nil else { throw VocabularyFailure.unavailable }
            classes[cp] = value
        }
        self.classes = classes
        var compositions: [UInt64: UInt32] = [:]
        for (key, value) in table.composition {
            let pair = key.split(separator: ",", omittingEmptySubsequences: false)
            guard pair.count == 2, let a = UInt32(pair[0]), let b = UInt32(pair[1]),
                  scalar(a), scalar(b), scalar(value) else { throw VocabularyFailure.unavailable }
            let identity = Self.pairKey(a, b)
            guard compositions[identity] == nil else { throw VocabularyFailure.unavailable }
            compositions[identity] = value
        }
        self.compositions = compositions
    }
    private static func pairKey(_ a: UInt32, _ b: UInt32) -> UInt64 { UInt64(a) << 21 | UInt64(b) }
    private func contains(_ cp: UInt32, in ranges: [[UInt32]]) -> Bool {
        var lower = 0, upper = ranges.count
        while lower < upper {
            let mid = lower + (upper - lower) / 2
            let row = ranges[mid]
            if cp < row[0] { upper = mid } else if cp > row[1] { lower = mid + 1 } else { return true }
        }
        return false
    }
    enum Category { case letter, other, nonLetter }
    func category(_ cp: UInt32) -> Category {
        if contains(cp, in: letters) { return .letter }
        return contains(cp, in: others) ? .other : .nonLetter
    }
    func fullCaseFold(_ value: String) -> String {
        var result = String.UnicodeScalarView()
        for scalar in value.unicodeScalars {
            for cp in folds[scalar.value] ?? [scalar.value] { result.append(Unicode.Scalar(cp)!) }
        }
        return String(result)
    }

    /// UAX15 canonical decomposition, stable CCC ordering and blocked composition.
    /// Hangul syllables use the normative algorithm, not a platform normalizer.
    func nfc(_ value: String) throws -> String {
        var ordered: [UInt32] = []
        func appendOrdered(_ cp: UInt32) throws {
            guard ordered.count < 8192 else { throw VocabularyFailure.invalidTerm }
            ordered.append(cp)
            let c = classes[cp] ?? 0
            if c == 0 { return }
            var i = ordered.count - 1
            while i > 0 && (classes[ordered[i - 1]] ?? 0) > c {
                ordered.swapAt(i - 1, i); i -= 1
            }
        }
        func decompose(_ cp: UInt32, _ depth: Int) throws {
            guard depth < 32 else { throw VocabularyFailure.unavailable }
            let sBase: UInt32 = 0xac00, lBase: UInt32 = 0x1100, vBase: UInt32 = 0x1161, tBase: UInt32 = 0x11a7
            if cp >= sBase && cp < sBase + 11172 {
                let s = cp - sBase
                try appendOrdered(lBase + s / 588)
                try appendOrdered(vBase + (s % 588) / 28)
                if s % 28 != 0 { try appendOrdered(tBase + s % 28) }
            } else if let parts = decompositions[cp] {
                for part in parts { try decompose(part, depth + 1) }
            } else { try appendOrdered(cp) }
        }
        for scalar in value.unicodeScalars { try decompose(scalar.value, 0) }
        guard let first = ordered.first else { return "" }
        var result = [first], starter = first, starterIndex = 0, lastClass = classes[first] ?? 0
        func composite(_ a: UInt32, _ b: UInt32) -> UInt32? {
            if a >= 0x1100 && a < 0x1100 + 19 && b >= 0x1161 && b < 0x1161 + 21 {
                return 0xac00 + (a - 0x1100) * 588 + (b - 0x1161) * 28
            }
            if a >= 0xac00 && a < 0xac00 + 11172 && (a - 0xac00) % 28 == 0 && b > 0x11a7 && b < 0x11a7 + 28 {
                return a + b - 0x11a7
            }
            return compositions[Self.pairKey(a, b)]
        }
        for cp in ordered.dropFirst() {
            let c = classes[cp] ?? 0
            if let combined = composite(starter, cp), lastClass == 0 || lastClass < c {
                result[starterIndex] = combined; starter = combined
            } else {
                if c == 0 { starterIndex = result.count; starter = cp }
                result.append(cp); lastClass = c
            }
        }
        return String(String.UnicodeScalarView(result.map { Unicode.Scalar($0)! }))
    }
}
