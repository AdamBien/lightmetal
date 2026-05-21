package lm.tools.control;

import java.util.Optional;

import org.json.JSONObject;
import org.json.JSONTokener;

import lm.tools.entity.ToolCall;

public interface ToolCallParser {

    sealed interface Parsed {}
    record Text(String text) implements Parsed {}
    record Call(ToolCall toolCall) implements Parsed {}

    static Parsed parse(String generated) {
        var trimmed = generated == null ? "" : generated.strip();
        return parseJson(trimmed)
                .map(json -> switch (json.optString("type", "")) {
                    case "tool_use" -> (Parsed) new Call(ToolCall.of(
                            json.optString("name", ""),
                            json.optJSONObject("input") == null ? new JSONObject() : json.optJSONObject("input")));
                    case "text" -> (Parsed) new Text(json.optString("text", trimmed));
                    default -> (Parsed) new Text(trimmed);
                })
                .orElse(new Text(trimmed));
    }

    private static Optional<JSONObject> parseJson(String text) {
        if (text.isEmpty() || text.charAt(0) != '{') {
            return Optional.empty();
        }
        try {
            return Optional.of(new JSONObject(new JSONTokener(text)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Optional.empty();
        }
    }
}
