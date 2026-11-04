package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;

/** Fixed NAVI transaction. The durable snapshot must precede every native change. */
final class ClusterNaviTestState {
    static final String NAVI = "qg.car.cluster.NAVI", FULL = NAVI + "_FULL", AR = NAVI + "_AR";
    interface Port {
        int mode() throws Exception;
        void mode(int value) throws Exception;
        void ready(boolean value) throws Exception;
        void request(String category) throws Exception;
        void frame(String category) throws Exception;
        void show(String category) throws Exception;
        void hide(String category) throws Exception;
    }
    interface Save { void save() throws Exception; }
    static void validate(int mode, String category) throws IOException {
        if (mode < 1 || mode > 3 || !("qg.car.cluster.MEDIA_SMALL".equals(category) || FULL.equals(category) ||
                (NAVI + "_SMALL").equals(category) || (NAVI + "_AR").equals(category)))
            throw new IOException("Unreviewed original navigation state");
    }
    static String category(int testMode) throws IOException {
        if (testMode == 2) return FULL;
        if (testMode == 3) return AR;
        throw new IOException("Unreviewed test navigation mode");
    }
    static void begin(Port port, Save save, int testMode) throws Exception {
        String requested = category(testMode);
        save.save();
        port.mode(testMode);
        port.ready(true);
        port.request(requested);
    }
    static void firstFrame(Port port, int testMode) throws Exception {
        String requested = category(testMode);
        port.ready(true);
        // A new root connection must obtain a fresh grant before acknowledging this frame.
        port.request(requested);
        port.frame(requested);
    }
    static void restore(Port port, int mode, boolean ready, String category, int testMode) throws Exception {
        validate(mode, category);
        String requested = category(testMode);
        Exception failure = null;
        try { port.hide(requested); } catch (Exception e) { failure = e; }
        // Do not overwrite a mode the driver changed during the test.
        try { if (port.mode() == testMode) port.mode(mode); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        try { port.ready(ready); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        try { port.show(category); } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }
}
