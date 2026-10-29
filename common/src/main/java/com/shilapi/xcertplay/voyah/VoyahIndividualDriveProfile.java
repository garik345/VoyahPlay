package com.shilapi.xcertplay.voyah;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Same per-account keys as OEM DriveModeViewManager and VoyahTune; never invent a profile. */
final class VoyahIndividualDriveProfile {
    private VoyahIndividualDriveProfile() {}
    static int[] read(Context context) throws Exception {
        File file=new File("/private/configs/token/accountInfo");
        if (!file.isFile() || file.length()>65536) throw new IllegalStateException("OEM account unavailable");
        String raw=new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        String account;
        if (raw.equals("guest")) account="guest";
        else {
            String[] parts=raw.split("%",-1);
            if(parts.length<5) throw new IllegalStateException("OEM account format unknown");
            account=parts[parts.length-2].trim();
            if(account.isEmpty()) throw new IllegalStateException("OEM account empty");
        }
        return new int[]{readSetting(context,"drive_mode_steeringWheelAssist"+account,2),
            readSetting(context,"drive_mode_runState"+account,1)};
    }
    private static int readSetting(Context context,String key,int fallback) {
        try(Cursor cursor=context.getContentResolver().query(Uri.parse("content://qinggan.settings/global"),
                new String[]{"value"},"name=?",new String[]{key},null)) {
            if(cursor==null) throw new IllegalStateException("OEM settings provider unavailable");
            if(!cursor.moveToNext() || cursor.isNull(0)) return fallback;
            return Integer.parseInt(cursor.getString(0).trim());
        }
    }
}
