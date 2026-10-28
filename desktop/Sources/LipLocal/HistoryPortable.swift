import Foundation

// External IDs/strings are byte identities, not Swift canonical-equivalence identities.
private func historyBytesEqual(_ a: String, _ b: String) -> Bool { a.utf8.elementsEqual(b.utf8) }
private func historyBytesEqual(_ a: String?, _ b: String?) -> Bool {
    switch (a,b) { case (nil,nil): return true; case let (a?,b?): return historyBytesEqual(a,b); default: return false }
}
struct HistoryPortableMetadata: Codable, Equatable {
    let id: String
    let createdAtUnixMs: Int64
    let language: String?
    let model: String?
    let modelDetails: String?
    let legacyCloudCleanup: Bool
    static func == (a: Self, b: Self) -> Bool {
        historyBytesEqual(a.id,b.id) && a.createdAtUnixMs == b.createdAtUnixMs &&
        historyBytesEqual(a.language,b.language) && historyBytesEqual(a.model,b.model) &&
        historyBytesEqual(a.modelDetails,b.modelDetails) && a.legacyCloudCleanup == b.legacyCloudCleanup
    }
}
struct HistoryPortableRecord: Equatable {
    let metadata: HistoryPortableMetadata
    let original: String
    let output: String
    static func == (a: Self, b: Self) -> Bool {
        a.metadata == b.metadata && historyBytesEqual(a.original,b.original) && historyBytesEqual(a.output,b.output)
    }
}
struct HistoryPortableDocument: Equatable { let sourcePlatform: String; let record: HistoryPortableRecord }
enum HistoryPortableDisposition { case new, identical, conflict, ambiguous }

// Model diagnostics are not the transcript. Portable v1 keeps its strict 4 KiB
// metadata budget; legacy native payloads are never rewritten during export.
enum HistoryModelDetails {
    static let maximumBytes = 4096
    static func bounded(_ value: String, notice: String) -> String {
        if value.utf8.count <= maximumBytes { return value }
        let suffix = "\n[" + notice + "]"
        let budget = maximumBytes - suffix.utf8.count
        precondition(budget >= 0)
        var prefix = ""; var bytes = 0
        for scalar in value.unicodeScalars {
            let count = scalar.utf8.count
            if bytes + count > budget { break }
            prefix.unicodeScalars.append(scalar); bytes += count
        }
        return prefix + suffix
    }
    static func portable(_ value: String) -> String {
        bounded(value, notice: "Metadata shortened for portable export; the original local history metadata is unchanged.")
    }
}

