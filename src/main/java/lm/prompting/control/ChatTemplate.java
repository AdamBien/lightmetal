package lm.prompting.control;

import java.util.List;

import lm.http.entity.AnthropicMessagesRequest.Turn;
import lm.tools.control.ToolCallParser;
import lm.tools.entity.Tool;

public sealed interface ChatTemplate permits Mistral4ChatTemplate, GemmaChatTemplate {

    String render(String system, List<Tool> tools, List<Turn> turns);

    ToolCallParser.Parsed parse(String generated);

    default List<String> stopSequences() {
        return List.of();
    }
}
