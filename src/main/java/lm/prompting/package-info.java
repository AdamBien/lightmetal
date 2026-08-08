/// # Prompting
/// > Render conversations into each model family's exact wire format, and
/// > read the model's raw output back into text, thinking, and tool calls.
///
/// ## Boundary
///
/// - `resolve-family` — bind a GGUF's model name to its chat template
/// - `render-prompt` — system + tools + turns → the exact prompt string the
///   model expects
/// - `parse-output` — raw generated text → visible text and structured tool
///   calls
/// - `tag-channels` — split a token stream into thought vs final channels
/// - `stop-sequences` — the family's generation stop markers
///
/// ## Requirements
///
/// ### R1: Family resolution
///
/// - R1.1 — When a model name is resolved, the BC shall match family
///   fragments case-insensitively with spaces and underscores collapsed to
///   hyphens.
/// - R1.2 — The BC shall try most-specific fragments first. _(why:
///   "nemotron" contains "nemo"; without ordering, a Nemotron GGUF would
///   silently render with the Mistral template)_
/// - R1.3 — If no fragment matches, then the BC shall report no family
///   rather than fall back to a generic template. _(why: a silently wrong
///   template produces garbage that looks like a model bug; adding a model
///   is a one-line registry entry)_
///
/// ### R2: Rendering
///
/// - R2.1 — When a conversation is rendered, the BC shall emit the family's
///   wire format for the system prompt, each declared tool, and each turn
///   kind (user text, assistant text, assistant tool calls, tool results).
/// - R2.2 — The BC shall end the rendered prompt so the model continues as
///   the assistant.
/// - R2.3 — While a family separates reasoning from answers, the BC shall
///   drop the reasoning from assistant turns preceding the newest user
///   message. _(why: replayed thinking burns context and steers the model
///   into re-reasoning old turns)_
/// - R2.4 — While a family retains reasoning inside the turn in flight, the
///   BC shall keep the reasoning of assistant turns that follow the newest
///   user message. _(why: a tool-calling loop is one turn — dropping the
///   reasoning between calls makes the model re-derive why it called the
///   tool)_
///
/// ### R3: Output parsing
///
/// - R3.1 — When generated output is parsed, the BC shall strip thinking
///   sections and trailing turn markers from the visible text.
/// - R3.2 — When the output encodes tool calls, the BC shall recover them
///   with their arguments.
/// - R3.3 — The BC shall expose the family's stop sequences and tool-call
///   open markers, so drivers can stop decoding and hold back streamed
///   content.
///
/// ### R4: Channel tagging
///
/// - R4.1 — While a family prefills the thinking marker, the BC shall tag
///   emitted tokens as thought until the close marker, then as final.
/// - R4.2 — While a marker is split across token pieces, the BC shall buffer
///   the ambiguous tail until disambiguated, and flush leftovers at stream
///   end.
/// - R4.3 — While a family opens its own thinking marker, the BC shall tag
///   emitted tokens as final until that marker arrives and as thought from
///   there to the close marker, emitting neither marker. _(why: a bare
///   generation prompt puts the open marker in the stream rather than the
///   prompt, so a prefilling reader would tag the tag itself as thought)_
///
/// ## Entities
///
/// - Turn (UserText, AssistantText, AssistantToolCalls, UserToolResults),
///   ToolResult
///
/// ## Out of scope
///
/// - The Mistral `[TOOL_CALLS]` wire-format parsing itself — tools BC; this
///   BC only routes to it.
/// - Reading GGUF metadata — inspection BC.
package lm.prompting;
