package pl.kejlo.zutnik;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class GradeDistribution {
    static final class Entry {
        final String symbol;
        final double percentage;

        Entry(String symbol, double percentage) {
            this.symbol = symbol;
            this.percentage = percentage;
        }
    }

    final List<Entry> entries;
    final boolean fromCache;

    GradeDistribution(List<Entry> entries, boolean fromCache) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.fromCache = fromCache;
    }

    static GradeDistribution parse(JSONObject response, boolean fromCache) throws JSONException {
        JSONArray values = response.getJSONArray("grades_distribution");
        List<Entry> result = new ArrayList<>();
        java.util.Set<String> symbols = new java.util.HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            String symbol = value.getString("grade_symbol").trim();
            double percentage = value.getDouble("percentage");
            if (symbol.isEmpty() || !Double.isFinite(percentage) || percentage < 0d || percentage > 100d
                    || !symbols.add(normalize(symbol))) throw new JSONException("Invalid grade distribution");
            result.add(new Entry(symbol, percentage));
        }
        result.sort(Comparator.comparingDouble((Entry entry) -> numeric(entry.symbol))
                .thenComparing(entry -> entry.symbol));
        return new GradeDistribution(result, fromCache);
    }

    static boolean sameSymbol(String first, String second) {
        return normalize(first).equals(normalize(second));
    }

    private static String normalize(String value) {
        String text = value == null ? "" : value.trim().replace(',', '.');
        try { return new java.math.BigDecimal(text).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException ignored) { return text.toUpperCase(java.util.Locale.ROOT); }
    }

    private static double numeric(String value) {
        try { return Double.parseDouble(value.replace(',', '.')); }
        catch (NumberFormatException ignored) { return Double.MAX_VALUE; }
    }
}
