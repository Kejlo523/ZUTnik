package pl.kejlo.zutnik;

final class PlanSyncPolicy {
    private PlanSyncPolicy() { }

    static long remainingMs(long lastSuccess, long now, long interval) {
        if (lastSuccess <= 0L || now < lastSuccess) return 0L;
        return Math.max(0L, interval - (now - lastSuccess));
    }
}
