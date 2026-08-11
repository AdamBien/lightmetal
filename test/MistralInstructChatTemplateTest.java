import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import lm.configuration.control.ZCfg;
import lm.prompting.control.ChatTemplate;
import lm.prompting.control.MistralInstructChatTemplate;
import lm.prompting.control.ModelFamily;
import lm.prompting.entity.AssistantText;
import lm.prompting.entity.AssistantToolCalls;
import lm.prompting.entity.ToolResult;
import lm.prompting.entity.Turn;
import lm.prompting.entity.UserText;
import lm.prompting.entity.UserToolResults;
import lm.tools.control.ToolCallParser;
import lm.tools.entity.Tool;
import lm.tools.entity.ToolCall;

void main() {
    ZCfg.load("lightmetal-tests-no-such-file");

    var tpl = ModelFamily.MISTRAL_INSTRUCT.template();
    if (!(tpl instanceof MistralInstructChatTemplate))
        throw new AssertionError("ModelFamily.MISTRAL_INSTRUCT.template() returned " + tpl.getClass());

    testPlainTurnSpacing(tpl);
    testNoTekkenMarkersEverAppear(tpl);
    testSystemOpensTheFirstUserBlock(tpl);
    testAlternatingHistory(tpl);
    testToolDeclarationFormat(tpl);
    testToolsSitBeforeTheLastUserTurn(tpl);
    testToolCallAndResultShareOneCallId(tpl);
    testParserRecoversMarkedCalls(tpl);
    testParserRecoversUnmarkedArray(tpl);
    testParserAcceptsStringifiedArguments(tpl);
    testParserKeepsCallWithMalformedArguments(tpl);
    testParserDropsNamelessCalls(tpl);
    testParserLeavesOrdinaryTextAlone(tpl);
    testStopSequences(tpl);

    IO.println("[ok] mistral instruct (v3) template render + parse");
}

// mistral-common tokenizer V3: one space AFTER each control token, never before one.
void testPlainTurnSpacing(ChatTemplate tpl) {
    var out = tpl.render("", List.of(), List.of(new UserText("hi")));
    var expected = "[INST] hi[/INST]";
    if (!expected.equals(out))
        throw new AssertionError("plain render mismatch.\nexpected:\n" + expected + "\nactual:\n" + out);
}

// The whole point of this template: these models have a 32768-entry SentencePiece
// vocabulary with none of the tekken control tokens, so emitting them would put literal
// text into the prompt.
void testNoTekkenMarkersEverAppear(ChatTemplate tpl) {
    var call = new ToolCall("toolu_01ABCdef", "get_weather", new JSONObject().put("location", "Paris"));
    var turns = List.<Turn>of(
            new UserText("weather?"),
            new AssistantToolCalls("Checking.", List.of(call)),
            new UserToolResults(List.of(new ToolResult("toolu_01ABCdef", "22C, sunny"))));
    var out = tpl.render("be terse", List.of(weatherTool()), turns);

    for (var forbidden : List.of("[SYSTEM_PROMPT]", "[/SYSTEM_PROMPT]",
            "[MODEL_SETTINGS]", "[/MODEL_SETTINGS]", "[ARGS]", "[THINK]", "[CALL_ID]")) {
        if (out.contains(forbidden))
            throw new AssertionError(forbidden + " is not in this vocabulary:\n" + out);
    }
}

// There is no system role in v3 — the system prompt opens the first user block.
void testSystemOpensTheFirstUserBlock(ChatTemplate tpl) {
    var out = tpl.render("be terse", List.of(), List.of(new UserText("hi")));
    var expected = "[INST] be terse\n\nhi[/INST]";
    if (!expected.equals(out))
        throw new AssertionError("system folding mismatch.\nexpected:\n" + expected + "\nactual:\n" + out);
}

void testAlternatingHistory(ChatTemplate tpl) {
    var turns = List.<Turn>of(new UserText("a"), new AssistantText("b"), new UserText("c"));
    var out = tpl.render("sys", List.of(), turns);
    var expected = "[INST] sys\n\na[/INST] b</s>[INST] c[/INST]";
    if (!expected.equals(out))
        throw new AssertionError("history mismatch.\nexpected:\n" + expected + "\nactual:\n" + out);
    // the system prompt belongs to the first block only, not to every one
    if (out.indexOf("sys") != out.lastIndexOf("sys"))
        throw new AssertionError("system prompt repeated:\n" + out);
}

void testToolDeclarationFormat(ChatTemplate tpl) {
    var out = tpl.render("", List.of(weatherTool()), List.of(new UserText("hi")));
    // key order is fixed by hand — org.json is HashMap-backed
    var expectedDecl = "[AVAILABLE_TOOLS] [{\"type\": \"function\", \"function\": {\"name\": \"get_weather\", "
            + "\"description\": \"Get current weather\", \"parameters\": {";
    if (!out.startsWith(expectedDecl))
        throw new AssertionError("tool envelope mismatch.\nexpected prefix:\n" + expectedDecl + "\nactual:\n" + out);
    if (!out.contains("}}][/AVAILABLE_TOOLS][INST] hi[/INST]"))
        throw new AssertionError("tool block must close straight onto the user turn:\n" + out);
}

