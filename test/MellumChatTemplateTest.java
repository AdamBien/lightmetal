import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import lm.configuration.control.ZCfg;
import lm.configuration.entity.Token;
import lm.inspection.entity.GGUFMetadata;
import lm.prompting.entity.AssistantText;
import lm.prompting.entity.AssistantToolCalls;
import lm.prompting.entity.ToolResult;
import lm.prompting.entity.Turn;
import lm.prompting.entity.UserText;
import lm.prompting.entity.UserToolResults;
import lm.prompting.control.ChatTemplate;
import lm.prompting.control.MellumChatTemplate;
import lm.prompting.control.ModelFamily;
import lm.tools.control.ToolCallParser;
import lm.tools.entity.Tool;
import lm.tools.entity.ToolCall;

void main() {
    ZCfg.load("lightmetal-tests-no-such-file");

    var tpl = ModelFamily.MELLUM.template();
    if (!(tpl instanceof MellumChatTemplate))
        throw new AssertionError("ModelFamily.MELLUM.template() returned " + tpl.getClass());

    testFamilyMatchesGGUFName();
    testPlainChatOmitsSystemHeader(tpl);
    testSystemBlockContent(tpl);
    testThinkingLeavesGenerationPromptBare();
    testToolDeclarationFormat(tpl);
    testSystemPromptPrecedesToolsInOneHeader(tpl);
    testAssistantHistoryDropsReasoning(tpl);
    testCurrentTurnKeepsReasoning(tpl);
    testToolCallTurnRendering(tpl);
    testToolResponsesShareOneUserTurn(tpl);
    testParserStripsThinkingAndEndMarker(tpl);
    testParserRecoversToolCalls(tpl);
    testParserAcceptsStringifiedArguments(tpl);
    testStopSequences(tpl);
    testChannelTaggingAcrossTokenBoundaries();

    IO.println("[ok] mellum template render + parse");
}

void testFamilyMatchesGGUFName() {
    var metadata = new GGUFMetadata(3, 0L, Map.of("general.name", "Mellum2 12B A2.5B Thinking"));
    var family = ModelFamily.from(metadata).orElseThrow(
            () -> new AssertionError("no family matched Mellum2 12B A2.5B Thinking"));
    if (family != ModelFamily.MELLUM)
        throw new AssertionError("expected MELLUM, got " + family);
}

// Unlike Nemotron, an empty system turn is NOT part of the trained format
void testPlainChatOmitsSystemHeader(ChatTemplate tpl) {
    var out = tpl.render("", List.of(), List.of(new UserText("hi")));

    // thinking disabled by default: the block is pre-closed rather than omitted
    var expected = "<|im_start|>user\nhi<|im_end|>\n"
            + "<|im_start|>assistant\n<think>\n\n</think>\n\n";
    if (!expected.equals(out))
        throw new AssertionError("plain chat render mismatch.\nexpected:\n" + expected + "\nactual:\n" + out);
}

void testSystemBlockContent(ChatTemplate tpl) {
    var out = tpl.render("be terse", List.of(), List.of(new UserText("hi")));
    if (!out.startsWith("<|im_start|>system\nbe terse<|im_end|>\n"))
        throw new AssertionError("expected system block at top, got:\n" + out);
}

// The inverse of Nemotron: thinking ON means adding nothing, because the model
// opens its own <think>. A prefill here would be a format it never saw.
void testThinkingLeavesGenerationPromptBare() {
    System.setProperty("mellum.enable_thinking", "true");
    ZCfg.load("lightmetal-tests-no-such-file");
    try {
        var out = new MellumChatTemplate().render("", List.of(), List.of(new UserText("hi")));
        if (!out.endsWith("<|im_start|>assistant\n"))
            throw new AssertionError("expected bare generation prompt, got:\n" + out);
        if (out.contains("<think>"))
            throw new AssertionError("thinking enabled must not prefill a think block:\n" + out);
    } finally {
        System.clearProperty("mellum.enable_thinking");
        ZCfg.load("lightmetal-tests-no-such-file");
    }
}

