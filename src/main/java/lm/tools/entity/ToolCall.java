package lm.tools.entity;

import org.json.JSONObject;

public record ToolCall(String id, String name, JSONObject input) {

    public static ToolCall of(String name, JSONObject input) {
        return new ToolCall("toolu_" + Long.toHexString(System.nanoTime()), name, input);
    }
}