// mistral-common keeps the declarations adjacent to the live query rather than at the top.
void testToolsSitBeforeTheLastUserTurn(ChatTemplate tpl) {
    var turns = List.<Turn>of(new UserText("a"), new AssistantText("b"), new UserText("c"));
    var out = tpl.render("", List.of(weatherTool()), turns);
    if (!out.startsWith("[INST] a[/INST] b</s>[AVAILABLE_TOOLS] "))
        throw new AssertionError("tools must precede the LAST user turn:\n" + out);
}

// v3 requires 9 alphanumeric characters, and the model pairs a result to a call by that id.
void testToolCallAndResultShareOneCallId(ChatTemplate tpl) {
    var call = new ToolCall("toolu_01ABCdef", "get_weather", new JSONObject().put("location", "Paris"));
    var turns = List.<Turn>of(
            new UserText("weather?"),
            new AssistantToolCalls("Checking.", List.of(call)),
            new UserToolResults(List.of(new ToolResult("toolu_01ABCdef", "22C, sunny"))));
    var out = tpl.render("", List.of(), turns);

    var expected = "[INST] weather?[/INST] Checking."
            + "[TOOL_CALLS] [{\"name\": \"get_weather\", \"arguments\": {\"location\": \"Paris\"}, "
            + "\"id\": \"u01ABCdef\"}]</s>"
            + "[TOOL_RESULTS] {\"content\": \"22C, sunny\", \"call_id\": \"u01ABCdef\"}[/TOOL_RESULTS]";
    if (!expected.equals(out))
        throw new AssertionError("tool loop mismatch.\nexpected:\n" + expected + "\nactual:\n" + out);
}

void testParserRecoversMarkedCalls(ChatTemplate tpl) {
    var raw = "Let me check.[TOOL_CALLS] [{\"name\": \"get_weather\", \"arguments\": "
            + "{\"location\": \"Paris\", \"options\": {\"units\": \"celsius\"}}, \"id\": \"VvvODy9mT\"}, "
            + "{\"name\": \"get_time\", \"arguments\": {\"zone\": \"CET\"}, \"id\": \"AbcDefGhi\"}]</s>";
    var parsed = tpl.parse(raw);
    if (!(parsed instanceof ToolCallParser.Calls c))
        throw new AssertionError("expected Calls, got " + parsed);
    if (!"Let me check.".equals(c.leadingText()))
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

// The failure this template was written for: given a prompt in a format it never saw, the
// model emits the payload as plain text with no [TOOL_CALLS] token in front of it.
void testParserRecoversUnmarkedArray(ChatTemplate tpl) {
    var parsed = tpl.parse("[{\"name\": \"follow_pointer\", \"arguments\": {\"key\": \"9088906054343260771\"}}]");
    if (!(parsed instanceof ToolCallParser.Calls c))
        throw new AssertionError("expected Calls, got " + parsed);
    if (c.calls().size() != 1 || !"follow_pointer".equals(c.calls().getFirst().name()))
        throw new AssertionError("unmarked call not recovered: " + c.calls());
    if (!"9088906054343260771".equals(c.calls().getFirst().input().optString("key")))
        throw new AssertionError("unmarked call args wrong: " + c.calls().getFirst().input());
}

void testParserAcceptsStringifiedArguments(ChatTemplate tpl) {
    var parsed = tpl.parse("[TOOL_CALLS] [{\"name\": \"get_weather\", "
            + "\"arguments\": \"{\\\"location\\\": \\\"Paris\\\"}\"}]");
    if (!(parsed instanceof ToolCallParser.Calls c))
        throw new AssertionError("expected Calls, got " + parsed);
    if (!"Paris".equals(c.calls().getFirst().input().optString("location")))
        throw new AssertionError("stringified args not parsed: " + c.calls().getFirst().input());
}

void testParserKeepsCallWithMalformedArguments(ChatTemplate tpl) {
    var parsed = tpl.parse("[TOOL_CALLS] [{\"name\": \"get_weather\", \"arguments\": \"{not json\"}]");
    if (!(parsed instanceof ToolCallParser.Calls c))
        throw new AssertionError("expected Calls, got " + parsed);
    if (!c.calls().getFirst().input().isEmpty())
        throw new AssertionError("expected empty arguments, got " + c.calls().getFirst().input());
}

void testParserDropsNamelessCalls(ChatTemplate tpl) {
    var parsed = tpl.parse("[TOOL_CALLS] [{\"arguments\": {\"location\": \"Paris\"}}]");
    if (!(parsed instanceof ToolCallParser.Text))
        throw new AssertionError("a nameless call must not become a tool call, got " + parsed);
}

// The unmarked-array recovery must not turn any JSON array in an answer into tool calls.
void testParserLeavesOrdinaryTextAlone(ChatTemplate tpl) {
    for (var text : List.of("The answer is 42.", "[1, 2, 3]", "[{\"a\": 1}]", "[]", "[oops")) {
        var parsed = tpl.parse(text);
        if (!(parsed instanceof ToolCallParser.Text t))
            throw new AssertionError("'" + text + "' must parse as Text, got " + parsed);
        if (!text.equals(t.text()))
            throw new AssertionError("text altered: '" + t.text() + "'");
    }
}

void testStopSequences(ChatTemplate tpl) {
    if (!tpl.stopSequences().contains("</s>"))
        throw new AssertionError("expected </s> stop sequence, got " + tpl.stopSequences());
    if (!tpl.toolCallOpenMarkers().contains("[TOOL_CALLS]"))
        throw new AssertionError("expected [TOOL_CALLS] open marker, got " + tpl.toolCallOpenMarkers());
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
