import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import lm.configuration.control.ZCfg;
import lm.http.entity.AnthropicMessagesRequest.AssistantText;
import lm.http.entity.AnthropicMessagesRequest.AssistantToolCalls;
import lm.http.entity.AnthropicMessagesRequest.ToolResult;
import lm.http.entity.AnthropicMessagesRequest.Turn;
import lm.http.entity.AnthropicMessagesRequest.UserText;
import lm.http.entity.AnthropicMessagesRequest.UserToolResults;
import lm.prompting.control.ChatTemplate;
import lm.prompting.control.GemmaChatTemplate;
import lm.prompting.control.ModelFamily;
import lm.tools.control.ToolCallParser;
import lm.tools.entity.Tool;
import lm.tools.entity.ToolCall;

void main() {
    ZCfg.load("lightmetal-tests-no-such-file");

    var tpl = ModelFamily.GEMMA_4.template();
    if (!(tpl instanceof GemmaChatTemplate))
        throw new AssertionError("ModelFamily.GEMMA_4.template() returned " + tpl.getClass());

    testPlainChatGenerationPromptShape(tpl);
    testSystemBlockEmittedWhenSystemPresent(tpl);
    testToolDeclarationFormat(tpl);
    testToolCallAndResponseMerging(tpl);
    testParserRecoversToolCalls(tpl);
    testParserStripsThinking(tpl);
    testParserDropsNullLeadingSentinel(tpl);
    testParserDropsNullOnlyOutput(tpl);
    testRendererSuppressesNullAssistantText(tpl);
    testStopSequencesIncludeToolCallAndTurnClose(tpl);
    testParserStripsTrailingTurnMarker(tpl);

    IO.println("[ok] gemma4 template render + parse");
}

void testPlainChatGenerationPromptShape(ChatTemplate tpl) {
    var turns = List.<Turn>of(new UserText("hi"));
    var out = tpl.render("", List.of(), turns);

    // no system header when system is blank, no tools, thinking disabled
    if (out.contains("<|turn>system"))
        throw new AssertionError("did not expect system block:\n" + out);

    var expected = "<|turn>user\nhi<turn|>\n<|turn>model\n<|channel>thought\n<channel|>";
    if (!expected.equals(out))
        throw new AssertionError("plain chat render mismatch.\nexpected:\n" + expected + "\nactual:\n" + out);
}

void testSystemBlockEmittedWhenSystemPresent(ChatTemplate tpl) {
    var out = tpl.render("be terse", List.of(), List.of(new UserText("hi")));
    if (!out.startsWith("<|turn>system\nbe terse<turn|>\n"))
        throw new AssertionError("expected system block at top, got:\n" + out);
    if (!out.endsWith("<|turn>model\n<|channel>thought\n<channel|>"))
        throw new AssertionError("expected generation primer at end, got:\n" + out);
}

void testToolDeclarationFormat(ChatTemplate tpl) {
    var schema = new JSONObject()
            .put("type", "object")
            .put("properties", new JSONObject()
                    .put("location", new JSONObject()
                            .put("type", "string")
                            .put("description", "City name")))
            .put("required", new JSONArray(List.of("location")));
    var tool = new Tool("get_weather", "Get current weather", schema);

    var out = tpl.render("", List.of(tool), List.of(new UserText("hi")));

    var expectedDecl = "<|tool>declaration:get_weather{description:<|\"|>Get current weather<|\"|>,"
            + "parameters:{properties:{location:{description:<|\"|>City name<|\"|>,type:<|\"|>STRING<|\"|>}},"
            + "required:[<|\"|>location<|\"|>],type:<|\"|>OBJECT<|\"|>}}<tool|>";
    if (!out.contains(expectedDecl))
        throw new AssertionError("tool declaration not found.\nexpected substring:\n" + expectedDecl + "\nactual:\n" + out);
}

void testToolCallAndResponseMerging(ChatTemplate tpl) {
    var call = new ToolCall("toolu_1", "get_weather", new JSONObject().put("location", "Paris"));
    var turns = List.<Turn>of(
            new UserText("weather in Paris?"),
            new AssistantToolCalls("", List.of(call)),
            new UserToolResults(List.of(new ToolResult("toolu_1", "22C, sunny"))));
    var out = tpl.render("", List.of(), turns);

    var expectedSegment = "<|tool_call>call:get_weather{location:<|\"|>Paris<|\"|>}<tool_call|>"
            + "<|tool_response>response:get_weather{value:<|\"|>22C, sunny<|\"|>}<tool_response|>";
    if (!out.contains(expectedSegment))
        throw new AssertionError("tool_call+response merge mismatch.\nexpected substring:\n" + expectedSegment + "\nactual:\n" + out);

    // After an inlined tool_response with no trailing content, the turn must NOT close (model continues)
    var afterResponse = out.substring(out.lastIndexOf("<tool_response|>") + "<tool_response|>".length());
    if (afterResponse.contains("<turn|>"))
        throw new AssertionError("unexpected <turn|> after inlined tool_response:\n" + out);
}

