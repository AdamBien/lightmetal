package lm.prompting.control;

import java.util.List;

import lm.prompting.entity.Turn;
import lm.tools.control.ToolCallParser;
import lm.tools.entity.Tool;

public final class Mistral4ChatTemplate implements ChatTemplate {

    @Override
    public String render(String system, List<Tool> tools, List<Turn> turns) {
        return PromptTemplate.mistral4(system, tools, turns);
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
