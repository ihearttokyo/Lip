import Foundation
import CryptoKit
import Darwin

private final class ImportFixtureResults {
    private let lock=NSLock();private var values:[Result<HistoryPortableDisposition,Error>]=[]
    func add(_ result:Result<HistoryPortableDisposition,Error>){lock.lock();values.append(result);lock.unlock()}
    var all:[Result<HistoryPortableDisposition,Error>]{lock.lock();defer{lock.unlock()};return values}
}
private final class ImportCancelFlag {
    private let lock=NSLock();private var storage=false
    var value:Bool {get{lock.lock();defer{lock.unlock()};return storage}set{lock.lock();storage=newValue;lock.unlock()}}
}
func testHistoryImportAdmission(){
    var checks=0
    func check(_ value:@autoclosure()->Bool,_ message:String){precondition(value(),message);checks += 1}
    func refuses(_ message:String,_ work:()throws->Void){do{try work();preconditionFailure(message)}catch{checks += 1}}
    func until(_ label:String,_ work:()->Bool){let end=Date(timeIntervalSinceNow:5);while !work() && Date()<end{_ = RunLoop.current.run(mode:.default,before:Date(timeIntervalSinceNow:0.01))};check(work(),label)}
    let root=(try! HistoryVault.canonicalAnchor(FileManager.default.temporaryDirectory)).appendingPathComponent("LipImportAdmissionTests-"+UUID().uuidString,isDirectory:true)
    try! FileManager.default.createDirectory(at:root,withIntermediateDirectories:false,attributes:[.posixPermissions:0o700])
    let key=SymmetricKey(data:Data(repeating:0x39,count:32))
    func vault(_ name:String,limits:HistoryLimits=HistoryLimits())->HistoryVault{HistoryVault(root:root.appendingPathComponent(name),limits:limits,key:{_ in key})}
    func record(_ id:String="fixture-import",raw:String="か\u{3099} 你好\n🎙️",output:String="が 你好\n🎙️",time:Int64=1700000000125,language:String?=nil,model:String?=nil,details:String?=nil,cloud:Bool=true)->HistoryPortableRecord{
        HistoryPortableRecord(metadata:HistoryPortableMetadata(id:id,createdAtUnixMs:time,language:language,model:model,modelDetails:details,legacyCloudCleanup:cloud),original:raw,output:output)
    }
    func snapshot(_ vault:HistoryVault)throws->[String:Data]{
        guard FileManager.default.fileExists(atPath:vault.root.path)else{return[:]}
        return try Dictionary(uniqueKeysWithValues:FileManager.default.contentsOfDirectory(at:vault.root,includingPropertiesForKeys:nil).map{($0.lastPathComponent,try Data(contentsOf:$0))})
    }
    let item=record();var keyCalls=0
    let absent=HistoryVault(root:root.appendingPathComponent("absent"),key:{_ in keyCalls += 1;return key})
    check(try! absent.previewImport(item) == .new,"Absent preview is new")
    check(keyCalls==0 && !FileManager.default.fileExists(atPath:absent.root.path),"Absent preview creates no key/root/lock")
    let canceled=HistoryImportAuthorization();canceled.cancel()
    refuses("Pre-canceled import refuses"){_ = try absent.importRecord(item,authorization:canceled)}
    check(keyCalls==0 && !FileManager.default.fileExists(atPath:absent.root.path),"Pre-cancel no filesystem/key work")
    refuses("Invalid input refuses before IO"){_ = try absent.importRecord(record(""),authorization:HistoryImportAuthorization())}
    check(keyCalls==0 && !FileManager.default.fileExists(atPath:absent.root.path),"Invalid input no filesystem/key work")
    var cap=HistoryLimits();cap.records=1
    let exact=vault("exact-capacity",limits:cap);let auth=HistoryImportAuthorization()
    check(try! exact.importRecord(item,authorization:auth) == .new,"Explicit new import")
    let before=try! snapshot(exact)
    check(try! exact.previewImport(item) == .identical,"Preview identical at capacity")
    let noop=HistoryImportAuthorization();check(try! exact.importRecord(item,authorization:noop) == .identical,"Identical no-op at exact capacity")
    check(try! snapshot(exact)==before,"Identical preserves ciphertext byte-for-byte")
    refuses("No-op consumes authorization"){try noop.claim()};refuses("New import auth cannot repeat"){_ = try exact.importRecord(item,authorization:auth)}
    refuses("New record exceeds capacity without eviction"){_ = try exact.importRecord(record("next"),authorization:HistoryImportAuthorization())}
    check(try! snapshot(exact)==before,"Full archive unchanged")
    for changed in [record(time:1),record(raw:"other"),record(output:"other"),record(language:"en-US"),record(model:"pro"),record(details:"different"),record(cloud:false)] {
        check(try! exact.previewImport(changed) == .conflict,"Every field participates in conflict")
        refuses("Conflict never overwrites"){_ = try exact.importRecord(changed,authorization:HistoryImportAuthorization())}
        check(try! snapshot(exact)==before,"Conflict retains original ciphertext")
    }
    check("か\u{3099} 你好\n🎙️" == "が 你好\n🎙️","Actual full-text canonical-equivalence counterexample")
    for mode in ["payload","file","bytes"] {
        var limit=HistoryLimits()
        switch mode {case "payload":limit.payload=1;case "file":limit.file=1;default:limit.bytes=1}
        let bounded=vault("limit-"+mode,limits:limit)
        refuses("Import preserves storage limit"){_ = try bounded.importRecord(item,authorization:HistoryImportAuthorization())}
        check(!(try! snapshot(bounded)).keys.contains{!$0.hasPrefix(".")},"Limit failure publishes no record")
    }
    let stale=vault("stale-preview")
    check(try! stale.previewImport(item) == .new,"Initial new preview")
    _ = try! stale.importRecord(record(output:"competing"),authorization:HistoryImportAuthorization())
    let staleBefore=try! snapshot(stale)
    refuses("Confirmation rechecks after competing import"){_ = try stale.importRecord(item,authorization:HistoryImportAuthorization())}
    check(try! snapshot(stale)==staleBefore,"Stale preview cannot overwrite")
    let duplicates=vault("ambiguous")
    try! duplicates.save(HistoryPortableCodec.local(item));try! duplicates.save(HistoryPortableCodec.local(item))
    check(try! duplicates.previewImport(item) == .ambiguous,"Two external-ID matches ambiguous")
    let duplicateBefore=try! snapshot(duplicates)
    refuses("Ambiguous archive refuses import"){_ = try duplicates.importRecord(item,authorization:HistoryImportAuthorization())}
    check(try! snapshot(duplicates)==duplicateBefore,"Ambiguous original data retained")
    let unicode=vault("byte-identities")
    _ = try! unicode.importRecord(record("é"),authorization:HistoryImportAuthorization())
    _ = try! unicode.importRecord(record("e\u{301}"),authorization:HistoryImportAuthorization())
    check(try! unicode.list().records.count==2,"Canonically equivalent IDs stay distinct")
    check(try! unicode.previewImport(record("é",raw:"が 你好\n🎙️")) == .conflict,"Text bytes, not canonical equality")
    let corrupt=vault("corrupt")
    _ = try! corrupt.importRecord(item,authorization:HistoryImportAuthorization())
    let first=try! FileManager.default.contentsOfDirectory(at:corrupt.root,includingPropertiesForKeys:nil).first{$0.pathExtension=="json"}!
    var bad=try! Data(contentsOf:first);bad[bad.count/2] ^= 1;try! bad.write(to:first)
    let corruptBefore=try! snapshot(corrupt)
    refuses("Corrupt archive blocks preview"){_ = try corrupt.previewImport(record("new"))}
    refuses("Corrupt archive blocks new admission"){_ = try corrupt.importRecord(record("new"),authorization:HistoryImportAuthorization())}
    check(try! snapshot(corrupt)==corruptBefore,"Corrupt evidence untouched")
    let orphan=vault("orphan");try! FileManager.default.createDirectory(at:orphan.root,withIntermediateDirectories:false,attributes:[.posixPermissions:0o700])
    let orphanFile=orphan.root.appendingPathComponent(".pending-"+UUID().uuidString);try! Data([1,2,3]).write(to:orphanFile)
    refuses("Orphan blocks import"){_ = try orphan.importRecord(item,authorization:HistoryImportAuthorization())}
    check(try! Data(contentsOf:orphanFile)==Data([1,2,3]),"Orphan never auto-deleted")
    let unavailable=HistoryVault(root:exact.root,key:{_ in throw HistoryFailure.keyUnavailable})
    refuses("Missing key blocks import without replacement"){_ = try unavailable.importRecord(record("new"),authorization:HistoryImportAuthorization())}
    check(try! snapshot(exact)==before,"Key failure preserves existing bytes")
    let stageAuth=HistoryImportAuthorization()
    let staged=HistoryVault(root:root.appendingPathComponent("stage-cancel"),beforeAdmission:{stageAuth.cancel()},key:{_ in key})
    refuses("Cancel after encrypted staging fences publication"){_ = try staged.importRecord(item,authorization:stageAuth)}
    check(try! staged.list().records.isEmpty,"Unclaimed staged record not published")
    check(!(try! snapshot(staged)).keys.contains{$0.hasPrefix(".pending-")},"Only own canceled stage removed")
    let entered=DispatchSemaphore(value:0),release=DispatchSemaphore(value:0),finished=DispatchSemaphore(value:0)
    let claimedAuth=HistoryImportAuthorization();let claimedResults=ImportFixtureResults()
    let claimed=HistoryVault(root:root.appendingPathComponent("claimed-cancel"),beforePublish:{entered.signal();release.wait()},key:{_ in key})
    DispatchQueue.global().async{claimedResults.add(Result{try claimed.importRecord(item,authorization:claimedAuth)});finished.signal()}
    check(entered.wait(timeout:.now()+5) == .success,"Paused after final import claim")
    let returned=ImportCancelFlag()
    DispatchQueue.global().asyncAfter(deadline:.now()+5){if !returned.value{fputs("FAIL import cancel blocked inside claimed IO\n",stderr);_exit(78)}}
    claimedAuth.cancel();returned.value=true;release.signal()
    check(finished.wait(timeout:.now()+5) == .success,"Claimed publication completes after cancel")
    check((try? claimedResults.all.singleImportResult()) == .new,"Already admitted operation not falsely rolled back")
    check(try! claimed.list().records.count==1,"Claimed record remains")
    refuses("Consumed claimed authorization cannot retry"){_ = try claimed.importRecord(record("next"),authorization:claimedAuth)}
    let raceRoot=root.appendingPathComponent("concurrent-first-root")
    let one=HistoryVault(root:raceRoot,key:{_ in key}),two=HistoryVault(root:raceRoot,key:{_ in key})
    let start=DispatchSemaphore(value:0),done=DispatchGroup(),race=ImportFixtureResults()
    for v in [one,two]{done.enter();DispatchQueue.global().async{start.wait();race.add(Result{try v.importRecord(item,authorization:HistoryImportAuthorization())});done.leave()}}
    start.signal();start.signal();check(done.wait(timeout:.now()+5) == .success,"Concurrent independent vaults completed")
    let values=race.all.compactMap{try? $0.get()}
    check(values.count==2 && values.filter{$0 == .new}.count==1 && values.filter{$0 == .identical}.count==1,"One new + one no-op under shared lock")
    check(try! one.list().records.count==1,"Concurrent import never duplicates")
    var failReadback=false
    let readback=HistoryVault(root:root.appendingPathComponent("readback-unconfirmed"),beforePublish:{failReadback=true},key:{_ in if failReadback{throw HistoryFailure.keyUnavailable};return key})
    let readbackAuth=HistoryImportAuthorization()
    refuses("Readback failure is unconfirmed"){_ = try readback.importRecord(item,authorization:readbackAuth)}
    let recovered=HistoryVault(root:readback.root,key:{_ in key});check(try! recovered.list().records.count==1,"Published evidence retained after readback failure")
    refuses("Unconfirmed admitted authorization not automatically replayed"){_ = try recovered.importRecord(item,authorization:readbackAuth)}
    check(try! recovered.importRecord(item,authorization:HistoryImportAuthorization()) == .identical,"Explicit later request safely deduplicates")
    let reconstructed=HistoryVault(root:readback.root,key:{_ in key})
    check(try! HistoryPortableCodec.portable(reconstructed.list().records[0])==item,"Reconstruction reads exact imported metadata")
    let suite="dev.lip.import-admission-tests."+UUID().uuidString
    let defaults=UserDefaults(suiteName:suite)!
    let serviceVault=vault("service-off");let service=HistoryService(defaults:defaults,vault:serviceVault)
    var preview:HistoryImportEvent?;let operation=UUID()
    check(service.previewImport(item,operation:operation){event in check(Thread.isMainThread,"Preview callback main");preview=event}==operation,"Preview returns operation identity")
    until("Preview callback arrived"){preview != nil}
    check(preview?.operation==operation && (try? preview?.result.get()) == .new,"Preview callback identity/result")
    check(!FileManager.default.fileExists(atPath:serviceVault.root.path),"Service preview default-off creates no vault")
    for (badRecord,authorization) in [(record(""),HistoryImportAuthorization()),(item,{let a=HistoryImportAuthorization();a.cancel();return a}())] {
        var failure:HistoryImportEvent?
        service.importRecord(badRecord,authorization:authorization){event in check(Thread.isMainThread,"Rejected import callback main");failure=event}
        until("Rejected import callback arrived"){failure != nil}
        check(failure?.operation==authorization.id,"Rejected operation identity preserved")
        if case .failure? = failure?.result {checks += 1}else{preconditionFailure("Invalid/canceled service import accepted")}
        check(!FileManager.default.fileExists(atPath:serviceVault.root.path),"Rejected service import creates no writer/root/key")
    }
    let explicit=HistoryImportAuthorization();var imported:HistoryImportEvent?
    service.importRecord(item,authorization:explicit){event in check(Thread.isMainThread,"Admission callback main");imported=event}
    until("Explicit import callback arrived"){imported != nil}
    check(imported?.operation==explicit.id && (try? imported?.result.get()) == .new,"Explicit callback identity/result")
    check(!service.enabled && service.ticket()==nil && defaults.object(forKey:"history.enabled.v1")==nil,"Manual import never enables automatic capture")
    let foreignVault=HistoryVault(root:serviceVault.root,key:{_ in key});let foreignDefaults=UserDefaults(suiteName:suite+".foreign")!,foreign=HistoryService(defaults:foreignDefaults,vault:foreignVault)
    var denied:HistoryImportEvent?
    foreign.importRecord(record("foreign"),authorization:HistoryImportAuthorization()){denied=$0}
    until("Foreign writer rejection callback"){denied != nil}
    if case .failure? = denied?.result {checks += 1}else{preconditionFailure("Foreign service must not acquire writer")}
    check(try! serviceVault.list().records.count==1,"Foreign writer cannot mutate archive")
    withExtendedLifetime(service) {} // Writer must remain alive throughout the contender's attempt.
    print("PASS \(checks) Mac import admission checks; synthetic injected-key/service only, no GUI/real Keychain or user consent qualification")
    print("Synthetic import admission fixtures retained at \(root.path)")
}
private extension Array where Element == Result<HistoryPortableDisposition,Error> {
    func singleImportResult()throws->HistoryPortableDisposition{guard count==1 else{throw HistoryFailure.invalid};return try self[0].get()}
}
