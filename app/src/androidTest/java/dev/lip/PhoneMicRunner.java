package dev.lip;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;
import dev.lip.uiprotocol.PhoneMicChecks;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** One public speaker-to-mic replay through untouched production Main; the parent scores raw text. */
public final class PhoneMicRunner extends Instrumentation {
    private static final String APP = "dev.lip.android";
    private static final String PCM = PhoneMicChecks.FILE_NAME;
    private static final int RATE = 16_000;
    private final JSONObject report = new JSONObject();
    private final Set<Thread> ownedThreads = new HashSet<>();
    private long startedNs, deadlineMs, ownedOperation, startWallMs, finalNs;
    private Object controller, store, capture, source, ownedSession;
    private boolean startAttempted;
    private ExecutorService worker;
    private Activity home;
    private EditText editor;
    private UiAutomation automation;
    private int windowId = -1;
    private String stage = "preflight";

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        startedNs = SystemClock.elapsedRealtimeNanos();
        deadlineMs = SystemClock.elapsedRealtime() + 165_000;
        PhoneMicChecks.FixtureDirectory directory = null;
        AudioTrack track = null;
        JSONObject settingsBefore = null;
        boolean observed = false;
        try {
            report.put("status", "FAIL").put("scope", "speaker-to-mic replay; owned Main editor only")
                    .put("parent_scoring_required", true).put("scorer_version", 2).put("max_error_rate", 0.05)
                    .put("fixture", "fleurs-en-013").put("fixture_sha256", PhoneMicChecks.SHA256)
                    .put("fixture_bytes", PhoneMicChecks.BYTES).put("fixture_frames", PhoneMicChecks.FRAMES)
                    .put("sample_rate", RATE).put("started_monotonic_ns", startedNs)
                    .put("work_budget_ms", 165_000).put("cleanup_budget_ms", 15_000)
                    .put("functional_parity_proven", false).put("cancellation_cohort_proven", false);
            require(APP.equals(getTargetContext().getPackageName()), "WrongTargetPackage");
            require(getTargetContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED, "MicrophonePermissionMissing");
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            Class<?> dictation = Class.forName("dev.lip.Dictation", true, getTargetContext().getClassLoader());
            Object companion = dictation.getField("Companion").get(null);
            controller = companion.getClass().getMethod("get", Context.class).invoke(companion, getTargetContext());
            require(!(boolean) invoke(controller, "getBusy"), "AppBusy");
            require(captureThreads().isEmpty(), "CaptureAlreadyActive");
            store = invoke(controller, "getStore");
            settingsBefore = settings();
            report.put("settings_before", settingsBefore);
            require("en-US".equals(invoke(store, "getLanguage")), "EnglishLanguageRequired");
            require(((SharedPreferences) invoke(store, "getSettings")).contains("language"), "ExplicitEnglishSettingRequired");
            if ((boolean) invoke(store, "getHistoryEnabled")) {
                stage = "legacy_history_metadata_gate";
                File encrypted = new File(getTargetContext().getNoBackupFilesDir(), "encrypted");
                // Normal production save migrates/deletes a legacy aggregate; this canary has no such authority.
                try { PhoneMicChecks.requireNoLegacyHistory(encrypted); }
                catch (java.io.IOException legacy) { throw new Rejected("LegacyHistoryMigrationNeedsOwnerReview"); }
                report.put("legacy_history_absent", true);
            }
            stage = "verified_existing_model";
            Object model = invoke(controller, "getSpeechModel");
            File verified = (File) invoke(model, "verifiedFile");
            require(verified != null, "ExistingVerifiedModelRequired");
            report.put("model_verified", true).put("model_name", verified.getName()).put("model_bytes", verified.length())
                    .put("model_sha256", model.getClass().getField("SHA256").get(null));
            budget();

            stage = "public_fixture_staging";
            directory = PhoneMicChecks.createFixtureDirectory(getTargetContext().getNoBackupFilesDir());
            File owned = directory.directory;
            sendStatus(1, marker("PHONE_MIC_READY", new JSONObject().put("target_package", APP)
                    .put("session", owned.getName()).put("directory", owned.getAbsolutePath())
                    .put("fixture_name", PCM).put("fixture_sha256", PhoneMicChecks.SHA256)
                    .put("fixture_bytes", PhoneMicChecks.BYTES).put("wait_ms", 30_000)));
            long fixtureDeadline = boundedDeadline(30_000);
            while (!directory.fixtureExists() && SystemClock.elapsedRealtime() < fixtureDeadline) SystemClock.sleep(100);
            byte[] pcm = directory.readFixture();
            report.put("fixture_admitted", true);

            Object secure = field(store, "secure");
            Set<String> historyBefore = historyNames(secure);
            stage = "owned_main_editor";
            home = startActivitySync(new Intent().setClassName(APP, "dev.lip.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            long focusDeadline = boundedDeadline(3_000);
            while (SystemClock.elapsedRealtime() < focusDeadline) {
                boolean[] focused = {false};
                runOnMainSync(() -> focused[0] = home.hasWindowFocus());
                if (focused[0]) break;
                SystemClock.sleep(50);
            }
            main(() -> {
                require(home.hasWindowFocus() && !home.isDestroyed(), "OwnedMainNotFocused");
                editor = (EditText) field(home, "testEditor");
                require(editor != null && "Dictation test editor".contentEquals(editor.getContentDescription())
                        && editor.getText().length() == 0, "OwnedEmptyEditorRequired");
            });
            AccessibilityNodeInfo root = appRoot();
            try { windowId = root.getWindowId(); } finally { root.recycle(); }
            act("Dictation test editor", true, AccessibilityNodeInfo.ACTION_FOCUS);
            main(() -> require(editor.isFocused(), "EditorNotFocused"));
            stage = "actual_start_control";
            startWallMs = System.currentTimeMillis();
            startAttempted = true;
            act("Try dictation", false, AccessibilityNodeInfo.ACTION_CLICK);
            main(() -> {
                ownedOperation = (long) field(controller, "operation");
                ownedSession = field(controller, "capture");
                rememberCapture();
                require(ownedOperation > 0 && (boolean) invoke(controller, "getBusy"), "StartNotAccepted");
                report.put("dictation_start_monotonic_ns", SystemClock.elapsedRealtimeNanos())
                        .put("owned_operation", ownedOperation);
            });
            stage = "production_recorder_ready";
            long readyDeadline = boundedDeadline(60_000);
            boolean ready = false;
            while (SystemClock.elapsedRealtime() < readyDeadline) {
                ready = observe(true);
                if (ready) break;
                SystemClock.sleep(50);
            }
            require(ready, "RecorderNotReady");
            main(() -> { owned(); ownedThreads.addAll(captureThreads()); });
            report.put("recorder_ready_monotonic_ns", SystemClock.elapsedRealtimeNanos());
            long acceptedBefore = acceptedBytes();

            stage = "owned_speaker_playback";
            AudioManager audio = (AudioManager) getTargetContext().getSystemService(Context.AUDIO_SERVICE);
            int volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
            report.put("media_volume", volume).put("media_muted", muted);
            require(volume > 0 && !muted, "SpeakerMuted");
            AudioDeviceInfo speaker = null;
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { speaker = device; break; }
            }
            require(speaker != null, "BuiltinSpeakerUnavailable");
            int bufferBytes = Math.max(6_400, AudioTrack.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT));
            track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferBytes).build();
            require(track.getState() == AudioTrack.STATE_INITIALIZED, "TrackNotInitialized");
            require(track.getSampleRate() == RATE && track.getChannelCount() == 1
                    && track.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT, "TrackFormatMismatch");
            report.put("track_sample_rate", track.getSampleRate()).put("track_channels", track.getChannelCount())
                    .put("track_encoding", track.getAudioFormat());
            boolean preferred = track.setPreferredDevice(speaker);
            report.put("speaker_preference_accepted", preferred).put("preferred_device_id", speaker.getId());
            require(preferred, "SpeakerPreferenceRejected");
            int written = 0;
            boolean playing = false, routed = false;
            long playbackDeadline = boundedDeadline(20_000);
            while (SystemClock.elapsedRealtime() < playbackDeadline) {
                if (written < pcm.length) {
                    int count = track.write(pcm, written, Math.min(6_400, pcm.length - written), AudioTrack.WRITE_NON_BLOCKING);
                    require(count >= 0 && count % 2 == 0, "TrackWriteFailed");
                    written += count;
                }
                if (!playing && written > 0) {
                    track.play(); playing = true;
                    report.put("playback_start_monotonic_ns", SystemClock.elapsedRealtimeNanos());
                }
                AudioDeviceInfo actual = track.getRoutedDevice();
                if (actual != null) {
                    report.put("actual_output_device_id", actual.getId()).put("actual_output_device_type", actual.getType());
                    require(actual.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "NotRoutedToBuiltinSpeaker");
                    routed = true;
                }
                long frames = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
                report.put("audio_written_bytes", written).put("audio_played_frames", frames);
                require(observe(true), "RecorderStoppedDuringPlayback");
                if (written == pcm.length && frames == PhoneMicChecks.FRAMES) break;
                require(frames <= PhoneMicChecks.FRAMES, "UnexpectedPlaybackPosition");
                SystemClock.sleep(20);
            }
            long playedNs = SystemClock.elapsedRealtimeNanos();
            long playedFrames = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
            report.put("playback_complete_monotonic_ns", playedNs).put("actual_speaker_route_observed", routed)
                    .put("audio_underruns_at_completion", track.getUnderrunCount());
            require(written == PhoneMicChecks.BYTES && playedFrames == PhoneMicChecks.FRAMES && routed, "PlaybackIncompleteOrUnverifiedRoute");
            stage = "unchanged_two_second_tail";
            while (SystemClock.elapsedRealtimeNanos() - playedNs < 2_000_000_000L) {
                budget(); require(observe(true), "RecorderStoppedDuringTail"); SystemClock.sleep(50);
            }
            long tailNs = SystemClock.elapsedRealtimeNanos() - playedNs;
            require(PhoneMicChecks.playbackComplete(written, playedFrames, tailNs), "PlaybackOrTailIncomplete");
            AudioDeviceInfo finalRoute = track.getRoutedDevice();
            require(finalRoute != null && finalRoute.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "SpeakerRouteLostDuringTail");
            require(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == volume
                    && !audio.isStreamMute(AudioManager.STREAM_MUSIC), "MediaVolumeChangedDuringPlayback");
            long acceptedAfter = acceptedBytes();
            report.put("tail_ns", tailNs).put("accepted_bytes_before_playback", acceptedBefore)
                    .put("accepted_bytes_before_finish", acceptedAfter)
                    .put("capture_byte_identity_expected", false);
            require(acceptedAfter > acceptedBefore, "NoProductionPcmProgress");

