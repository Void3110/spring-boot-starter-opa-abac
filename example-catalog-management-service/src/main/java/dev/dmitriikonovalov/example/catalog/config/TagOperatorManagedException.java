package dev.dmitriikonovalov.example.catalog.config;

/**
 * Thrown when a <b>public</b> tag write would change the presence-or-value of an operator-managed key —
 * an assign (absent → present), a re-value, or a strip (present → absent). → 409 Conflict, with the
 * catalog code {@code TAG_OPERATOR_MANAGED}.
 *
 * <p>Distinct from {@code TAG_DEFINITION_IMMUTABLE} on purpose: that one names protection of a
 * <em>definition's</em> shape, while this names protection of a key's <em>values</em> on a resource. They
 * fire on different requests for different reasons, and a caller (or an e2e cell) must be able to tell
 * which guard answered.
 *
 * <p>The state is a conflict rather than a validation failure: the submitted map is perfectly well-formed
 * and the key is perfectly legal — it is the <em>current</em> state of the resource that the caller is not
 * permitted to move. Echoing the existing value is therefore fine and does not throw.
 */
public class TagOperatorManagedException extends RuntimeException {

    public TagOperatorManagedException(String message) {
        super(message);
    }
}
