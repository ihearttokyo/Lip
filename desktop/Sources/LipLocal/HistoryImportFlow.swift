import Foundation
import Darwin

/// One window operation; canceled IO retains its slot until its own callback drains.
/// Pure CPU state only: no AppKit, filesystem, provider or caller code under lock.
final class HistoryImportFlow {
    private enum Phase { case warning, picking, reading, preview, committing, canceled }
    private enum Work { case reading, commit }
    private struct Operation { let id: UUID; var phase: Phase; var record: HistoryPortableRecord?; var authorization: HistoryImportAuthorization?; var pending: Work? }
    private let lock=NSLock();private var operation:Operation?
    var busy:Bool {lock.lock();defer{lock.unlock()};return operation != nil}
    func begin()->UUID? {lock.lock();defer{lock.unlock()};guard operation==nil else{return nil};let id=UUID();operation=Operation(id:id,phase:.warning,record:nil,authorization:nil,pending:nil);return id}
    func warning(_ id:UUID)->Bool {lock.lock();defer{lock.unlock()};return operation?.id==id && operation?.phase == .warning}
    func picking(_ id:UUID)->Bool {lock.lock();defer{lock.unlock()};return operation?.id==id && operation?.phase == .picking}
    func previewing(_ id:UUID)->Bool {lock.lock();defer{lock.unlock()};return operation?.id==id && operation?.phase == .preview}
    func openPicker(_ id:UUID)->Bool {lock.lock();defer{lock.unlock()};guard operation?.id==id,operation?.phase == .warning else{return false};operation?.phase = .picking;return true}
    func current(_ id:UUID)->Bool {lock.lock();defer{lock.unlock()};return operation?.id==id && operation?.phase != .canceled}
    func startRead(_ id:UUID)->Bool {lock.lock();defer{lock.unlock()};guard operation?.id==id,operation?.phase == .picking else{return false};operation?.phase = .reading;operation?.pending = .reading;return true}
    func reading(_ id:UUID)->Bool {lock.lock();defer{lock.unlock()};return operation?.id==id && operation?.phase == .reading}
    /// True means the current visible operation may present this preview.
    func preview(_ id:UUID,record:HistoryPortableRecord,disposition:HistoryPortableDisposition)->Bool {lock.lock();defer{lock.unlock()};guard operation?.id==id,operation?.pending == .reading else{return false};if operation?.phase == .canceled{operation=nil;return false};guard operation?.phase == .reading else{return false};operation?.phase = .preview;operation?.pending=nil;operation?.record=(disposition == .new || disposition == .identical) ? record : nil;return true}
    func confirm(_ id:UUID)->(HistoryPortableRecord,HistoryImportAuthorization)? {lock.lock();defer{lock.unlock()};guard operation?.id==id,operation?.phase == .preview,let record=operation?.record else{return nil};let auth=HistoryImportAuthorization();operation?.phase = .committing;operation?.pending = .commit;operation?.record=nil;operation?.authorization=auth;return(record,auth)}
    @discardableResult func cancel(_ id:UUID)->Bool {lock.lock();defer{lock.unlock()};guard operation?.id==id else{return false};operation?.authorization?.cancel();operation?.record=nil;if operation?.pending != nil{operation?.phase = .canceled}else{operation=nil};return true}
    func cancelCurrent(){lock.lock();defer{lock.unlock()};operation?.authorization?.cancel();operation?.record=nil;if operation?.pending != nil{operation?.phase = .canceled}else{operation=nil}}
    func failRead(_ id:UUID)->Bool {lock.lock();defer{lock.unlock()};guard operation?.id==id,operation?.pending == .reading else{return false};let visible=operation?.phase != .canceled;operation=nil;return visible}
    func finishCommit(_ id:UUID,authorization:UUID)->Bool {lock.lock();defer{lock.unlock()};guard operation?.id==id,operation?.pending == .commit,operation?.authorization?.id==authorization else{return false};let visible=operation?.phase != .canceled;operation=nil;return visible}
}

