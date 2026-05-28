import java.util.ServiceLoader;
import java.util.function.BinaryOperator;

import lm.generation.boundary.LightMetalProvider;

void main() {
    testSpiDiscovery();
    testReturnsLightMetalProviderInstance();
    IO.println("[ok] LightMetalProvider tests");
}

void testSpiDiscovery() {
    var found = false;
    for (var op : ServiceLoader.load(BinaryOperator.class)) {
        if (op instanceof LightMetalProvider) {
            found = true;
            break;
        }
    }
    if (!found)
        throw new AssertionError(
                "LightMetalProvider not discoverable via ServiceLoader<BinaryOperator> — "
                        + "check META-INF/services/java.util.function.BinaryOperator is on the classpath");
}

void testReturnsLightMetalProviderInstance() {
    var provider = ServiceLoader.load(BinaryOperator.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(LightMetalProvider.class::isInstance)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no LightMetalProvider registered"));
    if (!(provider instanceof BinaryOperator<?>))
        throw new AssertionError("registered provider does not implement BinaryOperator");
}
