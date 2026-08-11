import lm.backend.control.RepeatGuard;

void main() {
    testFiresAtTheLimit();
    testResetsOnADifferentToken();
    testDisabled();

    IO.println("[ok] repeat guard");
}

void testFiresAtTheLimit() {
    var guard = new RepeatGuard(4);
    for (var i = 1; i < 4; i++) {
        if (guard.degenerate(31))
            throw new AssertionError("fired after " + i + " repetitions, limit is 4");
    }
    if (!guard.degenerate(31))
        throw new AssertionError("expected the 4th repetition to fire");
    if (guard.run() != 4)
        throw new AssertionError("expected run=4, got " + guard.run());
}

// A run is consecutive: any other token resets it, so alternating output never trips.
void testResetsOnADifferentToken() {
    var guard = new RepeatGuard(3);
    for (var i = 0; i < 20; i++) {
        if (guard.degenerate(i % 2 == 0 ? 31 : 32))
            throw new AssertionError("alternating tokens must not fire, tripped at i=" + i);
    }
    guard.degenerate(7);
    guard.degenerate(7);
    if (!guard.degenerate(7))
        throw new AssertionError("a fresh run of 3 must still fire");
}

void testDisabled() {
    for (var limit : new int[]{0, -1}) {
        var guard = new RepeatGuard(limit);
        for (var i = 0; i < 1000; i++) {
            if (guard.degenerate(31))
                throw new AssertionError("limit " + limit + " must disable the guard");
        }
    }
}
