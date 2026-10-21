package dev.lip.uiprotocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class PhoneUiProtocol {
    public static final int MAX_REQUEST_BYTES = 4096;
    public static final int MAX_TEXT_BYTES = 1024;
    public static final class Command {
        public final long id;
        public final String action, packageName, selector, value, text, direction, authorization;
        Command(long id, String action, String packageName, String selector, String value,
                String text, String direction, String authorization) {
            this.id = id; this.action = action; this.packageName = packageName;
            this.selector = selector; this.value = value; this.text = text;
            this.direction = direction; this.authorization = authorization;
        }
    }
    public static final class Rejected extends IllegalArgumentException {
        public final long id;
        public final String action;
        Rejected(long id, String action) { super("ProtocolRejected"); this.id = id; this.action = action; }
    }
    public static Command parse(byte[] bytes, long expectedId) {
        long id = 0;
        String action = "invalid";
        try {
            require(bytes != null && bytes.length > 0 && bytes.length <= MAX_REQUEST_BYTES);
            Map<String, Object> fields = new FlatJson(StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString()).object();
            require(fields.get("id") instanceof Long);
            id = (Long) fields.get("id");
            String suppliedAction = string(fields, "action", 32, false);
            require(Arrays.asList("query", "click", "scroll", "set_text", "stop").contains(suppliedAction));
            action = suppliedAction;
            require(expectedId > 0 && id > 0 && id == expectedId);
            if (action.equals("stop")) {
                require(fields.keySet().equals(Set.of("id", "action")));
                return new Command(id, action, null, null, null, null, null, null);
            }
            Set<String> keys = new HashSet<>(Arrays.asList("id", "action", "package", "selector", "value"));
            if (action.equals("scroll")) keys.add("direction");
            if (action.equals("set_text")) { keys.add("text"); keys.add("authorization"); }
            require(fields.keySet().equals(keys));
            String pkg = string(fields, "package", 128, false);
            require(Arrays.asList("dev.lip.android", "dev.lip.android.test", "com.android.settings",
                    "com.miui.securitycenter", "org.blokada.sex").contains(pkg));
            String selector = string(fields, "selector", 32, false);
            require(selector.equals("text") || selector.equals("content_description"));
            String value = string(fields, "value", 256, false);
            String text = null, direction = null, authorization = null;
            if (action.equals("scroll")) {
                direction = string(fields, "direction", 16, false);
                require(direction.equals("forward") || direction.equals("backward"));
            }
            if (action.equals("set_text")) {
                text = string(fields, "text", MAX_TEXT_BYTES, true);
                authorization = string(fields, "authorization", 32, false);
                require(authorization.equals("public_test_text") || authorization.equals("public_domain"));
            }
            return new Command(id, action, pkg, selector, value, text, direction, authorization);
        } catch (Exception rejected) {
            throw new Rejected(id, action);
        }
    }
    public static void authorizeNode(Command command, String activePackage, String nodePackage,
            boolean password, boolean editable) {
        if (command.packageName == null || !command.packageName.equals(activePackage)
                || !command.packageName.equals(nodePackage) || password
                || (command.action.equals("set_text") && !editable))
            throw new Rejected(command.id, command.action);
    }
    private static String string(Map<String, Object> fields, String key, int maxBytes, boolean allowEmpty) {
        Object value = fields.get(key);
        require(value instanceof String);
        String text = (String) value;
        require((allowEmpty || !text.isEmpty()) && text.getBytes(StandardCharsets.UTF_8).length <= maxBytes);
        return text;
    }
    private static void require(boolean condition) {
        if (!condition) throw new Rejected(0, "invalid");
    }
    // Flat scalar commands avoid recursive parsing and Android/host JSON coercion differences.
    private static final class FlatJson {
        final String input;
        int at;
        FlatJson(String input) { this.input = input; }
        char peek() { return at < input.length() ? input.charAt(at) : 0; }
        void whitespace() { while (peek() != 0 && " \t\r\n".indexOf(peek()) >= 0) at++; }
        void take(char expected) { whitespace(); require(peek() == expected); at++; }
        Map<String, Object> object() {
            Map<String, Object> fields = new HashMap<>();
            take('{'); whitespace();
            if (peek() != '}') while (true) {
                String key = quoted(); take(':'); whitespace();
                Object value = peek() == '"' ? quoted() : number();
                require(fields.size() < 8 && !fields.containsKey(key));
                fields.put(key, value); whitespace();
                if (peek() != ',') break;
                at++;
            }
            take('}'); whitespace(); require(at == input.length());
            return fields;
        }
        long number() {
            int start = at;
            while (peek() >= '0' && peek() <= '9') at++;
            require(at > start && (at == start + 1 || input.charAt(start) != '0'));
            return Long.parseLong(input.substring(start, at));
        }
        String quoted() {
            take('"');
            StringBuilder result = new StringBuilder();
            while (true) {
                char c = peek(); at++;
                require(c >= ' ');
                if (c == '"') break;
                if (c == '\\') {
                    c = peek(); at++;
                    switch (c) {
                        case '"': case '\\': case '/': break;
                        case 'b': c = '\b'; break;
                        case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break;
                        case 't': c = '\t'; break;
                        case 'u':
                            int value = 0;
                            for (int i = 0; i < 4; i++) {
                                char digit = peek(); at++;
                                require((digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f') || (digit >= 'A' && digit <= 'F'));
                                value = value * 16 + Character.digit(digit, 16);
                            }
                            c = (char) value; break;
                        default: throw new Rejected(0, "invalid");
                    }
                }
                result.append(c);
            }
            for (int i = 0; i < result.length(); i++) {
                char c = result.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    require(i + 1 < result.length() && Character.isLowSurrogate(result.charAt(++i)));
                } else require(!Character.isLowSurrogate(c));
            }
            return result.toString();
        }
    }
    private PhoneUiProtocol() { }
}
