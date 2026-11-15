package pl.kejlo.zutnik;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Maps the payments contract, independently of UI and network/cache policy. */
final class UsosFinanceMapper {
    static final String PAYMENT_FIELDS = "id|type|description|total_amount|saldo_amount"
            + "|payment_deadline|account_number|currency|state|debt_type";
    static final String REMITTANCE_FIELDS = "id|description|amount|remaining|date|currency|is_settled";

    private UsosFinanceMapper() { }

    static List<FinanceRecord> map(JSONArray payments, JSONArray remittances,
            String paymentTitle, String remittanceTitle) throws JSONException {
        List<FinanceRecord> result = new ArrayList<>();
        for (int i = 0; i < payments.length(); i++) {
            JSONObject item = payments.getJSONObject(i);
            FinanceRecord record = new FinanceRecord();
            String id = text(item, "id");
            // USOS IDs are not unique across payment kinds. Virtual payments have a null ID.
            record.recordId = id == null ? null : "payment:" + text(item, "type")
                    + ":" + text(item, "debt_type") + ":" + id;
            record.title = localized(item, "description", paymentTitle);
            record.currency = FinanceRecord.normalizeCurrency(item.opt("currency"));
            BigDecimal charge = money(item, "total_amount");
            BigDecimal saldo = money(item, "saldo_amount");
            record.balanceText = decimal(saldo);
            record.chargeText = decimal(charge);
            record.isPayment = true;
            // A settlement balance is relative to the charge, not the amount still due.
            // ZUT's corrected installment: charge 1, settlement balance -139 => due 140.
            // total_amount already includes the server's interest/bonus calculation;
            // adding interest or subtracting a bonus again would count them twice.
            applyPaymentBalance(record, charge, saldo);
            record.dueDateText = text(item, "payment_deadline");
            record.accountText = text(item, "account_number");
            result.add(record);
        }
        for (int i = 0; i < remittances.length(); i++) {
            JSONObject item = remittances.getJSONObject(i);
            FinanceRecord record = new FinanceRecord();
            String id = text(item, "id");
            record.recordId = id == null ? null : "remittance:" + id;
            record.title = localized(item, "description", remittanceTitle);
            record.currency = FinanceRecord.normalizeCurrency(item.opt("currency"));
            BigDecimal amount = money(item, "amount");
            BigDecimal remaining = money(item, "remaining");
            record.amountText = decimal(amount);
            record.amountValue = amount == null ? 0d : amount.doubleValue();
            record.paidText = decimal(amount);
            record.paidValue = record.amountValue;
            record.balanceText = decimal(remaining);
            record.balanceValue = remaining == null ? 0d : remaining.max(BigDecimal.ZERO).doubleValue();
            record.statusOverride = amount == null ? FinanceRecord.Status.UNKNOWN
                    : record.balanceValue > 0d ? FinanceRecord.Status.OVERPAID : FinanceRecord.Status.PAID;
            record.paidDateText = text(item, "date");
            result.add(record);
        }
        return result;
    }

    private static BigDecimal money(JSONObject item, String key) throws JSONException {
        Object raw = item.opt(key);
        if (raw == null || raw == JSONObject.NULL) return null;
        if (!(raw instanceof String) && !(raw instanceof Number)) {
            throw new JSONException("Invalid payment amount: " + key);
        }
        try {
            return new BigDecimal(raw.toString().trim().replace(',', '.'));
        } catch (NumberFormatException invalid) {
            throw new JSONException("Invalid payment amount: " + key);
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    static void repairLegacyPayment(FinanceRecord record) {
        record.isPayment = true;
        // Only v2 snapshots retained the charge in amountText. V3 overwrote it with
        // abs(saldo) and cannot be used to reconstruct a corrected payment.
        BigDecimal charge = record.amountText == null ? null
                : new BigDecimal(record.amountText.replace(',', '.'));
        BigDecimal saldo = record.balanceText == null ? null
                : new BigDecimal(record.balanceText.replace(',', '.'));
        record.chargeText = decimal(charge);
        applyPaymentBalance(record, charge, saldo);
    }

    private static void applyPaymentBalance(FinanceRecord record, BigDecimal charge, BigDecimal settlements) {
        BigDecimal balance = charge == null || settlements == null ? null : settlements.subtract(charge);
        BigDecimal outstanding = balance == null ? null : balance.negate().max(BigDecimal.ZERO);
        record.amountText = decimal(outstanding);
        record.amountValue = outstanding == null ? 0d : outstanding.doubleValue();
        record.balanceValue = balance == null ? 0d : balance.doubleValue();
        record.statusOverride = balance == null ? FinanceRecord.Status.UNKNOWN
                : balance.signum() < 0 ? FinanceRecord.Status.DUE
                : balance.signum() > 0 ? FinanceRecord.Status.OVERPAID : FinanceRecord.Status.PAID;
    }

    private static String localized(JSONObject item, String key, String fallback) {
        JSONObject dict = item.optJSONObject(key);
        String value = dict == null ? text(item, key) : text(dict, "pl");
        if (value == null && dict != null) value = text(dict, "en");
        return value == null ? fallback : value;
    }

    private static String text(JSONObject item, String key) {
        Object value = item.opt(key);
        if (!(value instanceof String) && !(value instanceof Number)) return null;
        return FinanceRecord.normalizeText(value.toString());
    }
}
