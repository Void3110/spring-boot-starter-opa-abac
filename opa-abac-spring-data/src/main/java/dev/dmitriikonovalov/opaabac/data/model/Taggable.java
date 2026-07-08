package dev.dmitriikonovalov.opaabac.data.model;

/**
 * A domain model that carries a set of ABAC {@link ResourceTags}. Implemented by
 * {@link AbstractSecuredEntity}, where the tags back the resource attributes an OPA policy
 * evaluates against. Kept on the <em>secure</em> base only: entities that don't need
 * authorization attributes never pay for a tag column.
 */
public interface Taggable {

    /**
     * The JPA <em>attribute</em> name of the JSONB tags column the data-filter reads
     * ({@code root.get(TAGS_ATTRIBUTE)}). A bring-your-own entity may map any DB <em>column</em> name,
     * but must keep this attribute name — the filter addresses the attribute, never the column. If a
     * consumer ever needs a different global attribute name, promote this to configuration
     * ({@code opa.abac.data.tags-attribute}) rather than widening the SPI.
     */
    String TAGS_ATTRIBUTE = "tags";

    ResourceTags getTags();

    void setTags(ResourceTags tags);
}
