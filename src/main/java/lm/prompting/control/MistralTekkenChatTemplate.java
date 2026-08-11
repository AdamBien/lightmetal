package lm.prompting.control;

import java.util.List;

import lm.prompting.entity.Turn;
import lm.tools.control.ToolCallParser;
import lm.tools.entity.Tool;

// The tekken wire format — [SYSTEM_PROMPT], [AVAILABLE_TOOLS], [MODEL_SETTINGS] and
// [TOOL_CALLS]<name>[ARGS]<json>. Named after the tokenizer family rather than a product
// version because it is shared by every model that ships that vocabulary: arch=mistral3
// (Mistral Medium 3.5), arch=mistral4 (Mistral Small 4 2603 — verified byte-identical
// special-token tables and chat templates), Devstral and Mistral Nemo.
public final class MistralTekkenChatTemplate implements ChatTemplate {

    @Override
    public String render(String system, List<Tool> tools, List<Turn> turns) {
        return PromptTemplate.mistralTekken(system, tools, turns);
    }

    @Override
    public ToolCallParser.Parsed parse(String generated) {
        return ToolCallParser.parse(generated);
    }

    @Override
    public List<String> toolCallOpenMarkers() {
        return List.of(ToolCallParser.TOOL_CALLS_MARKER);
    }
}
