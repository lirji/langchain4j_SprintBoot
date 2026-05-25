package com.lrj.langchain4j.store.doris;

import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThan;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotIn;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Translate a LangChain4j {@link Filter} tree to a Doris SQL WHERE clause that operates
 * on the JSON {@code metadata} column. Values are bound through PreparedStatement
 * parameters; JSON keys are validated against a strict allow-list to prevent injection
 * via the path string (which has to be inlined inside the json function call).
 */
public class DorisFilterTranslator {

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_.-]+");

    public static class Translated {
        public final String whereClause; // empty string if no filter
        public final List<Object> params;

        Translated(String whereClause, List<Object> params) {
            this.whereClause = whereClause;
            this.params = params;
        }
    }

    public Translated translate(Filter filter) {
        if (filter == null) {
            return new Translated("", List.of());
        }
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        render(filter, sql, params);
        return new Translated(sql.toString(), params);
    }

    private void render(Filter f, StringBuilder sql, List<Object> params) {
        if (f instanceof IsEqualTo eq) {
            renderComparison(eq.key(), "=", eq.comparisonValue(), sql, params);
        } else if (f instanceof IsNotEqualTo ne) {
            renderComparison(ne.key(), "<>", ne.comparisonValue(), sql, params);
        } else if (f instanceof IsGreaterThan gt) {
            renderComparison(gt.key(), ">", gt.comparisonValue(), sql, params);
        } else if (f instanceof IsGreaterThanOrEqualTo gte) {
            renderComparison(gte.key(), ">=", gte.comparisonValue(), sql, params);
        } else if (f instanceof IsLessThan lt) {
            renderComparison(lt.key(), "<", lt.comparisonValue(), sql, params);
        } else if (f instanceof IsLessThanOrEqualTo lte) {
            renderComparison(lte.key(), "<=", lte.comparisonValue(), sql, params);
        } else if (f instanceof IsIn in) {
            renderIn(in.key(), in.comparisonValues(), false, sql, params);
        } else if (f instanceof IsNotIn nin) {
            renderIn(nin.key(), nin.comparisonValues(), true, sql, params);
        } else if (f instanceof And and) {
            sql.append('(');
            render(and.left(), sql, params);
            sql.append(" AND ");
            render(and.right(), sql, params);
            sql.append(')');
        } else if (f instanceof Or or) {
            sql.append('(');
            render(or.left(), sql, params);
            sql.append(" OR ");
            render(or.right(), sql, params);
            sql.append(')');
        } else if (f instanceof Not not) {
            sql.append("NOT (");
            render(not.expression(), sql, params);
            sql.append(')');
        } else {
            throw new UnsupportedOperationException("Unsupported filter: " + f.getClass().getName());
        }
    }

    private void renderComparison(String key, String op, Object value, StringBuilder sql, List<Object> params) {
        String expr = jsonAccess(key, value);
        sql.append(expr).append(' ').append(op).append(" ?");
        params.add(normalize(value));
    }

    private void renderIn(String key, Collection<?> values, boolean negated, StringBuilder sql, List<Object> params) {
        if (values.isEmpty()) {
            sql.append(negated ? "1=1" : "1=0");
            return;
        }
        Object sample = values.iterator().next();
        String expr = jsonAccess(key, sample);
        sql.append(expr).append(negated ? " NOT IN (" : " IN (");
        boolean first = true;
        for (Object v : values) {
            if (!first) sql.append(',');
            first = false;
            sql.append('?');
            params.add(normalize(v));
        }
        sql.append(')');
    }

    /** Build the JSON access expression with the right type cast for the value being compared. */
    private String jsonAccess(String key, Object value) {
        String safeKey = sanitizeKey(key);
        String getStr = "get_json_string(metadata, '$." + safeKey + "')";
        if (value instanceof Number) {
            return "CAST(" + getStr + " AS DOUBLE)";
        }
        if (value instanceof Boolean) {
            return getStr; // stored as "true"/"false" string via Jackson
        }
        return getStr;
    }

    private static Object normalize(Object value) {
        if (value instanceof Number || value instanceof Boolean) return value;
        return value == null ? null : value.toString();
    }

    private static String sanitizeKey(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Unsafe metadata key for Doris filter: " + key);
        }
        return key;
    }
}