enum HistoryPortableCodec {
    static let schema = "dev.lip.dictation-history"
    static let maximumBytes = 4 * 1024 * 1024
    static func validate(_ record: HistoryPortableRecord) throws {
        let m = record.metadata
        // Match Kotlin Char.isWhitespace (Java whitespace OR space-char), including
        // U+001C..001F and excluding U+0085. No Unicode normalization/case folding.
        let blank = m.id.unicodeScalars.allSatisfy { scalar in
            let n = scalar.value
            return (9...13).contains(n) || (28...32).contains(n) || [0xA0,0x1680,0x2028,0x2029,0x202F,0x205F,0x3000].contains(n) || (0x2000...0x200A).contains(n)
        }
        guard !blank, m.id.utf8.count <= 256, (0...253402300799999).contains(m.createdAtUnixMs) else { throw HistoryFailure.invalid }
        for field in [m.language,m.model,m.modelDetails] { if let field, field.utf8.count > 4096 { throw HistoryFailure.limit } }
    }
    static func decode(_ data: Data) throws -> HistoryPortableDocument {
        guard data.count <= maximumBytes else { throw HistoryFailure.limit }
        guard String(data: data, encoding: .utf8) != nil else { throw HistoryFailure.invalid }
        var reader = Reader(bytes: Array(data)); let document = try reader.document()
        try validate(document.record); return document
    }
    static func encode(_ document: HistoryPortableDocument) throws -> Data {
        try validate(document.record)
        guard ["android","macos"].contains(document.sourcePlatform) else { throw HistoryFailure.invalid }
        let r = document.record, m = r.metadata
        let record: [String:Any] = ["id":m.id, "createdAtUnixMs":m.createdAtUnixMs,
            "original":r.original, "output":r.output, "language":m.language.map { $0 as Any } ?? NSNull(),
            "model":m.model.map { $0 as Any } ?? NSNull(), "modelDetails":m.modelDetails.map { $0 as Any } ?? NSNull(), "legacyCloudCleanup":m.legacyCloudCleanup]
        let data = try JSONSerialization.data(withJSONObject: ["schema":schema,"version":1,"sourcePlatform":document.sourcePlatform,"record":record], options:[.prettyPrinted,.sortedKeys])
        guard data.count <= maximumBytes else { throw HistoryFailure.limit }; return data
    }
    static func classify(_ record: HistoryPortableRecord, existing: [HistoryPortableRecord]) -> HistoryPortableDisposition {
        let same = existing.filter { historyBytesEqual($0.metadata.id,record.metadata.id) }
        if same.isEmpty { return .new }; if same.count > 1 { return .ambiguous }
        return same[0] == record ? .identical : .conflict
    }
    static func local(_ portable: HistoryPortableRecord, storageID: UUID = UUID()) throws -> HistoryRecord {
        try validate(portable)
        return HistoryRecord(id:storageID, created:Date(timeIntervalSince1970:Double(portable.metadata.createdAtUnixMs)/1000),
            profile:portable.metadata.model ?? "Unknown model", original:portable.original, output:portable.output,
            modelDetails:portable.metadata.modelDetails ?? "", imported:portable.metadata)
    }
    static func validateLocal(_ record: HistoryRecord) throws {
        guard let m = record.imported else { return } // Existing legacy native records retain their read semantics.
        try validate(HistoryPortableRecord(metadata:m, original:record.original, output:record.output))
        guard historyBytesEqual(record.profile,m.model ?? "Unknown model"), historyBytesEqual(record.modelDetails,m.modelDetails ?? ""),
            record.created.timeIntervalSince1970.isFinite,
            abs(record.created.timeIntervalSince1970 * 1000 - Double(m.createdAtUnixMs)) <= 1 else { throw HistoryFailure.invalid }
    }
    static func portable(_ local: HistoryRecord) throws -> HistoryPortableRecord {
        try validateLocal(local)
        if let m = local.imported { return HistoryPortableRecord(metadata:m, original:local.original, output:local.output) }
        let ms = local.created.timeIntervalSince1970 * 1000
        guard ms.isFinite, ms >= 0, ms <= 253402300799999 else { throw HistoryFailure.invalid }
        let record = HistoryPortableRecord(metadata:HistoryPortableMetadata(id:local.id.uuidString, createdAtUnixMs:Int64(ms.rounded(.down)),
            language:"en-US", model:local.profile, modelDetails:HistoryModelDetails.portable(local.modelDetails), legacyCloudCleanup:false), original:local.original, output:local.output)
        try validate(record); return record
    }
    // Callers must authenticate/decode every archive member first. Only matching
    // byte-identical external/native IDs need portable conversion/classification.
    static func classify(_ incoming: HistoryPortableRecord, local: [HistoryRecord]) throws -> HistoryPortableDisposition {
        let matching = try local.filter { record in
            (record.imported?.id ?? record.id.uuidString).utf8.elementsEqual(incoming.metadata.id.utf8)
        }.map { try portable($0) }
        return classify(incoming, existing: matching)
    }
    static func export(_ local: HistoryRecord) throws -> Data { try encode(HistoryPortableDocument(sourcePlatform:"macos",record:portable(local))) }