void testParserRecoversToolCalls(ChatTemplate tpl) {
    var raw = "Sure, calling tool. <|tool_call>call:get_weather{location:<|\"|>Paris<|\"|>,units:<|\"|>celsius<|\"|>}<tool_call|>";
    var parsed = tpl.parse(raw);
    if (!(parsed instanceof ToolCallParser.Calls c))
        throw new AssertionError("expected Calls, got " + parsed.getClass() + ": " + parsed);
    if (!"Sure, calling tool.".equals(c.leadingText()))
        throw new AssertionError("leading text wrong: '" + c.leadingText() + "'");
    if (c.calls().size() != 1)
        throw new AssertionError("expected 1 call, got " + c.calls().size());
    var only = c.calls().getFirst();
    if (!"get_weather".equals(only.name()))
        throw new AssertionError("call name wrong: " + only.name());
    if (!"Paris".equals(only.input().optString("location")))
        throw new AssertionError("location arg wrong: " + only.input());
    if (!"celsius".equals(only.input().optString("units")))
        throw new AssertionError("units arg wrong: " + only.input());
}

void testParserStripsThinking(ChatTemplate tpl) {
    var raw = "<|channel>thought\ninternal reasoning here\n<channel|>The answer is 42.";
    var parsed = tpl.parse(raw);
    if (!(parsed instanceof ToolCallParser.Text t))
        throw new AssertionError("expected Text, got " + parsed.getClass());
    if (!"The answer is 42.".equals(t.text()))
        throw new AssertionError("thinking not stripped, got: '" + t.text() + "'");
}

void testParserDropsNullLeadingSentinel(ChatTemplate tpl) {
    var raw = "null<|tool_call>call:get_weather{location:<|\"|>Paris<|\"|>}<tool_call|>";
    var parsed = tpl.parse(raw);
    if (!(parsed instanceof ToolCallParser.Calls c))
        throw new AssertionError("expected Calls, got " + parsed.getClass());
    if (!c.leadingText().isEmpty())
        throw new AssertionError("expected empty leading (null sentinel stripped), got: '" + c.leadingText() + "'");
    if (c.calls().size() != 1 || !"get_weather".equals(c.calls().getFirst().name()))
        throw new AssertionError("tool call lost during null strip");
}

void testParserDropsNullOnlyOutput(ChatTemplate tpl) {
    var parsed = tpl.parse("null");
    if (!(parsed instanceof ToolCallParser.Text t))
        throw new AssertionError("expected Text, got " + parsed.getClass());
    if (!t.text().isEmpty())
        throw new AssertionError("expected empty text, got: '" + t.text() + "'");
}

void testStopSequencesIncludeToolCallAndTurnClose(ChatTemplate tpl) {
    var stops = tpl.stopSequences();
    if (!stops.contains("<tool_call|>"))
        throw new AssertionError("expected <tool_call|> in stop sequences, got: " + stops);
    if (!stops.contains("<turn|>"))
        throw new AssertionError("expected <turn|> in stop sequences, got: " + stops);
}

void testParserStripsTrailingTurnMarker(ChatTemplate tpl) {
    var parsed = tpl.parse("Hello there.<turn|>");
    if (!(parsed instanceof ToolCallParser.Text t))
        throw new AssertionError("expected Text, got " + parsed.getClass());
    if (!"Hello there.".equals(t.text()))
        throw new AssertionError("trailing <turn|> not stripped, got: '" + t.text() + "'");
}

void testRendererSuppressesNullAssistantText(ChatTemplate tpl) {
    var call = new ToolCall("toolu_1", "delegate_to_link_researcher",
            new JSONObject().put("task", "find link"));
    var turns = List.<Turn>of(
            new UserText("go"),
            new AssistantToolCalls("null", List.of(call)),
            new UserToolResults(List.of(new ToolResult("toolu_1", "ok"))));
    var out = tpl.render("", List.of(), turns);
    if (out.contains("<tool_response|>null"))
        throw new AssertionError("'null' sentinel leaked into prompt:\n" + out);
    if (out.contains("null<turn|>"))
        throw new AssertionError("'null' before <turn|> leaked:\n" + out);
}