/// Byte-bounded readonly snapshot. O_NONBLOCK prevents FIFO open from stalling;
/// regular-file IO may still stall in the OS/provider, never on the UI thread.
enum HistoryImportSource {
    static func read(_ url:URL,current:()->Bool)throws->HistoryPortableDocument {
        guard current(),url.isFileURL,url.path.hasPrefix("/"),!url.path.contains("\0") else{throw HistoryFailure.importRevoked}
        let fd=open(url.path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK)
        guard fd>=0 else{throw HistoryFailure.unavailable};defer{_ = close(fd)}
        return try readDescriptor(fd,current:current)
    }
    /// Borrows a fixture/caller descriptor; caller retains ownership.
    static func readDescriptor(_ fd:Int32,current:()->Bool)throws->HistoryPortableDocument {
        guard current() else{throw HistoryFailure.importRevoked};var before=stat()
        guard fstat(fd,&before)==0,before.st_mode&S_IFMT==S_IFREG,before.st_size>0,before.st_size<=HistoryPortableCodec.maximumBytes,lseek(fd,0,SEEK_CUR)==0 else{throw HistoryFailure.invalid}
        var data=Data(count:Int(before.st_size))
        try data.withUnsafeMutableBytes{buffer in
            var offset=0
            while offset<buffer.count {
                guard current() else{throw HistoryFailure.importRevoked}
                let n=Darwin.read(fd,buffer.baseAddress!.advanced(by:offset),min(65536,buffer.count-offset))
                if n<0 && errno==EINTR{continue};guard n>0 else{throw HistoryFailure.invalid};offset += n
            }
        }
        guard current() else{throw HistoryFailure.importRevoked};var extra:UInt8=0,after=stat()
        guard Darwin.read(fd,&extra,1)==0,fstat(fd,&after)==0,after.st_size==before.st_size,after.st_dev==before.st_dev,after.st_ino==before.st_ino,
            after.st_mtimespec.tv_sec==before.st_mtimespec.tv_sec,after.st_mtimespec.tv_nsec==before.st_mtimespec.tv_nsec,
            after.st_ctimespec.tv_sec==before.st_ctimespec.tv_sec,after.st_ctimespec.tv_nsec==before.st_ctimespec.tv_nsec else{throw HistoryFailure.invalid}
        return try HistoryPortableCodec.decode(data)
    }
}

enum HistoryImportPresentation {
    static func excerpt(_ value:String,limit:Int=600)->String {
        var result="";var count=0
        for scalar in value.unicodeScalars {
            if count==limit{return result+"…"};count += 1
            let n=scalar.value
            if (n<32 && n != 10 && n != 9)||(127...159).contains(n)||[0x061C,0x200E,0x200F].contains(n)||(0x202A...0x202E).contains(n)||(0x2066...0x2069).contains(n){result += "\\u{"+String(n,radix:16)+"}"}else{result.unicodeScalars.append(scalar)}
        };return result
    }
    static func text(_ document:HistoryPortableDocument,disposition:HistoryPortableDisposition)->String {
        let r=document.record,m=r.metadata
        func field(_ s:String?)->String{s.map{excerpt($0,limit:256).replacingOccurrences(of:"\n",with:"\\n").replacingOccurrences(of:"\t",with:"\\t")} ?? "Unknown"}
        let note:String
        switch disposition {case .new:note="New record. Confirm to add it to encrypted local history.";case .identical:note="Identical record exists; confirmation will not duplicate or rewrite it.";case .conflict,.ambiguous:note="ID conflict or ambiguity. Import is blocked; no record will be overwritten."}
        return "Untrusted file metadata (not verified capture provenance)\nExporter: \(document.sourcePlatform)\nID: \(field(m.id))\nUnix milliseconds: \(m.createdAtUnixMs)\nLanguage: \(field(m.language))\nModel: \(field(m.model))\nDetails: \(field(m.modelDetails))\nLegacy cloud-cleanup flag: \(m.legacyCloudCleanup)\n\nOriginal (\(r.original.utf8.count) UTF-8 bytes)\n\(excerpt(r.original))\n\nOutput (\(r.output.utf8.count) UTF-8 bytes)\n\(excerpt(r.output))\n\nPreview is bounded to 600 Unicode scalars per text; controls/bidi are escaped for display only. The complete record is imported unchanged.\n\(note)\nAutomatic recording stays unchanged. An already-admitted import may finish after closing."
    }
}

/// Main-thread view bookkeeping, independent of import authorization. Export
/// work must never replace the token responsible for clearing a pending list.
struct HistoryWindowActivity {
    struct Ticket: Equatable { let id:UUID;let window:UUID }
    private var window=UUID(),status=UUID()
    private var list:Ticket?,mutation:Ticket?,export:Ticket?
    var loading:Bool {list != nil || mutation != nil}
    mutating func newStatus()->UUID {status=UUID();return status}
    func ownsStatus(_ id:UUID)->Bool {status==id}
    mutating func beginList()->Ticket? {guard !loading else{return nil};let t=Ticket(id:UUID(),window:window);list=t;status=t.id;return t}
    mutating func finishList(_ t:Ticket)->(accepted:Bool,status:Bool) {guard list==t,t.window==window else{return(false,false)};list=nil;return(true,status==t.id)}
    mutating func beginMutation()->Ticket? {guard !loading else{return nil};let t=Ticket(id:UUID(),window:window);mutation=t;status=t.id;return t}
    mutating func finishMutation(_ t:Ticket)->Bool {guard mutation==t,t.window==window else{return false};mutation=nil;return true}
    mutating func beginExport()->Ticket {let t=Ticket(id:UUID(),window:window);export=t;return t}
    func currentExport(_ t:Ticket)->Bool {export==t && t.window==window}
    mutating func startExport(_ t:Ticket)->Bool {guard currentExport(t)else{return false};status=t.id;return true}
    mutating func finishExport(_ t:Ticket)->Bool {guard currentExport(t)else{return false};export=nil;return status==t.id}
    mutating func cancelExport(_ t:Ticket){if currentExport(t){export=nil}}
    mutating func close(){window=UUID();status=UUID();list=nil;mutation=nil;export=nil}
}
