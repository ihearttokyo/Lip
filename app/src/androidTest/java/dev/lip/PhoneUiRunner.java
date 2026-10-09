package dev.lip;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.lip.uiprotocol.PhoneUiProtocol;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

/** Own-test-UID semantic bridge; the parent owns consent and stops on OEM denial. */
public final class PhoneUiRunner extends Instrumentation {
    private static final String TEST_PACKAGE = "dev.lip.android.test";
    private static final long LIFETIME_MS = 20 * 60 * 1000L;
    private static final int MAX_NODES = 1024;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        File directory = null;
        String status = "timeout";
        String errorClass = "";
        JSONObject lastResponse = null;
        try {
            check(TEST_PACKAGE.equals(getContext().getPackageName())
                    && TEST_PACKAGE.equals(getTargetContext().getPackageName()));
            UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            String session = "phone-ui-" + UUID.randomUUID();
            File mailbox = new File(getTargetContext().getNoBackupFilesDir(), session);
            check(mailbox.mkdir());
            directory = mailbox;
            File request = new File(mailbox, "request.json");
            File processing = new File(mailbox, "processing.json");
            long deadline = SystemClock.elapsedRealtime() + LIFETIME_MS;
            long nextId = 1;
            sendStatus(1, report("PHONE_UI_READY", new JSONObject()
                    .put("session", session).put("directory", mailbox.getAbsolutePath())
                    .put("next_id", nextId).put("lifetime_ms", LIFETIME_MS)
                    .put("max_request_bytes", PhoneUiProtocol.MAX_REQUEST_BYTES)));
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!request.exists()) { SystemClock.sleep(100); continue; }
                check(request.renameTo(processing));
                long id = 0;
                String action = "invalid";
                int count = 0;
                String responseStatus = "ok";
                String responseError = "";
                boolean stop = false;
                try {
                    PhoneUiProtocol.Command command = PhoneUiProtocol.parse(readRequest(processing), nextId);
                    id = command.id; action = command.action;
                    nextId++;
                    if (action.equals("stop")) { stop = true; status = "stopped"; }
                    else count = act(automation, command, deadline);
                } catch (PhoneUiProtocol.Rejected rejected) {
                    id = rejected.id; action = rejected.action;
                    responseStatus = "rejected"; responseError = "ProtocolRejected";
                } catch (SelectionRejected rejected) {
                    count = rejected.count;
                    responseStatus = "rejected"; responseError = rejected.reason;
                } catch (ActionFailed failed) {
                    count = 1;
                    responseStatus = "rejected"; responseError = "ActionFailed";
                    stop = true; status = "rejected"; errorClass = responseError;
                } catch (Exception failed) {
                    responseStatus = "rejected"; responseError = failed.getClass().getSimpleName();
                    stop = true; status = "rejected"; errorClass = responseError;
                } finally {
                    check(processing.delete());
                }
                JSONObject response = new JSONObject().put("id", id).put("action", action).put("count", count)
                        .put("status", responseStatus).put("error_class", responseError).put("next_id", nextId);
                lastResponse = response;
                publish(mailbox, response);
                if (stop) break;
            }
        } catch (Exception failed) {
            status = "rejected"; errorClass = failed.getClass().getSimpleName();
        } finally {
            if (directory != null) {
                // Delete only this session's fixed private mailbox files, never other app data.
                for (String name : new String[]{"request.tmp", "request.json", "processing.json", "response.tmp", "response.json"}) {
                    File file = new File(directory, name);
                    if (file.exists() && !file.delete()) { status = "rejected"; errorClass = "CleanupFailed"; }
                }
                if (!directory.delete()) { status = "rejected"; errorClass = "CleanupFailed"; }
            }
        }
        Bundle result;
        try {
            result = report("PHONE_UI_RESULT", new JSONObject()
                    .put("status", status).put("error_class", errorClass).put("last_response", lastResponse));
        } catch (JSONException failed) {
            status = "rejected";
            result = new Bundle();
            result.putString(REPORT_KEY_STREAMRESULT,
                    "\nPHONE_UI_RESULT {\"status\":\"rejected\",\"error_class\":\"JSONException\"}\n");
        }
        finish(status.equals("rejected") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }

    private static Bundle report(String marker, JSONObject value) {
        Bundle result = new Bundle();
        result.putString(REPORT_KEY_STREAMRESULT, "\n" + marker + " " + value + "\n");
        return result;
    }

    private static byte[] readRequest(File file) throws IOException {
        byte[] bytes = new byte[PhoneUiProtocol.MAX_REQUEST_BYTES + 1];
        int length = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (length < bytes.length) {
                int read = input.read(bytes, length, bytes.length - length);
                if (read < 0) break;
                length += read;
            }
        }
        return Arrays.copyOf(bytes, length);
    }

    private static void publish(File directory, JSONObject response) throws IOException {
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        check(bytes.length <= 4096);
        File temporary = new File(directory, "response.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes); output.getFD().sync();
        }
        check(temporary.renameTo(new File(directory, "response.json")));
    }

    private static int act(UiAutomation automation, PhoneUiProtocol.Command command, long deadline) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) throw new SelectionRejected("RootUnavailable", 0);
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        nodes.add(root);
        try {
            if (!command.packageName.equals(string(root.getPackageName()))) throw new SelectionRejected("PackageMismatch", 0);
            ArrayList<AccessibilityNodeInfo> candidates = new ArrayList<>();
            int index = 0;
            while (index < nodes.size()) {
                check(SystemClock.elapsedRealtime() < deadline);
                AccessibilityNodeInfo node = nodes.get(index++);
                if (node.isPassword() || !command.packageName.equals(string(node.getPackageName()))) continue;
                if (node.isVisibleToUser() && matches(node, command)) candidates.add(node);
                int childCount = node.getChildCount();
                if (childCount > MAX_NODES - nodes.size()) throw new SelectionRejected("TreeLimit", candidates.size());
                for (int child = 0; child < childCount; child++) {
                    AccessibilityNodeInfo next = node.getChild(child);
                    if (next != null) nodes.add(next);
                }
            }
            if (candidates.size() != 1) throw new SelectionRejected(
                    candidates.isEmpty() ? "NodeUnavailable" : "AmbiguousSelector", candidates.size());
            AccessibilityNodeInfo node = candidates.get(0);
            if (!node.refresh() || node.isPassword() || !node.isVisibleToUser() || !matches(node, command))
                throw new SelectionRejected("NodeChanged", 1);
            AccessibilityNodeInfo currentRoot = automation.getRootInActiveWindow();
            if (currentRoot == null) throw new SelectionRejected("RootUnavailable", 1);
            try {
                if (currentRoot.getWindowId() != root.getWindowId()) throw new SelectionRejected("WindowChanged", 1);
                PhoneUiProtocol.authorizeNode(command, string(currentRoot.getPackageName()),
                        string(node.getPackageName()), node.isPassword(), node.isEditable());
            } finally { currentRoot.recycle(); }
            if (command.action.equals("query")) return 1;
            int action;
            switch (command.action) {
                case "click": action = AccessibilityNodeInfo.ACTION_CLICK; break;
                case "scroll": action = command.direction.equals("forward") ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
                case "set_text": action = AccessibilityNodeInfo.ACTION_SET_TEXT; break;
                default: throw new IllegalStateException("UnexpectedAction");
            }
            boolean supported = false;
            for (AccessibilityNodeInfo.AccessibilityAction available : node.getActionList()) {
                if (available.getId() == action) { supported = true; break; }
            }
            if (!supported) throw new SelectionRejected("UnsupportedAction", 1);
            Bundle arguments = null;
            if (command.action.equals("set_text")) {
                arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command.text);
            }
            check(SystemClock.elapsedRealtime() < deadline);
            if (!node.performAction(action, arguments)) throw new ActionFailed();
            return 1;
        } finally { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
    }

    private static boolean matches(AccessibilityNodeInfo node, PhoneUiProtocol.Command command) {
        return command.value.equals(string(command.selector.equals("text") ? node.getText() : node.getContentDescription()));
    }

    private static String string(CharSequence value) { return value == null ? null : value.toString(); }
    private static void check(boolean condition) { if (!condition) throw new IllegalStateException(); }

    private static final class SelectionRejected extends IllegalStateException {
        final String reason;
        final int count;
        SelectionRejected(String reason, int count) { this.reason = reason; this.count = count; }
    }

    private static final class ActionFailed extends IllegalStateException { }
}
