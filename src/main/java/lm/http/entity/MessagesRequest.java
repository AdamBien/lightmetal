package lm.http.entity;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import lm.configuration.entity.GenerationConfig;
import lm.tools.entity.Tool;
import lm.tools.entity.ToolCall;

public record MessagesRequest(
        String system,
        List<Turn> turns,
        List<Tool> tools,
        int maxTokens,
        float temperature) {

    public sealed interface Turn permits UserText, AssistantText, AssistantToolCalls, UserToolResults {}
    public record UserText(String text) implements Turn {}
    public record AssistantText(String text) implements Turn {}
    public record AssistantToolCalls(String text, List<ToolCall> calls) implements Turn {}
    public record UserToolResults(List<ToolResult> results) implements Turn {}
    public record ToolResult(String callId, String content) {}

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
            var content = msg.get("content");
            turns.addAll(turnsFrom(role, content));
        }
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("messages contain no usable content");
        }
        return new MessagesRequest(system, turns, tools, maxTokens, temperature);
    }

    static List<Turn> turnsFrom(String role, Object content) {
        if (content instanceof String s) {
            return s.isEmpty() ? List.of() : List.of(textTurn(role, s));
        }
        if (content instanceof JSONArray arr) {
            return turnsFromBlocks(role, arr);
        }
        return List.of();
    }

    static List<Turn> turnsFromBlocks(String role, JSONArray blocks) {
        var out = new ArrayList<Turn>();
        var textBuf = new StringBuilder();
        var calls = new ArrayList<ToolCall>();
        var results = new ArrayList<ToolResult>();

        for (var i = 0; i < blocks.length(); i++) {
            var block = blocks.optJSONObject(i);
            if (block == null) continue;
            switch (block.optString("type", "")) {
                case "text" -> appendText(textBuf, block.optString("text", ""));
                case "tool_use" -> calls.add(new ToolCall(
                        block.optString("id", ""),
                        block.optString("name", ""),
                        block.optJSONObject("input") == null ? new JSONObject() : block.optJSONObject("input")));
                case "tool_result" -> results.add(new ToolResult(
                        block.optString("tool_use_id", ""),
                        stringifyResult(block.get("content"))));
                default -> { /* ignore unknown block types */ }
            }
        }

        if ("assistant".equals(role)) {
            if (!calls.isEmpty()) {
                out.add(new AssistantToolCalls(textBuf.toString(), calls));
            } else if (!textBuf.isEmpty()) {
                out.add(new AssistantText(textBuf.toString()));
            }
        } else {
            if (!results.isEmpty()) {
                out.add(new UserToolResults(results));
            }
            if (!textBuf.isEmpty()) {
                out.add(new UserText(textBuf.toString()));
            }
        }
        return out;
    }

    static Turn textTurn(String role, String text) {
        return "assistant".equals(role) ? new AssistantText(text) : new UserText(text);
    }

    static void appendText(StringBuilder buf, String text) {
        if (text.isEmpty()) return;
        if (buf.length() > 0) buf.append('\n');
        buf.append(text);
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