    // Fixed-depth v1 grammar. No Foundation duplicate-key/numeric coercion behavior.
    private struct Reader {
        let bytes: [UInt8]; var at = 0
        mutating func whitespace() { while at < bytes.count && [9,10,13,32].contains(bytes[at]) { at += 1 } }
        mutating func peek(_ byte: UInt8) -> Bool { whitespace(); return at < bytes.count && bytes[at] == byte }
        mutating func take(_ byte: UInt8) throws { whitespace(); guard at < bytes.count, bytes[at] == byte else { throw HistoryFailure.invalid }; at += 1 }
        mutating func literal(_ value: String) throws {
            whitespace(); let token = Array(value.utf8)
            guard bytes.count-at >= token.count, bytes[at..<at+token.count].elementsEqual(token) else { throw HistoryFailure.invalid }; at += token.count
        }
        mutating func hex() throws -> UInt32 {
            guard bytes.count-at >= 4 else { throw HistoryFailure.invalid }; var code: UInt32 = 0
            for _ in 0..<4 {
                let b = bytes[at]; at += 1; let digit: UInt8
                switch b { case 48...57: digit=b-48; case 65...70: digit=b-65+10; case 97...102: digit=b-97+10; default: throw HistoryFailure.invalid }
                code = code*16 + UInt32(digit)
            }; return code
        }
        mutating func string() throws -> String {
            try take(34); var out: [UInt8] = []
            while at < bytes.count {
                let b = bytes[at]; at += 1
                if b == 34 { guard let value = String(bytes:out,encoding:.utf8) else { throw HistoryFailure.invalid }; return value }
                guard b >= 32 else { throw HistoryFailure.invalid }
                if b != 92 { out.append(b); continue }
                guard at < bytes.count else { throw HistoryFailure.invalid }; let e = bytes[at]; at += 1
                switch e {
                case 34,47,92: out.append(e)
                case 98: out.append(8); case 102: out.append(12); case 110: out.append(10); case 114: out.append(13); case 116: out.append(9)
                case 117:
                    var code = try hex()
                    if (0xD800...0xDBFF).contains(code) {
                        guard bytes.count-at >= 2, bytes[at] == 92, bytes[at+1] == 117 else { throw HistoryFailure.invalid }; at += 2
                        let low = try hex(); guard (0xDC00...0xDFFF).contains(low) else { throw HistoryFailure.invalid }
                        code = 0x10000 + (code-0xD800)*0x400 + low-0xDC00
                    } else if (0xDC00...0xDFFF).contains(code) { throw HistoryFailure.invalid }
                    guard let scalar = Unicode.Scalar(code) else { throw HistoryFailure.invalid }; out.append(contentsOf:String(scalar).utf8)
                default: throw HistoryFailure.invalid
                }
            }; throw HistoryFailure.invalid
        }
        mutating func nullable() throws -> String? { if peek(110) { try literal("null"); return nil }; return try string() }
        mutating func integer() throws -> Int64 {
            whitespace(); let start = at
            guard at < bytes.count, (48...57).contains(bytes[at]) else { throw HistoryFailure.invalid }
            if bytes[at] == 48 { at += 1 } else { while at < bytes.count && (48...57).contains(bytes[at]) { at += 1; if at-start > 19 { throw HistoryFailure.invalid } } }
            guard let n = Int64(String(decoding:bytes[start..<at],as:UTF8.self)) else { throw HistoryFailure.invalid }; return n
        }
        mutating func boolean() throws -> Bool { if peek(116) { try literal("true"); return true }; try literal("false"); return false }
        mutating func object(_ expected: Set<String>, _ value: (inout Reader,String) throws -> Void) throws {
            try take(123); var seen = Set<String>()
            if !peek(125) { while true {
                let key = try string(); guard expected.contains(key), seen.insert(key).inserted else { throw HistoryFailure.invalid }
                try take(58); try value(&self,key); if peek(125) { break }; try take(44)
            } }
            try take(125); guard seen == expected else { throw HistoryFailure.invalid }
        }
        mutating func record() throws -> HistoryPortableRecord {
            var id="", original="", output=""; var date:Int64 = -1; var language:String?, model:String?, details:String?; var cloud=false
            try object(["id","createdAtUnixMs","original","output","language","model","modelDetails","legacyCloudCleanup"]) { r,key in
                switch key {
                case "id": id=try r.string(); case "createdAtUnixMs": date=try r.integer(); case "original": original=try r.string(); case "output": output=try r.string()
                case "language": language=try r.nullable(); case "model": model=try r.nullable(); case "modelDetails": details=try r.nullable(); default: cloud=try r.boolean()
                }
            }
            return HistoryPortableRecord(metadata:HistoryPortableMetadata(id:id,createdAtUnixMs:date,language:language,model:model,modelDetails:details,legacyCloudCleanup:cloud),original:original,output:output)
        }
        mutating func document() throws -> HistoryPortableDocument {
            var schema="", source=""; var version:Int64 = -1; var item:HistoryPortableRecord?
            try object(["schema","version","sourcePlatform","record"]) { r,key in
                switch key { case "schema": schema=try r.string(); case "version": version=try r.integer(); case "sourcePlatform": source=try r.string(); default: item=try r.record() }
            }
            whitespace(); guard at == bytes.count, schema == HistoryPortableCodec.schema, version == 1, ["android","macos"].contains(source), let item else { throw HistoryFailure.invalid }
            return HistoryPortableDocument(sourcePlatform:source,record:item)
        }
    }
}
