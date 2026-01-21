package space.controlnet.minecraftmatrixbridge;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of resolving an event name to a fully-qualified class name (FQCN).
 *
 * <p>This is an algebraic data type (ADT) with three variants:
 * <ul>
 *   <li>{@link Success} - exactly one FQCN matched</li>
 *   <li>{@link Ambiguous} - multiple FQCNs matched (user must specify)</li>
 *   <li>{@link NotFound} - no matching FQCN found</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * ResolveResult result = eventIndex.resolve("ServerChatEvent");
 * if (result instanceof ResolveResult.Success success) {
 *     String fqcn = success.fqcn();
 *     // proceed with fqcn
 * } else if (result instanceof ResolveResult.Ambiguous ambiguous) {
 *     List<String> candidates = ambiguous.candidates();
 *     // ask user to clarify
 * } else if (result instanceof ResolveResult.NotFound) {
 *     // inform user that event was not found
 * }
 * }</pre>
 */
public abstract class ResolveResult {

    private ResolveResult() {
        // Sealed hierarchy - only inner classes can extend
    }

    /**
     * Returns true if this is a {@link Success} result.
     */
    public boolean isSuccess() {
        return this instanceof Success;
    }

    /**
     * Returns true if this is an {@link Ambiguous} result.
     */
    public boolean isAmbiguous() {
        return this instanceof Ambiguous;
    }

    /**
     * Returns true if this is a {@link NotFound} result.
     */
    public boolean isNotFound() {
        return this instanceof NotFound;
    }

    /**
     * Returns the FQCN if this is a {@link Success}, otherwise throws.
     *
     * @throws IllegalStateException if not a Success
     */
    public String getFqcn() {
        throw new IllegalStateException("Not a Success result: " + getClass().getSimpleName());
    }

    /**
     * Returns the candidates if this is an {@link Ambiguous}, otherwise throws.
     *
     * @throws IllegalStateException if not Ambiguous
     */
    public List<String> getCandidates() {
        throw new IllegalStateException("Not an Ambiguous result: " + getClass().getSimpleName());
    }

    // -------------------------------------------------------------------------
    // Static factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a successful resolution result.
     *
     * @param fqcn the fully-qualified class name
     * @return a Success result
     */
    public static Success success(String fqcn) {
        return new Success(fqcn);
    }

    /**
     * Creates an ambiguous resolution result.
     *
     * @param candidates list of candidate FQCNs (should have 2+ entries)
     * @return an Ambiguous result
     */
    public static Ambiguous ambiguous(List<String> candidates) {
        return new Ambiguous(candidates);
    }

    /**
     * Creates a not-found resolution result.
     *
     * @return a NotFound result
     */
    public static NotFound notFound() {
        return NotFound.INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Variant classes
    // -------------------------------------------------------------------------

    /**
     * Successful resolution - exactly one FQCN matched.
     */
    public static final class Success extends ResolveResult {
        private final String fqcn;

        private Success(String fqcn) {
            this.fqcn = Objects.requireNonNull(fqcn, "fqcn");
        }

        /**
         * Returns the resolved fully-qualified class name.
         */
        public String fqcn() {
            return fqcn;
        }

        @Override
        public String getFqcn() {
            return fqcn;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Success success = (Success) o;
            return fqcn.equals(success.fqcn);
        }

        @Override
        public int hashCode() {
            return fqcn.hashCode();
        }

        @Override
        public String toString() {
            return "Success{fqcn='" + fqcn + "'}";
        }
    }

    /**
     * Ambiguous resolution - multiple FQCNs matched the input.
     *
     * <p>The user should be asked to provide a more specific name (e.g., full FQCN).
     */
    public static final class Ambiguous extends ResolveResult {
        private final List<String> candidates;

        private Ambiguous(List<String> candidates) {
            Objects.requireNonNull(candidates, "candidates");
            this.candidates = Collections.unmodifiableList(List.copyOf(candidates));
        }

        /**
         * Returns the list of candidate FQCNs that matched.
         */
        public List<String> candidates() {
            return candidates;
        }

        @Override
        public List<String> getCandidates() {
            return candidates;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Ambiguous ambiguous = (Ambiguous) o;
            return candidates.equals(ambiguous.candidates);
        }

        @Override
        public int hashCode() {
            return candidates.hashCode();
        }

        @Override
        public String toString() {
            return "Ambiguous{candidates=" + candidates + "}";
        }
    }

    /**
     * Not found - no FQCN matched the input.
     */
    public static final class NotFound extends ResolveResult {
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
