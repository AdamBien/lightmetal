package lm.prompting.control;

import java.util.List;

import lm.prompting.entity.Turn;
import lm.tools.control.ToolCallParser;
import lm.tools.entity.Tool;

// Mistral-7B-Instruct v0.1/v0.2/v0.3 and their derivatives — arch=llama, a 32768-entry
// SentencePiece vocabulary whose only control tokens are [INST], [/INST], [TOOL_CALLS],
// [AVAILABLE_TOOLS] and [TOOL_RESULTS]. Everything the tekken template emits on top of
// that ([SYSTEM_PROMPT], [MODEL_SETTINGS], [ARGS]) is plain text to these models, which is
// why they need their own template rather than a shared one.
public final class MistralInstructChatTemplate implements ChatTemplate {

    @Override
    public String render(String system, List<Tool> tools, List<Turn> turns) {
        return PromptTemplate.mistralV3(system, tools, turns);
    }

    @Override
    public ToolCallParser.Parsed parse(String generated) {
        return ToolCallParser.parseArray(generated);
    }

    // </s> is an EOG token, so generation normally halts before this matches. Kept as a
    // safety net for quantizations that fail to mark it — the same reason GemmaChatTemplate
    // carries <turn|>.
    @Override
    public List<String> stopSequences() {
        return List.of(PromptTemplate.MISTRAL_EOS);
    }

    @Override
    public List<String> toolCallOpenMarkers() {
        return List.of(ToolCallParser.TOOL_CALLS_MARKER);
    }
}
