package space.controlnet.minecraftmatrixbridge;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of resolving an event name to a fully-qualified class name (FQCN).
 *
 * <p>This is a sealed interface with three variants:
 * <ul>
 *   <li>{@link Success} - exactly one FQCN matched</li>
 *   <li>{@link Ambiguous} - multiple FQCNs matched (user must specify)</li>
 *   <li>{@link NotFound} - no matching FQCN found</li>
 * </ul>
 */
public sealed interface ResolveResult permits ResolveResult.Success, ResolveResult.Ambiguous, ResolveResult.NotFound {

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default boolean isAmbiguous() {
        return this instanceof Ambiguous;
    }

    default boolean isNotFound() {
        return this instanceof NotFound;
    }

    default String getFqcn() {
        throw new IllegalStateException("Not a Success result: " + getClass().getSimpleName());
    }

    default List<String> getCandidates() {
        throw new IllegalStateException("Not an Ambiguous result: " + getClass().getSimpleName());
    }

    static Success success(String fqcn) {
        return new Success(fqcn);
    }

    static Ambiguous ambiguous(List<String> candidates) {
        return new Ambiguous(candidates);
    }

    static NotFound notFound() {
        return NotFound.INSTANCE;
    }

    record Success(String fqcn) implements ResolveResult {
        public Success {
            Objects.requireNonNull(fqcn, "fqcn");
        }

        @Override
        public String getFqcn() {
            return fqcn;
        }
    }

    record Ambiguous(List<String> candidates) implements ResolveResult {
        public Ambiguous {
            Objects.requireNonNull(candidates, "candidates");
            candidates = Collections.unmodifiableList(List.copyOf(candidates));
        }

        @Override
        public List<String> getCandidates() {
            return candidates;
        }
    }

    final class NotFound implements ResolveResult {
        private static final NotFound INSTANCE = new NotFound();

        private NotFound() {
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof NotFound;
        }

        @Override
        public int hashCode() {
            return NotFound.class.hashCode();
        }

        @Override
        public String toString() {
            return "NotFound{}";
        }
    }
}
