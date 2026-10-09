package com.shilapi.xcertplay.voyah;

import java.util.HashMap;
import java.util.Map;

/** Read-only presentation; retained levels never imply that a switched-off function is active. */
public final class VoyahComfortValues {
    private final Map<String, Integer> values;
    private final boolean available;
    public VoyahComfortValues(Map<String, Integer> values, boolean available) {
        this.values = values == null ? new HashMap<>() : new HashMap<>(values);
        this.available = available;
    }
    private int get(String name) {
        Integer value = values.get(name);
        return value == null ? -1 : value;
    }
    private static String raw(int value) {
        return value == -1 || value == Integer.MIN_VALUE ? "нет данных" : Integer.toString(value);
    }
    private static String onOff(int value) {
        return value == 1 ? "выкл" : value == 2 ? "вкл" : "неизвестно (" + raw(value) + ")";
    }
    public static String level(int state, int level) {
        if (state == 1) return "выкл";
        if (state != 2) return "нет данных о включении";
        if (level < 1 || level > 3) return "вкл · уровень неизвестен";
        return new String[]{"", "●○○", "●●○", "●●●"}[level] + " " + level + "/3";
    }
    private String seat(String side, String name) {
        String suffix = "_" + side;
        return name +
            "\nПодогрев: " + level(get("FRONT_SEAT_HEATING_SWITCH"+suffix), get("FRONT_SEAT_HEATING_COMMAND"+suffix)) +
            "\nВентиляция: " + level(get("FRONT_SEAT_VENTILATION_SWITCH"+suffix), get("FRONT_SEAT_VENTILATION_COMMAND"+suffix)) +
            "\nМассаж: " + level(get("FRONT_SEAT_MASS_SWITCH"+suffix), get("FRONT_SEAT_MASS_INTEN"+suffix)) +
            "\nПрограмма массажа: " + raw(get("FRONT_SEAT_MASS_COMMAND"+suffix));
    }
    public String display() {
        if (!available) return "Сиденья / руль / аромат / свет:\nданные недоступны";
        return seat("LEFT", "Водитель") + "\n\n" + seat("RIGHT", "Пассажир") +
            "\n\nПодогрев руля: " + onOff(get("STEER_WHEEL_HEAT_SWITCH")) +
            "\nОбратная связь руля: " + raw(get("STEER_WHEEL_HEAT_SWITCH_FB")) +
            "\nОшибка руля (код): " + raw(get("STEER_WHEEL_HEAT_ERROR")) +
            "\n\nАроматизатор: " + onOff(get("FCM_SW_REQ")) +
            "\nВыбранный аромат: " + raw(get("IVI_FRAG_TASTE")) +
            "\nИнтенсивность (код): " + raw(get("IVI_FRAG_CONCERNTION")) +
            "\nОтвет: вкл=" + raw(get("FCM_SW_REQ_FB")) +
            ", аромат=" + raw(get("IVI_FRAG_TASTE_FB")) +
            ", сила=" + raw(get("IVI_FRAG_CONCERNTION_FB")) +
            "\n\nСвет · сырые коды" +
            "\nРежим: " + raw(get("COMBINATION_LIGHT_SWITCH")) +
            "\nФары: " + raw(get("HEAD_LIGHT_STATUS")) +
            "\nБлижний: " + raw(get("LOW_BEAM")) +
            "\nГабариты: " + raw(get("PARK_LIGHT")) +
            "\nПерекл. габаритов: " + raw(get("POSITION_LAMP_SWITCH")) +
            "\nВыключение: " + raw(get("OUT_LAMP_OFF")) +
            "\nАвто: " + raw(get("AUTO_LAMP_SWITCH"));
    }
}
