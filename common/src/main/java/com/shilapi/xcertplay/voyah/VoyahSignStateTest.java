package com.shilapi.xcertplay.voyah;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** TX57 snapshots and explicit TSR/recognition-only ISA TX58, gated to the inspected OEM APK. */
public final class VoyahSignStateTest {
    private static final String PKG="com.qinggan.canbus.service";
    private static final String API="com.qinggan.canbus.ICanBusService";
    private static final String HASH="96ac5182e795ad70c43c78f26b9cf29e76b59db67c2d5c09216ba1d8425c427c";
    private static final String[] NAMES={"TSR_SWITCH","TSR_OPERATING_STATUS","TSR_SPEED_LIMIT","TSR_SPEED_LIMIT_UNIT","ISA_ISLC_SWITCH","ISA_ISLC_MODE","ISA_ISLC_OVER_SPEED_WARNING_SWITCH","ISA_ISLC_STATUS"};
    // ordinal and stable ID from VehicleState.<clinit> of the hash above, not interchangeable.
    private static final int[] ORDINALS={208,210,211,213,924,925,926,931};
    private static final int[] IDS={277,279,280,282,1141,1142,1143,1148};
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static String history=""; // Main thread only, bounded; copyable across dialog reopen.
    private VoyahSignStateTest(){}
    public static void show(Activity activity){
        TextView text=new TextView(activity);text.setTextIsSelectable(true);text.setTextSize(15);
        int pad=(int)(16*activity.getResources().getDisplayMetrics().density);text.setPadding(pad,pad,pad,pad);
        text.setText(history.isEmpty()?"Чтение кэша TSR/ISA. Переключатели TSR и ISA/ISLC. Для ISA используется только распознавание, без корректировки круиза.":history);
        Switch toggle=new Switch(activity);toggle.setText("TSR: состояние неизвестно");toggle.setEnabled(false);
        Switch isaToggle=new Switch(activity);isaToggle.setText("ISA/ISLC: состояние неизвестно");isaToggle.setEnabled(false);
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);content.addView(toggle);content.addView(isaToggle);content.addView(text);
        ScrollView scroll=new ScrollView(activity);scroll.addView(content);
        toggle.setOnClickListener(v->{
            int wanted=toggle.isChecked()?1:2;
            toggle.setChecked(!toggle.isChecked());
            read(activity,text,toggle,isaToggle,0,wanted);
        });
        isaToggle.setOnClickListener(v->{
            int wanted=isaToggle.isChecked()?1:2;
            isaToggle.setChecked(!isaToggle.isChecked());
            read(activity,text,toggle,isaToggle,4,wanted);
        });
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Знаки скорости: состояние TSR / ISA")
            .setView(scroll).setPositiveButton("Прочитать",null).setNeutralButton("Скопировать",null).setNegativeButton("Закрыть",null).create();
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->read(activity,text,toggle,isaToggle,0,0));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                ((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("VoyahPlay TSR ISA",history));
                Toast.makeText(activity,"Отчёт скопирован",Toast.LENGTH_SHORT).show();
            });
        });
        dialog.show();
        read(activity,text,toggle,isaToggle,0,0);
    }
    private static void append(WeakReference<TextView> target,String report){
        history=history+report+"\n\n";
        if(history.length()>16000)history=history.substring(history.length()-16000);
        TextView view=target.get();if(view!=null)view.setText(history);
    }
    private static void read(Context c,TextView text,Switch toggle,Switch isaToggle,int field,int wanted){
        if(!BUSY.compareAndSet(false,true)){Toast.makeText(c,"Предыдущий запрос ещё выполняется",Toast.LENGTH_LONG).show();return;}
        toggle.setEnabled(false);toggle.setText(wanted==0?"TSR: чтение…":"TSR: выполнение команды…");
        isaToggle.setEnabled(false);isaToggle.setText("ISA/ISLC: чтение / выполнение…");
        Session s=new Session(c.getApplicationContext(),new WeakReference<>(text),new WeakReference<>(toggle),new WeakReference<>(isaToggle),field,wanted);
        text.setText("Чтение…\n\n"+history);
        MAIN.postDelayed(s.timeout,20000);WORKER.execute(s::run);
    }
    private static final class Session implements ServiceConnection {
        final Context context;final WeakReference<TextView> target;
        final WeakReference<Switch> toggle,isaToggle;final int field,wanted;int observed=-1,isaObserved=-1,isaMode=-1,isaWarning=-1;
        final CountDownLatch ready=new CountDownLatch(1);
        volatile IBinder binder;volatile boolean expired;boolean bound;
        final Runnable timeout;
        Session(Context c,WeakReference<TextView> t,WeakReference<Switch> switchRef,WeakReference<Switch> isaRef,int fieldIndex,int w){
            context=c;target=t;toggle=switchRef;isaToggle=isaRef;field=fieldIndex;wanted=w;
            timeout=()->{
                expired=true;ready.countDown();release();
                Switch sw=toggle.get();if(sw!=null){sw.setEnabled(false);sw.setText("TSR: тайм-аут, состояние неизвестно");}
                Switch isaView=isaToggle.get();if(isaView!=null){isaView.setEnabled(false);isaView.setText("ISA/ISLC: тайм-аут, состояние неизвестно");}
                append(target,"TIMEOUT: чтение не завершено за 20 секунд. Повторный запрос доступен только после завершения текущего.");
            };
        }
        void run(){
            StringBuilder result=new StringBuilder("VoyahPlay — TSR / ISA — ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.ROOT).format(new Date())).append('\n');
            try{
                verify();if(expired)return;
                MAIN.post(()->{if(expired){ready.countDown();return;}try{
                    bound=context.bindService(new Intent().setComponent(new ComponentName(PKG,PKG+".CanBusService")),this,Context.BIND_AUTO_CREATE);
                    if(!bound)ready.countDown();
                }catch(Exception e){ready.countDown();}});
                if(!ready.await(5,TimeUnit.SECONDS)||binder==null||expired)throw new IOException("CAN service unavailable");
                IBinder service=binder;
                if(!API.equals(service.getInterfaceDescriptor()))throw new IOException("Unexpected Binder descriptor");
                if(wanted!=0&&!expired){
                    try{
                        int before=get(service,field);
                        if(before!=1&&before!=2)throw new IOException("Switch state unknown; command blocked");
                        if(context.checkSelfPermission("com.qinggan.permission.WRITE_CANBUS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                            throw new SecurityException("Нет системного разрешения WRITE_CANBUS; обычной установки APK может быть недостаточно");
                        if(field==4){
                            if(wanted==1){
                                if(before!=2)throw new IOException("ISA state changed; refresh before enabling");
                                ensure(service,5,4,result);
                                ensure(service,6,1,result);
                                if(get(service,5)!=4||get(service,6)!=1||get(service,4)!=2)
                                    throw new IOException("ISA prerequisites changed; enable blocked");
                                ensure(service,4,1,result);
                            }else ensure(service,4,2,result);
                        }else if(before!=wanted){
                            if(expired)throw new IOException("Timed out before write");
                            setTsr(service,wanted);
                            result.append("Команда TSR отправлена: ").append(wanted).append(". Это не подтверждение распознавания знаков.\n");
                            for(int attempt=0;attempt<5&&!expired;attempt++){
                                new CountDownLatch(1).await(400,TimeUnit.MILLISECONDS);
                                if(get(service,0)==wanted)break;
                            }
                        }else result.append("TSR уже в выбранном состоянии.\n");
                    }catch(Exception e){result.append("WRITE ERROR: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');}
                }
                for(int i=0;i<NAMES.length&&!expired;i++){
                    try{int value=get(service,i);if(i==0)observed=value;if(i==4)isaObserved=value;if(i==5)isaMode=value;if(i==6)isaWarning=value;result.append(NAMES[i]).append(" = ").append(value).append(" · ").append(label(i,value)).append('\n');}
                    catch(Exception e){result.append(NAMES[i]).append(" = ERROR: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');}
                }
                if(wanted!=0)result.append((field==0?observed==wanted:isaObserved==wanted&&(wanted==2||isaMode==4&&isaWarning==1))?"Кэш соответствует запросу; действие на автомобиле проверьте отдельно.\n":"Изменение не подтверждено чтением.\n");
                result.append("Это кэш, не измерение камеры. -1 не доказывает отсутствие знака. Ограничение и единицы показаны сырыми; сверяйте с приборкой.");
            }catch(Exception e){result.append("ERROR: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());}
            finally{MAIN.post(()->{MAIN.removeCallbacks(timeout);release();if(!expired){
                    append(target,result.toString());
                    Switch sw=toggle.get();if(sw!=null){
                        sw.setChecked(observed==1);sw.setEnabled(observed==1||observed==2);
                        sw.setText(observed==1?"TSR: включено (кэш)":observed==2?"TSR: выключено (кэш)":"TSR: состояние неизвестно");
                    }
                    Switch isaView=isaToggle.get();if(isaView!=null){
                        isaView.setChecked(isaObserved==1);isaView.setEnabled(isaObserved==1||isaObserved==2);
                        isaView.setText(isaObserved==1?(isaMode==4?"ISA/ISLC: включено — только распознавание (кэш)":"ISA/ISLC: включено, иной/неизвестный режим — см. отчёт"):
                            isaObserved==2?"ISA/ISLC: выключено (кэш)":"ISA/ISLC: состояние неизвестно");
                    }
                }BUSY.set(false);});}
        }
        void ensure(IBinder service,int index,int value,StringBuilder result)throws Exception{
            if(expired)throw new IOException("Timed out before "+NAMES[index]);
            if(get(service,index)==value)return;
            if(expired)throw new IOException("Timed out before write");
            setAllowed(service,index,value);
            result.append("Отправлено ").append(NAMES[index]).append(" = ").append(value).append('\n');
            for(int attempt=0;attempt<5&&!expired;attempt++){
                new CountDownLatch(1).await(400,TimeUnit.MILLISECONDS);
                if(get(service,index)==value)return;
            }
            throw new IOException("Cache did not confirm "+NAMES[index]+"; subsequent commands stopped");
        }
        void verify()throws Exception{
            android.content.pm.ApplicationInfo app=context.getPackageManager().getApplicationInfo(PKG,0);
            if(app.splitSourceDirs!=null&&app.splitSourceDirs.length>0)throw new IOException("Unsupported split APK");
            File f=new File(app.sourceDir);if(!f.isFile()||f.length()<1||f.length()>128L*1024*1024)throw new IOException("Invalid CAN APK");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(f)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){if(expired)throw new IOException("Timed out");digest.update(b,0,n);}}
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!HASH.contentEquals(hash))throw new IOException("CAN APK differs from inspected build; read blocked");
        }
        public void onServiceConnected(ComponentName n,IBinder b){binder=b;ready.countDown();}
        public void onServiceDisconnected(ComponentName n){binder=null;}
        public void onNullBinding(ComponentName n){ready.countDown();}
        public void onBindingDied(ComponentName n){binder=null;ready.countDown();}
        void release(){if(bound){bound=false;try{context.unbindService(this);}catch(RuntimeException ignored){}}}
    }
    private static int get(IBinder binder,int index)throws Exception{
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken(API);data.writeInt(1);data.writeInt(ORDINALS[index]);data.writeInt(IDS[index]);
            if(!binder.transact(57,data,reply,0))throw new IOException("TX57 unsupported");
            reply.readException();if(reply.dataAvail()!=4)throw new IOException("Unexpected reply layout");return reply.readInt();
        }finally{data.recycle();reply.recycle();}
    }
    private static void setTsr(IBinder binder,int value)throws Exception{setAllowed(binder,0,value);}
    private static void setAllowed(IBinder binder,int index,int value)throws Exception{
        if(!((index==0||index==4)&&(value==1||value==2)||index==5&&value==4||index==6&&value==1))
            throw new IllegalArgumentException("Unsupported sign command");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken(API);data.writeInt(1);data.writeInt(ORDINALS[index]);data.writeInt(IDS[index]);data.writeInt(value);
            if(!binder.transact(58,data,reply,0))throw new IOException("TX58 unsupported");
            reply.readException();if(reply.dataAvail()!=0)throw new IOException("Unexpected setter reply");
        }finally{data.recycle();reply.recycle();}
    }
    private static String label(int i,int v){
        if(v==-1)return "нет доступного значения";
        if(i==0||i==4)return v==1?"включено":v==2?"выключено":"неизвестное значение";
        if(i==5)return v==4?"только распознавание":v==3?"коррекция с подтверждением":v==2?"автоматическая коррекция":"неизвестный режим";
        if(i==6)return v==1?"предупреждение выключено":v==2?"предупреждение включено":"неизвестное значение";
        return "сырое значение OEM";
    }
}
