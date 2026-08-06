package dev.dmitriikonovalov.opaabac.core;

import tools.jackson.databind.JsonNode;
import dev.dmitriikonovalov.opaabac.core.Condition.Operator;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates an OPA Compile API response ({@code POST /v1/compile}) into a neutral {@link PartialResult}
 * residual (DNF), so {@code opa-abac-core} exposes no OPA AST to the data-filtering layer.
 *
 * <h2>The result shape (verified against OPA 1.x)</h2>
 * <pre>
 *   {"result": {}}                      → query is UNSATISFIABLE for every row  → DENY_ALL
 *   {"result": {"queries": [[]]}}       → query is UNCONDITIONALLY true         → ALLOW_ALL
 *   {"result": {"queries": [[expr,…]]}} → residual conditions (one query per disjunct, DNF) → CONDITIONAL
 * </pre>
 *
 * <p><strong>The empty-result boundary is the load-bearing fail-closed property.</strong> OPA returns the
 * same empty {@code {"result": {}}} for an <em>unsatisfiable</em> query as for a missing result, so it is
 * mapped to {@code DENY_ALL} — never {@code ALLOW_ALL}. {@code ALLOW_ALL} is produced <em>only</em> by an
 * explicit query whose conjunction is empty (a satisfiable, condition-free residual). An absent/ambiguous
 * compile output therefore denies.
 *
 * <h2>Expression shape</h2>
 * Each query is a list of expressions; the disjunction of queries is the DNF, the expressions within a
 * query are its conjunction. An expression is {@code {"terms": [<opRef>, <operand>, <operand>]}} where the
 * operator is a built-in var ({@code eq}/{@code equal}, {@code neq}, {@code internal.member_2}). One
 * operand is a {@code ref} into {@code input.resource.…} (the row attribute), the other a literal.
 *
 * <h2>Unsupported → deny</h2>
 * Any expression whose operator is outside the closed set, that references something other than
 * {@code input.resource.*}, or whose operand is not a recognized literal, makes the <em>whole</em>
 * residual {@link PartialResult#denyAll()} — unless its entire disjunct is folded away by a
 * foreign-type binding (next section), in which case the discarded disjunct poisons nothing.
 * Narrow-but-correct beats wide-but-wrong.
 *
 * <h2>Foreign-type disjuncts fold away (multi-type roles)</h2>
 * A role granting permissions on several resource types compiles to a DNF with disjuncts for
 * <em>every</em> granted type, each guarded by {@code eq(<type>, input.resource.type)}. Every row this
 * residual is applied to has {@code type == resourceType} (the same premise that makes the matching
 * binding a tautology), so a disjunct bound to a <em>different</em> definite type is identically false
 * here — it is <strong>dropped</strong>, not treated as unsupported. Dropping a disjunct only ever
 * narrows the OR, so the fold is fail-closed by construction. Only the exact shape
 * {@code EQ(type, <definite STRING literal ≠ resourceType>)} folds — the discriminator the parser
 * compares against is a string, and the parser only folds what it can positively prove contradictory.
 * Any other constraint on {@code type} (negated, non-{@code EQ}, a non-string or unrecognized
 * literal, a null parser type) still poisons the residual as before.
 *
 * <p><strong>If every disjunct folds away, the residual is {@link PartialResult#unsupported()}, not a
 * clean deny.</strong> An all-foreign DNF means the compiled {@code filter} rule says nothing about
 * this type at all — but the full policy still might: a role with no direct grant on this type may
 * admit rows through policy-side <em>inheritance</em> (an inheritable ancestor grant the {@code filter}
 * entrypoint deliberately does not model), and a type-vocabulary drift between the application's
 * resource types and the policy's would also look exactly like this. Both need the caller's exact,
 * hierarchy-aware batch re-check — the pre-fold behavior for this shape — not a definitive empty list
 * that would diverge from what a single-resource {@code allow} decides.
 */
final class CompileResponseParser {

    // OPA AST term field/type names, named once so the parser reads against the wire shape.
    private static final String FIELD_VALUE = "value";
    private static final String TYPE_STRING = "string";

    /** The known resource type for the query; an {@code eq} on {@code input.resource.type} against it is a tautology. */
    private final String resourceType;

    CompileResponseParser(String resourceType) {
        this.resourceType = resourceType;
    }

    /**
     * @param root the parsed Compile API response body ({@code {"result": …}})
     * @return the residual; {@link PartialResult#denyAll()} on any unsupported/absent shape
     */
    PartialResult parse(JsonNode root) {
        if (root == null) {
            return PartialResult.denyAll();
        }
        JsonNode result = root.get("result");
        // {"result": {}} or missing/empty → unsatisfiable → DENY_ALL (fail-closed).
        if (result == null || result.isEmpty()) {
            return PartialResult.denyAll();
        }
        JsonNode queries = result.get("queries");
        if (queries == null || !queries.isArray() || queries.isEmpty()) {
            // result present but no queries → no satisfiable disjunct → DENY_ALL.
            return PartialResult.denyAll();
        }

        List<Conjunction> disjuncts = new ArrayList<>();
        boolean folded = false;
        for (JsonNode query : queries) {
            ConjunctionResult cj = parseQuery(query);
            if (cj.unsupported()) {
                // A single unsupported expression poisons the whole residual → fail closed, but FLAGGED
                // not-fully-SQL so a caller with the allowlist on can batch-recheck instead of returning empty.
                return PartialResult.unsupported();
            }
            if (cj.alwaysFalse()) {
                // A disjunct bound to a DIFFERENT resource type can never match a row of this type —
                // dropping it narrows the OR (fail-closed), it does not poison the residual.
                folded = true;
                continue;
            }
            if (cj.alwaysTrue()) {
                // An empty/tautological conjunction means this disjunct holds for every row → ALLOW_ALL.
                return PartialResult.allowAll();
            }
            disjuncts.add(new Conjunction(cj.conditions()));
        }
        if (disjuncts.isEmpty() && folded) {
            // EVERY disjunct was foreign-type: the compiled filter says nothing about this type, but the
            // full policy might (inheritance the filter rule does not model; or a type-vocabulary drift).
            // Not-fully-SQL → the caller's exact, hierarchy-aware batch re-check — never a definitive
            // empty list that could diverge from the single-resource decision. See the class doc.
            return PartialResult.unsupported();
        }
        return PartialResult.conditional(disjuncts);
    }

    private ConjunctionResult parseQuery(JsonNode query) {
        if (query == null || !query.isArray()) {
            return ConjunctionResult.notSupported();
        }
        List<Condition> conditions = new ArrayList<>();
        boolean sawUnsupported = false;
        for (JsonNode expr : query) {
            ExprResult er = parseExpression(expr);
            if (er.contradiction()) {
                // X AND false = false regardless of the siblings — even unsupported ones — so a
                // foreign-type binding anywhere makes the whole disjunct droppable.
                return ConjunctionResult.asAlwaysFalse();
            }
            if (er.unsupported()) {
                // Keep scanning: a later foreign-type binding would still fold this disjunct away.
                sawUnsupported = true;
                continue;
            }
            // er.tautology() → drop (e.g. the resource-type binding); contributes no condition.
            if (er.condition() != null) {
                conditions.add(er.condition());
            }
        }
        if (sawUnsupported) {
            return ConjunctionResult.notSupported();
        }
        // No conditions survived (all tautologies / empty query) → the disjunct is unconditionally true.
        return new ConjunctionResult(conditions, conditions.isEmpty(), false, false);
    }

    /** Parse one expression {@code {"terms": [opRef, operand, operand]}}. */
    private ExprResult parseExpression(JsonNode expr) {
        if (expr == null) {
            return ExprResult.notSupported();
        }
        // A negated expression carries "negated": true — fold it into the operator (eq→neq), else unsupported.
        boolean negated = expr.path("negated").asBoolean(false);
        JsonNode terms = expr.get("terms");
        if (terms == null || !terms.isArray() || terms.size() != 3) {
            // Only binary operator expressions are supported (op, lhs, rhs).
            return ExprResult.notSupported();
        }
        String op = operatorName(terms.get(0));
        if (op == null) {
            return ExprResult.notSupported();
        }

        JsonNode first = terms.get(1);
        JsonNode second = terms.get(2);
        // Identify which operand is the resource ref and which is the literal, remembering the SIDE
        // (the resource ref being the LEFT vs RIGHT operand changes membership semantics).
        String refPath = resourceRefPath(first);
        boolean refIsLeft = refPath != null;
        JsonNode literal = second;
        if (refPath == null) {
            refPath = resourceRefPath(second);
            literal = first;
        }
        if (refPath == null) {
            return ExprResult.notSupported(); // neither operand references input.resource.*
        }

        Operator operator = mapOperator(op, refIsLeft, negated);
        if (operator == null) {
            return ExprResult.notSupported();
        }

        if ("type".equals(refPath)) {
            return typeBindingResult(operator, literal);
        }

        Object value = (operator == Operator.IN) ? literalList(literal) : literalValue(literal);
        if (value == null) {
            return ExprResult.notSupported();
        }
        String path = mapPath(refPath);
        return ExprResult.of(new Condition(path, operator, value));
    }

    /**
     * Classify a constraint on {@code input.resource.type}. The binding {@code eq} against the known
     * type is a tautology (dropped from its conjunction); the same binding against a <em>different</em>
     * definite <em>string</em> literal is a contradiction — every row this residual is applied to has
     * {@code type == resourceType}, so the whole disjunct folds away (see the class doc). Anything else
     * (an unknown parser type, a non-string/unrecognized literal, a non-{@code EQ} operator) is not
     * something the parser will vouch for → unsupported.
     */
    private ExprResult typeBindingResult(Operator operator, JsonNode literal) {
        Object lit = literalValue(literal);
        if (operator == Operator.EQ && resourceType != null && lit instanceof String) {
            return resourceType.equals(lit) ? ExprResult.asTautology() : ExprResult.asContradiction();
        }
        return ExprResult.notSupported();
    }

    /** The operator var name from the terms[0] ref ({@code [{var: "eq"}]} or {@code [{var:"internal"},{string:"member_2"}]}). */
    private static String operatorName(JsonNode opTerm) {
        if (opTerm == null || !"ref".equals(opTerm.path("type").asString())) {
            return null;
        }
        JsonNode value = opTerm.get(FIELD_VALUE);
        if (value == null || !value.isArray() || value.isEmpty()) {
            return null;
        }
        StringBuilder name = new StringBuilder();
        for (JsonNode part : value) {
            String t = part.path("type").asString();
            if ("var".equals(t) || TYPE_STRING.equals(t)) {
                if (!name.isEmpty()) {
                    name.append('.');
                }
                name.append(part.path(FIELD_VALUE).asString());
            } else {
                return null;
            }
        }
        return name.toString();
    }

    /**
     * Map an OPA operator var to the closed operator set, honoring operand side and negation. Returns null
     * if unsupported.
     *
     * <p>{@code internal.member_2} (the lowering of {@code x in y}) is the subtle case — the side decides:
     * <ul>
     *   <li>{@code member_2(resourceRef, {literals})} — "the row's value is one of these" → {@code IN};</li>
     *   <li>{@code member_2(literal, resourceRef)} — "this literal is in the row's value(s)" → {@code CONTAINS}
     *       (scalar-or-array membership, so the SQL {@code ?} operator agrees with the single-decision
     *       scalar-as-singleton-set normalize).</li>
     * </ul>
     * A negated membership is not representable in the closed set → unsupported.
     */
    private static Operator mapOperator(String op, boolean refIsLeft, boolean negated) {
        return switch (op) {
            case "eq", "equal" -> negated ? Operator.NEQ : Operator.EQ;
            case "neq" -> negated ? Operator.EQ : Operator.NEQ;
            case "internal.member_2" -> {
                if (negated) {
                    yield null; // a negated membership is not representable in the closed operator set
                }
                yield refIsLeft ? Operator.IN : Operator.CONTAINS;
            }
            default -> null;
        };
    }

    /**
     * If {@code term} is a ref into {@code input.resource.…}, return the dotted path <em>after</em>
     * {@code resource} (e.g. {@code "attributes.region"}, {@code "type"}, {@code "id"}); else null.
     */
    private static String resourceRefPath(JsonNode term) {
        if (term == null || !"ref".equals(term.path("type").asString())) {
            return null;
        }
        JsonNode value = term.get(FIELD_VALUE);
        if (value == null || !value.isArray() || value.size() < 3) {
            return null;
        }
        if (!"var".equals(value.get(0).path("type").asString())
                || !"input".equals(value.get(0).path(FIELD_VALUE).asString())) {
            return null;
        }
        if (!"resource".equals(value.get(1).path(FIELD_VALUE).asString())) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (int i = 2; i < value.size(); i++) {
            JsonNode part = value.get(i);
            if (!TYPE_STRING.equals(part.path("type").asString())) {
                return null; // a dynamic/var key (not a literal path) is not SQL-expressible
            }
            if (!path.isEmpty()) {
                path.append('.');
            }
            path.append(part.path(FIELD_VALUE).asString());
        }
        return path.isEmpty() ? null : path.toString();
    }

    /**
     * Map a resource attribute path to a {@link Condition} path. {@code attributes.<key>} (the OPA tag
     * map) becomes {@code tags.<key>}; an intrinsic like {@code id} stays as-is (mapped to a column later).
     */
    private static String mapPath(String resourcePath) {
        if (resourcePath.startsWith("attributes.")) {
            return "tags." + resourcePath.substring("attributes.".length());
        }
        return resourcePath;
    }

    private static Object literalValue(JsonNode literal) {
        if (literal == null) {
            return null;
        }
        return switch (literal.path("type").asString()) {
            case TYPE_STRING -> literal.path(FIELD_VALUE).asString();
            case "number" -> literal.path(FIELD_VALUE).isIntegralNumber()
                    ? (Object) literal.path(FIELD_VALUE).asLong()
                    : (Object) literal.path(FIELD_VALUE).asDouble();
            case "boolean" -> literal.path(FIELD_VALUE).asBoolean();
            default -> null;
        };
    }

    /** A set/array literal for IN: {@code {"type":"set","value":[{string},…]}} or an array term. */
    private static List<Object> literalList(JsonNode literal) {
        if (literal == null) {
            return null;
        }
        String type = literal.path("type").asString();
        if (!"set".equals(type) && !"array".equals(type)) {
            return null;
        }
        JsonNode value = literal.get(FIELD_VALUE);
        if (value == null || !value.isArray()) {
            return null;
        }
        List<Object> out = new ArrayList<>();
        for (JsonNode element : value) {
            Object v = literalValue(element);
            if (v == null) {
                return null; // a non-literal element → unsupported
            }
            out.add(v);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Outcome of parsing one expression: a condition, a dropped tautology, a contradiction (a
     * foreign-type binding — folds its whole disjunct away), or unsupported.
     */
    private record ExprResult(Condition condition, boolean tautology, boolean contradiction, boolean unsupported) {
        static ExprResult of(Condition c) {
            return new ExprResult(c, false, false, false);
        }

        static ExprResult asTautology() {
            return new ExprResult(null, true, false, false);
        }

        static ExprResult asContradiction() {
            return new ExprResult(null, false, true, false);
        }

        static ExprResult notSupported() {
            return new ExprResult(null, false, false, true);
        }
    }

    /**
     * Outcome of parsing one query (conjunction): conditions, "always true", "always false" (a
     * foreign-type disjunct — dropped from the DNF), or unsupported.
     */
    private record ConjunctionResult(
            List<Condition> conditions, boolean alwaysTrue, boolean alwaysFalse, boolean unsupported) {
        static ConjunctionResult notSupported() {
            return new ConjunctionResult(List.of(), false, false, true);
        }

        static ConjunctionResult asAlwaysFalse() {
            return new ConjunctionResult(List.of(), false, true, false);
        }
    }
}
