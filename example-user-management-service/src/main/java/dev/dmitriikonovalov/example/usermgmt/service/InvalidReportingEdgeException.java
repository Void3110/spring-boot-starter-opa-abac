package dev.dmitriikonovalov.example.usermgmt.service;

/**
 * A reporting edge was rejected <b>on write</b> because it is structurally illegal: a self-edge
 * ({@code a → a}) or an edge that would close a cycle in the reporting relation. Mapped to
 * {@code 422 REPORTING_EDGE_INVALID} by {@code ApiExceptionHandler}.
 *
 * <p>Rejecting on write is the primary guard; the read-time derivation additionally fails
 * <b>closed to empty</b> on a cycle it nevertheless finds (ADR 0029 §1), because a partial closure is
 * indistinguishable from a correct smaller one.
 */
public class InvalidReportingEdgeException extends RuntimeException {

    public InvalidReportingEdgeException(String message) {
        super(message);
    }
}
