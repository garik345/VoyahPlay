package com.shilapi.xcertplay.voyah;

import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

/** Display-only values. A read time is not a vehicle signal timestamp. */
public final class VoyahValues {
    public final Float battery, fuel, left, right;
    public final Integer fan;
    public final long receivedAt;
    private final int[] doors, windows;
    private final VoyahComfortValues comfort;
    private final Map<String, Number> climate, fuelRaw, odometer, tires;
    public VoyahComfortValues comfortValues() { return comfort; }
    public Map<String, Number> climateValues() { return climate; }
    /** OEM FuelLevel fields as read; empty when the getter was unavailable. */
    public Map<String, Number> fuelValues() { return fuelRaw; }
    /** OEM Odometer fields as read (mileage and range candidates); empty when unavailable. */
    public Map<String, Number> odometerValues() { return odometer; }
    /** OEM TPMSInfo fields as read (pressures in bar); empty when unavailable. */
    public Map<String, Number> tireValues() { return tires; }

    public VoyahValues(float battery, float fuel, float left, float right, int fan, long time) {
        this(battery, fuel, left, right, fan, time, null, null);
    }

    public VoyahValues(float battery, float fuel, float left, float right, int fan, long time, int[] doors, int[] windows) {
        this(battery, fuel, left, right, fan, time, doors, windows, null);
    }

    public VoyahValues(float battery, float fuel, float left, float right, int fan, long time,
            int[] doors, int[] windows, VoyahComfortValues comfort) {
        this(battery, fuel, left, right, fan, time, doors, windows, comfort, null);
    }

    public VoyahValues(float battery, float fuel, float left, float right, int fan, long time,
            int[] doors, int[] windows, VoyahComfortValues comfort, Map<String, Number> climate) {
        this(battery, fuel, left, right, fan, time, doors, windows, comfort, climate, null, null, null);
    }

    public VoyahValues(float battery, float fuel, float left, float right, int fan, long time,
            int[] doors, int[] windows, VoyahComfortValues comfort, Map<String, Number> climate,
            Map<String, Number> fuelRaw, Map<String, Number> odometer, Map<String, Number> tires) {
        this.climate = copy(climate);
        this.fuelRaw = copy(fuelRaw);
        this.odometer = copy(odometer);
        this.tires = copy(tires);
        this.comfort = comfort;
        this.doors = doors == null ? null : doors.clone();
        this.windows = windows == null ? null : windows.clone();
        this.battery = valid(battery, 0, 100);
        this.fuel = valid(fuel, 0, 100);
        this.left = valid(left, 16, 32);
        this.right = valid(right, 16, 32);
        this.fan = fan >= 0 && fan <= 10 ? fan : null;
        this.receivedAt = time;
    }

    private static Map<String, Number> copy(Map<String, Number> values) {
        return Collections.unmodifiableMap(values == null ? new HashMap<>() : new HashMap<>(values));
    }

    private static Float valid(float value, float min, float max) {
        return Float.isFinite(value) && value >= min && value <= max ? value : null;
    }

    public static String number(Float value) {
        return value == null ? "—" : String.format(Locale.getDefault(), "%.1f", value);
    }

    public static String opening(int value, boolean window) {
        if (value == 0) return window ? "частично открыто" : "закрыто";
        if (value == 1) return "открыто";
        if (window && value == 2) return "закрыто";
        return "нет данных";
    }

    private static String openings(int[] values, boolean window) {
        String[] names = {"Передняя левая", "Передняя правая", "Задняя левая", "Задняя правая"};
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            text.append("\n").append(names[i]).append(": ")
                .append(opening(values == null || i >= values.length ? -1 : values[i], window));
        }
        return text.toString();
    }

    public String display(long now) {
        if (now < receivedAt || now - receivedAt > 6000) return "Данные не обновляются";
        return "Батарея  " + number(battery) + " %\nТопливо  " + number(fuel) +
            " %\nКлимат  " + number(left) + " / " + number(right) +
            " °C\nВентилятор  " + (fan == null ? "—" : fan) +
            "\n\n" + (comfort == null ? "Комфорт: нет данных" : comfort.display()) +
            "\n\nДвери" + openings(doors, false) + "\n\nОкна" + openings(windows, true);
    }
}
