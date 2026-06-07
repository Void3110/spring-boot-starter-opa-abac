package dev.dmitriikonovalov.opaabac.data.hierarchy;

import dev.dmitriikonovalov.opaabac.core.ParentRef;
import java.util.UUID;

/**
 * The label convention shared by the {@code ltree} path-maintainer ({@link AbstractHierarchicalEntity})
 * and the {@link LtreeAncestorResolver} decoder. They MUST agree, so the encode/decode pair lives here.
 *
 * <p>A path is a dotted sequence of labels, one per ancestor, root-first:
 * {@code catalog_<hex>.category_<hex>.product_<hex>}. Each label is {@code <type>_<id>}.
 *
 * <p><b>ltree-legality.</b> Postgres {@code ltree} labels accept only {@code [A-Za-z0-9_]} (and are
 * dot-separated), so a UUID's hyphens are not legal. We therefore encode a {@link UUID} id as its 32 hex
 * digits with the hyphens removed; decoding restores the canonical dashed form. A non-UUID id that already
 * matches {@code [A-Za-z0-9_]} is used verbatim. The leading {@code _} of a label separates the type from
 * the id at the <em>first</em> underscore, so a type never contains {@code _} (our ABAC types —
 * {@code catalog}/{@code category}/{@code product} — do not).
 */
public final class HierarchyLabels {

    private HierarchyLabels() {}

    /** The single label for one resource hop: {@code <type>_<ltree-safe-id>}. */
    public static String label(String type, String id) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("hierarchy label type must be non-blank");
        }
        if (type.indexOf('_') >= 0) {
            throw new IllegalArgumentException("hierarchy label type must not contain '_': " + type);
        }
        return type + "_" + encodeId(id);
    }

    /** The label for a resource's parent reference. */
    public static String label(ParentRef ref) {
        return label(ref.type(), ref.id());
    }

    /** Decode one label back into a {@link ParentRef}. Throws on a malformed label. */
    public static ParentRef decode(String labelToken) {
        if (labelToken == null || labelToken.isBlank()) {
            throw new AncestorResolutionException("empty ltree label in path");
        }
        int sep = labelToken.indexOf('_');
        if (sep <= 0 || sep == labelToken.length() - 1) {
            throw new AncestorResolutionException("malformed ltree label (expected <type>_<id>): " + labelToken);
        }
        String type = labelToken.substring(0, sep);
        String rawId = labelToken.substring(sep + 1);
        return new ParentRef(type, decodeId(rawId));
    }

    /** A UUID id becomes 32 dash-free hex digits; any other id is used verbatim (must be ltree-safe). */
    private static String encodeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("hierarchy label id must be non-blank");
        }
        UUID uuid = tryParseUuid(id);
        if (uuid != null) {
            return id.replace("-", "");
        }
        if (!id.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("non-UUID hierarchy id is not ltree-safe: " + id);
        }
        return id;
    }

    /** Restore a canonical dashed UUID from 32 hex digits; otherwise return the token unchanged. */
    private static String decodeId(String token) {
        if (token.length() == 32 && token.matches("[0-9a-fA-F]{32}")) {
            String dashed = token.substring(0, 8) + "-" + token.substring(8, 12) + "-"
                    + token.substring(12, 16) + "-" + token.substring(16, 20) + "-" + token.substring(20);
            UUID uuid = tryParseUuid(dashed);
            if (uuid == null) {
                throw new AncestorResolutionException("malformed UUID label segment: " + token);
            }
            return uuid.toString();
        }
        return token;
    }

    private static UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
