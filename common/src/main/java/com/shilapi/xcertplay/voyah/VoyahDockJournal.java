package com.shilapi.xcertplay.voyah;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Small private, synchronous diagnostic journal, independent of OEM logcat filtering. */
final class VoyahDockJournal {
    private static final int LIMIT=128*1024;
    private static File file(Context c) { return new File(c.getFilesDir(),"voyah-dock-journal.txt"); }
    static synchronized void append(Context c,String text) {
        try {
            File f=file(c);
            if(f.length()>LIMIT) {
                String previous=read(c);
                try(FileOutputStream out=new FileOutputStream(f)) {
                    byte[] bytes=previous.getBytes(StandardCharsets.UTF_8);
                    out.write(bytes,Math.max(0,bytes.length-LIMIT/2),Math.min(bytes.length,LIMIT/2));
                }
            }
            String line=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z",Locale.ROOT).format(new Date())+
                " pid="+android.os.Process.myPid()+" thread="+Thread.currentThread().getName()+" "+text+"\n";
            try(FileOutputStream out=new FileOutputStream(f,true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8)); out.flush(); out.getFD().sync();
            }
        } catch(IOException | RuntimeException e) { android.util.Log.e("VoyahDockJournal","Cannot save journal",e); }
    }
    static synchronized String read(Context c) {
        try(FileInputStream in=new FileInputStream(file(c)); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[4096]; int count;
            while((count=in.read(bytes))!=-1) out.write(bytes,0,count);
            return out.toString("UTF-8");
        } catch(FileNotFoundException e) { return "Предыдущих записей нет.\n"; }
        catch(IOException e) { return "Не удалось прочитать журнал: "+e; }
    }
    static void exitHistory(Context c) {
        if(Build.VERSION.SDK_INT<30) return;
        try {
            ActivityManager manager=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> records=manager.getHistoricalProcessExitReasons(c.getPackageName(),0,5);
            append(c,"История завершений своего приложения, записей="+records.size());
            for(ApplicationExitInfo info:records) {
                append(c,"EXIT time="+info.getTimestamp()+" pid="+info.getPid()+" process="+info.getProcessName()+
                    " reason="+info.getReason()+" status="+info.getStatus()+" description="+info.getDescription());
            }
        } catch(RuntimeException | LinkageError e) { append(c,"История завершений недоступна: "+e); }
    }
    static String stack(Throwable e) {
        StringWriter out=new StringWriter(); e.printStackTrace(new PrintWriter(out)); return out.toString();
    }
}
