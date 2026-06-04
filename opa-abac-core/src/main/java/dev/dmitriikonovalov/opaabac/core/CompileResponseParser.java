package dev.dmitriikonovalov.opaabac.core;

import com.fasterxml.jackson.databind.JsonNode;
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
 * residual {@link PartialResult#denyAll()}. Narrow-but-correct beats wide-but-wrong.
 */
final class CompileResponseParser {

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
        for (JsonNode query : queries) {
            ConjunctionResult cj = parseQuery(query);
            if (cj.unsupported()) {
                // A single unsupported expression poisons the whole residual → fail closed.
                return PartialResult.denyAll();
            }
            if (cj.alwaysTrue()) {
                // An empty/tautological conjunction means this disjunct holds for every row → ALLOW_ALL.
                return PartialResult.allowAll();
            }
            disjuncts.add(new Conjunction(cj.conditions()));
        }
        return PartialResult.conditional(disjuncts);
    }

    private ConjunctionResult parseQuery(JsonNode query) {
        if (query == null || !query.isArray()) {
            return ConjunctionResult.notSupported();
        }
        List<Condition> conditions = new ArrayList<>();
        for (JsonNode expr : query) {
            ExprResult er = parseExpression(expr);
            if (er.unsupported()) {
                return ConjunctionResult.notSupported();
            }
            // er.tautology() → drop (e.g. the resource-type binding); contributes no condition.
            if (er.condition() != null) {
                conditions.add(er.condition());
            }
        }
        // No conditions survived (all tautologies / empty query) → the disjunct is unconditionally true.
        return new ConjunctionResult(conditions, conditions.isEmpty(), false);
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

        JsonNode a = terms.get(1);
        JsonNode b = terms.get(2);
        // Identify which operand is the resource ref and which is the literal.
        String refPath = resourceRefPath(a);
        JsonNode literal = b;
        if (refPath == null) {
            refPath = resourceRefPath(b);
            literal = a;
        }
        if (refPath == null) {
            return ExprResult.notSupported(); // neither operand references input.resource.*
        }

        Operator base = mapOperator(op);
        if (base == null) {
            return ExprResult.notSupported();
        }
        Operator operator = negated ? negate(base) : base;
        if (operator == null) {
            return ExprResult.notSupported(); // a negation we can't represent
        }

        // The resource-type binding (eq on input.resource.type against the known type) is a tautology here.
        if ("type".equals(refPath)) {
            Object lit = literalValue(literal);
            if (operator == Operator.EQ && resourceType != null && resourceType.equals(lit)) {
                return ExprResult.asTautology();
            }
            return ExprResult.notSupported(); // any other constraint on type is not SQL-expressible safely
        }

        Object value = (operator == Operator.IN) ? literalList(literal) : literalValue(literal);
        if (value == null) {
            return ExprResult.notSupported();
        }
        String path = mapPath(refPath);
        return ExprResult.of(new Condition(path, operator, value));
    }

    /** The operator var name from the terms[0] ref ({@code [{var: "eq"}]} or {@code [{var:"internal"},{string:"member_2"}]}). */
    private static String operatorName(JsonNode opTerm) {
        if (opTerm == null || !"ref".equals(opTerm.path("type").asText())) {
            return null;
        }
        JsonNode value = opTerm.get("value");
        if (value == null || !value.isArray() || value.isEmpty()) {
            return null;
        }
        StringBuilder name = new StringBuilder();
        for (JsonNode part : value) {
            String t = part.path("type").asText();
            if ("var".equals(t) || "string".equals(t)) {
                if (name.length() > 0) {
                    name.append('.');
                }
                name.append(part.path("value").asText());
            } else {
                return null;
            }
        }
        return name.toString();
    }

    /** Map an OPA operator var name to the closed operator set, or null if unsupported. */
    private static Operator mapOperator(String op) {
        return switch (op) {
            case "eq", "equal" -> Operator.EQ;
            case "neq" -> Operator.NEQ;
            case "internal.member_2" -> Operator.IN; // `x in {…}` lowers to member_2(x, set)
            default -> null;
        };
    }

    private static Operator negate(Operator op) {
        return switch (op) {
            case EQ -> Operator.NEQ;
            case NEQ -> Operator.EQ;
            default -> null; // negated IN/CONTAINS not represented in the closed set → unsupported
        };
    }

    /**
     * If {@code term} is a ref into {@code input.resource.…}, return the dotted path <em>after</em>
     * {@code resource} (e.g. {@code "attributes.region"}, {@code "type"}, {@code "id"}); else null.
     */
    private static String resourceRefPath(JsonNode term) {
        if (term == null || !"ref".equals(term.path("type").asText())) {
            return null;
        }
        JsonNode value = term.get("value");
        if (value == null || !value.isArray() || value.size() < 3) {
            return null;
        }
        if (!"var".equals(value.get(0).path("type").asText())
                || !"input".equals(value.get(0).path("value").asText())) {
            return null;
        }
        if (!"resource".equals(value.get(1).path("value").asText())) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (int i = 2; i < value.size(); i++) {
            JsonNode part = value.get(i);
            if (!"string".equals(part.path("type").asText())) {
                return null; // a dynamic/var key (not a literal path) is not SQL-expressible
            }
            if (path.length() > 0) {
                path.append('.');
            }
            path.append(part.path("value").asText());
        }
        return path.length() == 0 ? null : path.toString();
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
        return switch (literal.path("type").asText()) {
            case "string" -> literal.path("value").asText();
            case "number" -> literal.path("value").isIntegralNumber()
                    ? (Object) literal.path("value").asLong()
                    : (Object) literal.path("value").asDouble();
            case "boolean" -> literal.path("value").asBoolean();
            default -> null;
        };
    }

    /** A set/array literal for IN: {@code {"type":"set","value":[{string},…]}} or an array term. */
    private static List<Object> literalList(JsonNode literal) {
        if (literal == null) {
            return null;
        }
        String type = literal.path("type").asText();
        if (!"set".equals(type) && !"array".equals(type)) {
            return null;
        }
        JsonNode value = literal.get("value");
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

    /** Outcome of parsing one expression: a condition, a dropped tautology, or unsupported. */
    private record ExprResult(Condition condition, boolean tautology, boolean unsupported) {
        static ExprResult of(Condition c) {
            return new ExprResult(c, false, false);
        }

        static ExprResult asTautology() {
            return new ExprResult(null, true, false);
        }

        static ExprResult notSupported() {
            return new ExprResult(null, false, true);
        }
    }

    /** Outcome of parsing one query (conjunction): conditions, "always true", or unsupported. */
    private record ConjunctionResult(List<Condition> conditions, boolean alwaysTrue, boolean unsupported) {
        static ConjunctionResult notSupported() {
            return new ConjunctionResult(List.of(), false, true);
        }
    }
}
