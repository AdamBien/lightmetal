package lm.prompting.control;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;

import lm.configuration.control.ZCfg;
import lm.prompting.entity.AssistantText;
import lm.prompting.entity.AssistantToolCalls;
import lm.prompting.entity.Turn;
import lm.prompting.entity.UserText;
import lm.prompting.entity.UserToolResults;
import lm.tools.entity.Tool;
import lm.tools.entity.ToolCall;

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

    // Shared by every ChatML-derived family (Nemotron, Mellum): the same six markers
    // are literal special tokens in both vocabularies. What differs between them is
    // the payload — Nemotron encodes tool calls as <function=> XML, Mellum as JSON —
    // so the renderers stay separate while the markers do not.
    String CHATML_IM_START = "<|im_start|>";
    String CHATML_IM_END = "<|im_end|>";
    String CHATML_THINK_OPEN = "<think>";
    String CHATML_THINK_CLOSE = "</think>";
    String CHATML_TOOL_CALL_OPEN = "<tool_call>";
    String CHATML_TOOL_CALL_CLOSE = "</tool_call>";
    /// Keeps what follows the LAST `</think>`. A block left unterminated — generation
    /// hit max-tokens mid-thought — has no close, so everything before the open is
    /// the answer; for a prefilling family (Nemotron) the open lives in the prompt
    /// rather than the output, and the whole text is the answer.
    static String chatmlStripThinking(String text) {
        var close = text.lastIndexOf(CHATML_THINK_CLOSE);
        if (close >= 0) {
            return text.substring(close + CHATML_THINK_CLOSE.length());
        }
        var open = text.indexOf(CHATML_THINK_OPEN);
        return open >= 0 ? text.substring(0, open) : text;
    }

    static String chatmlStripTrailingEnd(String s) {
        var t = s.stripTrailing();
        while (t.endsWith(CHATML_IM_END)) {
            t = t.substring(0, t.length() - CHATML_IM_END.length()).stripTrailing();
        }
        return t;
    }

    // Verbatim from the GGUF chat template (Nemotron-3-Nano) — the model is trained
    // against this exact wording, so it must not be reformatted.
    String NEMOTRON_TOOL_INSTRUCTIONS = """
            If you choose to call a function ONLY reply in the following format with NO suffix:

            <tool_call>
            <function=example_function_name>
            <parameter=example_parameter_1>
            value_1
            </parameter>
            <parameter=example_parameter_2>
            This is the value for the second parameter
            that can span
            multiple lines
            </parameter>
            </function>
            </tool_call>

            <IMPORTANT>
            Reminder:
            - Function calls MUST follow the specified format: an inner <function=...></function> block must be nested within <tool_call></tool_call> XML tags
            - Required parameters MUST be specified
            - You may provide optional reasoning for your function call in natural language BEFORE the function call, but NOT after
            - If there is no function call available, answer the question like normal with your current knowledge and do not tell the user about function calls
            </IMPORTANT>""";

    static String nemotron(String system, List<Tool> tools, List<Turn> turns) {
        var enableThinking = ZCfg.bool("nemotron.enable_thinking", false);
        var sb = new StringBuilder();
        // The GGUF template emits the system header even when there is no system
        // prompt and no tools — an empty system turn is part of the trained format.
        sb.append(CHATML_IM_START).append("system\n");
        var hasSystem = system != null && !system.isBlank();
        if (hasSystem) sb.append(system.strip());
        if (tools != null && !tools.isEmpty()) {
            if (hasSystem) sb.append("\n\n");
            appendNemotronTools(sb, tools);
        }
        sb.append(CHATML_IM_END).append('\n');
        for (var turn : turns) {
            appendNemotronTurn(sb, turn);
        }
        sb.append(CHATML_IM_START).append("assistant\n");
        sb.append(enableThinking
                ? CHATML_THINK_OPEN + "\n"
                : CHATML_THINK_OPEN + CHATML_THINK_CLOSE);
        return sb.toString();
    }

    private static void appendNemotronTurn(StringBuilder sb, Turn turn) {
        switch (turn) {
            case UserText u -> sb.append(CHATML_IM_START).append("user\n")
                    .append(u.text() == null ? "" : u.text())
                    .append(CHATML_IM_END).append('\n');
            case AssistantText a -> sb.append(CHATML_IM_START).append("assistant\n")
                    .append(nemotronAssistantContent(a.text()))
                    .append(CHATML_IM_END).append('\n');
            case AssistantToolCalls a -> {
                var content = nemotronAssistantContent(a.text());
                sb.append(CHATML_IM_START).append("assistant\n").append(content);
                // the GGUF template joins an empty think block and <tool_call> directly
                if (content.length() > (CHATML_THINK_OPEN + CHATML_THINK_CLOSE).length()) {
                    sb.append('\n');
                }
                for (var call : a.calls()) {
                    sb.append(CHATML_TOOL_CALL_OPEN).append('\n')
                            .append("<function=").append(call.name()).append(">\n");
                    appendNemotronParameters(sb, call.input());
                    sb.append("</function>\n").append(CHATML_TOOL_CALL_CLOSE).append('\n');
                }
                sb.append(CHATML_IM_END).append('\n');
            }
            case UserToolResults r -> {
                sb.append(CHATML_IM_START).append("user\n");
                for (var result : r.results()) {
                    sb.append("<tool_response>\n")
                            .append(result.content() == null ? "" : result.content())
                            .append("\n</tool_response>\n");
                }
                sb.append(CHATML_IM_END).append('\n');
            }
        }
    }

    // History keeps only the answer: reasoning is truncated (like the GGUF template
    // does for past turns) and every assistant message carries the empty think block
    // the model expects in its own prior output.
    static String nemotronAssistantContent(String text) {
        var t = text == null ? "" : text;
        var close = t.lastIndexOf(CHATML_THINK_CLOSE);
        if (close >= 0) {
            t = t.substring(close + CHATML_THINK_CLOSE.length());
        } else {
            var open = t.indexOf(CHATML_THINK_OPEN);
            if (open >= 0) t = t.substring(0, open);
        }
        return CHATML_THINK_OPEN + CHATML_THINK_CLOSE + t.strip();
    }

    private static void appendNemotronParameters(StringBuilder sb, JSONObject input) {
        if (input == null) return;
        for (var key : new TreeSet<>(input.keySet())) {
            sb.append("<parameter=").append(key).append(">\n");
            var value = input.get(key);
            sb.append(value instanceof JSONObject || value instanceof JSONArray
                    ? pythonJson(value)
                    : String.valueOf(value));
            sb.append("\n</parameter>\n");
        }
    }

    private static void appendNemotronTools(StringBuilder sb, List<Tool> tools) {
        sb.append("# Tools\n\nYou have access to the following functions:\n\n<tools>");
        for (var tool : tools) {
            sb.append("\n<function>\n<name>").append(tool.name()).append("</name>");
            if (tool.description() != null && !tool.description().isBlank()) {
                sb.append("\n<description>").append(tool.description().strip()).append("</description>");
            }
            sb.append("\n<parameters>");
            var schema = tool.inputSchema();
            var props = schema == null ? null : schema.optJSONObject("properties");
            if (props != null) {
                for (var key : new TreeSet<>(props.keySet())) {
                    appendNemotronParameterDeclaration(sb, key, props.optJSONObject(key));
                }
            }
            var required = schema == null ? null : schema.optJSONArray("required");
            if (required != null && !required.isEmpty()) {
                sb.append("\n<required>").append(pythonJson(required)).append("</required>");
            }
            sb.append("\n</parameters>\n</function>");
        }
        sb.append("\n</tools>\n\n").append(NEMOTRON_TOOL_INSTRUCTIONS);
    }

    private static void appendNemotronParameterDeclaration(StringBuilder sb, String name, JSONObject fields) {
        sb.append("\n<parameter>\n<name>").append(name).append("</name>");
        if (fields == null) {
            sb.append("\n</parameter>");
            return;
        }
        var type = fields.optString("type", null);
        if (type != null) {
            sb.append("\n<type>").append(type).append("</type>");
        }
        var description = fields.optString("description", null);
        if (description != null && !description.isEmpty()) {
            sb.append("\n<description>").append(description.strip()).append("</description>");
        }
        var enums = fields.optJSONArray("enum");
        if (enums != null) {
            sb.append("\n<enum>").append(pythonJson(enums)).append("</enum>");
        }
        sb.append("\n</parameter>");
    }

    // Verbatim from the GGUF chat template (Mellum2-12B-A2.5B) — the model is trained
    // against this exact wording, so it must not be reformatted.
    String MELLUM_TOOLS_PREAMBLE = """
            # Tools

            You may call one or more functions to assist with the user query.

            You are provided with function signatures within <tools></tools> XML tags:
            <tools>""";
    String MELLUM_TOOL_INSTRUCTIONS = """
            For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:
            <tool_call>
            {"name": <function-name>, "arguments": <args-json-object>}
            </tool_call>""";

    static String mellum(String system, List<Tool> tools, List<Turn> turns) {
        var sb = new StringBuilder();
        var hasSystem = system != null && !system.isBlank();
        var hasTools = tools != null && !tools.isEmpty();
        // Unlike Nemotron, the system header appears only when it carries something —
        // a bare conversation opens directly on the user turn.
        if (hasTools) {
            sb.append(CHATML_IM_START).append("system\n");
            if (hasSystem) sb.append(system.strip()).append("\n\n");
            appendMellumTools(sb, tools);
            sb.append(CHATML_IM_END).append('\n');
        } else if (hasSystem) {
            sb.append(CHATML_IM_START).append("system\n").append(system.strip())
                    .append(CHATML_IM_END).append('\n');
        }
        var lastQuery = lastMellumQueryIndex(turns);
        var i = 0;
        while (i < turns.size()) {
            i = appendMellumTurn(sb, turns, i, lastQuery);
        }
        sb.append(CHATML_IM_START).append("assistant\n");
        // Thinking off is expressed as a pre-closed block rather than by omitting it:
        // left bare, the model opens its own <think> and reasons anyway (verified
        // against the GGUF). Nemotron's template prefills <think>\n to turn thinking
        // ON; Mellum's does the inverse, which is why there is no prefill here.
        if (!ZCfg.bool("mellum.enable_thinking", false)) {
            sb.append(CHATML_THINK_OPEN).append("\n\n").append(CHATML_THINK_CLOSE).append("\n\n");
        }
        return sb.toString();
    }

    private static int appendMellumTurn(StringBuilder sb, List<Turn> turns, int index, int lastQuery) {
        return switch (turns.get(index)) {
            case UserText u -> {
                sb.append(CHATML_IM_START).append("user\n")
                        .append(u.text() == null ? "" : u.text())
                        .append(CHATML_IM_END).append('\n');
                yield index + 1;
            }
            case AssistantText a -> {
                appendMellumAssistant(sb, a.text(), List.of(), index, turns.size(), lastQuery);
                yield index + 1;
            }
            case AssistantToolCalls a -> {
                appendMellumAssistant(sb, a.text(), a.calls(), index, turns.size(), lastQuery);
                yield index + 1;
            }
            // Consecutive tool results share ONE user turn, as the GGUF template does —
            // a separate turn per result is a format the model has never seen.
            case UserToolResults ignored -> {
                sb.append(CHATML_IM_START).append("user");
                var next = index;
                while (next < turns.size() && turns.get(next) instanceof UserToolResults r) {
                    for (var result : r.results()) {
                        sb.append("\n<tool_response>\n")
                                .append(result.content() == null ? "" : result.content())
                                .append("\n</tool_response>");
                    }
                    next++;
                }
                sb.append(CHATML_IM_END).append('\n');
                yield next;
            }
        };
    }

    // Reasoning survives only for assistant turns belonging to the CURRENT query —
    // everything after the last user message. Replayed history renders as the answer
    // alone, so old thinking neither burns context nor invites re-reasoning.
    private static void appendMellumAssistant(StringBuilder sb, String raw, List<ToolCall> calls,
            int index, int count, int lastQuery) {
        var content = mellumContent(raw);
        var reasoning = mellumReasoning(raw);
        sb.append(CHATML_IM_START).append("assistant\n");
        if (index > lastQuery && (index == count - 1 || !reasoning.isEmpty())) {
            sb.append(CHATML_THINK_OPEN).append('\n').append(reasoning).append('\n')
                    .append(CHATML_THINK_CLOSE).append("\n\n");
        }
        sb.append(content);
        for (var i = 0; i < calls.size(); i++) {
            // the template joins the first call straight onto empty content
            if (i > 0 || !content.isEmpty()) sb.append('\n');
            var call = calls.get(i);
            sb.append(CHATML_TOOL_CALL_OPEN).append("\n{\"name\": ")
                    .append(JSONObject.quote(call.name())).append(", \"arguments\": ")
                    .append(pythonJson(call.input() == null ? new JSONObject() : call.input()))
                    .append("}\n").append(CHATML_TOOL_CALL_CLOSE);
        }
        sb.append(CHATML_IM_END).append('\n');
    }

    // Mirrors the template's last_query_index. With no user message at all (tool
    // results only) it degrades to the last turn, so nothing counts as current and
    // every assistant turn renders as bare history — same as the Jinja default.
    static int lastMellumQueryIndex(List<Turn> turns) {
        for (var i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i) instanceof UserText) return i;
        }
        return turns.size() - 1;
    }

    /// Jinja: `content.split('</think>')[-1].lstrip('\n')` — the answer is whatever
    /// follows the LAST close marker; text without one is already an answer.
    static String mellumContent(String text) {
        var t = text == null ? "" : text;
        var close = t.lastIndexOf(CHATML_THINK_CLOSE);
        return close < 0 ? t : stripLeadingNewlines(t.substring(close + CHATML_THINK_CLOSE.length()));
    }

    /// Jinja: `content.split('</think>')[0].rstrip('\n').split('<think>')[-1].lstrip('\n')`
    /// — note it reads up to the FIRST close, while the answer is taken after the last.
    static String mellumReasoning(String text) {
        var t = text == null ? "" : text;
        var close = t.indexOf(CHATML_THINK_CLOSE);
        if (close < 0) return "";
        var head = stripTrailingNewlines(t.substring(0, close));
        var open = head.lastIndexOf(CHATML_THINK_OPEN);
        return stripLeadingNewlines(open < 0 ? head : head.substring(open + CHATML_THINK_OPEN.length()));
    }

    private static void appendMellumTools(StringBuilder sb, List<Tool> tools) {
        sb.append(MELLUM_TOOLS_PREAMBLE);
        for (var tool : tools) {
            sb.append('\n').append(mellumToolJson(tool));
        }
        sb.append("\n</tools>\n\n").append(MELLUM_TOOL_INSTRUCTIONS);
    }

    // `tool | tojson` over the OpenAI function envelope the template is trained on.
    // Assembled by hand rather than via JSONObject: org.json is HashMap-backed, and
    // the trained order is type, then name, description, parameters.
    private static String mellumToolJson(Tool tool) {
        return "{\"type\": \"function\", \"function\": {\"name\": "
                + JSONObject.quote(tool.name())
                + ", \"description\": " + JSONObject.quote(tool.description() == null ? "" : tool.description())
                + ", \"parameters\": "
                + pythonJson(tool.inputSchema() == null ? new JSONObject() : tool.inputSchema())
                + "}}";
    }

    private static String stripLeadingNewlines(String s) {
        var start = 0;
        while (start < s.length() && s.charAt(start) == '\n') start++;
        return s.substring(start);
    }

    private static String stripTrailingNewlines(String s) {
        var end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') end--;
        return s.substring(0, end);
    }

    String GEMMA_QUOTE = "<|\"|>";
    String GEMMA_TURN_OPEN = "<|turn>";
    String GEMMA_TURN_CLOSE = "<turn|>";
    String GEMMA_TOOL_OPEN = "<|tool>";
    String GEMMA_TOOL_CLOSE = "<tool|>";
    String GEMMA_TOOL_CALL_OPEN = "<|tool_call>";
    String GEMMA_TOOL_CALL_CLOSE = "<tool_call|>";
    String GEMMA_TOOL_RESPONSE_OPEN = "<|tool_response>";
    String GEMMA_TOOL_RESPONSE_CLOSE = "<tool_response|>";
    String GEMMA_CHANNEL_OPEN = "<|channel>";
    String GEMMA_CHANNEL_CLOSE = "<channel|>";
    String GEMMA_THINK = "<|think|>";
    Set<String> GEMMA_PROPERTY_STANDARD_KEYS =
            Set.of("description", "type", "properties", "required", "nullable");

    static String gemma4(String system, List<Tool> tools, List<Turn> turns) {
        var enableThinking = ZCfg.bool("gemma4.enable_thinking", false);
        var sb = new StringBuilder();
        var hasHeader = enableThinking
                || (tools != null && !tools.isEmpty())
                || (system != null && !system.isBlank());
        if (hasHeader) {
            sb.append(GEMMA_TURN_OPEN).append("system\n");
            if (enableThinking) sb.append(GEMMA_THINK).append('\n');
            if (system != null && !system.isBlank()) sb.append(system.strip());
            if (tools != null) {
                for (var t : tools) {
                    sb.append(GEMMA_TOOL_OPEN);
                    appendGemmaToolDeclaration(sb, t);
                    sb.append(GEMMA_TOOL_CLOSE);
                }
            }
            sb.append(GEMMA_TURN_CLOSE).append('\n');
        }

        var prevType = "";
        var prevNonToolRole = "";
        var i = 0;
        while (i < turns.size()) {
            var turn = turns.get(i);

            if (turn instanceof UserToolResults r) {
                for (var result : r.results()) {
                    appendGemmaToolResponse(sb, "unknown", result.content());
                }
                prevType = "tool_response";
                i++;
                continue;
            }

            var role = (turn instanceof AssistantText || turn instanceof AssistantToolCalls)
                    ? "model" : "user";
            var continueModel = "model".equals(role) && "model".equals(prevNonToolRole);
            if (!continueModel) {
                sb.append(GEMMA_TURN_OPEN).append(role).append('\n');
            }

            var hasContent = false;
            var inlinedResponses = false;

            switch (turn) {
                case AssistantToolCalls a -> {
                    for (var call : a.calls()) {
                        sb.append(GEMMA_TOOL_CALL_OPEN)
                                .append("call:").append(call.name()).append('{');
                        appendGemmaArgsBody(sb, call.input());
                        sb.append('}').append(GEMMA_TOOL_CALL_CLOSE);
                    }
                    prevType = "tool_call";

                    var k = i + 1;
                    while (k < turns.size() && turns.get(k) instanceof UserToolResults r) {
                        for (var result : r.results()) {
                            appendGemmaToolResponse(sb,
                                    toolNameForCallId(a.calls(), result.callId()),
                                    result.content());
                        }
                        inlinedResponses = true;
                        prevType = "tool_response";
                        k++;
                    }
                    i = k - 1;

                    var text = gemmaAssistantContent(a.text());
                    if (!text.isBlank()) {
                        sb.append(text);
                        hasContent = true;
                    }
                }
                case AssistantText a -> {
                    var text = gemmaAssistantContent(a.text());
                    if (!text.isBlank()) {
                        sb.append(text);
                        hasContent = true;
                    }
                    prevType = "";
                }
                case UserText u -> {
                    var text = u.text() == null ? "" : u.text().strip();
                    if (!text.isEmpty()) {
                        sb.append(text);
                        hasContent = true;
                    }
                    prevType = "";
                }
                case UserToolResults ignored -> { /* handled above */ }
            }

            if ("tool_call".equals(prevType) && !inlinedResponses) {
                sb.append(GEMMA_TOOL_RESPONSE_OPEN);
            } else if (!(inlinedResponses && !hasContent)) {
                sb.append(GEMMA_TURN_CLOSE).append('\n');
            }

            prevNonToolRole = role;
            i++;
        }

        if (!"tool_response".equals(prevType) && !"tool_call".equals(prevType)) {
            sb.append(GEMMA_TURN_OPEN).append("model\n");
            if (!enableThinking) {
                sb.append(GEMMA_CHANNEL_OPEN).append("thought\n").append(GEMMA_CHANNEL_CLOSE);
            }
        }
        return sb.toString();
    }

    static String gemmaStripThinking(String text) {
        if (text == null || text.isEmpty()) return "";
        var sb = new StringBuilder();
        var parts = text.split(java.util.regex.Pattern.quote(GEMMA_CHANNEL_CLOSE), -1);
        for (var part : parts) {
            var open = part.indexOf(GEMMA_CHANNEL_OPEN);
            sb.append(open >= 0 ? part.substring(0, open) : part);
        }
        return sb.toString().strip();
    }

    static String gemmaAssistantContent(String text) {
        var stripped = gemmaStripThinking(text);
        return "null".equals(stripped) ? "" : stripped;
    }

    private static String toolNameForCallId(List<ToolCall> calls, String callId) {
        if (callId == null) return "unknown";
        for (var c : calls) {
            if (callId.equals(c.id())) return c.name();
        }
        return "unknown";
    }

    private static void appendGemmaToolResponse(StringBuilder sb, String toolName, String content) {
        sb.append(GEMMA_TOOL_RESPONSE_OPEN)
                .append("response:").append(toolName).append("{value:")
                .append(gemmaQuoted(content == null ? "" : content))
                .append('}').append(GEMMA_TOOL_RESPONSE_CLOSE);
    }

    private static String gemmaQuoted(String s) {
        return GEMMA_QUOTE + s + GEMMA_QUOTE;
    }

    private static void appendGemmaArgsBody(StringBuilder sb, JSONObject input) {
        if (input == null) return;
        var sorted = new TreeSet<>(input.keySet());
        var first = true;
        for (var key : sorted) {
            if (!first) sb.append(',');
            first = false;
            sb.append(key).append(':');
            appendGemmaValue(sb, input.get(key));
        }
    }

    private static void appendGemmaValue(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> sb.append(gemmaQuoted(s));
            case Boolean b -> sb.append(b);
            case Number n -> sb.append(n);
            case JSONObject obj -> {
                sb.append('{');
                appendGemmaArgsBody(sb, obj);
                sb.append('}');
            }
            case JSONArray arr -> {
                sb.append('[');
                for (var i = 0; i < arr.length(); i++) {
                    if (i > 0) sb.append(',');
                    appendGemmaValue(sb, arr.get(i));
                }
                sb.append(']');
            }
            default -> sb.append(gemmaQuoted(value.toString()));
        }
    }

    private static void appendGemmaToolDeclaration(StringBuilder sb, Tool tool) {
        sb.append("declaration:").append(tool.name()).append('{')
                .append("description:").append(gemmaQuoted(tool.description() == null ? "" : tool.description()));
        var params = tool.inputSchema();
        if (params != null) {
            sb.append(",parameters:{");
            var hasInner = false;
            var props = params.optJSONObject("properties");
            if (props != null && !props.isEmpty()) {
                sb.append("properties:{");
                appendGemmaProperties(sb, props, false);
                sb.append("},");
                hasInner = true;
            }
            var required = params.optJSONArray("required");
            if (required != null && !required.isEmpty()) {
                sb.append("required:[");
                for (var i = 0; i < required.length(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(gemmaQuoted(required.optString(i, "")));
                }
                sb.append("],");
                hasInner = true;
            }
            if (hasInner || params.has("type")) {
                sb.append("type:")
                        .append(gemmaQuoted(params.optString("type", "object").toUpperCase()))
                        .append('}');
            } else {
                sb.append('}');
            }
        }
        sb.append('}');
    }

    private static void appendGemmaProperties(StringBuilder sb, JSONObject props, boolean filterStdKeys) {
        var first = true;
        for (var key : new TreeSet<>(props.keySet())) {
            if (filterStdKeys && GEMMA_PROPERTY_STANDARD_KEYS.contains(key)) continue;
            var value = props.optJSONObject(key);
            if (value == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(key).append(":{");
            appendGemmaPropertyContents(sb, value);
            sb.append('}');
        }
    }

    private static void appendGemmaPropertyContents(StringBuilder sb, JSONObject value) {
        var addComma = false;
        var description = value.optString("description", null);
        if (description != null && !description.isEmpty()) {
            sb.append("description:").append(gemmaQuoted(description));
            addComma = true;
        }
        var type = value.optString("type", "").toUpperCase();
        if ("STRING".equals(type)) {
            var enums = value.optJSONArray("enum");
            if (enums != null && !enums.isEmpty()) {
                if (addComma) sb.append(',');
                addComma = true;
                sb.append("enum:[");
                for (var i = 0; i < enums.length(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(gemmaQuoted(enums.optString(i, "")));
                }
                sb.append(']');
            }
        } else if ("ARRAY".equals(type)) {
            var items = value.optJSONObject("items");
            if (items != null && !items.isEmpty()) {
                if (addComma) sb.append(',');
                addComma = true;
                sb.append("items:{");
                appendGemmaItemContents(sb, items);
                sb.append('}');
            }
        }
        if (value.optBoolean("nullable", false)) {
            if (addComma) sb.append(',');
            addComma = true;
            sb.append("nullable:true");
        }
        if ("OBJECT".equals(type)) {
            var nested = value.optJSONObject("properties");
            if (nested != null) {
                if (addComma) sb.append(',');
                addComma = true;
                sb.append("properties:{");
                appendGemmaProperties(sb, nested, false);
                sb.append('}');
            } else {
                if (addComma) sb.append(',');
                addComma = true;
                sb.append("properties:{");
                appendGemmaProperties(sb, value, true);
                sb.append('}');
            }
            var nestedReq = value.optJSONArray("required");
            if (nestedReq != null && !nestedReq.isEmpty()) {
                if (addComma) sb.append(',');
                addComma = true;
                sb.append("required:[");
                for (var i = 0; i < nestedReq.length(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(gemmaQuoted(nestedReq.optString(i, "")));
                }
                sb.append(']');
            }
        }
        if (addComma) sb.append(',');
        sb.append("type:").append(gemmaQuoted(type));
    }

    private static void appendGemmaItemContents(StringBuilder sb, JSONObject items) {
        var first = true;
        for (var key : new TreeSet<>(items.keySet())) {
            var raw = items.opt(key);
            if (raw == null) continue;
            if (!first) sb.append(',');
            first = false;
            switch (key) {
                case "properties" -> {
                    sb.append("properties:{");
                    if (raw instanceof JSONObject obj) {
                        appendGemmaProperties(sb, obj, false);
                    }
                    sb.append('}');
                }
                case "required" -> {
                    sb.append("required:[");
                    if (raw instanceof JSONArray arr) {
                        for (var i = 0; i < arr.length(); i++) {
                            if (i > 0) sb.append(',');
                            sb.append(gemmaQuoted(arr.optString(i, "")));
                        }
                    }
                    sb.append(']');
                }
                case "type" -> {
                    if (raw instanceof String s) {
                        sb.append("type:").append(gemmaQuoted(s.toUpperCase()));
                    } else if (raw instanceof JSONArray arr) {
                        sb.append("type:[");
                        for (var i = 0; i < arr.length(); i++) {
                            if (i > 0) sb.append(',');
                            sb.append(gemmaQuoted(arr.optString(i, "").toUpperCase()));
                        }
                        sb.append(']');
                    }
                }
                default -> {
                    sb.append(key).append(':');
                    appendGemmaValue(sb, raw);
                }
            }
        }
    }
}
