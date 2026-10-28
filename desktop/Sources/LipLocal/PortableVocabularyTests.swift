import Foundation

/// Real pinned CPython/UCD16 tables; no OS normalization or fake compatibility adapter.
func testPortableVocabulary(tableData: Data? = nil, differentialData: Data? = nil) {
    let unicode = try! tableData.map { try PinnedUnicode16(data: $0) } ?? PinnedUnicode16.bundled()
    let codec = PortableVocabulary(unicode: unicode)
    var checks = 0
    func check(_ okay: @autoclosure () -> Bool, _ label: String) { precondition(okay(), label); checks += 1 }
    func refuses(_ label: String, _ work: () throws -> Void) {
        do { try work(); preconditionFailure(label) } catch let failure as VocabularyFailure {
            check(!failure.localizedDescription.isEmpty, label)
        } catch { preconditionFailure("Unexpected non-vocabulary error") }
    }
    func row(_ term: String = "Ada", weight: String = "1", extra: String = "") -> String {
        "{\"term\":\"\(term)\",\"language\":\"en\",\"weight\":\(weight),\"source\":\"manual\"\(extra)}"
    }
    func document(_ rows: String = "{\"term\":\"Ada\",\"language\":\"en\",\"weight\":1,\"source\":\"manual\"}", extra: String = "") -> String {
        "{\"format\":\"lip.learned-words\",\"version\":1,\"unicode_version\":\"16.0.0\",\"entries\":[\(rows)]\(extra)}"
    }
    func decode(_ value: String) throws -> [PortableVocabulary.Entry] { try codec.decode(Data(value.utf8)) }
    func bytesEqual(_ a: String, _ b: String) -> Bool { a.utf8.elementsEqual(b.utf8) }
    for (input, expected) in [("cafe\u{301}", "café"), ("a\u{315}\u{300}", "à\u{315}"),
                              ("A\u{301}\u{300}", "Á\u{300}"), ("\u{344}", "\u{308}\u{301}"),
                              ("\u{1100}\u{1161}\u{11a8}", "각")] {
        check(bytesEqual(try! unicode.nfc(input), expected), "Pinned NFC canonical order/block/exclusion/Hangul")
    }
    for (input, folded) in [("Straße", "strasse"), ("İ", "i\u{307}"), ("ς", "σ"), ("ﬃ", "ffi"),
                            ("\u{10570}", "\u{10597}"), ("\u{1c89}", "\u{1c8a}")] {
        check(bytesEqual(try! unicode.nfc(unicode.fullCaseFold(input)), folded), "Real Unicode16 full folding")
    }
    check(unicode.category(0x10570) == .letter && unicode.category(0x1c89) == .letter, "Unicode16 letters")
    check(unicode.category(0x202e) == .other && unicode.category(0xe000) == .other && unicode.category(0x378) == .other, "Pinned category C")
    check((try! decode(document(row("  cafe\\u0301  ")))).single!.term == "café", "Trim and display NFC")
    check((try! decode(document(row("\\u001c\\u001f\\u0085\\u00a0Ada\\u3000")))).single!.term == "Ada", "CPython strip includes C0 and NBSP")
    refuses("Fullfold collision") { _ = try decode(document(row("Straße") + "," + row("STRASSE"))) }
    refuses("Canonical collision") { _ = try decode(document(row("café") + "," + row("cafe\\u0301"))) }
    let existing = [PortableVocabulary.Entry(term: "Straße", source: "manual")]
    let incoming = [PortableVocabulary.Entry(term: "STRASSE", weight: 9, source: "gmail")]
    let merged = try! codec.merge(existing: existing, incoming: incoming)
    check(merged == [.init(term: "Straße", weight: 9, source: "manual")], "Preserve approved spelling/source and max weight")
    check((try! codec.merge(existing: merged, incoming: incoming)) == merged, "Idempotent merge")
    let sorted = try! codec.merge(existing: [], incoming: [.init(term: "\u{10400}"), .init(term: "Ａ"), .init(term: "Alpha", language: "es"), .init(term: "Alpha")])
    check(sorted.map(\.term) == ["Alpha", "Ａ", "\u{10400}", "Alpha"], "Python tuple/codepoint ordering, not UTF16")
    for value in [document(extra: ",\"version\":1"), document(row(extra: ",\"term\":\"Other\"")),
                  document(row(extra: ",\"te\\u0072m\":\"Other\""))] {
        refuses("Duplicate JSON property") { _ = try decode(value) }
    }
    for value in [document(extra: ",\"extra\":null"), document().replacingOccurrences(of: "\"version\":1,", with: ""),
                  document().replacingOccurrences(of: "\"version\":1", with: "\"version\":2"),
                  document().replacingOccurrences(of: "16.0.0", with: "17.0.0"),
                  document(row(extra: ",\"other\":1")), document().replacingOccurrences(of: ",\"source\":\"manual\"", with: "")] {
        refuses("Strict schema/version") { _ = try decode(value) }
    }
    for weight in ["true", "false", "null", "1.0", "1e0", "NaN", "Infinity", "01", "+1", "0", "-1", "1000001", "99999999999999999999999"] {
        refuses("Strict finite integer weight") { _ = try decode(document(row(weight: weight))) }
    }
    for value in ["[]", "null", document() + "true", String(document().dropLast()), document(row() + ","),
                  document().replacingOccurrences(of: "\"source\":\"manual\"", with: "\"source\":{}"), String(repeating: "[", count: 10000)] {
        refuses("Malformed and nested JSON") { _ = try decode(value) }
    }
    refuses("Malformed UTF8") { _ = try codec.decode(Data([0xc0, 0xaf])) }
    refuses("BOM") { _ = try decode("\u{feff}" + document()) }
    for term in ["\\ud800", "\\udc00", "\\ud800Ada", "\\udc00\\ud800"] { refuses("Unpaired surrogate") { _ = try decode(document(row(term))) } }
    check((try! decode(document(row("\\ud801\\udc00")))).single!.term == "\u{10400}", "Valid supplementary escape pair")
    for term in ["", "123456", "abc\\u202e", "a@example.com", "https://example.com", "WWW.example.com", "Ada\\u0000", "Ada\\tRiver", "Ada\\nRiver", "Ada\\ue000", "Ada\\u0378", "\\u200bAda", "Ada\\u001cRiver"] {
        refuses("Term content refusal") { _ = try decode(document(row(term))) }
    }
    for entry in [PortableVocabulary.Entry(term: "Ada", language: "fr"), .init(term: "Ada", weight: 0), .init(term: "Ada", source: "conversation-id"), .init(term: "123")] {
        refuses("Constructed entries validated") { _ = try codec.encode([entry]) }
    }
    let supplementary = String(repeating: "\u{10400}", count: 128)
    check((try! decode(document(row(supplementary)))).single!.term.unicodeScalars.count == 128, "Scalar/UTF8 limit, not grapheme/UTF16 count")
    refuses("Term limit plus one") { _ = try decode(document(row(supplementary + "\u{10400}"))) }
    refuses("Pretrim limit") { _ = try decode(document(row(String(repeating: " ", count: 256) + "A"))) }
    let expected = """
    {
      "format": "lip.learned-words",
      "version": 1,
      "unicode_version": "16.0.0",
      "entries": [
        {
          "term": "Ada",
          "language": "en",
          "weight": 1,
          "source": "manual"
        }
      ]
    }
    """ + "\n"
    check((try! codec.encode([.init(term: "Ada")])) == Data(expected.utf8), "Python pretty JSON exact bytes")
    let quote = PortableVocabulary.Entry(term: "Ada \"River\" \\ path")
    check((try! codec.decode(codec.encode([quote]))) == [quote], "Escaped quote/backslash round trip")
    check((try! codec.decode(codec.encode([]))).isEmpty, "Empty document round trip")
    refuses("Byte limit") { _ = try codec.decode(Data(repeating: 32, count: PortableVocabulary.maxBytes + 1)) }
    refuses("Merge work limit") { _ = try codec.merge(existing: [], incoming: Array(repeating: .init(term: "Ada"), count: PortableVocabulary.maxEntries * 2 + 1)) }
    let tooMany = (0...PortableVocabulary.maxEntries).map { row("Term\($0)") }.joined(separator: ",")
    refuses("Entry count limit") { _ = try decode(document(tooMany)) }
    let large = (0..<PortableVocabulary.maxEntries).map { PortableVocabulary.Entry(term: String(repeating: "A", count: 110) + String($0)) }
    refuses("Encoded size limit") { _ = try codec.encode(large) }
    if let tableData {
        refuses("Corrupt/mismatched table refuses") { _ = try PinnedUnicode16(data: tableData + Data([32])) }
    }
    if let differentialData {
        struct Oracle: Decodable {
            struct Case: Decodable { let input: String; let nfc: String; let fold: String }
            let unicode_version: String
            let cases: [Case]
        }
        let oracle = try! JSONDecoder().decode(Oracle.self, from: differentialData)
        check(oracle.unicode_version == "16.0.0", "Oracle Unicode contract")
        for row in oracle.cases {
            check(bytesEqual(try! unicode.nfc(row.input), row.nfc), "CPython16 NFC differential")
            check(bytesEqual(try! unicode.nfc(unicode.fullCaseFold(row.input)), row.fold), "CPython16 fullfold differential")
        }
        print("PASS: \(oracle.cases.count) real CPython16 NFC/fullfold differential cases")
    }
    print("PASS: portable vocabulary \(checks) pinned-Unicode/strict-codec controls")
}

private extension Array { var single: Element? { count == 1 ? first : nil } }
