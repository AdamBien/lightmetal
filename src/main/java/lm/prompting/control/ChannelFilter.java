package lm.prompting.control;

import java.util.ArrayList;
import java.util.List;

import lm.configuration.entity.Token;

// Tags streamed tokens with their channel. Thinking families come in two shapes:
//   - prefilled (Nemotron, Gemma) — the generation prompt opens the thought, so the
//     model starts inside it and only ever emits the close marker.
//   - self-opening (Mellum) — the model writes the open marker itself, so the stream
//     starts on the final channel and switches once that marker arrives.
// Either marker may be split across token pieces: the trailing bytes that could be a
// partial match stay buffered until disambiguated, and flush() emits the leftovers
// when the stream ends.
final class ChannelFilter {

    private final String openMarker;
    private final String closeMarker;
    private final StringBuilder pending = new StringBuilder();
    private String channel;
    private int lastId;

    // Prefilled families: no open marker ever reaches the stream.
    ChannelFilter(String closeMarker, boolean enableThinking) {
        this(null, closeMarker, enableThinking);
    }

    ChannelFilter(String openMarker, String closeMarker, boolean enableThinking) {
        this.openMarker = openMarker;
        this.closeMarker = closeMarker;
        // Only a prefilling family starts mid-thought; a self-opening one has not
        // said anything yet, so it starts final even when thinking is enabled.
        this.channel = enableThinking && openMarker == null ? "thought" : "final";
    }

    List<Token> consume(Token in) {
        lastId = in.id();
        pending.append(in.text());
        var out = new ArrayList<Token>();
        while (true) {
            var marker = activeMarker();
            var idx = pending.indexOf(marker);
            if (idx >= 0) {
                if (idx > 0) {
                    out.add(new Token(in.id(), pending.substring(0, idx), channel));
                }
                pending.delete(0, idx + marker.length());
                channel = marker.equals(openMarker) ? "thought" : "final";
                continue;
            }
            var safeLen = pending.length() - (marker.length() - 1);
            if (safeLen > 0) {
                out.add(new Token(in.id(), pending.substring(0, safeLen), channel));
                pending.delete(0, safeLen);
            }
            return out;
        }
    }

    // Inside a thought the only exit is the close marker. On the final channel a
    // self-opening family can still enter thinking, while a prefilling one keeps
    // watching for the close — unchanged from single-marker behaviour.
    private String activeMarker() {
        return "thought".equals(channel) || openMarker == null ? closeMarker : openMarker;
    }

    List<Token> flush() {
        if (pending.isEmpty()) return List.of();
        var out = List.of(new Token(lastId, pending.toString(), channel));
        pending.setLength(0);
        return out;
    }
}
