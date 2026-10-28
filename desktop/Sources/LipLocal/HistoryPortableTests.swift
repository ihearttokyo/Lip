import Foundation
import CryptoKit

private let historyInteropRaw = "INTEROP_SYNTHETIC か\u{3099} 你好\n🎙️"
private let historyInteropOutput = "INTEROP_SYNTHETIC が 你好\n🎙️"
private let historyInteropUUID = UUID(uuidString:"A0000000-0000-4000-8000-000000000001")!
func historyInteropNativeRecord() -> HistoryRecord {
    HistoryRecord(id:historyInteropUUID,created:Date(timeIntervalSince1970:1700000000.125),profile:"clean",original:historyInteropRaw,output:historyInteropOutput,modelDetails:"synthetic interchange")
}
func testHistoryPortable() {
    var checks=0
    func check(_ okay:@autoclosure()->Bool,_ label:String){precondition(okay(),label);checks += 1}
    func refuses(_ label:String,_ work:()throws->Void){do{try work();preconditionFailure(label)}catch{checks += 1}}
    let golden = #"{"schema":"dev.lip.dictation-history","version":1,"sourcePlatform":"android","record":{"id":"android-fixture-id","createdAtUnixMs":1700000000125,"original":"か\u3099 你好\n🎙️","output":"が 你好\n🎙️","language":null,"model":null,"modelDetails":null,"legacyCloudCleanup":true}}"#
    func decode(_ text:String)throws->HistoryPortableDocument{try HistoryPortableCodec.decode(Data(text.utf8))}
    let document=try! decode(golden), item=document.record
    check(document.sourcePlatform=="android" && item.metadata.id=="android-fixture-id","Android golden schema")
    check(item.original.utf8.elementsEqual("か\u{3099} 你好\n🎙️".utf8) && item.output.utf8.elementsEqual("が 你好\n🎙️".utf8),"Exact Unicode spelling/newline preserved")
    check(item.metadata.language==nil && item.metadata.model==nil && item.metadata.modelDetails==nil && item.metadata.legacyCloudCleanup,"Unknown metadata not invented")
    let local=try! HistoryPortableCodec.local(item)
    check(local.id.uuidString != item.metadata.id && local.imported==item.metadata,"Storage UUID is separate, external ID retained")
    check(try! HistoryPortableCodec.decode(HistoryVault.export(local)).record==item,"Null metadata local re-export fidelity")
    let old=historyInteropNativeRecord();let plain=try! JSONEncoder().encode(old)
    check((try! JSONSerialization.jsonObject(with:plain) as! [String:Any])["imported"]==nil,"Native local encoding omits imported metadata")
    check(try! JSONDecoder().decode(HistoryRecord.self,from:plain)==old,"Legacy native local payload still decodes")
    let native=try! HistoryPortableCodec.decode(HistoryVault.export(old))
    check(native.sourcePlatform=="macos" && native.record.metadata.id==old.id.uuidString && native.record.metadata.language=="en-US" && native.record.metadata.model=="clean" && !native.record.metadata.legacyCloudCleanup,"Native export semantics unchanged")
    check(native.record.metadata.createdAtUnixMs==1700000000125,"Native exact fixture milliseconds")
    let m=item.metadata
    func replace(id:String?=nil,time:Int64?=nil,language:String??=nil,model:String??=nil,details:String??=nil,cloud:Bool?=nil,raw:String?=nil,out:String?=nil)->HistoryPortableRecord{
        HistoryPortableRecord(metadata:HistoryPortableMetadata(id:id ?? m.id,createdAtUnixMs:time ?? m.createdAtUnixMs,language:language ?? m.language,model:model ?? m.model,modelDetails:details ?? m.modelDetails,legacyCloudCleanup:cloud ?? m.legacyCloudCleanup),original:raw ?? item.original,output:out ?? item.output)
    }
    check("か\u{3099}" == "が","Actual Swift canonical-equivalence counterexample")
    check(replace(id:"é") != replace(id:"e\u{301}"),"Portable IDs use byte identity")
    check(replace(raw:"か\u{3099}") != replace(raw:"が"),"Portable text uses byte identity")
    check(HistoryPortableCodec.classify(replace(id:"é"),existing:[replace(id:"e\u{301}")]) == .new,"No canonically equivalent ID merge")
    check(HistoryPortableCodec.classify(item,existing:[]) == .new,"New classification")
    check(HistoryPortableCodec.classify(item,existing:[item]) == .identical,"Identical classification")
    check(HistoryPortableCodec.classify(item,existing:[item,item]) == .ambiguous,"Ambiguous existing ID")
    for changed in [replace(time:1),replace(language:.some("en-US")),replace(model:.some("pro")),replace(details:.some("fixture")),replace(cloud:false),replace(raw:"other"),replace(out:"other")] {
        check(HistoryPortableCodec.classify(changed,existing:[item]) == .conflict,"Every changed field is a conflict")
    }
    for bad in [replace(id:""),replace(id:" \t\u{1C}"),replace(id:String(repeating:"é",count:129)),replace(time:-1),replace(time:253402300800000),replace(model:.some(String(repeating:"x",count:4097)))] {
        refuses("Constructed invalid metadata refuses"){_ = try HistoryPortableCodec.local(bad)}
    }
    try! HistoryPortableCodec.validate(replace(id:"\u{85}")) // Kotlin whitespace policy excludes NEL.
    checks += 1
    let fixtures=[
        golden.replacingOccurrences(of:"\"version\":1",with:"\"version\":1,\"version\":1"),
        golden.replacingOccurrences(of:"\"version\":1",with:#""version":1,"vers\u0069on":1"#),
        golden.replacingOccurrences(of:"\"language\":null,",with:""),
        golden.replacingOccurrences(of:"\"language\":null",with:"\"language\":{},\"extra\":null"),
        golden.replacingOccurrences(of:"\"id\"",with:"id"),golden.replacingOccurrences(of:"\"",with:"'"),
        golden.replacingOccurrences(of:"\"version\":1",with:"\"version\":1/*comment*/"),
        golden+"{}",golden+"\u{A0}","\u{FEFF}"+golden,
        golden.replacingOccurrences(of:"\"language\":null",with:"\"language\":undefined"),
        golden.replacingOccurrences(of:"\"id\":\"android-fixture-id\"",with:#""id":"\uD800""#),
        golden.replacingOccurrences(of:"\"id\":\"android-fixture-id\"",with:#""id":"\uDC00""#),
        golden.replacingOccurrences(of:"\"id\":\"android-fixture-id\"",with:#""id":"\u٠٠٠٠""#),
        golden.replacingOccurrences(of:"\"id\":\"android-fixture-id\"",with:#""id":"\x41""#),
        golden.replacingOccurrences(of:"\"id\":\"android-fixture-id\"",with:"\"id\":\"a\nb\""),
        golden.replacingOccurrences(of:"\"sourcePlatform\":\"android\"",with:"\"sourcePlatform\":\"other\""),
        golden.replacingOccurrences(of:"\"language\":null",with:"\"language\":"+String(repeating:"[",count:10000)+"0"+String(repeating:"]",count:10000))]
    for bad in fixtures { check(bad != golden,"Malformed fixture must differ");refuses("Strict grammar/Unicode refusal"){_ = try decode(bad)} }
    for number in ["-1","253402300800000","9223372036854775808","01","1.0","1e2","\"1\"","true","null","+1"] {
        refuses("Timestamp cannot coerce"){_ = try decode(golden.replacingOccurrences(of:"1700000000125",with:number))}
    }
    for version in ["0","2","1.0","1e0","01","\"1\"","true"] {
        refuses("Version cannot coerce"){_ = try decode(golden.replacingOccurrences(of:"\"version\":1",with:"\"version\":"+version))}
    }
    for flag in ["\"true\"","1","null","True"] {
        refuses("Boolean cannot coerce"){_ = try decode(golden.replacingOccurrences(of:"\"legacyCloudCleanup\":true",with:"\"legacyCloudCleanup\":"+flag))}
    }
    for bytes in [Data([0xC0,0xAF]),Data([0xED,0xA0,0x80]),Data(golden.utf8)+Data([0xFF])] {refuses("Malformed UTF8 refuses"){_ = try HistoryPortableCodec.decode(bytes)}}
    let escaped=golden.replacingOccurrences(of:"🎙️",with:#"\uD83C\uDF99\uFE0F"#)
    check(try! decode(escaped)==document,"Equivalent valid escape spelling")
    check(try! decode(" \t\r\n"+golden+"\n")==document,"Only JSON whitespace accepted")
    let boundary=Data(golden.utf8)+Data(repeating:32,count:HistoryPortableCodec.maximumBytes-golden.utf8.count)
    check(try! HistoryPortableCodec.decode(boundary)==document,"Exact document byte cap")
    refuses("Cap plus one refuses"){_ = try HistoryPortableCodec.decode(boundary+Data([32]))}
    for field in ["language","model","modelDetails"] {
        let value=String(repeating:"é",count:2048)
        _ = try! decode(golden.replacingOccurrences(of:"\"\(field)\":null",with:"\"\(field)\":\"\(value)\""));checks += 1
        refuses("Metadata byte cap plus one"){_ = try decode(golden.replacingOccurrences(of:"\"\(field)\":null",with:"\"\(field)\":\"\(value)a\""))}
    }
    let root=(try! HistoryVault.canonicalAnchor(FileManager.default.temporaryDirectory)).appendingPathComponent("LipPortableTests-"+UUID().uuidString,isDirectory:true)
    let key=SymmetricKey(data:Data(repeating:0x6B,count:32))
    let vault=HistoryVault(root:root,key:{_ in key})
    try! vault.save(local);let file=root.appendingPathComponent(local.id.uuidString+".json");let originalCipher=try! Data(contentsOf:file)
    let baseline=root.deletingLastPathComponent().appendingPathComponent(root.lastPathComponent+"-baseline.json")
    try! originalCipher.write(to:baseline,options:.withoutOverwriting) // Retain untampered encrypted control even if a later assertion fails.
    let envelope=try! JSONDecoder().decode(HistoryVault.Envelope.self,from:originalCipher)
    check(envelope.version==2,"Imported local storage uses v2")
    let restored=try! vault.list();check(restored.unreadable==0 && restored.records.count==1,"Injected-key encrypted imported record roundtrip")
    check(try! HistoryPortableCodec.portable(restored.records[0])==item,"Encrypted roundtrip preserves exact portable ID/unknowns")
    refuses("Old v1 AAD cannot authenticate imported record"){_ = try AES.GCM.open(AES.GCM.SealedBox(combined:envelope.sealed),using:key,authenticating:Data("lip-transcript-history-v1|\(local.id.uuidString)".utf8))}
    let damaged=try! JSONEncoder().encode(HistoryVault.Envelope(version:1,id:envelope.id,sealed:envelope.sealed))
    try! damaged.write(to:file)
    check(try! vault.list().unreadable==1,"Header downgrade refused")
    refuses("Invalid downgraded record not deleted"){try vault.delete(local.id)}
    check(try! Data(contentsOf:file)==damaged,"Downgrade evidence retained")
    try! originalCipher.write(to:file);try! vault.delete(local.id)
    try! vault.save(old);let nativeEnvelope=try! JSONDecoder().decode(HistoryVault.Envelope.self,from:Data(contentsOf:root.appendingPathComponent(old.id.uuidString+".json")))
    check(nativeEnvelope.version==1,"Native local records remain v1")
    _ = try! AES.GCM.open(AES.GCM.SealedBox(combined:nativeEnvelope.sealed),using:key,authenticating:Data("lip-transcript-history-v1|\(old.id.uuidString)".utf8));checks += 1
    for id in ["é","e\u{301}"] {
        let exact=replace(id:id);let stored=try! HistoryPortableCodec.local(exact);try! vault.save(stored)
        let result=try! vault.list().records.first { $0.id==stored.id }!
        check(try! HistoryPortableCodec.portable(result)==exact,"Encrypted imported ID spelling preserved")
    }
    // Correct authentication with the WRONG metadata/version pairing must still
    // refuse; this is distinct from an unauthenticated header bit flip.
    let nativeFile=root.appendingPathComponent(old.id.uuidString+".json")
    let nativeControl=try! Data(contentsOf:nativeFile)
    try! nativeControl.write(to:root.deletingLastPathComponent().appendingPathComponent(root.lastPathComponent+"-native-baseline.json"),options:.withoutOverwriting)
    for (wrong,version) in [(local,1),(old,2)] {
        let plain=try! JSONEncoder().encode(wrong)
        let box=try! AES.GCM.seal(plain,using:key,authenticating:Data("lip-transcript-history-v\(version)|\(wrong.id.uuidString)".utf8))
        let bytes=try! JSONEncoder().encode(HistoryVault.Envelope(version:version,id:wrong.id,sealed:box.combined!))
        let path=root.appendingPathComponent(wrong.id.uuidString+".json")
        try! bytes.write(to:root.deletingLastPathComponent().appendingPathComponent(root.lastPathComponent+"-mismatch-v\(version).json"),options:.withoutOverwriting)
        try! bytes.write(to:path)
        check(try! vault.list().unreadable==1,"Authenticated metadata/version mismatch rejected")
        refuses("Invalid pairing cannot be deleted"){try vault.delete(wrong.id)}
        check(try! Data(contentsOf:path)==bytes,"Rejected pairing retained unchanged")
        if wrong.id==old.id {try! nativeControl.write(to:path)} else {try! FileManager.default.removeItem(at:path)} // This exact owned fixture only.
    }
    var inconsistent=local;inconsistent.imported=HistoryPortableMetadata(id:m.id,createdAtUnixMs:m.createdAtUnixMs,language:nil,model:"different",modelDetails:nil,legacyCloudCleanup:true)
    refuses("Imported local metadata/display mismatch refuses before save"){try vault.save(inconsistent)}
    print("PASS \(checks) portable history codec/bridge checks; synthetic/injected-key CLI only, no real Keychain/UI/import admission")
    print("Synthetic portable fixtures retained at \(root.path)")
    print("INTEROP_MAC_EXPORT_B64 \((try! HistoryVault.export(old)).base64EncodedString())")
}

/// Synthetic payload-only fixture bridge, never an end-user import or arbitrary file reader.
func testHistoryPortableReceive(_ encoded: String) throws {
    guard encoded.utf8.count<=8192 else { throw HistoryFailure.invalid }
    guard let data=Data(base64Encoded:encoded),let document=try? HistoryPortableCodec.decode(data) else { throw HistoryFailure.invalid }
    let m=document.record.metadata
    guard document.record.original.utf8.elementsEqual(historyInteropRaw.utf8), document.record.output.utf8.elementsEqual(historyInteropOutput.utf8),
        m.createdAtUnixMs==1700000000125, m.id==historyInteropUUID.uuidString || m.id=="android-fixture-東京" else { throw HistoryFailure.invalid }
    if m.id==historyInteropUUID.uuidString {
        guard m.language=="en-US",m.model=="clean",m.modelDetails=="synthetic interchange",!m.legacyCloudCleanup else { throw HistoryFailure.invalid }
    } else {
        guard m.language==nil,m.model==nil,m.modelDetails==nil,m.legacyCloudCleanup else { throw HistoryFailure.invalid }
    }
    let local=try HistoryPortableCodec.local(document.record)
    let result=try HistoryVault.export(local)
    guard try HistoryPortableCodec.decode(result).record==document.record else { throw HistoryFailure.invalid }
    print("PASS synthetic cross-platform codec/local-bridge receive; no key/storage/UI/admission")
    print("INTEROP_MAC_RETURN_B64 \(result.base64EncodedString())")
}
