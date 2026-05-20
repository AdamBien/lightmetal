package lm.prompting.control;

public interface PromptTemplate {

    public static String mistralInstruct(String userPrompt) {
        return "[INST] " + userPrompt + " [/INST]";
    }
}
