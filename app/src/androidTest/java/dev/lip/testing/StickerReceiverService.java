package dev.lip.testing;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.provider.OpenableColumns;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Transport-only, test-package sticker receiver. This service deliberately has no
 * activity, clipboard, editor, persistent state, or arbitrary object parceling.
 */
public class StickerReceiverService extends Service {
    public static final String DESCRIPTOR = "dev.lip.sticker.receiver.fixture.v1";
    public static final int HELLO = 1;
    public static final int READ = 2;
    public static final int HOLD = 3;
    public static final int CLOSE_HELD = 4;
    public static final int SELF_EXIT = 5;
    public static final int PERMISSION = 6;
    public static final int PARTIAL_PROBE = 7;

    private static final String TEST_PACKAGE = "dev.lip.android.sizingfixture.test";
    private static final String APP_PACKAGE = "dev.lip.android.sizingfixture";
    private static final String HANDOFF_AUTHORITY = "dev.lip.android.sizingfixture.sticker-handoff";
    private static final Pattern TOKEN_PATH = Pattern.compile("/v1/[0-9a-f]{80}");
    private static final Pattern SAFE_MIME = Pattern.compile("(?:image|application)/[a-z0-9.+-]{1,32}");
    private static final int MAX_URI_CHARS = 512;
    private static final int MAX_MODE_CHARS = 3;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PROBE_BYTES = MAX_BYTES + 1;
    private static final int BUFFER_BYTES = 32 * 1024;

    private final Object heldLock = new Object();
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private ParcelFileDescriptor held;

