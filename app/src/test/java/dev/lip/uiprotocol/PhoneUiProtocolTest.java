package dev.lip.uiprotocol;

import org.junit.Test;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PhoneUiProtocolTest {
    private static final String QUERY = "{\"id\":1,\"action\":\"query\",\"package\":\"dev.lip.android\","
            + "\"selector\":\"text\",\"value\":\"Try dictation\"}";
    private static final String EDIT = QUERY.replace("\"query\"", "\"set_text\"")
            .replace("}", ",\"text\":\"hello 東京\",\"authorization\":\"public_test_text\"}");
    private PhoneUiProtocol.Command parse(String json, long expected) {
        return PhoneUiProtocol.parse(json.getBytes(StandardCharsets.UTF_8), expected);
    }
    private void rejects(String json) {
        assertThrows(PhoneUiProtocol.Rejected.class, () -> parse(json, 1));
    }
    @Test public void validCommandsKeepExactValues() {
        PhoneUiProtocol.Command query = parse(QUERY, 1);
        assertEquals(1, query.id); assertEquals("query", query.action);
        assertEquals("dev.lip.android", query.packageName);
        assertEquals("text", query.selector); assertEquals("Try dictation", query.value);
        assertEquals("click", parse(QUERY.replace("query", "click"), 1).action);
        assertEquals("content_description", parse(QUERY.replace("\"text\"", "\"content_description\""), 1).selector);
        assertEquals("hello 東京", parse(EDIT, 1).text);
        assertEquals("public_domain", parse(EDIT.replace("public_test_text", "public_domain"), 1).authorization);
        assertEquals("", parse(EDIT.replace("hello 東京", ""), 1).text);
        assertEquals("forward", parse(QUERY.replace("query", "scroll")
                .replace("}", ",\"direction\":\"forward\"}"), 1).direction);
        assertEquals("backward", parse(QUERY.replace("query", "scroll")
                .replace("}", ",\"direction\":\"backward\"}"), 1).direction);
        assertEquals("stop", parse("{\"id\":2,\"action\":\"stop\"}", 2).action);
    }
    @Test public void unknownKeysAndWrongShapesReject() {
        rejects(QUERY.replace("}", ",\"shell\":\"input tap\"}"));
        rejects(QUERY.replace("\"value\":\"Try dictation\"", "\"value\":true"));
        rejects(QUERY.replace("\"value\":\"Try dictation\"", "\"value\":null"));
        rejects(QUERY.replace("\"value\":\"Try dictation\"", "\"value\":[]"));
        rejects(QUERY.replace("\"value\":\"Try dictation\"", "\"value\":{}"));
        rejects(QUERY.replace("\"value\":\"Try dictation\"", "\"value\":5"));
        rejects(QUERY.replace("\"value\":\"Try dictation\"", "\"value\":\"\""));
        rejects(QUERY.replace("\"selector\":\"text\",", ""));
        rejects(QUERY.replace("\"text\"", "\"contains\""));
        rejects(QUERY.replace("}", ",\"text\":\"unapproved\"}"));
        rejects("{\"id\":1,\"action\":\"stop\",\"package\":\"dev.lip.android\"}");
    }
    @Test public void idsAreExactPositiveOrderedIntegers() {
        for (String id : new String[]{"0", "-1", "1.0", "1e0", "\"1\"", "true", "null", "9223372036854775808"})
            rejects(QUERY.replace("\"id\":1", "\"id\":" + id));
        assertThrows(PhoneUiProtocol.Rejected.class, () -> parse(QUERY, 2));
        rejects(QUERY.replace("\"id\":1", "\"id\":2"));
        assertThrows(PhoneUiProtocol.Rejected.class, () -> parse(QUERY, 0));
    }
    @Test public void actionsAndPackagesStayInFixedScope() {
        for (String action : new String[]{"tap", "shell", "intent", "back", ""})
            rejects(QUERY.replace("query", action));
        for (String pkg : new String[]{"*", "com.whatsapp", "com.android.chrome", "com.google.android.permissioncontroller", "dev.lip.android.extra"})
            rejects(QUERY.replace("dev.lip.android", pkg));
        for (String pkg : new String[]{"dev.lip.android", "dev.lip.android.test", "com.android.settings", "com.miui.securitycenter", "org.blokada.sex"})
            assertEquals(pkg, parse(QUERY.replace("dev.lip.android", pkg), 1).packageName);
    }
    @Test public void editsNeedExplicitPublicAuthorizationAndNonPasswordEditor() {
        rejects(EDIT.replace(",\"authorization\":\"public_test_text\"", ""));
        rejects(EDIT.replace("\"public_test_text\"", "true"));
        rejects(EDIT.replace("public_test_text", "password"));
        rejects(EDIT.replace(",\"text\":\"hello 東京\"", ""));
        rejects(QUERY.replace("}", ",\"authorization\":\"public_test_text\"}"));
        PhoneUiProtocol.Command edit = parse(EDIT, 1);
        PhoneUiProtocol.authorizeNode(edit, "dev.lip.android", "dev.lip.android", false, true);
        assertThrows(PhoneUiProtocol.Rejected.class, () -> PhoneUiProtocol.authorizeNode(edit, "dev.lip.android", "dev.lip.android", true, true));
        assertThrows(PhoneUiProtocol.Rejected.class, () -> PhoneUiProtocol.authorizeNode(edit, "dev.lip.android", "dev.lip.android", false, false));
        assertThrows(PhoneUiProtocol.Rejected.class, () -> PhoneUiProtocol.authorizeNode(edit, "com.android.settings", "dev.lip.android", false, true));
        assertThrows(PhoneUiProtocol.Rejected.class, () -> PhoneUiProtocol.authorizeNode(edit, "dev.lip.android", "com.android.settings", false, true));
        PhoneUiProtocol.Command query = parse(QUERY, 1);
        assertThrows(PhoneUiProtocol.Rejected.class, () -> PhoneUiProtocol.authorizeNode(query, "dev.lip.android", "dev.lip.android", true, false));
    }
    @Test public void byteBoundsApplyBeforeParsingAndToUnicodeText() {
        assertEquals("a".repeat(1024), parse(EDIT.replace("hello 東京", "a".repeat(1024)), 1).text);
        rejects(EDIT.replace("hello 東京", "a".repeat(1025)));
        rejects(EDIT.replace("hello 東京", "東".repeat(342)));
        rejects(QUERY.replace("Try dictation", "a".repeat(257)));
        String padded = QUERY + " ".repeat(4096 - QUERY.getBytes(StandardCharsets.UTF_8).length);
        assertEquals("query", parse(padded, 1).action);
        rejects(padded + " ");
        assertThrows(PhoneUiProtocol.Rejected.class, () -> PhoneUiProtocol.parse(new byte[]{(byte)0xff}, 1));
    }
    @Test public void malformedDuplicateNestedAndTrailingInputReject() {
        for (String json : new String[]{"", "[]", QUERY + "{}", QUERY.replace("}", ",}"),
                QUERY.replace("\"id\":1", "\"id\":1,\"id\":1"), QUERY.replace("\"query\"", "query"),
                QUERY.replace("\"id\"", "'id'"), QUERY.replace("\"id\":1", "\"id\":01"),
                QUERY.replace("Try dictation", "raw\nnewline")}) rejects(json);
    }
    @Test public void jsonEscapesRoundTripWithoutCoercion() {
        for (String text : new String[]{"line\nnext\t\"quote\"\\slash/", "🙂東京", "\b\f\r", "\u0000"}) {
            JSONObject json = new JSONObject(EDIT).put("text", text);
            assertEquals(text, parse(json.toString(), 1).text);
        }
        assertEquals("東京", parse(EDIT.replace("hello 東京", "\\u6771\\u4eac"), 1).text);
        for (String text : new String[]{"\\uD800", "\\uDC00", "\\uZZZZ", "\\x", "\\u123"})
            rejects(EDIT.replace("hello 東京", text));
        assertThrows(PhoneUiProtocol.Rejected.class, () -> PhoneUiProtocol.parse(null, 1));
        rejects(QUERY.replace("\"value\":\"Try dictation\"", "\"value\":" + "[".repeat(1000)));
    }
    @Test public void rejectionsExposeOnlySafeCorrelationMetadata() {
        PhoneUiProtocol.Rejected stale = assertThrows(PhoneUiProtocol.Rejected.class, () -> parse(QUERY, 2));
        assertEquals(1, stale.id); assertEquals("query", stale.action);
        assertEquals("ProtocolRejected", stale.getMessage());
        PhoneUiProtocol.Rejected unknown = assertThrows(PhoneUiProtocol.Rejected.class,
                () -> parse(QUERY.replace("query", "secret-value"), 1));
        assertEquals("invalid", unknown.action);
        assertFalse(unknown.toString().contains("secret-value"));
    }
}
