import Foundation
import Darwin

private final class ImportFlowOwner { let flow=HistoryImportFlow(); deinit{flow.cancelCurrent()} }
func testHistoryImportFlow(){
    var checks=0
    func check(_ okay:@autoclosure()->Bool,_ label:String){precondition(okay(),label);checks += 1}
    func refuses(_ label:String,_ action:()throws->Void){do{try action();preconditionFailure(label)}catch{checks += 1}}
    let record=HistoryPortableRecord(metadata:HistoryPortableMetadata(id:"fixture-flow",createdAtUnixMs:1700000000125,language:nil,model:nil,modelDetails:nil,legacyCloudCleanup:false),original:"か\u{3099} 你好\n🎙️",output:"OUTPUTA")
    let document=HistoryPortableDocument(sourcePlatform:"android",record:record)
    let flow=HistoryImportFlow(),a=UUID()
    check(!flow.busy && flow.confirm(a)==nil,"Idle has no authority")
    let first=flow.begin()!;check(flow.warning(first) && flow.begin()==nil,"One warning operation only")
    check(!flow.startRead(first) && flow.confirm(first)==nil,"Cannot bypass warning/picker/preview")
    check(flow.cancel(first) && !flow.busy,"Warning cancel clears slot")
    let second=flow.begin()!;check(!flow.openPicker(first) && !flow.startRead(first),"Stale warning/picker ignored")
    check(flow.openPicker(second) && !flow.openPicker(second),"Warning positive transitions once")
    check(flow.picking(second) && !flow.warning(second),"Picker phase")
    check(flow.startRead(second) && !flow.startRead(second),"One read worker per operation")
    check(flow.reading(second) && flow.confirm(second)==nil,"No authorization during read")
    flow.cancelCurrent();check(flow.busy && flow.begin()==nil && !flow.reading(second),"Canceled read drains before reopen can enqueue")
    check(!flow.preview(second,record:record,disposition:.new) && !flow.busy,"Canceled successful read drains without preview")
    let third=flow.begin()!;check(!flow.failRead(second) && flow.warning(third),"Late failed read cannot clear newer operation")
    _ = flow.cancel(third)
    let fourth=flow.begin()!;_ = flow.openPicker(fourth);_ = flow.startRead(fourth)
    check(flow.preview(fourth,record:record,disposition:.new) && flow.previewing(fourth),"Preview accepted after read")
    check(!flow.preview(fourth,record:record,disposition:.new) && !flow.failRead(fourth),"Double/stale read completion cannot destroy preview")
    let (confirmed,auth)=flow.confirm(fourth)!
    check(confirmed==record && flow.confirm(fourth)==nil && !flow.previewing(fourth),"Immutable record and one-shot positive")
    check(!flow.failRead(fourth) && flow.busy,"Read error cannot cancel commit")
    check(!flow.finishCommit(fourth,authorization:UUID()) && flow.busy,"Wrong service event cannot finish current commit")
    flow.cancelCurrent();refuses("Close revokes unclaimed commit"){try auth.claim()}
    check(flow.busy && flow.begin()==nil,"Canceled commit drains before reopen")
    check(!flow.preview(fourth,record:record,disposition:.new) && flow.busy,"Stale read cannot clear canceled commit slot")
    check(!flow.finishCommit(fourth,authorization:auth.id) && !flow.busy,"Canceled commit result drains invisibly")
    let fresh=flow.begin()!;check(!flow.finishCommit(fourth,authorization:auth.id) && flow.warning(fresh),"Late commit cannot finish replacement")
    _ = flow.cancel(fresh)
    for kind in [HistoryPortableDisposition.conflict,.ambiguous]{
        let id=flow.begin()!;_ = flow.openPicker(id);_ = flow.startRead(id);check(flow.preview(id,record:record,disposition:kind),"Blocked preview can be displayed")
        check(flow.confirm(id)==nil && flow.busy,"Blocked preview cannot mint authorization");_ = flow.cancel(id)
    }
    let live=flow.begin()!;_ = flow.openPicker(live);_ = flow.startRead(live);_ = flow.preview(live,record:record,disposition:.identical)
    let (_,claimed)=flow.confirm(live)!;try! claimed.claim();flow.cancelCurrent()
    check(!flow.finishCommit(live,authorization:claimed.id) && !flow.busy,"Claimed cancel suppresses UI result, not storage rollback")
    refuses("Claimed authorization remains consumed"){try claimed.claim()}
    var owner:ImportFlowOwner?=ImportFlowOwner();let retained=owner!.flow,id=retained.begin()!;_ = retained.openPicker(id);_ = retained.startRead(id);_ = retained.preview(id,record:record,disposition:.new)
    let (_,ownedAuth)=retained.confirm(id)!;owner=nil
    refuses("Owner deinit cancels unclaimed commit"){try ownedAuth.claim()};check(retained.busy,"Owner loss retains slot until drained");_ = retained.finishCommit(id,authorization:ownedAuth.id)
    let picking=flow.begin()!;_ = flow.openPicker(picking);_ = flow.cancel(picking);let replacement=flow.begin()!
    check(!flow.startRead(picking) && flow.warning(replacement),"Late selected file cannot start read under a replacement operation");_ = flow.cancel(replacement)
    let fail=flow.begin()!;_ = flow.openPicker(fail);_ = flow.startRead(fail);check(flow.failRead(fail) && !flow.busy,"Active failed read finishes once");check(!flow.failRead(fail),"Duplicate error ignored")
    let shown=HistoryImportPresentation.text(document,disposition:.new)
    check(shown.contains("Unknown") && shown.contains("complete record") && (shown.contains("unverified") || shown.contains("not verified")),"Preview names unknowns and scope")
    let malicious="a\u{202E}\0\u{200F}\n\t"
    let escaped=HistoryImportPresentation.excerpt(malicious)
    check(escaped.contains("\\u{202e}") && escaped.contains("\\u{0}") && escaped.contains("\\u{200f}"),"Display controls/bidi escaped without modifying payload")
    check(malicious.unicodeScalars.count==6,"Original display source unchanged")
    let cluster="a"+String(repeating:"\u{301}",count:100000)
    check(HistoryImportPresentation.excerpt(cluster).unicodeScalars.count==601,"Preview scalar bound even for one enormous grapheme")
    var activity=HistoryWindowActivity()
    let export=activity.beginExport(),pending=activity.beginList()!
    check(activity.loading && activity.startExport(export),"Export starts independently while list pending")
    check(activity.finishExport(export) && activity.loading,"Export completion cannot clear/strand list ownership")
    let completed=activity.finishList(pending)
    check(completed.accepted && !completed.status && !activity.loading,"Pending list finishes after export; no newer-status overwrite")
    let staleExport=activity.beginExport();activity.close()
    check(!activity.startExport(staleExport),"Stale closed-window SavePanel cannot start IO")
    let currentList=activity.beginList()!,newerExport=activity.beginExport()
    _ = activity.startExport(newerExport);activity.close();let reopened=activity.beginList()!
    check(!activity.finishList(currentList).accepted && !activity.finishExport(newerExport) && activity.loading,"Old callbacks cannot clear reopened list")
    check(activity.finishList(reopened).accepted && !activity.loading,"Reopened current list drains")
    let deletion=activity.beginMutation()!;activity.close();let afterDelete=activity.beginList()!
    check(!activity.finishMutation(deletion) && activity.loading,"Old delete cannot clear new listing")
    _ = activity.finishList(afterDelete)
    let superseded=activity.beginExport();_ = activity.startExport(superseded);_ = activity.newStatus()
    check(!activity.finishExport(superseded),"Older export cannot replace newer import/status")
    let root=(try! HistoryVault.canonicalAnchor(FileManager.default.temporaryDirectory)).appendingPathComponent("LipImportFlowTests-"+UUID().uuidString,isDirectory:true)
    try! FileManager.default.createDirectory(at:root,withIntermediateDirectories:false,attributes:[.posixPermissions:0o700])
    func file(_ name:String,_ data:Data)->URL{let path=root.appendingPathComponent(name);try! data.write(to:path,options:.withoutOverwriting);return path}
    let bytes=try! HistoryPortableCodec.encode(document),source=file("valid.json",bytes)
    check(try! HistoryImportSource.read(source,current:{true})==document,"Real readonly regular-file parse")
    check(try! Data(contentsOf:source)==bytes,"Source bytes unchanged")
    refuses("Canceled selection cannot open source"){_ = try HistoryImportSource.read(source,current:{false})}
    refuses("Remote URL not fetched"){_ = try HistoryImportSource.read(URL(string:"https://example.invalid/synthetic")!,current:{true})}
    refuses("Directory refused"){_ = try HistoryImportSource.read(root,current:{true})}
    let link=root.appendingPathComponent("link.json");try! FileManager.default.createSymbolicLink(at:link,withDestinationURL:source)
    refuses("Leaf symlink refused; no resolved-path fallback"){_ = try HistoryImportSource.read(link,current:{true})}
    let malformed=file("malformed.json",Data("{oops}".utf8));refuses("Malformed source refused"){_ = try HistoryImportSource.read(malformed,current:{true})}
    let empty=file("empty.json",Data());refuses("Empty source refused"){_ = try HistoryImportSource.read(empty,current:{true})}
    let oversized=file("oversized.json",Data(repeating:32,count:HistoryPortableCodec.maximumBytes+1));refuses("Oversize refused before allocation/read"){_ = try HistoryImportSource.read(oversized,current:{true})}
    let fifo=root.appendingPathComponent("pipe.json");check(mkfifo(fifo.path,0o600)==0,"Owned FIFO fixture created")
    refuses("FIFO open does not block; descriptor type refused"){_ = try HistoryImportSource.read(fifo,current:{true})}
    let fd=open(source.path,O_RDONLY|O_CLOEXEC);check(fd>=0,"Owned descriptor open")
    check(lseek(fd,1,SEEK_SET)==1,"Nonzero descriptor offset")
    refuses("Borrowed nonzero offset refused without rewind"){_ = try HistoryImportSource.readDescriptor(fd,current:{true})}
    check(fcntl(fd,F_GETFD)>=0,"Borrowed descriptor remains owned by caller");_ = close(fd)
    let large=HistoryPortableRecord(metadata:record.metadata,original:String(repeating:"x",count:200000),output:"OUTPUTA")
    let largeBytes=try! HistoryPortableCodec.encode(HistoryPortableDocument(sourcePlatform:"android",record:large)),largeFile=file("large.json",largeBytes)
    var calls=0;refuses("Cancellation observed between chunks"){_ = try HistoryImportSource.read(largeFile,current:{calls += 1;return calls<4})}
    check(try! Data(contentsOf:largeFile)==largeBytes,"Canceled source read never writes")
    let growing=file("growing.json",bytes);var growthCalls=0
    refuses("Observable length change refused"){_ = try HistoryImportSource.read(growing,current:{growthCalls += 1;if growthCalls==3{let h=try! FileHandle(forWritingTo:growing);try! h.seekToEnd();try! h.write(contentsOf:Data([32]));try! h.close()};return true})}
    let changing=file("same-length.json",bytes);var changeCalls=0;let changed=Data(String(decoding:bytes,as:UTF8.self).replacingOccurrences(of:"OUTPUTA",with:"OUTPUTB").utf8)
    check(changed.count==bytes.count && (try! HistoryPortableCodec.decode(changed)).record.output=="OUTPUTB","Same-length mutation stays valid JSON; parser alone cannot catch it")
    refuses("Observable timestamp change refused"){_ = try HistoryImportSource.read(changing,current:{changeCalls += 1;if changeCalls==3{try! changed.write(to:changing);try! FileManager.default.setAttributes([.modificationDate:Date(timeIntervalSince1970:1700000005)],ofItemAtPath:changing.path)};return true})}
    print("PASS \(checks) Mac import flow/readonly-source/presentation checks; synthetic only, no AppKit/security-scope/Keychain/window lifecycle qualification")
    print("Synthetic flow fixtures retained at \(root.path)")
}
