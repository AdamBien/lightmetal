package lm.tools.control;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import lm.tools.entity.Tool;

public final class SchemaToGbnf {

    private static final String PRIMITIVES = """
            string  ::= "\\"" ( [^"\\\\] | "\\\\" ["\\\\/bfnrt] | "\\\\u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] )* "\\""
            number  ::= "-"? ("0" | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
            integer ::= "-"? ("0" | [1-9] [0-9]*)
            boolean ::= "true" | "false"
            nullval ::= "null"
            """;

    private static final String TEXT_BLOCK_RULE = """
            text-block ::= "{\\"type\\":\\"text\\",\\"text\\":" string "}"
            """;

    private final StringBuilder rules = new StringBuilder();
    private int counter;

    private SchemaToGbnf() {
    }

    public static String compile(List<Tool> tools) {
        return new SchemaToGbnf().doCompile(tools);
    }

    private String doCompile(List<Tool> tools) {
        if (tools.isEmpty()) {
            return """
                    root ::= text-block
                    """
                    + TEXT_BLOCK_RULE
                    + PRIMITIVES;
        }

        rules.append("""
                root ::= text-block | tool-call
                """);
        rules.append(TEXT_BLOCK_RULE);

        var alternatives = new ArrayList<String>(tools.size());
        for (var i = 0; i < tools.size(); i++) {
            alternatives.add("tool-" + i + "-call");
        }
        rules.append("tool-call ::= ").append(String.join(" | ", alternatives)).append('\n');

        for (var i = 0; i < tools.size(); i++) {
            var tool = tools.get(i);
            var inputRule = compileSchema(tool.inputSchema());
            rules.append("""
                    tool-%d-call ::= "{\\"type\\":\\"tool_use\\",\\"name\\":\\"%s\\",\\"input\\":" %s "}"
                    """.formatted(i, tool.name(), inputRule));
        }

        rules.append(PRIMITIVES);
        return rules.toString();
    }

    private String compileSchema(JSONObject schema) {
        if (schema == null) {
            return "\"{}\"";
        }
        var enumVals = schema.optJSONArray("enum");
        if (enumVals != null && enumVals.length() > 0) {
            return compileEnum(enumVals);
        }
        var type = schema.optString("type", "object");
        return switch (type) {
            case "object" -> compileObject(schema);
            case "array" -> compileArray(schema);
            case "string" -> "string";
            case "integer" -> "integer";
            case "number" -> "number";
            case "boolean" -> "boolean";
            case "null" -> "nullval";
            default -> throw new IllegalArgumentException("unsupported schema type: " + type);
        };
    }

    private String compileObject(JSONObject schema) {
        var ruleName = "obj-" + (counter++);
        var props = schema.optJSONObject("properties");
        if (props == null || props.isEmpty()) {
            rules.append(ruleName).append(" ::= \"{}\"\n");
            return ruleName;
        }
        var required = collectRequired(schema, props);
        rules.append(ruleName).append(" ::= \"{\"");
        for (var i = 0; i < required.size(); i++) {
            var key = required.get(i);
            var valueRule = compileSchema(props.optJSONObject(key));
            if (i > 0) rules.append(" \",\"");
            rules.append(" \"\\\"%s\\\":\" %s".formatted(key, valueRule));
        }
        rules.append(" \"}\"\n");
        return ruleName;
    }

    private String compileArray(JSONObject schema) {
        var ruleName = "arr-" + (counter++);
        var items = schema.optJSONObject("items");
        var itemRule = items == null ? "string" : compileSchema(items);
        rules.append("%s ::= \"[\" (%s (\",\" %s)*)? \"]\"%n".formatted(ruleName, itemRule, itemRule));
        return ruleName;
    }

    private String compileEnum(JSONArray enumVals) {
        var ruleName = "enum-" + (counter++);
        var alternatives = new ArrayList<String>(enumVals.length());
        for (var i = 0; i < enumVals.length(); i++) {
            alternatives.add("\"\\\"%s\\\"\"".formatted(enumVals.optString(i, "")));
        }
        rules.append(ruleName).append(" ::= ").append(String.join(" | ", alternatives)).append('\n');
        return ruleName;
    }

    private static List<String> collectRequired(JSONObject schema, JSONObject props) {
        var required = schema.optJSONArray("required");
        if (required != null && required.length() > 0) {
            var out = new ArrayList<String>(required.length());
            for (var i = 0; i < required.length(); i++) {
                out.add(required.optString(i, ""));
            }
            return out;
        }
        return List.copyOf(props.keySet());
    }
}