void testToolDeclarationFormat(ChatTemplate tpl) {
    var out = tpl.render("", List.of(weatherTool()), List.of(new UserText("hi")));

    // envelope key order is fixed by hand — org.json is HashMap-backed
    var expectedDecl = "{\"type\": \"function\", \"function\": {\"name\": \"get_weather\", "
            + "\"description\": \"Get current weather\", \"parameters\": {";
    if (!out.contains(expectedDecl))
        throw new AssertionError("tool envelope not found.\nexpected substring:\n" + expectedDecl + "\nactual:\n" + out);
    if (!out.contains("You are provided with function signatures within <tools></tools> XML tags:\n<tools>\n"))
        throw new AssertionError("tools preamble missing:\n" + out);
    if (!out.contains("\n</tools>\n\nFor each function call, return a json object with function name "
            + "and arguments within <tool_call></tool_call> XML tags:"))
        throw new AssertionError("tool instructions missing:\n" + out);
    if (!out.contains("</tool_call><|im_end|>\n<|im_start|>user\n"))
        throw new AssertionError("tool header must close the system turn:\n" + out);
}

void testSystemPromptPrecedesToolsInOneHeader(ChatTemplate tpl) {
    var out = tpl.render("be terse", List.of(weatherTool()), List.of(new UserText("hi")));
    if (!out.startsWith("<|im_start|>system\nbe terse\n\n# Tools\n"))
        throw new AssertionError("system prompt must open the tools header:\n" + out);
    // exactly one system turn, not one per concern
    if (out.indexOf("<|im_start|>system") != out.lastIndexOf("<|im_start|>system"))
        throw new AssertionError("expected a single system turn:\n" + out);
}

void testAssistantHistoryDropsReasoning(ChatTemplate tpl) {
    var turns = List.<Turn>of(
            new UserText("hi"),
            new AssistantText("<think>\nsecret reasoning\n</think>\n\nhello!"),
            new UserText("again"));
    var out = tpl.render("", List.of(), turns);

    if (!out.contains("<|im_start|>assistant\nhello!<|im_end|>\n"))
        throw new AssertionError("expected bare answer in history, got:\n" + out);
    if (out.contains("secret reasoning"))
        throw new AssertionError("reasoning leaked into history:\n" + out);
}

// Reasoning from the turn still in flight (after the last user message) is what
// carries a tool-calling loop forward, so it must survive re-rendering.
void testCurrentTurnKeepsReasoning(ChatTemplate tpl) {
    var call = new ToolCall("toolu_1", "get_weather", new JSONObject().put("location", "Paris"));
    var turns = List.<Turn>of(
            new UserText("weather in Paris?"),
            new AssistantToolCalls("<think>\nneed the tool\n</think>\n\nChecking.", List.of(call)),
            new UserToolResults(List.of(new ToolResult("toolu_1", "22C, sunny"))));
    var out = tpl.render("", List.of(), turns);

    var expected = "<|im_start|>assistant\n<think>\nneed the tool\n</think>\n\nChecking.\n<tool_call>\n";
    if (!out.contains(expected))
        throw new AssertionError("current-turn reasoning dropped.\nexpected substring:\n" + expected + "\nactual:\n" + out);
}

void testToolCallTurnRendering(ChatTemplate tpl) {
    var call = new ToolCall("toolu_1", "get_weather", new JSONObject().put("location", "Paris"));
    var turns = List.<Turn>of(
            new UserText("weather in Paris?"),
            new AssistantToolCalls("", List.of(call)),
            new UserToolResults(List.of(new ToolResult("toolu_1", "22C, sunny"))));
    var out = tpl.render("", List.of(), turns);

    // empty content joins straight onto the call — no leading newline
    var expectedCall = "<|im_start|>assistant\n<tool_call>\n"
            + "{\"name\": \"get_weather\", \"arguments\": {\"location\": \"Paris\"}}\n"
            + "</tool_call><|im_end|>\n";
    if (!out.contains(expectedCall))
        throw new AssertionError("tool call turn mismatch.\nexpected substring:\n" + expectedCall + "\nactual:\n" + out);
}

void testToolResponsesShareOneUserTurn(ChatTemplate tpl) {
    var turns = List.<Turn>of(
            new UserText("weather?"),
            new AssistantToolCalls("", List.of(new ToolCall("toolu_1", "get_weather", new JSONObject()))),
            new UserToolResults(List.of(new ToolResult("toolu_1", "22C, sunny"))),
            new UserToolResults(List.of(new ToolResult("toolu_2", "windy"))));
    var out = tpl.render("", List.of(), turns);

    var expected = "<|im_start|>user"
            + "\n<tool_response>\n22C, sunny\n</tool_response>"
            + "\n<tool_response>\nwindy\n</tool_response>"
            + "<|im_end|>\n";
    if (!out.contains(expected))
        throw new AssertionError("tool responses must share one user turn.\nexpected substring:\n"
                + expected + "\nactual:\n" + out);
}

