package com.shilapi.xcertplay.voyah;



/** Qinggan focus attributes must resolve to the same stream as the CarPlay AudioTrack. */
final class VoyahAudioPolicy {
    final int usage, content, gain;
    private VoyahAudioPolicy(int usage, int content, int gain) {
        this.usage = usage;
        this.content = content;
        this.gain = gain;
    }
    static VoyahAudioPolicy forStream(int stream) {
        switch (stream) {
            case 3: return new VoyahAudioPolicy(1, 2, 1);
            case 1: return new VoyahAudioPolicy(12, 1, 3);
            case 6: return new VoyahAudioPolicy(2, 11, 2);
            case 22: return new VoyahAudioPolicy(16, 14, 3);
            default: return null;
        }
    }
    static int clampVolume(int value, int maximum) {
        if (value < 0 || maximum <= 0) throw new IllegalArgumentException("Invalid volume");
        return Math.min(value, maximum);
    }
}
