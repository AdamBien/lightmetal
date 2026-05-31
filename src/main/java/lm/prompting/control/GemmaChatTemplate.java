package lm.prompting.control;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Gatherer;
import java.util.stream.Stream;

import org.json.JSONObject;
import org.json.JSONTokener;

import lm.configuration.entity.Token;
import lm.http.entity.AnthropicMessagesRequest.Turn;
import lm.tools.control.ToolCallParser;
import lm.tools.entity.Tool;
import lm.tools.entity.ToolCall;

public final class GemmaChatTemplate implements ChatTemplate {

    private static final String TOOL_CALL_OPEN = "<|tool_call>";
    private static final String TOOL_CALL_CLOSE = "<tool_call|>";
    private static final String CALL_PREFIX = "call:";
    // Generation must halt at <tool_call|> so the runtime can dispatch the call before
    // the model invents a fake <|tool_response> and keeps going. <turn|> is the assistant
    // turn terminator — kept here as a safety net because this gemma-4 GGUF does not map
    // it to an EOG token in the vocab (verified against gemma-4-E2B-it-UD-Q8_K_XL.gguf).
    private static final List<String> STOPS =
            List.of(PromptTemplate.GEMMA_TOOL_CALL_CLOSE, PromptTemplate.GEMMA_TURN_CLOSE);

    @Override
    public String render(String system, List<Tool> tools, List<Turn> turns) {
        return PromptTemplate.gemma4(system, tools, turns);
    }

    @Override
    public List<String> stopSequences() {
        return STOPS;
    }

    // The gemma4 prefill (`<|channel>thought\n<channel|>`) starts the model inside
    // the thought channel, so streamed tokens are tagged "thought" until the model
    // emits `<channel|>` to switch to the answer channel ("final"). The marker may
    // be split across token pieces — the gatherer buffers the trailing bytes that
    // could be a partial match so the marker is detected even across token
    // boundaries, and its finisher flushes the leftover bytes when the stream ends.
    @Override
    public Stream<Token> tagChannels(Stream<Token> tokens) {
        return tokens.gather(Gatherer.<Token, ChannelFilter, Token>ofSequential(
                ChannelFilter::new,
                (filter, in, down) -> {
                    filter.consume(in).forEach(down::push);
                    return true;
                },
                (filter, down) -> filter.flush().forEach(down::push)));
    }

    static final class ChannelFilter {
        private static final String CLOSE = PromptTemplate.GEMMA_CHANNEL_CLOSE;
        private final StringBuilder pending = new StringBuilder();
        private String channel = "thought";
        private int lastId;

        List<Token> consume(Token in) {
            lastId = in.id();
            pending.append(in.text());
            var out = new ArrayList<Token>();
            while (true) {
                var idx = pending.indexOf(CLOSE);
                if (idx >= 0) {
                    if (idx > 0) {
                        out.add(new Token(in.id(), pending.substring(0, idx), channel));
                    }
                    pending.delete(0, idx + CLOSE.length());
                    channel = "final";
                    continue;
                }
                var safeLen = pending.length() - (CLOSE.length() - 1);
                if (safeLen > 0) {
                    out.add(new Token(in.id(), pending.substring(0, safeLen), channel));
                    pending.delete(0, safeLen);
                }
                return out;
            }
        }

        List<Token> flush() {
            if (pending.isEmpty()) return List.of();
            var out = List.of(new Token(lastId, pending.toString(), channel));
            pending.setLength(0);
            return out;
        }
    }

    @Override
    public ToolCallParser.Parsed parse(String generated) {
        var text = stripTrailingTurn(PromptTemplate.gemmaStripThinking(generated == null ? "" : generated));
        var firstOpen = text.indexOf(TOOL_CALL_OPEN);
        if (firstOpen < 0) {
            return new ToolCallParser.Text(stripNullSentinel(text));
        }
        var leading = stripNullSentinel(text.substring(0, firstOpen));
        var calls = extractCalls(text, firstOpen);
        return calls.isEmpty()
                ? new ToolCallParser.Text(stripNullSentinel(text))
                : new ToolCallParser.Calls(leading, calls);
    }

    static String stripNullSentinel(String s) {
        var stripped = s.strip();
        return "null".equals(stripped) ? "" : stripped;
    }

    static String stripTrailingTurn(String s) {
        var t = s;
        while (t.endsWith(PromptTemplate.GEMMA_TURN_CLOSE)) {
            t = t.substring(0, t.length() - PromptTemplate.GEMMA_TURN_CLOSE.length()).stripTrailing();
        }
        return t;
    }

    private static List<ToolCall> extractCalls(String text, int from) {
        var out = new ArrayList<ToolCall>();
        var cursor = from;
        while (true) {
            var open = text.indexOf(TOOL_CALL_OPEN, cursor);
            if (open < 0) break;
            var bodyStart = open + TOOL_CALL_OPEN.length();
            var close = text.indexOf(TOOL_CALL_CLOSE, bodyStart);
            if (close < 0) break;
            var body = text.substring(bodyStart, close);
            var call = parseCall(body);
            if (call != null) out.add(call);
            cursor = close + TOOL_CALL_CLOSE.length();
        }
        return out;
    }

    private static ToolCall parseCall(String body) {
        if (!body.startsWith(CALL_PREFIX)) return null;
        var afterPrefix = body.substring(CALL_PREFIX.length());
        var brace = afterPrefix.indexOf('{');
        if (brace < 0) return null;
        var name = afterPrefix.substring(0, brace).strip();
        if (name.isEmpty()) return null;
        var args = parseArgs(afterPrefix.substring(brace));
        return ToolCall.of(name, args);
    }

    static JSONObject parseArgs(String gemmaObject) {
        var jsonish = gemmaObject.replace(PromptTemplate.GEMMA_QUOTE, "\"");
        var quoted = quoteUnquotedKeys(jsonish);
        try {
            return new JSONObject(new JSONTokener(quoted));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new JSONObject();
        }
    }

    static String quoteUnquotedKeys(String s) {
        var sb = new StringBuilder(s.length() + 16);
        var inString = false;
        var escape = false;
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            if (escape) { sb.append(c); escape = false; continue; }
            if (c == '\\') { sb.append(c); escape = true; continue; }
            if (c == '"') { sb.append(c); inString = !inString; continue; }
            if (inString) { sb.append(c); continue; }
            if ((c == '{' || c == ',') && isStartOfUnquotedKey(s, i + 1)) {
                sb.append(c);
                var end = i + 1;
                while (end < s.length() && isIdentChar(s.charAt(end))) end++;
                sb.append('"').append(s, i + 1, end).append('"');
                i = end - 1;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isStartOfUnquotedKey(String s, int idx) {
        if (idx >= s.length()) return false;
        var c = s.charAt(idx);
        if (!isIdentStart(c)) return false;
        var j = idx + 1;
        while (j < s.length() && isIdentChar(s.charAt(j))) j++;
        return j < s.length() && s.charAt(j) == ':';
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isIdentChar(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }
}
