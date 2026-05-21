package lm.tools.entity;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

public record Tool(String name, String description, JSONObject inputSchema) {

    public static List<Tool> from(JSONArray tools) {
        if (tools == null) return List.of();
        var out = new ArrayList<Tool>(tools.length());
        for (var i = 0; i < tools.length(); i++) {
            var t = tools.optJSONObject(i);
            if (t == null) continue;
            var name = t.optString("name", "");
            if (name.isEmpty()) continue;
            out.add(new Tool(
                    name,
                    t.optString("description", ""),
                    t.optJSONObject("input_schema")));
        }
        return out;
    }
}
