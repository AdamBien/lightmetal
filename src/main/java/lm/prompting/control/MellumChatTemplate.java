package lm.prompting.control;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Gatherer;
import java.util.stream.Stream;

import org.json.JSONObject;
import org.json.JSONTokener;

import lm.configuration.control.ZCfg;
import lm.configuration.entity.Token;
import lm.prompting.entity.Turn;
import lm.tools.control.ToolCallParser;
import lm.tools.entity.Tool;
import lm.tools.entity.ToolCall;

/// Mellum2 shares Nemotron's ChatML markers but not its tool encoding: calls are
/// Qwen-style JSON objects inside `<tool_call>`, not `<function=>` XML. The two
/// families are unrelated in lineage — only in wire format.
public final class MellumChatTemplate implements ChatTemplate {

    private final boolean enableThinking = ZCfg.bool("mellum.enable_thinking", false);

    @Override
    public String render(String system, List<Tool> tools, List<Turn> turns) {
        return PromptTemplate.mellum(system, tools, turns);
    }

    // <|im_end|> is the EOG token in the Mellum GGUF (id 28, verified against
    // Mellum2-12B-A2.5B-Thinking) — a safety net for quantizations that miss the
    // mapping, as in NemotronChatTemplate.
    @Override
    public List<String> stopSequences() {
        return List.of(PromptTemplate.CHATML_IM_END);
    }

    @Override
    public List<String> toolCallOpenMarkers() {
        return List.of(PromptTemplate.CHATML_TOOL_CALL_OPEN);
    }

    // Nothing is prefilled here: with thinking enabled the model emits its own
    // `<think>` as the first tokens, so the stream starts on the final channel and
    // switches only once that marker actually arrives.
    @Override
    public Stream<Token> tagChannels(Stream<Token> tokens) {
        return tokens.gather(Gatherer.<Token, ChannelFilter, Token>ofSequential(
                () -> new ChannelFilter(
                        PromptTemplate.CHATML_THINK_OPEN,
                        PromptTemplate.CHATML_THINK_CLOSE,
                        enableThinking),
                (filter, in, down) -> {
                    filter.consume(in).forEach(down::push);
                    return true;
                },
                (filter, down) -> filter.flush().forEach(down::push)));
    }

    @Override
    public ToolCallParser.Parsed parse(String generated) {
        var text = PromptTemplate.chatmlStripThinking(
                PromptTemplate.chatmlStripTrailingEnd(generated == null ? "" : generated));
        var firstOpen = text.indexOf(PromptTemplate.CHATML_TOOL_CALL_OPEN);
        if (firstOpen < 0) {
            return new ToolCallParser.Text(text.strip());
        }
        var calls = extractCalls(text, firstOpen);
        return calls.isEmpty()
                ? new ToolCallParser.Text(text.strip())
                : new ToolCallParser.Calls(text.substring(0, firstOpen).strip(), calls);
    }

    private static List<ToolCall> extractCalls(String text, int from) {
        var out = new ArrayList<ToolCall>();
        var cursor = from;
        while (true) {
            var open = text.indexOf(PromptTemplate.CHATML_TOOL_CALL_OPEN, cursor);
            if (open < 0) break;
            var close = text.indexOf(PromptTemplate.CHATML_TOOL_CALL_CLOSE, open);
            if (close < 0) break;
            var body = text.substring(open + PromptTemplate.CHATML_TOOL_CALL_OPEN.length(), close);
            var call = parseCall(body);
            if (call != null) out.add(call);
            cursor = close + PromptTemplate.CHATML_TOOL_CALL_CLOSE.length();
        }
        return out;
    }

    // {"name": "get_weather", "arguments": {"location": "Paris"}}
    private static ToolCall parseCall(String body) {
        var json = parseObject(body);
        var name = json.optString("name", "").strip();
        if (name.isEmpty()) return null;
        // `arguments` is normally an object, but the trained format tolerates a JSON
        // string (the template's `tool_call.arguments is string` branch) — accept both
        // rather than silently dropping every argument.
        return ToolCall.of(name, switch (json.opt("arguments")) {
            case JSONObject o -> o;
            case String s -> parseObject(s);
            case null, default -> new JSONObject();
        });
    }

    private static JSONObject parseObject(String raw) {
        var stripped = raw.strip();
        if (stripped.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(new JSONTokener(stripped));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new JSONObject();
        }
    }
}
