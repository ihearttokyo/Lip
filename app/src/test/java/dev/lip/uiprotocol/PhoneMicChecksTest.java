package dev.lip.uiprotocol;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneMicChecksTest {
    @Test public void admitsOnlyTheFullFrozenPublicPcm() throws Exception {
        Path fixture = Path.of("eval/android/fixtures/fleurs-en-013.pcm");
        if (!Files.exists(fixture)) fixture = Path.of("../eval/android/fixtures/fleurs-en-013.pcm");
        byte[] original = Files.readAllBytes(fixture);
        assertEquals(PhoneMicChecks.BYTES, original.length);
        assertArrayEquals(original, PhoneMicChecks.readFixture(fixture.toFile()));
        Path directory = Files.createTempDirectory("phone-mic-check-");
        Path candidate = directory.resolve("fleurs-en-013.pcm");
        try {
            for (byte[] invalid : new byte[][] {new byte[0], Arrays.copyOf(original, original.length - 2),
                    Arrays.copyOf(original, original.length + 1), new byte[original.length]}) {
                Files.write(candidate, invalid);
                assertThrows(IOException.class, () -> PhoneMicChecks.readFixture(candidate.toFile()));
            }
            byte[] changed = original.clone(); changed[changed.length - 1] ^= 1;
            Files.write(candidate, changed);
            assertThrows(IOException.class, () -> PhoneMicChecks.readFixture(candidate.toFile()));
            Files.delete(candidate);
            Files.createSymbolicLink(candidate, fixture.toAbsolutePath());
            assertThrows(IOException.class, () -> PhoneMicChecks.readFixture(candidate.toFile()));
        } finally { Files.deleteIfExists(candidate); Files.delete(directory); }
        assertThrows(IOException.class, () -> PhoneMicChecks.readFixture(new File("missing-public-canary")));
    }
    @Test public void finishNeedsEveryWrittenAndPlayedFrameAndTheUnchangedTail() {
        assertTrue(PhoneMicChecks.playbackComplete(241_280, 120_640, 2_000_000_000L));
        assertFalse(PhoneMicChecks.playbackComplete(241_278, 120_640, 2_000_000_000L));
        assertFalse(PhoneMicChecks.playbackComplete(241_280, 120_639, 2_000_000_000L));
        assertFalse(PhoneMicChecks.playbackComplete(241_282, 120_641, 2_000_000_000L));
        assertFalse(PhoneMicChecks.playbackComplete(241_280, 120_640, 1_999_999_999L));
    }
    @Test public void cleanupNeverCancelsANewOrCompletedOperation() {
        assertTrue(PhoneMicChecks.mayCancel(3, 3, true));
        assertFalse(PhoneMicChecks.mayCancel(3, 4, true));
        assertFalse(PhoneMicChecks.mayCancel(3, 3, false));
        assertFalse(PhoneMicChecks.mayCancel(0, 0, true));
    }
    @Test public void lateFinalCanNeverTurnIntoALatencyPass() {
        assertTrue(PhoneMicChecks.latencyPassed(1, 5_000_000_000L));
        assertFalse(PhoneMicChecks.latencyPassed(1, 5_000_000_001L));
        assertFalse(PhoneMicChecks.latencyPassed(1, 15_000_000_001L));
        assertFalse(PhoneMicChecks.latencyPassed(2, 1));
        assertFalse(PhoneMicChecks.latencyPassed(0, 0));
    }
    @Test public void unobservedStartCannotCertifyEmptyCaptureHandles() {
        assertTrue(PhoneMicChecks.captureCleanupVerified(false, false));
        assertTrue(PhoneMicChecks.captureCleanupVerified(true, true));
        assertFalse(PhoneMicChecks.captureCleanupVerified(true, false));
    }
    @Test public void cleanupRejectsLinkedAndNonLinkedReplacedAncestors() throws Exception {
        for (boolean link : new boolean[]{true, false}) {
            Path root = Files.createTempDirectory("phone-mic-replaced-base-");
            PhoneMicChecks.FixtureDirectory fixtureDirectory = PhoneMicChecks.createFixtureDirectory(root.toFile());
            Path base = root.resolve("test-fixtures");
            Path owned = fixtureDirectory.directory.toPath();
            Path displaced = root.resolve("retained-base");
            Path unrelated = Files.createDirectory(root.resolve("unrelated"));
            Path other = Files.createDirectory(unrelated.resolve(owned.getFileName()));
            Path originalPcm = owned.resolve(PhoneMicChecks.FILE_NAME);
            Path otherPcm = other.resolve(PhoneMicChecks.FILE_NAME);
            Files.write(originalPcm, new byte[]{1}); Files.write(otherPcm, new byte[]{7});
            try {
                Files.move(base, displaced);
                if (link) Files.createSymbolicLink(base, unrelated);
                else Files.move(unrelated, base);
                Path replacementPcm = base.resolve(owned.getFileName()).resolve(PhoneMicChecks.FILE_NAME);
                assertThrows(IOException.class, fixtureDirectory::fixtureExists);
                IOException readFailure = assertThrows(IOException.class, fixtureDirectory::readFixture);
                assertEquals(link ? "OwnedDirectoryIdentityUnavailable" : "OwnedAncestorChanged", readFailure.getMessage());
                assertThrows(IOException.class, () -> PhoneMicChecks.cleanupFixtureDirectory(fixtureDirectory));
                assertArrayEquals(new byte[]{7}, Files.readAllBytes(replacementPcm));
                assertTrue(Files.isDirectory(replacementPcm.getParent()));
                assertArrayEquals(new byte[]{1}, Files.readAllBytes(displaced.resolve(owned.getFileName()).resolve(PhoneMicChecks.FILE_NAME)));
            } finally {
                Path replacement = link ? other : base.resolve(owned.getFileName());
                Files.deleteIfExists(replacement.resolve(PhoneMicChecks.FILE_NAME)); Files.deleteIfExists(replacement);
                Files.deleteIfExists(base); Files.deleteIfExists(unrelated);
                Files.delete(displaced.resolve(owned.getFileName()).resolve(PhoneMicChecks.FILE_NAME));
                Files.delete(displaced.resolve(owned.getFileName())); Files.delete(displaced); Files.delete(root);
            }
        }
    }
    @Test public void directoryIdentityProtectsTheUuidAndFixtureAdmission() throws Exception {
        Path fixture = Path.of("eval/android/fixtures/fleurs-en-013.pcm");
        if (!Files.exists(fixture)) fixture = Path.of("../eval/android/fixtures/fleurs-en-013.pcm");
        byte[] original = Files.readAllBytes(fixture);
        Path root = Files.createTempDirectory("phone-mic-replaced-session-");
        PhoneMicChecks.FixtureDirectory owned = PhoneMicChecks.createFixtureDirectory(root.toFile());
        Path directory = owned.directory.toPath();
        Path displaced = directory.resolveSibling("retained-session");
        try {
            Files.write(directory.resolve(PhoneMicChecks.FILE_NAME), original);
            assertTrue(owned.fixtureExists());
            assertArrayEquals(original, owned.readFixture());
            Files.move(directory, displaced);
            Files.createDirectory(directory);
            Files.write(directory.resolve(PhoneMicChecks.FILE_NAME), original);
            assertThrows(IOException.class, owned::fixtureExists);
            assertThrows(IOException.class, owned::readFixture);
            assertThrows(IOException.class, () -> PhoneMicChecks.cleanupFixtureDirectory(owned));
            assertArrayEquals(original, Files.readAllBytes(directory.resolve(PhoneMicChecks.FILE_NAME)));
            assertArrayEquals(original, Files.readAllBytes(displaced.resolve(PhoneMicChecks.FILE_NAME)));
        } finally {
            Files.deleteIfExists(directory.resolve(PhoneMicChecks.FILE_NAME)); Files.deleteIfExists(directory);
            Files.deleteIfExists(displaced.resolve(PhoneMicChecks.FILE_NAME)); Files.deleteIfExists(displaced);
            Files.delete(root.resolve("test-fixtures")); Files.delete(root);
        }
    }
    @Test public void fixtureCreationRejectsALinkedBaseWithoutCreatingAChild() throws Exception {
        Path root = Files.createTempDirectory("phone-mic-linked-base-");
        Path unrelated = Files.createDirectory(root.resolve("unrelated"));
        Path base = root.resolve("test-fixtures");
        try {
            Files.createSymbolicLink(base, unrelated);
            assertThrows(IOException.class, () -> PhoneMicChecks.createFixtureDirectory(root.toFile()));
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(unrelated)) {
                assertFalse(entries.iterator().hasNext());
            }
        } finally { Files.deleteIfExists(base); Files.delete(unrelated); Files.delete(root); }
    }
    @Test public void fixtureCleanupDeletesOnlyFixedFilesAndNeverDescends() throws Exception {
        Path root = Files.createTempDirectory("phone-mic-cleanup-");
        PhoneMicChecks.FixtureDirectory owned = PhoneMicChecks.createFixtureDirectory(root.toFile());
        Path directory = owned.directory.toPath();
        Path pcm = directory.resolve(PhoneMicChecks.FILE_NAME);
        Path temporary = directory.resolve("fixture.tmp");
        try {
            Files.write(pcm, new byte[]{1}); Files.write(temporary, new byte[]{2});
            PhoneMicChecks.cleanupFixtureDirectory(owned);
            assertFalse(Files.exists(directory));
            PhoneMicChecks.FixtureDirectory next = PhoneMicChecks.createFixtureDirectory(root.toFile());
            directory = next.directory.toPath(); pcm = directory.resolve(PhoneMicChecks.FILE_NAME);
            Path user = directory.resolve("not-owned.txt"); Files.write(user, new byte[]{3});
            assertThrows(IOException.class, () -> PhoneMicChecks.cleanupFixtureDirectory(next));
            assertArrayEquals(new byte[]{3}, Files.readAllBytes(user)); Files.delete(user);
            Files.createDirectory(pcm);
            Path nested = pcm.resolve("retained.txt"); Files.write(nested, new byte[]{4});
            assertThrows(IOException.class, () -> PhoneMicChecks.cleanupFixtureDirectory(next));
            assertArrayEquals(new byte[]{4}, Files.readAllBytes(nested));
            Files.delete(nested); Files.delete(pcm);
            Files.createSymbolicLink(pcm, Path.of("not-owned.txt"));
            assertThrows(IOException.class, () -> PhoneMicChecks.cleanupFixtureDirectory(next));
            assertTrue(Files.isSymbolicLink(pcm)); Files.delete(pcm);
        } finally {
            Files.deleteIfExists(pcm); Files.deleteIfExists(temporary); Files.deleteIfExists(directory);
            Files.delete(root.resolve("test-fixtures")); Files.delete(root);
        }
    }
    @Test public void normalProductionSaveCannotMigrateAnyExistingLegacyHistory() throws Exception {
        Path encrypted = Files.createTempDirectory("phone-mic-legacy-");
        try {
            PhoneMicChecks.requireNoLegacyHistory(encrypted.toFile());
            for (String name : new String[]{"history", "history.bak", "history.new"}) {
                Path legacy = encrypted.resolve(name);
                try {
                    Files.write(legacy, new byte[]{7});
                    assertThrows(IOException.class, () -> PhoneMicChecks.requireNoLegacyHistory(encrypted.toFile()));
                    assertArrayEquals(new byte[]{7}, Files.readAllBytes(legacy));
                } finally { Files.deleteIfExists(legacy); }
            }
            Path legacy = encrypted.resolve("history");
            try {
                Files.createSymbolicLink(legacy, Path.of("missing-target"));
                assertThrows(IOException.class, () -> PhoneMicChecks.requireNoLegacyHistory(encrypted.toFile()));
                assertTrue(Files.isSymbolicLink(legacy));
            } finally { Files.deleteIfExists(legacy); }
        } finally { Files.delete(encrypted); }
    }
}
