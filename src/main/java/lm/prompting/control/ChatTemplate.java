package lm.prompting.control;

import java.util.List;
import java.util.stream.Stream;

import lm.configuration.entity.Token;
import lm.prompting.entity.Turn;
import lm.tools.control.ToolCallParser;
import lm.tools.entity.Tool;

public sealed interface ChatTemplate permits Mistral4ChatTemplate, GemmaChatTemplate {

    String render(String system, List<Tool> tools, List<Turn> turns);

    ToolCallParser.Parsed parse(String generated);

    default List<String> stopSequences() {
        return List.of();
    }

    // Markers that begin a tool call in the model's raw output. Streaming consumers use
    // these to stop emitting visible content the moment a call starts: parse() only
    // reports a call once its CLOSING marker arrives, so until then an in-progress call
    // is indistinguishable from plain text and would otherwise leak into the stream.
    default List<String> toolCallOpenMarkers() {
        return List.of();
    }

    default Stream<Token> tagChannels(Stream<Token> tokens) {
        return tokens;
    }
}
