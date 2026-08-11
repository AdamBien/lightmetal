package lm.tools.control;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import lm.tools.entity.ToolCall;

public interface ToolCallParser {

    String TOOL_CALLS_MARKER = "[TOOL_CALLS]";
    String ARGS_MARKER = "[ARGS]";

    sealed interface Parsed {}
    record Text(String text) implements Parsed {}
    record Calls(String leadingText, List<ToolCall> calls) implements Parsed {}

    static Parsed parse(String generated) {
        var text = generated == null ? "" : stripTrailingEos(generated);
        var firstMarker = text.indexOf(TOOL_CALLS_MARKER);
        if (firstMarker < 0) {
            return new Text(text.strip());
        }
        var leading = text.substring(0, firstMarker).strip();
        var calls = parseCalls(text.substring(firstMarker));
        return calls.isEmpty() ? new Text(text.strip()) : new Calls(leading, calls);
    }

    /// The other Mistral wire form: `[TOOL_CALLS] [{"name": …, "arguments": {…}, "id": …}]`
    /// — one JSON array, no [ARGS] marker. Used by the v0.1/v0.2/v0.3 Instruct line, whose
    /// vocabulary has no [ARGS] token at all.
    static Parsed parseArray(String generated) {
        var text = generated == null ? "" : stripTrailingEos(generated);
        var marker = text.indexOf(TOOL_CALLS_MARKER);
        if (marker < 0) {
            // A prompt these models find unfamiliar makes them emit the payload without
            // the control token. The array shape is specific enough to recover from.
            var stripped = text.strip();
            var recovered = parseCallArray(stripped);
            return recovered.isEmpty() ? new Text(stripped) : new Calls("", recovered);
        }
        var leading = text.substring(0, marker).strip();
        var calls = parseCallArray(text.substring(marker + TOOL_CALLS_MARKER.length()).strip());
        return calls.isEmpty() ? new Text(text.strip()) : new Calls(leading, calls);
    }

    /// Every element must be an object carrying a name — anything looser would turn an
    /// ordinary JSON array in an answer into phantom tool calls.
    private static List<ToolCall> parseCallArray(String json) {
        if (!json.startsWith("[")) return List.of();
        JSONArray array;
        try {
            array = new JSONArray(new JSONTokener(json));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return List.of();
        }
        var out = new ArrayList<ToolCall>();
        for (var i = 0; i < array.length(); i++) {
            var entry = array.optJSONObject(i);
            if (entry == null) return List.of();
            var name = entry.optString("name", "").strip();
            if (name.isEmpty()) return List.of();
            out.add(ToolCall.of(name, arguments(entry.opt("arguments"))));
        }
        return out;
    }

    /// The trained format carries an object, but a stringified object is common enough in
    /// the wild to accept. Anything else — or malformed JSON — yields empty arguments
    /// rather than dropping the call.
    private static JSONObject arguments(Object raw) {
        return switch (raw) {
            case JSONObject obj -> obj;
            case String s -> parseObject(s.strip());
            case null, default -> new JSONObject();
        };
    }

    private static List<ToolCall> parseCalls(String text) {
        var out = new ArrayList<ToolCall>();
        var cursor = 0;
        while (true) {
            var start = text.indexOf(TOOL_CALLS_MARKER, cursor);
            if (start < 0) break;
            var afterMarker = start + TOOL_CALLS_MARKER.length();
            var argsAt = text.indexOf(ARGS_MARKER, afterMarker);
            if (argsAt < 0) break;
            var name = text.substring(afterMarker, argsAt).strip();
            var jsonStart = argsAt + ARGS_MARKER.length();
            var next = text.indexOf(TOOL_CALLS_MARKER, jsonStart);
            var jsonEnd = next < 0 ? text.length() : next;
            var jsonStr = text.substring(jsonStart, jsonEnd).strip();
            var input = parseObject(jsonStr);
            if (!name.isEmpty()) {
                out.add(ToolCall.of(name, input));
            }
            cursor = jsonEnd;
        }
        return out;
    }

    private static JSONObject parseObject(String jsonStr) {
        if (jsonStr.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(new JSONTokener(jsonStr));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new JSONObject();
        }
    }

    private static String stripTrailingEos(String text) {
        var t = text;
        while (t.endsWith("</s>")) {
            t = t.substring(0, t.length() - "</s>".length());
        }
        return t;
    }
}
