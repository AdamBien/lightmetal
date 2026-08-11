import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import lm.configuration.control.ZCfg;
import lm.prompting.control.ChatTemplate;
import lm.prompting.control.MistralTekkenChatTemplate;
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

// The expected strings below are the shape the GGUF's own Jinja produces — verified
// identical for Mistral-Medium-3.5 (arch=mistral3) and Mistral-Small-4-119B-2603
// (arch=mistral4), which share one tekken vocabulary.
void main() {
    ZCfg.load("lightmetal-tests-no-such-file");

    var tpl = ModelFamily.MISTRAL_3.template();
    if (!(tpl instanceof MistralTekkenChatTemplate))
        throw new AssertionError("ModelFamily.MISTRAL_3.template() returned " + tpl.getClass());

    testPlainChatCarriesModelSettings(tpl);
    testHeaderOrder(tpl);
    testToolDeclarationFormat(tpl);
    testAlternatingHistory(tpl);
    testToolCallTurnRendering(tpl);
    testReasoningEffortOverride();
    testParserRecoversToolCalls(tpl);
    testParserStripsTrailingEos(tpl);
    testToolCallOpenMarkers(tpl);

    IO.println("[ok] mistral tekken template render + parse");
}

// [MODEL_SETTINGS] is unconditional in the trained format, even with no system and no tools.
void testPlainChatCarriesModelSettings(ChatTemplate tpl) {
    var out = tpl.render("", List.of(), List.of(new UserText("hi")));
    var expected = "[MODEL_SETTINGS]{\"reasoning_effort\": \"none\"}[/MODEL_SETTINGS][INST]hi[/INST]";
    if (!expected.equals(out))
        throw new AssertionError("plain render mismatch.\nexpected:\n" + expected + "\nactual:\n" + out);
}

// System prompt, then tools, then model settings — the order the Jinja emits them in.
void testHeaderOrder(ChatTemplate tpl) {
    var out = tpl.render("be terse", List.of(weatherTool()), List.of(new UserText("hi")));
    if (!out.startsWith("[SYSTEM_PROMPT]be terse[/SYSTEM_PROMPT][AVAILABLE_TOOLS]"))
        throw new AssertionError("system must precede tools:\n" + out);
    if (!out.contains("[/AVAILABLE_TOOLS][MODEL_SETTINGS]{\"reasoning_effort\": \"none\"}"
            + "[/MODEL_SETTINGS][INST]hi[/INST]"))
        throw new AssertionError("model settings must close the header:\n" + out);
}

void testToolDeclarationFormat(ChatTemplate tpl) {
    var out = tpl.render("", List.of(weatherTool()), List.of(new UserText("hi")));
    // key order is fixed by hand — org.json is HashMap-backed
    var expectedDecl = "[AVAILABLE_TOOLS][{\"type\": \"function\", \"function\": {\"name\": \"get_weather\", "
            + "\"description\": \"Get current weather\", \"parameters\": {";
    if (!out.startsWith(expectedDecl))
        throw new AssertionError("tool envelope mismatch.\nexpected prefix:\n" + expectedDecl + "\nactual:\n" + out);
}

void testAlternatingHistory(ChatTemplate tpl) {
    var turns = List.<Turn>of(new UserText("a"), new AssistantText("b"), new UserText("c"));
    var out = tpl.render("", List.of(), turns);
    if (!out.endsWith("[INST]a[/INST]b</s>[INST]c[/INST]"))
        throw new AssertionError("history mismatch:\n" + out);
}

void testToolCallTurnRendering(ChatTemplate tpl) {
    var call = new ToolCall("toolu_1", "get_weather", new JSONObject().put("location", "Paris"));
    var turns = List.<Turn>of(
            new UserText("weather?"),
            new AssistantToolCalls("Checking.", List.of(call)),
            new UserToolResults(List.of(new ToolResult("toolu_1", "22C, sunny"))));
    var out = tpl.render("", List.of(), turns);

    var expected = "[INST]weather?[/INST]Checking."
            + "[TOOL_CALLS]get_weather[ARGS]{\"location\": \"Paris\"}</s>"
            + "[TOOL_RESULTS]22C, sunny[/TOOL_RESULTS]";
    if (!out.endsWith(expected))
        throw new AssertionError("tool loop mismatch.\nexpected suffix:\n" + expected + "\nactual:\n" + out);
}

// The GGUF template rejects anything but "none" or "high", so the property is passed straight through.
void testReasoningEffortOverride() {
    System.setProperty("mistral4.reasoning_effort", "high");
    ZCfg.load("lightmetal-tests-no-such-file");
    try {
        var out = new MistralTekkenChatTemplate().render("", List.of(), List.of(new UserText("hi")));
        if (!out.startsWith("[MODEL_SETTINGS]{\"reasoning_effort\": \"high\"}[/MODEL_SETTINGS]"))
            throw new AssertionError("reasoning_effort not honoured:\n" + out);
    } finally {
        System.clearProperty("mistral4.reasoning_effort");
        ZCfg.load("lightmetal-tests-no-such-file");
    }
}

void testParserRecoversToolCalls(ChatTemplate tpl) {
    var raw = "Checking.[TOOL_CALLS]get_weather[ARGS]{\"location\": \"Paris\"}"
            + "[TOOL_CALLS]get_time[ARGS]{\"zone\": \"CET\"}</s>";
    var parsed = tpl.parse(raw);
    if (!(parsed instanceof ToolCallParser.Calls c))
        throw new AssertionError("expected Calls, got " + parsed);
    if (!"Checking.".equals(c.leadingText()))
        throw new AssertionError("leading text wrong: '" + c.leadingText() + "'");
    if (c.calls().size() != 2)
        throw new AssertionError("expected 2 calls, got " + c.calls().size());
    if (!"Paris".equals(c.calls().getFirst().input().optString("location")))
        throw new AssertionError("location arg wrong: " + c.calls().getFirst().input());
    if (!"get_time".equals(c.calls().get(1).name()))
        throw new AssertionError("second call name wrong: " + c.calls().get(1).name());
}

void testParserStripsTrailingEos(ChatTemplate tpl) {
    var parsed = tpl.parse("The answer is 42.</s>");
    if (!(parsed instanceof ToolCallParser.Text t))
        throw new AssertionError("expected Text, got " + parsed);
    if (!"The answer is 42.".equals(t.text()))
        throw new AssertionError("eos not stripped: '" + t.text() + "'");
}

void testToolCallOpenMarkers(ChatTemplate tpl) {
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
