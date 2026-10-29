package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;

/** Reviewed firmware pairs; the same 1.1 extension preserves the original Tweaks gates. */
final class TweaksFirmwareProfile {
    static final String RU_SERVICES = "0faed7ebf1094c62a4f92b4bc533bbb3c08480a2cd847567cb1cff91b2d1d045";
    static final String PI_SERVICES = "483fedea7707833454272f84a64692078996127d64abac63f13863b76246a579";
    static final String PI72_SERVICES = "262bfd515f5c181a556477cadbdee1e574ead0ef1acff158dc3fa25bd8b84c38";
    static final String BRIDGE = "575735ed6f6074f1c33483c8df5c8984e51e6dc0fba328b1e4179ca10b9f9d7d";
    static final String ORIGINAL_BRIDGE = "8a5e9b9c612dd3ad09ee3218383bbef6c352835f7238011271a92bb3317011e2";
    static final String INJECTOR = "0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa";
    static final String RU_UI = "28f5d93eaec22b755b339193a733bd0d4160c496cf301bcd2fcfb88c36ade162";
    static final String PI_UI = "5d5f7498d92ee68e927bc0b891daedf15978d079b85a254399112bad98d2dd6b";
    static final String PI72_UI = "b676a3a426ba3b59fabb9d98355ff0e0ac7b07d7ad6545ac44d9cbf85f1700c1";

    static String verify(String services, String bridge, String injector, String ui) throws IOException {
        final String profile, expectedUi;
        if (RU_SERVICES.equals(services)) { profile = "RU"; expectedUi = RU_UI; }
        else if (PI_SERVICES.equals(services)) { profile = "PI"; expectedUi = PI_UI; }
        else if (PI72_SERVICES.equals(services)) { profile = "PI-OTA72"; expectedUi = PI72_UI; }
        else throw changed("/system/framework/services.jar");
        if (!INJECTOR.equals(injector)) throw changed("/data/local/tmp/frida-inject");
        if (!expectedUi.equals(ui)) throw changed("SystemUI.apk (пара прошивки " + profile + ")");
        if (ORIGINAL_BRIDGE.equals(bridge))
            throw new IOException("TWEAKS_BRIDGE_UPGRADE_REQUIRED: установлен исходный Tweaks без добавки VoyahPlay 1.1; требуется подготовка моста и перезагрузка мультимедиа");
        if (!BRIDGE.equals(bridge)) throw changed("/data/local/tmp/ss11.bin");
        return profile;
    }

    private static IOException changed(String file) {
        return new IOException("TWEAKS_FILES_CHANGED: отличается проверенный файл " + file);
    }
}
