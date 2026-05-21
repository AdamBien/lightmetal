package lm.http.entity;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import lm.configuration.entity.GenerationConfig;
import lm.tools.entity.Tool;

public record MessagesRequest(
        String system,
        List<Turn> turns,
        List<Tool> tools,
        int maxTokens,
        float temperature) {

    public record Turn(String role, String text) {}

    public static MessagesRequest from(JSONObject root, GenerationConfig defaults) {
        var system = root.optString("system", "");
        var maxTokens = root.optInt("max_tokens", defaults.maxTokens());
        var temperature = (float) root.optDouble("temperature", defaults.temperature());
        var tools = Tool.from(root.optJSONArray("tools"));

        var messages = root.optJSONArray("messages");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must be a non-empty array");
        }

        var turns = new ArrayList<Turn>(messages.length());
        for (var i = 0; i < messages.length(); i++) {
            var msg = messages.getJSONObject(i);
            var role = msg.optString("role", "user");
            var text = renderContent(msg.get("content"));
            if (!text.isEmpty()) {
                turns.add(new Turn(role, text));
            }
        }
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("messages contain no text content");
        }
        return new MessagesRequest(system, turns, tools, maxTokens, temperature);
    }

    static String renderContent(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof JSONArray arr) {
            var sb = new StringBuilder();
            for (var i = 0; i < arr.length(); i++) {
                var block = arr.optJSONObject(i);
                if (block == null) continue;
                var rendered = renderBlock(block);
                if (rendered.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(rendered);
            }
            return sb.toString();
        }
        return "";
    }

    static String renderBlock(JSONObject block) {
        return switch (block.optString("type", "")) {
            case "text" -> block.optString("text", "");
            case "tool_use" -> new JSONObject()
                    .put("type", "tool_use")
                    .put("name", block.optString("name", ""))
                    .put("input", block.optJSONObject("input") == null ? new JSONObject() : block.optJSONObject("input"))
                    .toString();
            case "tool_result" -> "Tool result (" + block.optString("tool_use_id", "") + "): "
                    + stringifyResult(block.get("content"));
            default -> "";
        };
    }

    static String stringifyResult(Object content) {
        if (content instanceof String s) return s;
        if (content instanceof JSONArray arr) {
            var sb = new StringBuilder();
            for (var i = 0; i < arr.length(); i++) {
                var block = arr.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(block.optString("text", ""));
                }
            }
            return sb.toString();
        }
        return content == null ? "" : content.toString();
    }
}
