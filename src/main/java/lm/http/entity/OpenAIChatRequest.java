package lm.http.entity;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import lm.configuration.entity.GenerationConfig;
import lm.http.entity.AnthropicMessagesRequest.AssistantText;
import lm.http.entity.AnthropicMessagesRequest.AssistantToolCalls;
import lm.http.entity.AnthropicMessagesRequest.ToolResult;
import lm.http.entity.AnthropicMessagesRequest.Turn;
import lm.http.entity.AnthropicMessagesRequest.UserText;
import lm.http.entity.AnthropicMessagesRequest.UserToolResults;
import lm.tools.entity.Tool;
import lm.tools.entity.ToolCall;

public record OpenAIChatRequest(
        String system,
        List<Turn> turns,
        List<Tool> tools,
        int maxTokens,
        float temperature) {

    public AnthropicMessagesRequest toAnthropicMessagesRequest() {
        return new AnthropicMessagesRequest(system, turns, tools, maxTokens, temperature);
    }

    public static OpenAIChatRequest from(JSONObject root, GenerationConfig defaults) {
        var maxTokens = root.optInt("max_tokens", defaults.maxTokens());
        var temperature = (float) root.optDouble("temperature", defaults.temperature());
        var tools = toolsFrom(root.optJSONArray("tools"));

        var messages = root.optJSONArray("messages");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must be a non-empty array");
        }

        var systemBuf = new StringBuilder();
        var turns = new ArrayList<Turn>(messages.length());
        var toolResultsBuf = new ArrayList<ToolResult>();

        for (var i = 0; i < messages.length(); i++) {
            var msg = messages.optJSONObject(i);
            if (msg == null) continue;
            var role = msg.optString("role", "user");

            if (!"tool".equals(role) && !toolResultsBuf.isEmpty()) {
                turns.add(new UserToolResults(List.copyOf(toolResultsBuf)));
                toolResultsBuf.clear();
            }

            switch (role) {
                case "system" -> appendSystem(systemBuf, extractText(msg.opt("content")));
                case "user" -> {
                    var text = extractText(msg.opt("content"));
                    if (!text.isEmpty()) turns.add(new UserText(text));
                }
                case "assistant" -> {
                    var text = extractText(msg.opt("content"));
                    var calls = toolCallsFrom(msg.optJSONArray("tool_calls"));
                    if (!calls.isEmpty()) {
                        turns.add(new AssistantToolCalls(text, calls));
                    } else if (!text.isEmpty()) {
                        turns.add(new AssistantText(text));
                    }
                }
                case "tool" -> toolResultsBuf.add(new ToolResult(
                        msg.optString("tool_call_id", ""),
                        extractText(msg.opt("content"))));
                default -> { /* ignore unknown roles (e.g. legacy "function") */ }
            }
        }
        if (!toolResultsBuf.isEmpty()) {
            turns.add(new UserToolResults(List.copyOf(toolResultsBuf)));
        }

        if (turns.isEmpty()) {
            throw new IllegalArgumentException("messages contain no usable content");
        }
        return new OpenAIChatRequest(systemBuf.toString(), turns, tools, maxTokens, temperature);
    }

    static String extractText(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof JSONArray arr) {
            var sb = new StringBuilder();
            for (var i = 0; i < arr.length(); i++) {
                var block = arr.optJSONObject(i);
                if (block == null) continue;
                var type = block.optString("type", "text");
                if (!"text".equals(type)) continue;
                var t = block.optString("text", "");
                if (t.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
            return sb.toString();
        }
        return content.toString();
    }

    static void appendSystem(StringBuilder buf, String text) {
        if (text.isEmpty()) return;
        if (buf.length() > 0) buf.append("\n\n");
        buf.append(text);
    }

    static List<Tool> toolsFrom(JSONArray tools) {
        if (tools == null) return List.of();
        var out = new ArrayList<Tool>(tools.length());
        for (var i = 0; i < tools.length(); i++) {
            var entry = tools.optJSONObject(i);
            if (entry == null) continue;
            var fn = entry.optJSONObject("function");
            if (fn == null) continue;
            var name = fn.optString("name", "");
            if (name.isEmpty()) continue;
            out.add(new Tool(
                    name,
                    fn.optString("description", ""),
                    fn.optJSONObject("parameters")));
        }
        return out;
    }

    static List<ToolCall> toolCallsFrom(JSONArray calls) {
        if (calls == null) return List.of();
        var out = new ArrayList<ToolCall>(calls.length());
        for (var i = 0; i < calls.length(); i++) {
            var call = calls.optJSONObject(i);
            if (call == null) continue;
            var fn = call.optJSONObject("function");
            if (fn == null) continue;
            var name = fn.optString("name", "");
            if (name.isEmpty()) continue;
            out.add(new ToolCall(
                    call.optString("id", ""),
                    name,
                    parseArguments(fn.opt("arguments"))));
        }
        return out;
    }

    static JSONObject parseArguments(Object arguments) {
        if (arguments == null) return new JSONObject();
        if (arguments instanceof JSONObject obj) return obj;
        if (arguments instanceof String s) {
            if (s.isBlank()) return new JSONObject();
            try {
                return new JSONObject(new JSONTokener(s));
            } catch (IllegalArgumentException | IllegalStateException e) {
                return new JSONObject();
            }
        }
        return new JSONObject();
    }
}
