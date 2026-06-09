/// Tools BC — tool / function-calling definitions and the Mistral tool-call
/// wire format.
///
/// ## Responsibility
///
/// Models the tool-calling surface shared by the Anthropic and OpenAI HTTP
/// endpoints, and parses the Mistral-specific
/// `[TOOL_CALLS]<name>[ARGS]{json}` sequence the model emits back into
/// structured records. The HTTP handlers re-serialise those records into
/// the protocol-specific shape (Anthropic `content[].tool_use` vs OpenAI
/// `tool_calls[].function`).
///
/// ## Wire format
///
/// Multiple tool calls in one response are concatenated without a
/// separator. Parsing walks `[TOOL_CALLS]` markers left-to-right, stops at
/// end-of-string or trailing `</s>`, and tolerates malformed JSON by
/// emitting an empty argument object rather than aborting the parse.
package lm.tools;
