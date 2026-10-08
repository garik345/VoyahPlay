package com.shilapi.xcertplay.hud

import android.os.SystemClock
import android.util.Base64
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.json.JSONArray
import org.json.JSONObject

/** Explicit, bounded capture of navigation frames only; no guessed speed-limit decoding. */
object VoyahRouteCapture {
    private var until = 0L
    private var started = 0L
    private var bytes = 0
    private var dropped = 0
    private var frames = JSONArray()
    @JvmStatic @Synchronized fun start() {
        started = SystemClock.elapsedRealtime(); until = started + 120000
        bytes = 0; dropped = 0; frames = JSONArray()
    }
    @JvmStatic @Synchronized fun stop() { until = 0 }
    @Synchronized internal fun onFrame(frame: Iap2Frame) {
        if (until == 0L || SystemClock.elapsedRealtime() >= until) return
        if (frame.messageId != 0x5201 && frame.messageId != 0x5202) return
        val data = frame.payload
        if (bytes + data.size > 40000 || frames.length() >= 300) { dropped++; return }
        bytes += data.size
        frames.put(JSONObject().put("elapsed_ms",SystemClock.elapsedRealtime()-started)
            .put("id",frame.messageId).put("payload_base64",Base64.encodeToString(data,Base64.NO_WRAP)))
    }
    @JvmStatic @Synchronized fun report(): String = JSONObject()
        .put("format","voyah-route-capture-1").put("limit_ms",120000)
        .put("dropped",dropped).put("frames",frames).toString(2)
}
