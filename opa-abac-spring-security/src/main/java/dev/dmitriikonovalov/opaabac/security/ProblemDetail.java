package dev.dmitriikonovalov.opaabac.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * The library's own RFC-7807 {@code application/problem+json} error body.
 *
 * <p>Five standard members ({@code type}, {@code title}, {@code status}, {@code detail}, {@code instance})
 * plus two documented extensions ({@code errorCode}, {@code timestamp}). It is the library's <em>own</em>
 * carrier — deliberately not {@code org.springframework.http.ProblemDetail}, whose untyped
 * {@code properties} map would carry {@code errorCode} untyped and would couple the wire contract to a
 * Spring type. Here {@code errorCode} is a first-class member, and each service's OpenAPI spec declares it
 * as a typed {@code enum}, so the generated client is typed and the vocabulary self-documents.
 *
 * <p>There is <strong>no legacy {@code message} field</strong> — the human, instance-specific text lives
 * in {@code detail} (RFC-7807's own field name).
 *
 * @param type a stable, relative, opaque identifier for the problem kind ({@code /problems/<kebab>})
 * @param title a short, status-stable summary of the problem kind
 * @param status the HTTP status code
 * @param detail the human, instance-specific explanation
 * @param instance the request path that produced the error (correlation)
 * @param errorCode the machine-stable code a consumer branches on
 * @param timestamp when the error was produced (correlation)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String errorCode,
        OffsetDateTime timestamp) {}
