package lm.prompting.control;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import lm.configuration.control.ZCfg;
import lm.http.entity.AnthropicMessagesRequest.AssistantText;
import lm.http.entity.AnthropicMessagesRequest.AssistantToolCalls;
import lm.http.entity.AnthropicMessagesRequest.Turn;
import lm.http.entity.AnthropicMessagesRequest.UserText;
import lm.http.entity.AnthropicMessagesRequest.UserToolResults;
import lm.tools.entity.Tool;

public interface PromptTemplate {

    static String modelSettings() {
        var effort = ZCfg.string("mistral4.reasoning_effort", "none");
        return "[MODEL_SETTINGS]{\"reasoning_effort\": \"" + effort + "\"}[/MODEL_SETTINGS]";
    }

    static String mistralInstruct(String userPrompt) {
        return "[INST] " + userPrompt + " [/INST]";
    }

    static String mistralChat(String system, List<String> alternating) {
        if (alternating == null || alternating.isEmpty()) {
            return mistralInstruct("");
        }
        var sys = (system == null || system.isBlank()) ? "" : system.strip() + "\n\n";
        var sb = new StringBuilder();
        sb.append("[INST] ").append(sys).append(alternating.get(0)).append(" [/INST]");
        for (var i = 1; i < alternating.size(); i++) {
            var turn = alternating.get(i);
            if (i % 2 == 1) {
                sb.append(' ').append(turn).append("</s>");
            } else {
                sb.append("[INST] ").append(turn).append(" [/INST]");
            }
        }
        return sb.toString();
    }

    static String mistral4(String system, List<Tool> tools, List<Turn> turns) {
        var sb = new StringBuilder();
        if (system != null && !system.isEmpty()) {
            sb.append("[SYSTEM_PROMPT]").append(system).append("[/SYSTEM_PROMPT]");
        }
        if (!tools.isEmpty()) {
            sb.append("[AVAILABLE_TOOLS]").append(mistralToolsJson(tools)).append("[/AVAILABLE_TOOLS]");
        }
        sb.append(modelSettings());
        for (var turn : turns) {
            appendTurn(sb, turn);
        }
        return sb.toString();
    }

    private static void appendTurn(StringBuilder sb, Turn turn) {
        switch (turn) {
            case UserText u -> sb.append("[INST]").append(u.text()).append("[/INST]");
            case AssistantText a -> sb.append(a.text()).append("</s>");
            case AssistantToolCalls a -> {
                if (!a.text().isBlank()) sb.append(a.text());
                for (var call : a.calls()) {
                    sb.append("[TOOL_CALLS]").append(call.name())
                            .append("[ARGS]").append(pythonJson(call.input()));
                }
                sb.append("</s>");
            }
            case UserToolResults r -> {
                for (var result : r.results()) {
                    sb.append("[TOOL_RESULTS]").append(result.content()).append("[/TOOL_RESULTS]");
                }
            }
        }
    }

    private static String mistralToolsJson(List<Tool> tools) {
        var arr = new JSONArray();
        for (var t : tools) {
            arr.put(new JSONObject()
                    .put("type", "function")
                    .put("function", new JSONObject()
                            .put("name", t.name())
                            .put("description", t.description())
                            .put("parameters", t.inputSchema() == null ? new JSONObject() : t.inputSchema())));
        }
        return pythonJson(arr);
    }

    /**
     * Convert org.json's compact output to Python json.dumps default formatting
     * (", " and ": " separators) — matches what Jinja's tojson produces.
     */
    private static String pythonJson(Object jsonValue) {
        return reformat(jsonValue.toString());
    }

    private static String reformat(String compact) {
        var sb = new StringBuilder(compact.length() + 32);
        var inString = false;
        var escape = false;
        for (var i = 0; i < compact.length(); i++) {
            var c = compact.charAt(i);
            sb.append(c);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString && (c == ':' || c == ',')) sb.append(' ');
        }
        return sb.toString();
    }
}
