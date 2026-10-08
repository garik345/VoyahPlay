package com.shilapi.xcertplay.voyah;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, bounded read-only recording. Closing the dialog allows use of stock controls. */
public final class VoyahComfortTest {
    private static final String PKG="com.qinggan.canbus.service";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private static volatile Session current;
    private static volatile String history="Запись ещё не выполнялась.";
    private VoyahComfortTest() {}
    public static void show(Activity a) {
        new AlertDialog.Builder(a).setTitle("Сиденья, руль, свет, ароматизатор")
            .setMessage("Только чтение. Запись длится 2 минуты, примерно один снимок в 2 секунды. После запуска можно открыть штатное меню автомобиля. Переключайте одну функцию за раз и удерживайте состояние 5 секунд. Данные остаются в памяти до закрытия процесса.")
            .setPositiveButton("Записать 2 мин",(d,w)->start(a.getApplicationContext()))
            .setNeutralButton("Отчёт / остановить",(d,w)->report(a))
            .setNegativeButton("Закрыть",null).show();
    }
    private static void report(Activity a) {
        TextView text=new TextView(a);text.setText(history);text.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(a);scroll.addView(text);
        new AlertDialog.Builder(a).setTitle(BUSY.get()?"Запись выполняется":"Отчёт комфорта")
            .setView(scroll).setPositiveButton("Поделиться",(d,w)->{
                Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT,"VoyahPlay comfort JSON").putExtra(Intent.EXTRA_TEXT,history);
                try { a.startActivity(Intent.createChooser(send,"Сохранить отчёт")); }
                catch(ActivityNotFoundException e) {
                    ((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("VoyahPlay comfort",history));
                    Toast.makeText(a,"Отчёт скопирован",Toast.LENGTH_LONG).show();
                }
            }).setNeutralButton("Остановить",(d,w)->{Session s=current;if(s!=null)s.cancel();})
            .setNegativeButton("Закрыть",null).show();
    }
    private static void start(Context c) {
        if(!BUSY.compareAndSet(false,true)){Toast.makeText(c,"Запрос ещё выполняется",Toast.LENGTH_LONG).show();return;}
        Session s=new Session(c);current=s;history="Проверка OEM APK…";
        MAIN.postDelayed(s.timeout,120000);WORKER.execute(s::run);
        Toast.makeText(c,"Запись запущена. Откройте штатные настройки автомобиля.",Toast.LENGTH_LONG).show();
    }
    private static final class Session implements ServiceConnection {
        final Context context;final CountDownLatch ready=new CountDownLatch(1),stop=new CountDownLatch(1);
        final long deadline=SystemClock.elapsedRealtime()+120000;
        volatile boolean stopped;boolean bound;volatile IBinder binder;
        final Runnable timeout=this::cancel;
        Session(Context c){context=c;}
        boolean live(){return !stopped && SystemClock.elapsedRealtime()<deadline;}
        void cancel(){stopped=true;ready.countDown();stop.countDown();MAIN.post(this::release);}
        void run(){
            JSONArray samples=new JSONArray();String error="";
            try {
                verify();
                MAIN.post(()->{if(!live()){ready.countDown();return;}try{
                    bound=context.bindService(new Intent().setComponent(new ComponentName(PKG,PKG+".CanBusService")),this,Context.BIND_AUTO_CREATE);
                    if(!bound)ready.countDown();
                }catch(Exception e){ready.countDown();}});
                if(!ready.await(5,TimeUnit.SECONDS)||binder==null||!live())throw new IOException("CAN service unavailable");
                IBinder service=binder;
                if(!VoyahComfortSchema.API.equals(service.getInterfaceDescriptor()))throw new IOException("Unexpected Binder descriptor");
                while(live() && samples.length()<60){
                    long startMs=System.currentTimeMillis();
                    JSONObject values=VoyahComfortSchema.read(service,this::live);
                    samples.put(new JSONObject().put("time_ms",startMs).put("read_end_ms",System.currentTimeMillis()).put("values",values));
                    publish(samples,"",false);
                    if(stop.await(2,TimeUnit.SECONDS))break;
                }
            }catch(Exception e){if(!stopped)error=e.getClass().getSimpleName()+": "+e.getMessage();}
            finally {
                publish(samples,error,true);MAIN.removeCallbacks(timeout);
                MAIN.post(()->{release();if(current==this)current=null;BUSY.set(false);});
            }
        }
        void publish(JSONArray samples,String error,boolean ended){
            try {history=new JSONObject().put("app_version","0.1.15").put("kind","comfort_read_only")
                .put("quality","OEM cache; no source timestamps; command values are not hardware confirmation")
                .put("ended",ended).put("error",error).put("samples",samples).toString(2);}
            catch(JSONException ignored){}
        }
        void verify()throws Exception {
            android.content.pm.ApplicationInfo app=context.getPackageManager().getApplicationInfo(PKG,0);
            if(app.splitSourceDirs!=null&&app.splitSourceDirs.length>0)throw new IOException("Unsupported split APK");
            File f=new File(app.sourceDir);if(!f.isFile()||f.length()<1||f.length()>128L*1024*1024)throw new IOException("Invalid APK size");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(f)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1){if(!live())throw new IOException("Cancelled");digest.update(buf,0,n);}}
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!VoyahComfortSchema.HASH.contentEquals(hash))throw new IOException("CAN APK differs from reviewed build");
        }
        public void onServiceConnected(ComponentName n,IBinder b){binder=b;ready.countDown();}
        public void onServiceDisconnected(ComponentName n){binder=null;cancel();}
        public void onNullBinding(ComponentName n){ready.countDown();}
        public void onBindingDied(ComponentName n){binder=null;cancel();}
        void release(){if(bound){bound=false;try{context.unbindService(this);}catch(RuntimeException ignored){}}}
    }
}
