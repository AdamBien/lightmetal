/// # Tools
/// > Model the tool-calling surface and parse the Mistral tool-call wire
/// > format back into structured calls.
///
/// ## Boundary
///
/// - `define-tools` — protocol JSON tool declarations → tool definitions
/// - `parse-tool-calls` — raw generated text → plain text, or leading text
///   plus structured tool calls
///
/// ## Requirements
///
/// ### R1: Tool definitions
///
/// - R1.1 — When tool declarations are read, the BC shall keep each entry's
///   name, description, and input schema.
/// - R1.2 — If a declaration lacks a name or is malformed, then the BC shall
///   skip it rather than fail.
/// - R1.3 — If no declarations are supplied, then the BC shall return an
///   empty list.
///
/// ### R2: Tool-call parsing
///
/// - R2.1 — When the output contains no call marker, the BC shall return the
///   stripped text.
/// - R2.2 — When the output encodes calls
///   (`[TOOL_CALLS]<name>[ARGS]{json}`), the BC shall return each call with
///   a fresh id, plus any text preceding the first marker.
/// - R2.3 — When several calls are concatenated, the BC shall walk the
///   markers left-to-right and recover all of them. _(why: Mistral emits
///   multiple calls with no separator)_
/// - R2.4 — If a call's arguments are malformed JSON, then the BC shall keep
///   the call with empty arguments rather than abort the parse.
/// - R2.5 — If a call lacks a name, then the BC shall drop that call.
/// - R2.6 — The BC shall ignore trailing end-of-sequence markers.
/// - R2.7 — When the output encodes calls as one JSON array
///   (`[TOOL_CALLS] [{"name": …, "arguments": …, "id": …}]`), the BC shall
///   recover every entry. _(why: the Mistral Instruct line has no [ARGS]
///   token — its whole call list is a single array)_
/// - R2.8 — If the array form arrives without its marker, then the BC shall
///   still recover the calls, provided every entry is an object carrying a
///   name. _(why: a model given an unfamiliar prompt emits the payload as
///   plain text; the guard keeps an ordinary JSON array in an answer from
///   becoming phantom calls)_
/// - R2.9 — When a call's arguments arrive as a JSON string rather than an
///   object, the BC shall parse them.
///
/// ## Entities
///
/// - Tool, ToolCall
///
/// ## Out of scope
///
/// - Re-serialising calls into protocol shapes (Anthropic tool-use blocks
///   vs OpenAI function calls) — http BC.
/// - Deciding which template emits the wire format — prompting BC.
package lm.tools;
