package dev.dmitriikonovalov.opaabac.data.model;

/**
 * A domain model that carries a set of ABAC {@link ResourceTags}. Implemented by
 * {@link AbstractSecuredEntity}, where the tags back the resource attributes an OPA policy
 * evaluates against. Kept on the <em>secure</em> base only: entities that don't need
 * authorization attributes never pay for a tag column.
 */
public interface Taggable {

    ResourceTags getTags();

    void setTags(ResourceTags tags);
}
