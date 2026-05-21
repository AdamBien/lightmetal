package lm.tools.control;

import java.util.ArrayList;
import java.util.List;

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