            stage = "actual_finish_control";
            main(() -> { owned(); ownedThreads.addAll(captureThreads()); });
            long stopNs = SystemClock.elapsedRealtimeNanos();
            report.put("finish_request_monotonic_ns", stopNs).put("stop_clock", "conservative: before semantic Finish action");
            act("Finish dictation", false, AccessibilityNodeInfo.ACTION_CLICK);
            report.put("finish_control_dispatched_monotonic_ns", SystemClock.elapsedRealtimeNanos())
                    .put("partial_before_finish_observed", report.has("first_partial_observed_monotonic_ns"));
            long finishDeadline = Math.min(deadlineMs, stopNs / 1_000_000 + 15_000);
            while (finalNs == 0 && SystemClock.elapsedRealtime() < finishDeadline) {
                observe(false); SystemClock.sleep(50);
            }
            boolean withinObservation = finalNs != 0 && finalNs - stopNs < 15_000_000_000L;
            report.put("finish_15s_observation_passed", withinObservation);
            if (!withinObservation) {
                report.put("phase_at_15s", report.optString("phase"));
                // Late output is diagnostic only; the original deadline remains failed.
                long diagnosticDeadline = boundedDeadline(60_000);
                while (finalNs == 0 && SystemClock.elapsedRealtime() < diagnosticDeadline) {
                    observe(false); SystemClock.sleep(100);
                }
            }
            report.put("stop_to_final_ms", finalNs == 0 ? JSONObject.NULL : (finalNs - stopNs) / 1_000_000.0)
                    .put("latency_gate_passed", PhoneMicChecks.latencyPassed(stopNs, finalNs));
            require(finalNs != 0, "FinalNotObserved");
            main(() -> {
                owned();
                String phase = String.valueOf(invoke(controller, "getPhase"));
                require(phase.equals("READY") || phase.equals("IDLE"), "ProductionFinalError");
                require(!String.valueOf(invoke(controller, "getRaw")).trim().isEmpty(), "NoRawSpeech");
                require(((TextView) field(home, "preview")).getText().toString()
                        .equals(String.valueOf(invoke(controller, "getPreview"))), "RenderedPreviewMismatch");
                report.put("rendered_preview_matches", true);
            });
            stage = "owned_editor_insertion";
            boolean[] insert = {false};
            main(() -> insert[0] = "READY".equals(String.valueOf(invoke(controller, "getPhase")))
                    && (boolean) invoke(controller, "getCanInsert"));
            if (insert[0]) act("Insert", false, AccessibilityNodeInfo.ACTION_CLICK);
            long insertionDeadline = boundedDeadline(2_000);
            boolean[] busy = {true};
            while (busy[0] && SystemClock.elapsedRealtime() < insertionDeadline) {
                observe(false);
                main(() -> busy[0] = (boolean) invoke(controller, "getBusy"));
                if (busy[0]) SystemClock.sleep(50);
            }
            main(() -> {
                owned();
                String output = String.valueOf(invoke(controller, "getText"));
                String inserted = editor.getText().toString();
                report.put("output", output).put("editor", inserted).put("editor_equals_output", inserted.equals(output));
                require(!output.isEmpty() && inserted.equals(output), "OwnedEditorInsertionMismatch");
            });
            stage = "new_encrypted_canary_record";
            observeHistory(secure, historyBefore);
            require(withinObservation && PhoneMicChecks.latencyPassed(stopNs, finalNs), "OriginalLatencyGateFailed");
            observed = true;
        } catch (Exception | LinkageError failure) {
            put("error_stage", stage); put("error_class", failure.getClass().getSimpleName());
            if (failure instanceof Rejected) put("error_code", failure.getMessage());
            try { main(() -> {
                rememberCapture();
                if (controller == null || ownedSession == null || field(controller, "capture") != ownedSession) return;
                String phase = String.valueOf(invoke(controller, "getPhase"));
                if ((long) field(controller, "operation") == ownedOperation + 1 && (phase.equals("READY") || phase.equals("ERROR")))
                    report.put("phase", phase).put("raw", invoke(controller, "getRaw"))
                            .put("output", invoke(controller, "getText")).put("message", invoke(controller, "getMessage"));
            }); } catch (Exception | LinkageError diagnostic) { put("diagnostic_error_class", diagnostic.getClass().getSimpleName()); }
        } finally {
            boolean cleanup = true;
            if (track != null) {
                try { track.stop(); } catch (Exception failure) { cleanup = false; put("track_stop_error", failure.getClass().getSimpleName()); }
                try { track.release(); } catch (Exception failure) { cleanup = false; put("track_release_error", failure.getClass().getSimpleName()); }
            }
            long cleanupDeadline = SystemClock.elapsedRealtime() + 15_000;
            try {
                CountDownLatch lifetimeReturned = new CountDownLatch(ownedOperation == 0 ? 0 : 1);
                main(() -> {
                    if (controller == null) return;
                    rememberCapture();
                    if (ownedOperation == 0) {
                        require(!startAttempted || !(boolean) invoke(controller, "getBusy"), "StartOwnershipUnresolved");
                        return;
                    }
                    long current = (long) field(controller, "operation");
                    boolean busy = (boolean) invoke(controller, "getBusy");
                    boolean cancel = PhoneMicChecks.mayCancel(ownedOperation, current, busy)
                            && ownedSession != null && field(controller, "capture") == ownedSession
                            && !"INSERTING".equals(String.valueOf(invoke(controller, "getPhase")));
                    report.put("cleanup_current_operation", current).put("cleanup_cancelled_owned_operation", cancel);
                    if (current == ownedOperation && ownedSession != null && field(controller, "capture") == ownedSession)
                        ownedThreads.addAll(captureThreads());
                    if (cancel) invoke(controller, "cancel");
                });
                if (ownedOperation != 0) {
                    Class<?> work = Class.forName("dev.lip.Work", true, getTargetContext().getClassLoader());
                    ExecutorService lifetime = (ExecutorService) invoke(work.getField("INSTANCE").get(null), "getSpeech");
                    lifetime.execute(lifetimeReturned::countDown);
                }
                while ((!quiescent() || lifetimeReturned.getCount() != 0)
                        && SystemClock.elapsedRealtime() < cleanupDeadline) SystemClock.sleep(50);
                boolean quiet = quiescent() && lifetimeReturned.getCount() == 0;
                boolean lifecycleObserved = capture != null && worker != null;
                report.put("owned_capture_quiescent", quiet).put("owned_java_capture_threads", ownedThreads.size())
                        .put("owned_capture_lifecycle_observed", lifecycleObserved)
                        .put("cleanup_capture_evidence", PhoneMicChecks.captureCleanupVerified(startAttempted, lifecycleObserved)
                                ? "OBSERVED_OR_NOT_STARTED" : "UNVERIFIED_STARTED_CAPTURE_LIFECYCLE")
                        .put("owned_inference_executor_terminated", worker == null ? JSONObject.NULL : worker.isTerminated())
                        .put("owned_model_lifetime_queue_returned", lifetimeReturned.getCount() == 0);
                main(() -> {
                    if (controller != null && ownedOperation != 0 && (long) field(controller, "operation") == ownedOperation)
                        require(!(boolean) invoke(controller, "getBusy"), "OwnedControllerStillBusy");
                });
                cleanup &= quiet;
                if (settingsBefore != null) {
                    JSONObject after = settings();
                    report.put("settings_after", after).put("settings_unchanged", settingsBefore.toString().equals(after.toString()));
                    cleanup &= settingsBefore.toString().equals(after.toString());
                }
                if (quiet) main(() -> {
                    boolean active = controller != null && (boolean) invoke(controller, "getBusy");
                    report.put("owned_main_finish_skipped_active_operation", active);
                    if (home != null && !home.isDestroyed() && !active) home.finish();
                });
            } catch (Exception | LinkageError failure) {
                cleanup = false; put("cleanup_error_class", failure.getClass().getSimpleName());
                if (failure instanceof Rejected) put("cleanup_error_code", failure.getMessage());
            }
            if (directory != null) {
                try {
                    PhoneMicChecks.cleanupFixtureDirectory(directory);
                } catch (Exception failure) { cleanup = false; put("fixture_cleanup_error", failure.getClass().getSimpleName()); }
            }
            put("cleanup_ok", cleanup); put("warm_engine_may_remain", true);
            put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000);
            boolean history = report.optBoolean("history_gate_passed");
            put("transport_editor_latency_checks_passed", observed && cleanup);
            put("runner_checks_passed", observed && cleanup && history);
            put("status", observed && cleanup ? (history ? "OBSERVED_PENDING_PARENT_SCORE" : "SKIP_HISTORY_GATE_PENDING_PARENT_SCORE") : "FAIL");
        }
        finish(report.optBoolean("runner_checks_passed") ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
                marker("PHONE_MIC_RESULT", report));
    }

    private boolean observe(boolean listening) throws Exception {
        boolean[] ready = {false};
        main(() -> {
            rememberCapture();
            ownedHome();
            owned();
            String phase = String.valueOf(invoke(controller, "getPhase"));
            long now = SystemClock.elapsedRealtimeNanos();
            String raw = String.valueOf(invoke(controller, "getRaw"));
            report.put("phase", phase).put("raw", raw).put("message", invoke(controller, "getMessage"))
                    .put("cleanup_message", field(controller, "cleanedStatus"))
                    .put("output", invoke(controller, "getText")).put("preview", invoke(controller, "getPreview"));
            if (listening && !raw.isEmpty() && !report.has("first_partial_observed_monotonic_ns"))
                report.put("first_partial_observed_monotonic_ns", now).put("partial_clock", "sampled controller raw, not callback timestamp");
            if (!listening && !(boolean) invoke(controller, "getBusy") && finalNs == 0) {
                finalNs = now; report.put("final_observed_monotonic_ns", now);
            }
            if (listening) require(phase.equals("LISTENING"), "NoLongerListening");
            if (capture == null) return;
            Object captureState = field(capture, "state");
            long accepted;
            synchronized (captureState) { source = field(capture, "source"); accepted = (long) field(capture, "acceptedBytes"); }
            worker = (ExecutorService) field(capture, "worker");
            report.put("accepted_pcm_bytes", accepted);
            if (source == null || !listening) return;
            AudioRecord microphone = (AudioRecord) field(source, "microphone");
            require(microphone.getSampleRate() == RATE && microphone.getChannelCount() == 1
                    && microphone.getAudioFormat() == AudioFormat.ENCODING_PCM_16BIT, "ProductionRecorderFormatMismatch");
            AudioRecordingConfiguration config = microphone.getActiveRecordingConfiguration();
            boolean recording = microphone.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
            report.put("microphone_recording", recording).put("client_silenced", config == null ? JSONObject.NULL : config.isClientSilenced());
            AudioDeviceInfo input = microphone.getRoutedDevice();
            if (input != null) report.put("input_device_type", input.getType()).put("input_device_id", input.getId());
            ready[0] = accepted >= 3_200 && recording && config != null && !config.isClientSilenced();
        });
        budget(); return ready[0];
    }

    private void observeHistory(Object secure, Set<String> before) throws Exception {
        report.put("history_gate_passed", false);
        if (!(boolean) invoke(store, "getHistoryEnabled")) { report.put("history_status", "SKIP_RETENTION_DISABLED"); return; }
        Set<String> added;
        long until = boundedDeadline(5_000);
        do {
            added = historyNames(secure); added.removeAll(before);
            if (!added.isEmpty()) break;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
        report.put("new_history_entries", added.size());
        require(!added.isEmpty(), "NewHistoryRecordMissing");
        if (added.size() != 1) { report.put("history_status", "SKIP_AMBIGUOUS_NEW_RECORDS_NO_DECRYPT"); return; }
        String name = added.iterator().next();
        require(name.matches("history-[0-9a-f]{16}-[A-Za-z0-9_-]{43}"), "NewHistoryNameInvalid");
        long time = Long.parseUnsignedLong(name.substring(8, 24), 16) ^ Long.MIN_VALUE;
        if (time < startWallMs || time > System.currentTimeMillis()) {
            report.put("history_status", "SKIP_RECORD_OUTSIDE_OWNED_TIME_NO_DECRYPT"); return;
        }
        main(this::owned);
        String encoded = (String) secure.getClass().getMethod("read", String.class).invoke(secure, name);
        org.json.JSONArray rows = new org.json.JSONArray(encoded);
        require(rows.length() == 1, "CanaryHistoryShape");
        JSONObject entry = rows.getJSONObject(0);
        require(entry.getString("raw").equals(report.getString("raw"))
                && entry.getString("clean").equals(report.getString("output"))
                && entry.getString("language").equals("en-US") && entry.getLong("time") == time, "CanaryHistoryMismatch");
        report.put("history_status", "NEW_ENCRYPTED_CANARY_MATCHES").put("history_record", name)
                .put("history_used_chatgpt", entry.getBoolean("chatgpt")).put("history_gate_passed", true);
    }

    @SuppressWarnings("unchecked") private static Set<String> historyNames(Object secure) throws Exception {
        return new HashSet<>((List<String>) secure.getClass().getMethod("names", String.class).invoke(secure, "history-"));
    }
    private long acceptedBytes() throws Exception {
        synchronized (field(capture, "state")) { return (long) field(capture, "acceptedBytes"); }
    }
    private void rememberCapture() throws Exception {
        if (controller != null && ownedOperation > 0 && ownedSession != null
                && (long) field(controller, "operation") == ownedOperation && field(controller, "capture") == ownedSession) {
            Object current = field(controller, "localCapture");
            if (current != null) {
                require(capture == null || current == capture, "CaptureReplaced");
                if (capture == null) ownedThreads.addAll(captureThreads());
                capture = current;
            }
        }
        if (capture != null) {
            synchronized (field(capture, "state")) { source = field(capture, "source"); }
            worker = (ExecutorService) field(capture, "worker");
        }
    }
    private boolean quiescent() throws Exception {
        for (Thread thread : ownedThreads) if (thread.isAlive()) return false;
        if (worker != null && !worker.isTerminated()) return false;
        if (source != null) {
            boolean recording = ((AudioRecord) field(source, "microphone")).getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
            report.put("owned_recorder_recording_after_cleanup", recording);
            if (recording || ((AtomicBoolean) field(source, "running")).get()) return false;
        }
        return PhoneMicChecks.captureCleanupVerified(startAttempted, capture != null && worker != null);
    }
    private static Set<Thread> captureThreads() {
        Set<Thread> threads = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && (thread.getName().equals("lip-local-pcm") || thread.getName().equals("lip-local-reader")
                    || thread.getName().equals("lip-local-inference"))) threads.add(thread);
        }
        return threads;
    }
    private JSONObject settings() throws Exception {
        JSONObject result = new JSONObject();
        for (String key : new String[]{"Language", "Style", "CloudConsent", "LiveCleanup", "AutoInsert", "HistoryEnabled"})
            result.put(key, invoke(store, "get" + key));
        return result;
    }

    private void act(String value, boolean description, int action) throws Exception {
        for (int scroll = 0; scroll <= 12; scroll++) {
            budget();
            main(this::ownedHome);
            AccessibilityNodeInfo root = appRoot();
            ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>(); nodes.add(root);
            try {
                require(root.getWindowId() == windowId, "OwnedWindowChanged");
                AccessibilityNodeInfo match = null, scroller = null;
                for (int index = 0; index < nodes.size(); index++) {
                    AccessibilityNodeInfo node = nodes.get(index);
                    require(nodes.size() <= 1024, "OwnedTreeLimit");
                    if (!APP.equals(String.valueOf(node.getPackageName())) || node.isPassword()) continue;
                    boolean eligible = description ? node.isEditable() && "android.widget.EditText".contentEquals(node.getClassName())
                            : node.isClickable() && "android.widget.Button".contentEquals(node.getClassName());
                    if (eligible && node.isVisibleToUser()
                            && value.equals(String.valueOf(description ? node.getContentDescription() : node.getText()))) {
                        require(match == null, "AmbiguousOwnedControl"); match = node;
                    }
                    if (node.isVisibleToUser() && node.isScrollable()
                            && "android.widget.ScrollView".contentEquals(node.getClassName())) {
                        require(scroller == null, "AmbiguousOwnedScrollView"); scroller = node;
                    }
                    require(node.getChildCount() <= 1024 - nodes.size(), "OwnedTreeLimit");
                    for (int child = 0; child < node.getChildCount(); child++) {
                        AccessibilityNodeInfo next = node.getChild(child); if (next != null) nodes.add(next);
                    }
                }
                if (match != null) {
                    require(match.refresh() && match.isEnabled() && !match.isPassword() && APP.equals(String.valueOf(match.getPackageName()))
                            && value.equals(String.valueOf(description ? match.getContentDescription() : match.getText())), "OwnedControlChanged");
                    require(description ? match.isEditable() && "android.widget.EditText".contentEquals(match.getClassName())
                            : match.isClickable() && "android.widget.Button".contentEquals(match.getClassName()), "OwnedControlTypeChanged");
                    AccessibilityNodeInfo current = appRoot();
                    try { require(current.getWindowId() == windowId, "OwnedWindowChanged"); } finally { current.recycle(); }
                    main(this::ownedHome);
                    require(match.performAction(action), "OwnedControlActionFailed"); return;
                }
                require(scroll < 12 && scroller != null
                        && scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD), "OwnedControlUnavailable");
            } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
            SystemClock.sleep(100);
        }
        throw new Rejected("OwnedControlUnavailable");
    }
    private AccessibilityNodeInfo appRoot() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        require(root != null, "RootUnavailable");
        if (!APP.equals(String.valueOf(root.getPackageName()))) { root.recycle(); throw new Rejected("AppNotForeground"); }
        return root;
    }
    private void owned() throws Exception {
        require(ownedOperation > 0 && (long) field(controller, "operation") == ownedOperation
                && ownedSession != null && field(controller, "capture") == ownedSession, "OwnedOperationChanged");
    }
    private void ownedHome() throws Exception {
        require(home != null && home.hasWindowFocus() && "Home".equals(field(home, "tab"))
                && field(home, "testEditor") == editor && editor.isAttachedToWindow(), "OwnedHomeChanged");
        if (ownedOperation != 0) owned();
    }
    private long boundedDeadline(long durationMs) { budget(); return Math.min(deadlineMs, SystemClock.elapsedRealtime() + durationMs); }
    private void budget() { require(SystemClock.elapsedRealtime() < deadlineMs, "WorkDeadline"); }
    private static Object invoke(Object owner, String method) throws Exception { return owner.getClass().getMethod(method).invoke(owner); }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private void main(Checked action) throws Exception {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Exception | LinkageError caught) { failure[0] = caught; } });
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
        if (failure[0] != null) throw (LinkageError) failure[0];
    }
    private void put(String name, Object value) { try { report.put(name, value); } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); } }
    private static Bundle marker(String name, JSONObject value) {
        Bundle result = new Bundle(); result.putString(REPORT_KEY_STREAMRESULT, "\n" + name + " " + value + "\n"); return result;
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new Rejected(reason); }
    private interface Checked { void run() throws Exception; }
    private static final class Rejected extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        Rejected(String reason) { super(reason); }
    }
}
