package lm.tools.control;

import java.util.List;
import java.util.stream.Collectors;

import lm.tools.entity.Tool;

public interface ToolCatalogue {

    String PRELUDE = """
            You have access to the following tools. Respond ONLY with a single JSON object matching one of these two shapes:
              {"type":"text","text":"<answer>"}
              {"type":"tool_use","name":"<tool_name>","input":{...}}

            Available tools:
            """;

    String EPILOGUE = "\nPick tool_use when a tool can answer the request; otherwise pick text.";

    static String renderSystemPrelude(String userSystem, List<Tool> tools) {
        if (tools.isEmpty()) {
            return userSystem == null ? "" : userSystem;
        }
        var header = (userSystem == null || userSystem.isBlank()) ? "" : userSystem.strip() + "\n\n";
        var listing = tools.stream()
                .map(ToolCatalogue::describe)
                .collect(Collectors.joining("\n"));
        return header + PRELUDE + listing + EPILOGUE;
    }

    private static String describe(Tool tool) {
        var heading = tool.description().isBlank()
                ? "- " + tool.name()
                : "- %s: %s".formatted(tool.name(), tool.description());
        return tool.inputSchema() == null
                ? heading
                : heading + "\n  input_schema: " + tool.inputSchema().toString();
    }
}
