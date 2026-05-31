package dev.dmitriikonovalov.opaabac.core;

import java.util.Map;

/**
 * A domain object that can be authorized: exposes its ABAC resource type, id, and attributes
 * so the framework can build an {@link AbacContext.Resource} without coupling to the domain type.
 */
public interface AbacDataObject {

    String abacResourceType();

    String abacResourceId();

    default Map<String, Object> abacAttributes() {
        return Map.of();
    }
}