void testParserStripsThinkingAndEndMarker(ChatTemplate tpl) {
    var parsed = tpl.parse("<think>\ninternal reasoning\n</think>\n\nThe answer is 42.<|im_end|>");
    if (!(parsed instanceof ToolCallParser.Text t))
        throw new AssertionError("expected Text, got " + parsed);
    if (!"The answer is 42.".equals(t.text()))
        throw new AssertionError("thinking not stripped: '" + t.text() + "'");
}

void testParserRecoversToolCalls(ChatTemplate tpl) {
    var raw = "<think>\nreasoning\n</think>\n\nChecking the weather.\n"
            + "<tool_call>\n{\"name\": \"get_weather\", \"arguments\": "
            + "{\"location\": \"Paris\", \"options\": {\"units\": \"celsius\"}}}\n</tool_call>\n"
            + "<tool_call>\n{\"name\": \"get_time\", \"arguments\": {\"zone\": \"CET\"}}\n</tool_call><|im_end|>";
    var parsed = tpl.parse(raw);
    if (!(parsed instanceof ToolCallParser.Calls c))
        throw new AssertionError("expected Calls, got " + parsed);
    if (!"Checking the weather.".equals(c.leadingText()))
        throw new AssertionError("leading text wrong: '" + c.leadingText() + "'");
    if (c.calls().size() != 2)
        throw new AssertionError("expected 2 calls, got " + c.calls().size());

    var first = c.calls().getFirst();
    if (!"get_weather".equals(first.name()))
        throw new AssertionError("call name wrong: " + first.name());
    if (!"Paris".equals(first.input().optString("location")))
        throw new AssertionError("location arg wrong: " + first.input());
    var options = first.input().optJSONObject("options");
    if (options == null || !"celsius".equals(options.optString("units")))
        throw new AssertionError("nested JSON arg not parsed: " + first.input());
    if (!"get_time".equals(c.calls().get(1).name()))
        throw new AssertionError("second call name wrong: " + c.calls().get(1).name());
}

// the trained format tolerates arguments as a JSON string, not just an object
void testParserAcceptsStringifiedArguments(ChatTemplate tpl) {
    var raw = "<tool_call>\n{\"name\": \"get_weather\", \"arguments\": \"{\\\"location\\\": \\\"Paris\\\"}\"}\n</tool_call>";
    var parsed = tpl.parse(raw);
    if (!(parsed instanceof ToolCallParser.Calls c))
        throw new AssertionError("expected Calls, got " + parsed);
    if (!"Paris".equals(c.calls().getFirst().input().optString("location")))
        throw new AssertionError("stringified args not parsed: " + c.calls().getFirst().input());
}

void testStopSequences(ChatTemplate tpl) {
    if (!tpl.stopSequences().contains("<|im_end|>"))
        throw new AssertionError("expected <|im_end|> stop sequence, got " + tpl.stopSequences());
    if (!tpl.toolCallOpenMarkers().contains("<tool_call>"))
        throw new AssertionError("expected <tool_call> open marker, got " + tpl.toolCallOpenMarkers());
}

// The open marker arrives in the STREAM here (nothing is prefilled) and is split
// across pieces, so the filter has to recognise and swallow it mid-token.
void testChannelTaggingAcrossTokenBoundaries() {
    System.setProperty("mellum.enable_thinking", "true");
    ZCfg.load("lightmetal-tests-no-such-file");
    try {
        var tpl = new MellumChatTemplate();
        var pieces = List.of("<thi", "nk>rea", "soning</th", "ink>ans", "wer");
        var raw = pieces.stream().map(p -> new Token(0, p));

        var thought = new StringBuilder();
        var answer = new StringBuilder();
        tpl.tagChannels(raw).forEach(t -> {
            if ("thought".equals(t.channel())) thought.append(t.text());
            else answer.append(t.text());
        });

        if (!"reasoning".contentEquals(thought))
            throw new AssertionError("thought channel wrong: '" + thought + "'");
        if (!"answer".contentEquals(answer))
            throw new AssertionError("final channel wrong: '" + answer + "'");
    } finally {
        System.clearProperty("mellum.enable_thinking");
        ZCfg.load("lightmetal-tests-no-such-file");
    }
}

Tool weatherTool() {
    var schema = new JSONObject()
            .put("type", "object")
            .put("properties", new JSONObject()
                    .put("location", new JSONObject()
                            .put("type", "string")
                            .put("description", "City name")))
            .put("required", new JSONArray(List.of("location")));
    return new Tool("get_weather", "Get current weather", schema);
}
