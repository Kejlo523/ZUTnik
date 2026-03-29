package pl.kejlo.mzutv2;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FinanceRepository {

    private static final String PREFS_FINANCE_CACHE = "mzut_finance_cache";
    private static final String KEY_FINANCE_CACHE_PREFIX = "finance_snapshot_";
    private static final long FINANCE_TTL_MS = CachePolicy.FINANCE_TTL_MS;

    private final Context appContext;

    public FinanceRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static class FinanceSnapshot {
        public final List<FinanceRecord> records;
        public final long fetchedAt;
        public final boolean fromCache;

        FinanceSnapshot(List<FinanceRecord> records, long fetchedAt, boolean fromCache) {
            this.records = records != null ? records : new ArrayList<>();
            this.fetchedAt = fetchedAt;
            this.fromCache = fromCache;
        }
    }

    public List<Study> loadStudies(boolean forceRefresh) throws IOException, org.json.JSONException {
        return new GradesRepository().loadStudies(forceRefresh);
    }

    public FinanceSnapshot loadPaymentsForActiveStudy(boolean forceRefresh)
            throws Exception {
        String cacheKey = buildFinanceCacheKey();
        FinanceSnapshot cachedSnapshot = readSnapshot(cacheKey, true);
        boolean isOnline = NetworkStatusHelper.isNetworkAvailable(appContext);

        if (!forceRefresh) {
            FinanceSnapshot freshSnapshot = readSnapshot(cacheKey, false);
            if (freshSnapshot != null) {
                return freshSnapshot;
            }
            if (!isOnline && cachedSnapshot != null) {
                return cachedSnapshot;
            }
        } else if (!isOnline && cachedSnapshot != null) {
            return cachedSnapshot;
        }

        MzutSession session = MzutSession.getInstance();
        try {
            List<FinanceRecord> records = session.isUsosLogin()
                    ? loadUsosPayments()
                    : loadLegacyPayments(forceRefresh);
            long fetchedAt = System.currentTimeMillis();
            saveSnapshot(cacheKey, records, fetchedAt);
            return new FinanceSnapshot(records, fetchedAt, false);
        } catch (Exception e) {
            if (cachedSnapshot != null) {
                return cachedSnapshot;
            }
            throw e;
        }
    }

    private List<FinanceRecord> loadLegacyPayments(boolean forceRefreshStudies)
            throws Exception {
        LegacyPaymentRepository legacyRepo = new LegacyPaymentRepository();
        List<LegacyPaymentRepository.LegacyPayment> legacyItems =
                legacyRepo.loadPaymentsForActiveStudy(forceRefreshStudies);

        List<FinanceRecord> records = new ArrayList<>();
        for (LegacyPaymentRepository.LegacyPayment item : legacyItems) {
            if (item == null) {
                continue;
            }

            FinanceRecord record = new FinanceRecord();
            record.title = FinanceRecord.normalizeLegacyTitle(item.name);
            record.amountText = FinanceRecord.normalizeText(item.chargeAmount);
            record.paidText = FinanceRecord.normalizeText(item.paidAmount);
            record.dueDateText = FinanceRecord.normalizeText(item.dueDate);
            record.paidDateText = FinanceRecord.normalizeText(item.paidDate);
            record.balanceText = FinanceRecord.normalizeText(item.balance);
            record.accountText = FinanceRecord.normalizeText(item.account);

            record.amountValue = FinanceRecord.parseAmount(record.amountText);
            record.paidValue = FinanceRecord.parseAmount(record.paidText);
            record.balanceValue = FinanceRecord.parseAmount(record.balanceText);
            records.add(record);
        }
        return records;
    }

    private List<FinanceRecord> loadUsosPayments() throws Exception {
        JSONArray items = UsosApi.getArray("services/payments/user_payments", null);
        List<FinanceRecord> records = new ArrayList<>();
        if (items == null) {
            return records;
        }

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }

            FinanceRecord record = new FinanceRecord();
            record.title = firstNonEmpty(
                    extractLocalizedOrString(item, "name"),
                    extractLocalizedOrString(item, "title"),
                    item.optString("id", null));
            record.amountText = FinanceRecord.normalizeText(item.optString("amount", null));
            record.dueDateText = FinanceRecord.normalizeText(item.optString("due_date", null));
            record.paidDateText = null;
            record.balanceText = null;
            record.accountText = null;

            record.amountValue = FinanceRecord.parseAmount(record.amountText);
            boolean isPaid = isUsosPaymentPaid(item);
            record.paidText = isPaid ? record.amountText : "0";
            record.paidValue = isPaid ? record.amountValue : 0.0d;
            record.balanceValue = isPaid ? 0.0d : -record.amountValue;
            record.balanceText = isPaid ? "0" : formatOutstandingBalance(record.amountText);
            records.add(record);
        }

        return records;
    }

    private boolean isUsosPaymentPaid(JSONObject item) {
        JSONObject statusObj = item.optJSONObject("status");
        String statusSymbol = statusObj != null
                ? statusObj.optString("symbol", null)
                : item.optString("status", null);
        return "paid".equalsIgnoreCase(statusSymbol)
                || "1".equals(statusSymbol)
                || Boolean.TRUE.equals(item.opt("is_paid"));
    }

    private String extractLocalizedOrString(JSONObject obj, String key) {
        if (obj == null) {
            return null;
        }
        JSONObject localized = obj.optJSONObject(key);
        if (localized != null) {
            String value = localized.optString("pl", localized.optString("en", ""));
            if (!value.isEmpty()) {
                return value;
            }
        }
        String plain = obj.optString(key, null);
        return plain == null || plain.trim().isEmpty() ? null : plain.trim();
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private String formatOutstandingBalance(String amountText) {
        if (amountText == null || amountText.trim().isEmpty()) {
            return null;
        }
        String normalized = amountText.trim();
        return normalized.startsWith("-") ? normalized : "-" + normalized;
    }

    private String buildFinanceCacheKey() {
        MzutSession session = MzutSession.getInstance();
        StringBuilder sb = new StringBuilder(KEY_FINANCE_CACHE_PREFIX);
        sb.append(session.isUsosLogin() ? "usos" : "legacy").append('_');

        String activeId = session.getActiveStudyId();
        if (activeId != null && !activeId.trim().isEmpty()) {
            sb.append(activeId.trim());
        } else {
            Study activeStudy = session.getActiveStudy();
            if (activeStudy != null
                    && activeStudy.przynaleznoscId != null
                    && !activeStudy.przynaleznoscId.trim().isEmpty()) {
                sb.append(activeStudy.przynaleznoscId.trim());
            } else {
                sb.append("idx_").append(Math.max(0, session.getActiveStudyIndex()));
            }
        }

        return sb.toString();
    }

    private FinanceSnapshot readSnapshot(String cacheKey, boolean ignoreTtl) {
        if (cacheKey == null || cacheKey.trim().isEmpty()) {
            return null;
        }

        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_FINANCE_CACHE, Context.MODE_PRIVATE);
        String raw = prefs.getString(cacheKey, null);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }

        try {
            JSONObject root = new JSONObject(raw);
            long fetchedAt = root.optLong("timestamp", 0L);
            if (!ignoreTtl && fetchedAt > 0L) {
                long age = System.currentTimeMillis() - fetchedAt;
                if (age > FINANCE_TTL_MS) {
                    return null;
                }
            }

            JSONArray recordsJson = root.optJSONArray("records");
            List<FinanceRecord> records = new ArrayList<>();
            if (recordsJson != null) {
                for (int i = 0; i < recordsJson.length(); i++) {
                    JSONObject item = recordsJson.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    records.add(recordFromJson(item));
                }
            }
            return new FinanceSnapshot(records, fetchedAt, true);
        } catch (JSONException e) {
            return null;
        }
    }

    private void saveSnapshot(String cacheKey, List<FinanceRecord> records, long fetchedAt) {
        if (cacheKey == null || cacheKey.trim().isEmpty()) {
            return;
        }

        try {
            JSONObject root = new JSONObject();
            root.put("timestamp", fetchedAt);

            JSONArray recordsJson = new JSONArray();
            if (records != null) {
                for (FinanceRecord record : records) {
                    if (record == null) {
                        continue;
                    }
                    recordsJson.put(recordToJson(record));
                }
            }
            root.put("records", recordsJson);

            appContext.getSharedPreferences(PREFS_FINANCE_CACHE, Context.MODE_PRIVATE)
                    .edit()
                    .putString(cacheKey, root.toString())
                    .apply();
        } catch (JSONException ignored) {
        }
    }

    private JSONObject recordToJson(FinanceRecord record) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("title", record.title);
        obj.put("amountText", record.amountText);
        obj.put("paidText", record.paidText);
        obj.put("dueDateText", record.dueDateText);
        obj.put("paidDateText", record.paidDateText);
        obj.put("balanceText", record.balanceText);
        obj.put("accountText", record.accountText);
        obj.put("amountValue", record.amountValue);
        obj.put("paidValue", record.paidValue);
        obj.put("balanceValue", record.balanceValue);
        return obj;
    }

    private FinanceRecord recordFromJson(JSONObject obj) {
        FinanceRecord record = new FinanceRecord();
        record.title = FinanceRecord.normalizeText(obj.optString("title", null));
        record.amountText = FinanceRecord.normalizeText(obj.optString("amountText", null));
        record.paidText = FinanceRecord.normalizeText(obj.optString("paidText", null));
        record.dueDateText = FinanceRecord.normalizeText(obj.optString("dueDateText", null));
        record.paidDateText = FinanceRecord.normalizeText(obj.optString("paidDateText", null));
        record.balanceText = FinanceRecord.normalizeText(obj.optString("balanceText", null));
        record.accountText = FinanceRecord.normalizeText(obj.optString("accountText", null));
        record.amountValue = obj.optDouble("amountValue", FinanceRecord.parseAmount(record.amountText));
        record.paidValue = obj.optDouble("paidValue", FinanceRecord.parseAmount(record.paidText));
        record.balanceValue = obj.optDouble("balanceValue", FinanceRecord.parseAmount(record.balanceText));
        return record;
    }
}
