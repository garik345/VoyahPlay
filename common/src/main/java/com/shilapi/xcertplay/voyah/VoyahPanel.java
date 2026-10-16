package com.shilapi.xcertplay.voyah;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Foreground-only information panel, independent of CarPlay transport. */
public final class VoyahPanel extends LinearLayout {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final VoyahClient client;
    private TextView readings, status;
    private Switch enabled;
    private boolean hostActive, visible, attached, running;
    private final Runnable poll = this::poll;
    private final Runnable expire = () -> {
        if (running) {
            readings.setText("Данные не обновляются");
            status.setText("Ожидание ответа автомобиля…");
        }
    };

    public VoyahPanel(Context context) {
        super(context);
        client = new VoyahClient(context);
        setOrientation(VERTICAL);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        setBackgroundColor(Color.rgb(24, 33, 38));
        TextView title = text("VOYAH FREE", 20);
        title.setTextColor(Color.rgb(108, 220, 195));
        addView(title);
        readings = text("Данные ещё не получены", 18);
        addView(readings);
        status = text("", 13);
        addView(status);
        enabled = new Switch(context);
        enabled.setText("Обновлять данные Voyah");
        enabled.setTextColor(Color.WHITE);
        enabled.setChecked(context.getSharedPreferences("voyah_panel", Context.MODE_PRIVATE).getBoolean("enabled", true));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            context.getSharedPreferences("voyah_panel", Context.MODE_PRIVATE).edit().putBoolean("enabled", checked).apply();
            update();
        });
        addView(enabled);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setPadding(0, 6, 0, 6);
        return view;
    }

    public void setHostActive(boolean active) {
        hostActive = active;
        if (active) enabled.setChecked(getContext().getSharedPreferences("voyah_panel", Context.MODE_PRIVATE)
            .getBoolean("enabled", true));
        update();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; update(); }
    @Override protected void onDetachedFromWindow() { attached = false; update(); super.onDetachedFromWindow(); }
    @Override public void onVisibilityAggregated(boolean isVisible) { super.onVisibilityAggregated(isVisible); visible = isVisible; update(); }

    private void update() {
        if (enabled == null) return;
        boolean desired = attached && visible && hostActive && enabled.isChecked();
        if (desired == running) return;
        running = desired;
        handler.removeCallbacksAndMessages(null);
        client.cancel();
        readings.setText(desired ? "Получение данных…" : "Чтение приостановлено");
        status.setText("");
        if (desired) handler.post(poll);
    }

    private void poll() {
        if (!running) return;
        client.read((values, error) -> {
            if (!running) return;
            handler.removeCallbacks(expire);
            if (error != null) {
                readings.setText("Данные недоступны");
                if (VoyahClient.BUSY_MESSAGE.equals(error)) {
                    status.setText("Ожидание предыдущего запроса…");
                    handler.postDelayed(poll, 2000);
                    return;
                }
                status.setText(error + "\nВыключите и включите обновление для повтора.");
                // No automatic retries on unknown firmware, permission errors or a hung Binder.
                return;
            }
            readings.setText(values.display(SystemClock.elapsedRealtime()));
            status.setText("Кэш автомобиля · возраст сигнала неизвестен");
            handler.postDelayed(expire, 6000);
            handler.postDelayed(poll, 2000);
        });
    }
}
