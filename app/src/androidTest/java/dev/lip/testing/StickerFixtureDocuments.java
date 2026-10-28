package dev.lip.testing;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only SAF source for the isolated sticker UI instrumentation fixture.
 * It serves only two host-created ZIPs, and only while their owner token is active.
 * Keep this Java/SDK-only: the test-provider process cannot rely on the main APK's Kotlin runtime.
 */
public final class StickerFixtureDocuments extends DocumentsProvider {
    public static final String ROOT_TITLE = "Lip sticker test files";
    private static final String TEST_PACKAGE = "dev.lip.android.sizingfixture.test";
    private static final String OWNER_FILE = "sticker-ui-active-owner.json";
    private static final String FIXTURE_PREFIX = "sticker-ui-fixture-";
    private static final long MAX_SOURCE_BYTES = 16L * 1024L * 1024L;
    private static final String[] ROOT_COLUMNS = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON
    };
    private static final String[] DOCUMENT_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };

    private enum Kind {
        PACK("pack", "pack.zip", "Synthetic sticker pack.zip"),
        BROKEN("broken", "broken.zip", "Broken sticker pack.zip"),
        WECHAT("wechat", "wechat.zip", "Selected local reactions.zip");

        final String id;
        final String filename;
        final String label;
        Kind(String id, String filename, String label) {
            this.id = id; this.filename = filename; this.label = label;
        }
        static Kind parse(String id) throws FileNotFoundException {
            for (Kind kind : values()) if (kind.id.equals(id)) return kind;
            throw new FileNotFoundException("Unknown sticker fixture document");
        }
    }

    private static final class Owner {
        final String token;
        final File directory;
        Owner(String token, File directory) { this.token = token; this.directory = directory; }
    }

    private static final class ParsedDocument {
        final String owner;
        final Kind kind;
        ParsedDocument(String owner, Kind kind) { this.owner = owner; this.kind = kind; }
    }

    private void requireFixtureContext() {
        if (getContext() == null || !TEST_PACKAGE.equals(getContext().getPackageName()) ||
                !"ranchu".equals(android.os.Build.HARDWARE)) {
            throw new IllegalStateException("Sticker document fixture is emulator-test only");
        }
    }

    private static String canonicalOwner(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? value : null;
        } catch (IllegalArgumentException e) { return null; }
    }

    /** Missing/invalid owner intentionally yields no roots; linked or noncanonical state is inert. */
    private Owner activeOwner() {
        requireFixtureContext();
        File noBackup = getContext().getNoBackupFilesDir();
        if (Files.isSymbolicLink(noBackup.toPath()) || !Files.isDirectory(noBackup.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        File ownerFile = new File(noBackup, OWNER_FILE);
        if (Files.isSymbolicLink(ownerFile.toPath()) || !Files.isRegularFile(ownerFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                ownerFile.length() < 1 || ownerFile.length() > 256) return null;
        String token;
        try {
            byte[] bytes = Files.readAllBytes(ownerFile.toPath());
            JSONObject json = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            Set<String> keys = new HashSet<>();
            java.util.Iterator<String> iterator = json.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            if (!keys.equals(new HashSet<>(Arrays.asList("version", "owner")))) return null;
            Object version = json.opt("version");
            if (!(version instanceof Integer) || ((Integer) version) != 1) return null;
            Object rawOwner = json.opt("owner");
            if (!(rawOwner instanceof String)) return null;
            token = canonicalOwner((String) rawOwner);
            if (token == null) return null;
        } catch (Exception e) { return null; }

        File directory = new File(noBackup, FIXTURE_PREFIX + token);
        try {
            if (!directory.getParentFile().getCanonicalFile().equals(noBackup.getCanonicalFile()) ||
                    Files.isSymbolicLink(directory.toPath()) || !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        } catch (IOException e) { return null; }
        return new Owner(token, directory);
    }

    private static String rootId(String owner) { return "lip-sticker-root-" + owner; }
    private static String documentId(String owner, Kind kind) { return "lip-sticker-doc-" + owner + "-" + kind.id; }

    private static String parseRootId(String id) {
        if (id == null || !id.startsWith("lip-sticker-root-")) return null;
        return canonicalOwner(id.substring("lip-sticker-root-".length()));
    }

    private static ParsedDocument parseDocumentId(String id) {
        String prefix = "lip-sticker-doc-";
        if (id == null || !id.startsWith(prefix)) return null;
        String body = id.substring(prefix.length());
        int split = body.lastIndexOf('-');
        if (split <= 0) return null;
        String owner = canonicalOwner(body.substring(0, split));
        if (owner == null) return null;
        try { return new ParsedDocument(owner, Kind.parse(body.substring(split + 1))); }
        catch (FileNotFoundException e) { return null; }
    }

    private Owner requireCurrent(String token) throws FileNotFoundException {
        Owner owner = activeOwner();
        if (owner == null || !owner.token.equals(token)) throw new FileNotFoundException("Stale sticker fixture document");
        return owner;
    }

    /** Fixed direct-child names only; never resolve a caller-supplied path. */
    private File source(Owner owner, Kind kind, boolean mustExist) throws FileNotFoundException {
        requireCurrent(owner.token);
        File file = new File(owner.directory, kind.filename);
        try {
            if (!file.getParentFile().getCanonicalFile().equals(owner.directory.getCanonicalFile()) ||
                    Files.isSymbolicLink(file.toPath())) throw new FileNotFoundException("Invalid sticker fixture file");
        } catch (IOException e) { throw new FileNotFoundException("Invalid sticker fixture file"); }
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (mustExist) throw new FileNotFoundException("Sticker fixture document absent");
            return file;
        }
        if (file.length() > MAX_SOURCE_BYTES) throw new FileNotFoundException("Sticker fixture document exceeds limit");
        return file;
    }

    private static String[] columns(String[] projection, String[] defaults) {
        return projection == null ? defaults : projection;
    }

    private static void addRow(MatrixCursor cursor, Map<String, Object> values) {
        Object[] row = new Object[cursor.getColumnNames().length];
        String[] columns = cursor.getColumnNames();
        for (int i = 0; i < columns.length; i++) row[i] = values.get(columns[i]);
        cursor.addRow(row);
    }

    @Override public boolean onCreate() {
        requireFixtureContext();
        return true;
    }

    @Override public Cursor queryRoots(String[] projection) {
        requireFixtureContext();
        MatrixCursor cursor = new MatrixCursor(columns(projection, ROOT_COLUMNS));
        Owner owner = activeOwner();
        if (owner == null) return cursor;
        Map<String, Object> values = new HashMap<>();
        values.put(Root.COLUMN_ROOT_ID, rootId(owner.token));
        values.put(Root.COLUMN_DOCUMENT_ID, rootId(owner.token));
        values.put(Root.COLUMN_TITLE, ROOT_TITLE);
        values.put(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY);
        values.put(Root.COLUMN_MIME_TYPES, "application/zip");
        values.put(Root.COLUMN_ICON, android.R.drawable.ic_menu_save);
        addRow(cursor, values);
        return cursor;
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        String rootOwner = parseRootId(id);
        if (rootOwner != null) {
            requireCurrent(rootOwner);
            Map<String, Object> values = new HashMap<>();
            values.put(Document.COLUMN_DOCUMENT_ID, id);
            values.put(Document.COLUMN_DISPLAY_NAME, ROOT_TITLE);
            values.put(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
            values.put(Document.COLUMN_FLAGS, 0);
            values.put(Document.COLUMN_SIZE, 0L);
            values.put(Document.COLUMN_LAST_MODIFIED, 0L);
            addRow(cursor, values);
            return;
        }
        ParsedDocument parsed = parseDocumentId(id);
        if (parsed == null) throw new FileNotFoundException("Unknown sticker fixture document");
        Owner owner = requireCurrent(parsed.owner);
        File file = source(owner, parsed.kind, true);
        Map<String, Object> values = new HashMap<>();
        values.put(Document.COLUMN_DOCUMENT_ID, id);
        values.put(Document.COLUMN_DISPLAY_NAME, parsed.kind.label);
        values.put(Document.COLUMN_MIME_TYPE, "application/zip");
        values.put(Document.COLUMN_FLAGS, 0);
        values.put(Document.COLUMN_SIZE, file.length());
        values.put(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        addRow(cursor, values);
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        requireFixtureContext();
        MatrixCursor cursor = new MatrixCursor(columns(projection, DOCUMENT_COLUMNS));
        addDocument(cursor, documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        requireFixtureContext();
        String token = parseRootId(parentDocumentId);
        if (token == null) throw new FileNotFoundException("Unknown sticker fixture root");
        Owner owner = requireCurrent(token);
        MatrixCursor cursor = new MatrixCursor(columns(projection, DOCUMENT_COLUMNS));
        for (Kind kind : Kind.values()) {
            requireCurrent(owner.token);
            File file = source(owner, kind, false);
            if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) addDocument(cursor, documentId(owner.token, kind));
        }
        return cursor;
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        requireFixtureContext();
        String owner = parseRootId(parentDocumentId);
        ParsedDocument child = parseDocumentId(documentId);
        if (owner == null || child == null || !owner.equals(child.owner)) return false;
        try { source(requireCurrent(owner), child.kind, true); return true; }
        catch (FileNotFoundException e) { return false; }
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        requireFixtureContext();
        if (!"r".equals(mode)) throw new FileNotFoundException("Sticker fixture is read-only");
        if (signal != null) signal.throwIfCanceled();
        ParsedDocument parsed = parseDocumentId(documentId);
        if (parsed == null) throw new FileNotFoundException("Unknown sticker fixture document");
        Owner owner = requireCurrent(parsed.owner);
        File file = source(owner, parsed.kind, true);
        if (signal != null) signal.throwIfCanceled();

        java.io.FileDescriptor fd = null;
        try {
            fd = Os.open(file.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
            android.system.StructStat stat = Os.fstat(fd);
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 0 || stat.st_size > MAX_SOURCE_BYTES) {
                throw new FileNotFoundException("Invalid sticker fixture source");
            }
            if (activeOwner() == null || !owner.token.equals(activeOwner().token)) {
                throw new FileNotFoundException("Stale sticker fixture document");
            }
            if (signal != null) signal.throwIfCanceled();
            return ParcelFileDescriptor.dup(fd);
        } catch (FileNotFoundException e) { throw e; }
        catch (Exception e) {
            FileNotFoundException failure = new FileNotFoundException("Cannot open sticker fixture document");
            failure.initCause(e);
            throw failure;
        } finally {
            if (fd != null) try { Os.close(fd); } catch (Exception ignored) { }
        }
    }
}
