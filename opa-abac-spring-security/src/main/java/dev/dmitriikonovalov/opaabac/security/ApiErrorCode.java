package dev.dmitriikonovalov.opaabac.security;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * The contract slot for a machine-stable error code in an RFC-7807 {@code application/problem+json}
 * error body.
 *
 * <p>A consumer branches on {@link #code()} — a stable, typed value — instead of parsing the human
 * {@code detail} string. The library ships {@link LibraryErrorCode} for the failures it raises (and the
 * generic ones); each application ships its <em>own</em> {@code enum} implementing this same interface for
 * its domain failures. (A Java {@code enum} cannot be subclassed, so the shared contract is this
 * interface; the set of codes is open while each code stays typed.)
 *
 * <p>A code carries everything the advice needs to render a problem body: its {@link #status()} (so the
 * advice never re-invents the status at the call site), the wire {@link #code()}, the {@code type}
 * ({@link #problemType()}) and the {@code title} ({@link #title()}). {@link #problemType()} and
 * {@link #title()} have sensible defaults derived from {@link #code()}, so an implementor need only supply
 * {@link #code()} and {@link #status()}.
 */
public interface ApiErrorCode {

    /**
     * The stable, machine-readable wire value a consumer branches on (e.g. {@code "TAG_VALUE_ILLEGAL"}).
     * Conventionally the enum constant name.
     */
    String code();

    /**
     * The HTTP status this failure maps to. Carried on the code so the advice resolves
     * {@code (status, errorCode)} from one source and never re-invents the status at the call site.
     */
    HttpStatus status();

    /**
     * The stable, <strong>relative</strong>, opaque {@code type} identifier for this problem kind
     * (e.g. {@code "/problems/tag-value-illegal"}). It is <em>not</em> dereferenced — there is no hosted
     * registry; it is a stable identifier, not a live docs URL. The default derives a kebab-case path from
     * {@link #code()}.
     */
    default String problemType() {
        return "/problems/" + code().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * A short, status-stable human summary of the problem <em>kind</em> (not the instance). One title per
     * code. The default derives a Title Case phrase from {@link #code()}.
     */
    default String title() {
        String[] words = code().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder(code().length());
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