    /** Manifest may expose this second component with isolatedProcess=true. */
    public static class Isolated extends StickerReceiverService { }

    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            try {
                requireFixtureProcess();
                requireExpectedCaller(Binder.getCallingUid());
                if (code == INTERFACE_TRANSACTION) {
                    if (data.dataAvail() != 0) throw new IllegalArgumentException();
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                data.enforceInterface(DESCRIPTOR);
                switch (code) {
                    case HELLO:
                        data.enforceNoDataAvail();
                        reply.writeNoException();
                        reply.writeInt(Process.myUid());
                        reply.writeInt(Process.myPid());
                        return true;
                    case READ: {
                        String uri = readBoundedString(data, MAX_URI_CHARS);
                        String mode = readBoundedString(data, MAX_MODE_CHARS);
                        data.enforceNoDataAvail();
                        if (!isSupportedMode(mode)) throw new IllegalArgumentException();
                        ReadResult result = readUri(uri, mode);
                        writeReadResult(reply, result);
                        return true;
                    }
                    case HOLD: {
                        String uri = readBoundedString(data, MAX_URI_CHARS);
                        data.enforceNoDataAvail();
                        Result result = holdUri(uri);
                        writeResult(reply, result);
                        return true;
                    }
                    case CLOSE_HELD:
                        data.enforceNoDataAvail();
                        writeResult(reply, closeHeld());
                        return true;
                    case SELF_EXIT:
                        data.enforceNoDataAvail();
                        reply.writeNoException();
                        reply.writeInt(1);
                        reply.writeString("");
                        final int ownPid = Process.myPid();
                        handler.postDelayed(() -> Process.killProcess(ownPid), 30L);
                        return true;
                    case PERMISSION: {
                        String uri = readBoundedString(data, MAX_URI_CHARS);
                        data.enforceNoDataAvail();
                        int granted;
                        String permissionError = "";
                        try {
                            Uri parsed = requireSameHandoffAuthority(uri);
                            granted = checkUriPermission(parsed, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception e) {
                            granted = PackageManager.PERMISSION_DENIED;
                            permissionError = errorClass(e);
                        }
                        reply.writeNoException();
                        reply.writeInt(granted == PackageManager.PERMISSION_GRANTED ? 1 : 0);
                        reply.writeString(permissionError);
                        return true;
                    }
                    case PARTIAL_PROBE: {
                        data.enforceNoDataAvail();
                        writeReadResult(reply, consume(partialFailureStream(), null));
                        return true;
                    }
                    default:
                        data.enforceNoDataAvail();
                        return super.onTransact(code, data, reply, flags);
                }
            } catch (SecurityException e) {
                reply.writeException(new SecurityException());
                return true;
            } catch (IllegalArgumentException e) {
                reply.writeException(new IllegalArgumentException());
                return true;
            } catch (RuntimeException e) {
                // Do not expose attacker-controlled URI, parcel contents, or provider messages.
                reply.writeException(new IllegalArgumentException());
                return true;
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        requireFixtureProcess();
    }

    @Override public IBinder onBind(android.content.Intent intent) {
        requireFixtureProcess();
        return binder;
    }

    @Override public void onDestroy() {
        closeHeldDescriptor();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void requireFixtureProcess() {
        if (getPackageName() == null || !TEST_PACKAGE.equals(getPackageName()) || !"ranchu".equals(Build.HARDWARE)) {
            throw new SecurityException();
        }
    }

    private void requireExpectedCaller(int callingUid) {
        try {
            PackageManager manager = getPackageManager();
            ApplicationInfo app;
            if (Build.VERSION.SDK_INT >= 33) {
                app = manager.getApplicationInfo(APP_PACKAGE, PackageManager.ApplicationInfoFlags.of(0));
            } else {
                //noinspection deprecation
                app = manager.getApplicationInfo(APP_PACKAGE, 0);
            }
            if (callingUid != app.uid) throw new SecurityException();
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
            throw new SecurityException();
        }
    }

    private static String readBoundedString(Parcel data, int maxChars) {
        String value = data.readString();
        if (value == null || value.length() > maxChars) throw new IllegalArgumentException();
        return value;
    }

    private static boolean isSupportedMode(String mode) {
        return "r".equals(mode) || "w".equals(mode) || "rw".equals(mode);
    }

    private static boolean hasExactHandoffUri(Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) ||
                !HANDOFF_AUTHORITY.equals(uri.getAuthority()) || uri.getQuery() != null || uri.getFragment() != null ||
                uri.getUserInfo() != null || uri.getPort() != -1) return false;
        String path = uri.getEncodedPath();
        return path != null && TOKEN_PATH.matcher(path).matches();
    }

    /** Same-authority malformed paths are still probed through the provider, then fail closed. */
    private Uri requireSameHandoffAuthority(String raw) {
        if (raw == null || raw.length() > MAX_URI_CHARS) throw new IllegalArgumentException();
        Uri uri;
        try { uri = Uri.parse(raw); }
        catch (RuntimeException e) { throw new IllegalArgumentException(); }
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) ||
                !HANDOFF_AUTHORITY.equals(uri.getAuthority()) || uri.getUserInfo() != null || uri.getPort() != -1) {
            throw new SecurityException();
        }
        return uri;
    }

    private ReadResult readUri(String rawUri, String mode) {
        Uri uri = requireSameHandoffAuthority(rawUri);
        String mime = null;
        ContentResolver resolver = getContentResolver();
        try { mime = safeMime(resolver.getType(uri)); }
        catch (Exception ignored) { /* Metadata denial is not an open/read denial. */ }
        // Metadata access may be intentionally denied. Probe only fixed columns; never return names.
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                cursor.getColumnIndex(OpenableColumns.SIZE);
            }
        } catch (Exception ignored) { /* Metadata denial is not an open/read denial. */ }

        ParcelFileDescriptor pfd = null;
        try {
            pfd = resolver.openFileDescriptor(uri, mode);
            if (pfd == null) return ReadResult.failure(mime, "FileNotFoundException");
            // Opening with a non-read mode is observation only: never write to it.
            // Report what the provider actually allowed rather than masking unexpected grants.
            if (!"r".equals(mode)) return ReadResult.success(0, "", mime);
            InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            pfd = null; // stream now owns exact descriptor
            return consume(input, mime);
        } catch (Exception e) {
            return ReadResult.failure(mime, errorClass(e));
        } finally {
            if (pfd != null) try { pfd.close(); } catch (IOException ignored) { }
        }
    }

    /** Counts bytes from the actual stream even when a later read or close fails. */
    private static ReadResult consume(InputStream input, String mime) {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        String failure = "";
        try {
            while (total < MAX_PROBE_BYTES) {
                int request = Math.min(buffer.length, MAX_PROBE_BYTES - total);
                int count = input.read(buffer, 0, request);
                if (count < 0) break;
                if (count == 0) throw new IOException();
                total += count;
                if (total > MAX_BYTES) {
                    failure = "IOException";
                    break;
                }
                digest.update(buffer, 0, count);
            }
        } catch (Exception e) {
            failure = errorClass(e);
        }
        try {
            input.close();
        } catch (Exception e) {
            if (failure.isEmpty()) failure = errorClass(e);
        }
        if (!failure.isEmpty()) return ReadResult.failure(mime, failure, total);
        return ReadResult.success(total, hex(digest.digest()), mime);
    }

    /** Independent receiver counter probe; this synthetic stream is not a file/image/provider test. */
    private static InputStream partialFailureStream() {
        return new InputStream() {
            private boolean emitted;
            @Override public int read() throws IOException {
                if (emitted) throw new IOException();
                emitted = true;
                return 'a';
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (emitted) throw new IOException();
                if (length <= 0) return 0;
                buffer[offset] = 'a';
                buffer[offset + 1] = 'b';
                buffer[offset + 2] = 'c';
                emitted = true;
                return 3;
            }
        };
    }

    private Result holdUri(String rawUri) {
        Uri uri = requireSameHandoffAuthority(rawUri);
        if (!hasExactHandoffUri(uri)) return Result.failure("SecurityException");
        synchronized (heldLock) {
            if (held != null) return Result.failure("IllegalStateException");
            ParcelFileDescriptor pfd = null;
            try {
                pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) return Result.failure("FileNotFoundException");
                if (!hasExactHandoffUri(uri)) return Result.failure("SecurityException");
                held = pfd;
                pfd = null;
                return Result.success();
            } catch (Exception e) {
                return Result.failure(errorClass(e));
            } finally {
                if (pfd != null) try { pfd.close(); } catch (IOException ignored) { }
            }
        }
    }

    private Result closeHeld() {
        synchronized (heldLock) {
            if (held == null) return Result.success();
            ParcelFileDescriptor value = held;
            held = null;
            try { value.close(); return Result.success(); }
            catch (IOException e) { return Result.failure(errorClass(e)); }
        }
    }

    private void closeHeldDescriptor() {
        synchronized (heldLock) {
            ParcelFileDescriptor value = held;
            held = null;
            if (value != null) try { value.close(); } catch (IOException ignored) { }
        }
    }

    private static void writeResult(Parcel reply, Result result) {
        reply.writeNoException();
        reply.writeInt(result.success ? 1 : 0);
        reply.writeString(result.errorClass);
    }

    private static void writeReadResult(Parcel reply, ReadResult result) {
        reply.writeNoException();
        reply.writeInt(result.success ? 1 : 0);
        reply.writeInt(result.bytes);
        reply.writeString(result.sha256);
        reply.writeString(result.mime);
        reply.writeString(result.errorClass);
    }

    private static String errorClass(Throwable error) {
        String name = error.getClass().getSimpleName();
        return name.matches("[A-Za-z0-9]{1,64}") ? name : "Exception";
    }

    private static String safeMime(String mime) {
        return mime != null && SAFE_MIME.matcher(mime).matches() ? mime : null;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static class Result {
        final boolean success;
        final String errorClass;
        private Result(boolean success, String errorClass) { this.success = success; this.errorClass = errorClass; }
        static Result success() { return new Result(true, ""); }
        static Result failure(String errorClass) { return new Result(false, errorClass); }
    }

    private static final class ReadResult extends Result {
        final int bytes;
        final String sha256;
        final String mime;
        private ReadResult(boolean success, int bytes, String sha256, String mime, String errorClass) {
            super(success, errorClass);
            this.bytes = bytes; this.sha256 = sha256; this.mime = mime;
        }
        static ReadResult success(int bytes, String sha256, String mime) {
            return new ReadResult(true, bytes, sha256, mime, "");
        }
        static ReadResult failure(String mime, String errorClass) { return failure(mime, errorClass, 0); }
        static ReadResult failure(String mime, String errorClass, int bytes) {
            return new ReadResult(false, bytes, "", mime, errorClass);
        }
    }
}
