package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;
import java.util.function.LongSupplier;

/** One bounded OEM presentation request. Android visibility never grants presentation. */
final class ClusterNaviPresentation {
    static final String CONCERN = "CONCERN_PRESENTATION";
    static final long TIMEOUT_MS = 1500;
    private String category;
    private long until;
    private int response;
    private boolean active;

    synchronized void begin(String value, long now, long leaseDeadline) throws IOException {
        ClusterNaviTestState.validate(1, value);
        category = value; active = false;
        if (leaseDeadline <= now) throw failure("expired");
        response = 0; active = true;
        until = Math.min(leaseDeadline, now + TIMEOUT_MS);
    }
    synchronized void receive(String concern, String value, int state) {
        if (!active || !CONCERN.equals(concern) || !category.equals(value) || (state != 1 && state != 2)) return;
        // A close is terminal for this request, even if an older open arrives later.
        if (response != 2) response = state;
        notifyAll();
    }
    synchronized void awaitGrant(LongSupplier clock) throws IOException, InterruptedException {
        while (active && response == 0 && clock.getAsLong() < until)
            wait(Math.min(250, Math.max(1, until - clock.getAsLong())));
        requireGrant(category, clock.getAsLong());
    }
    synchronized void requireGrant(String value, long now) throws IOException {
        if (!active || !category.equals(value)) throw failure("not_requested");
        if (response == 2) throw failure("closed");
        if (now >= until) throw failure("timeout");
        if (response != 1) throw failure("pending");
    }
    synchronized void cancel() { active = false; notifyAll(); }
    private IOException failure(String result) {
        String message = "closed".equals(result) ? "Приборка закрыла запрошенную область; тест не показан."
                : "timeout".equals(result) ? "Приборка не подтвердила открытие области за отведённое время."
                : "expired".equals(result) ? "Срок теста истёк до запроса показа."
                : "Нет действующего разрешения приборки на показ.";
        return new IOException("CLUSTER_NAVI_PRESENTATION=" + result + "\nCLUSTER_NAVI_CATEGORY=" + category + "\n" + message);
    }
}
