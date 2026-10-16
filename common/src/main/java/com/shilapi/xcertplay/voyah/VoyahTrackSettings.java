package com.shilapi.xcertplay.voyah;
import android.app.Activity;
/** Compatibility entry; the main settings now contain the controls inline. */
public final class VoyahTrackSettings {
    private VoyahTrackSettings() {}
    public static void show(Activity activity) { VoyahSettings.showTracks(activity); }
}
