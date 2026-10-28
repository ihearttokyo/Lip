package dev.lip.uiprotocol;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure boundaries for the one public acoustic canary, not an Android simulation. */
public final class PhoneMicChecks {
    public static final int BYTES = 241_280;
    public static final int FRAMES = 120_640;
    public static final String FILE_NAME = "fleurs-en-013.pcm";
    public static final String SHA256 = "e42fdeed81feac1d9d660e888ceb0351e785760f24ec7b8e9d8420cfe96a800f";
    public static byte[] readFixture(File file) throws IOException {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() != BYTES)
            throw new IOException("FixtureSizeOrType");
        byte[] pcm = new byte[BYTES];
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.getChannel().size() != BYTES) throw new IOException("FixtureSizeChanged");
            int offset = 0;
            while (offset < pcm.length) {
                int count = input.read(pcm, offset, pcm.length - offset);
                if (count <= 0) throw new IOException("FixtureTruncated");
                offset += count;
            }
            if (input.read() != -1) throw new IOException("FixtureGrew");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(pcm);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (!SHA256.equals(hex.toString())) throw new IOException("FixtureShaMismatch");
        } catch (NoSuchAlgorithmException impossible) { throw new IOException("Sha256Unavailable", impossible); }
        return pcm;
    }
    public static boolean playbackComplete(long bytes, long frames, long tailNs) {
        return bytes == BYTES && frames == FRAMES && tailNs >= 2_000_000_000L;
    }
    public static boolean mayCancel(long owned, long current, boolean busy) {
        return owned > 0 && owned == current && busy;
    }
    public static boolean captureCleanupVerified(boolean startAttempted, boolean lifecycleObserved) {
        return !startAttempted || lifecycleObserved;
    }
    public static boolean latencyPassed(long stopNs, long finalNs) {
        return stopNs > 0 && finalNs >= stopNs && finalNs - stopNs < 5_000_000_000L;
    }
    public static void requireNoLegacyHistory(File encryptedDirectory) throws IOException {
        for (String name : new String[]{"history", "history.bak", "history.new"})
            if (Files.exists(new File(encryptedDirectory, name).toPath(), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("LegacyHistoryMigrationNeedsOwnerReview");
    }
    public static FixtureDirectory createFixtureDirectory(File noBackup) throws IOException {
        FixtureDirectory root = new FixtureDirectory(noBackup.getCanonicalFile());
        root.requireUnchanged();
        Path base = root.directory.toPath().resolve("test-fixtures");
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(base);
        root.requireUnchanged();
        FixtureDirectory parent = new FixtureDirectory(base.toFile());
        root.requireUnchanged();
        parent.requireUnchanged();
        Path owned = Files.createDirectory(base.resolve("phone-mic-" + UUID.randomUUID()));
        FixtureDirectory result = new FixtureDirectory(owned.toFile());
        parent.requireUnchanged();
        return result;
    }
    public static final class FixtureDirectory {
        public final File directory;
        private final List<Path> ancestors = new ArrayList<>();
        private final List<Object> identities = new ArrayList<>();
        private FixtureDirectory(File directory) throws IOException {
            this.directory = directory;
            for (Path path = directory.toPath().toAbsolutePath(); path != null; path = path.getParent()) ancestors.add(0, path);
            for (Path path : ancestors) identities.add(directoryIdentity(path));
        }
        private void requireUnchanged() throws IOException {
            // shortcut: detects observed swaps; concurrent same-UID staging writers require descriptor-relative IO.
            for (int index = 0; index < ancestors.size(); index++)
                if (!identities.get(index).equals(directoryIdentity(ancestors.get(index)))) throw new IOException("OwnedAncestorChanged");
        }
        public boolean fixtureExists() throws IOException {
            requireUnchanged();
            return Files.exists(new File(directory, FILE_NAME).toPath(), LinkOption.NOFOLLOW_LINKS);
        }
        public byte[] readFixture() throws IOException {
            requireUnchanged();
            byte[] pcm = PhoneMicChecks.readFixture(new File(directory, FILE_NAME));
            requireUnchanged();
            return pcm;
        }
    }
    private static Object directoryIdentity(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.fileKey() == null) throw new IOException("OwnedDirectoryIdentityUnavailable");
        return attributes.fileKey();
    }
    public static void cleanupFixtureDirectory(FixtureDirectory owned) throws IOException {
        owned.requireUnchanged();
        File directory = owned.directory;
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(directory.toPath())) {
            for (Path entry : entries)
                if (!entry.getFileName().toString().equals("fixture.tmp") && !entry.getFileName().toString().equals(FILE_NAME))
                    throw new IOException("OwnedDirectoryNotEmpty");
        }
        for (String name : new String[]{"fixture.tmp", FILE_NAME}) {
            owned.requireUnchanged();
            File file = new File(directory, name);
            if (Files.isSymbolicLink(file.toPath())) throw new IOException("OwnedFixtureSymlink");
            if (!file.exists()) continue;
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || !file.delete())
                throw new IOException("OwnedFixtureCleanupFailed");
        }
        owned.requireUnchanged();
        if (!directory.delete()) throw new IOException("OwnedDirectoryNotEmpty");
    }
}
