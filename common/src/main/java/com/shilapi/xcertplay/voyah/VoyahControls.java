package com.shilapi.xcertplay.voyah;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, allowlisted commands for the inspected CAN APK. No writes on read/open. */
public final class VoyahControls {
    private static final String PKG="com.qinggan.canbus.service";
    private static final String API="com.qinggan.canbus.ICanBusService";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private static volatile String verifiedApk;
    private static volatile boolean verifiedHeatedWheel;
    public interface Callback { void done(Map<String,Integer> states, String message); }
    public interface Ticket { void cancel(); }
    private enum Field {
        TSR(208,277,1,2), TSR_STATUS(210,279), SPEED(211,280), UNIT(213,282),
        ISA(924,1141,1,2), MODE(925,1142,2,3,4), WARNING(926,1143,1,2), ISA_STATUS(931,1148),
        HEAT_L(480,698,1,2), HEAT_R(479,697,1,2), HEAT_LEVEL_L(193,261,1,2,3), HEAT_LEVEL_R(192,260,1,2,3),
        VENT_L(482,700,1,2), VENT_R(481,699,1,2), VENT_LEVEL_L(195,263,1,2,3), VENT_LEVEL_R(194,262,1,2,3),
        MASS_L(484,702,1,2), MASS_R(483,701,1,2), MASS_LEVEL_L(488,706,1,2,3), MASS_LEVEL_R(487,705,1,2,3),
        MASS_PROGRAM_L(486,704,1,2), MASS_PROGRAM_R(485,703,1,2),
        WHEEL(881,1098,1,2), WHEEL_FB(891,1108),
        AROMA_SWITCH(555,774,1,2), AROMA_TASTE(556,775,1,2,3), AROMA_INTENSITY(558,777,1,2,3),
        AROMA_TIME(850,1067,0,1,2),
        LIGHT_LOW(136,215,1), LIGHT_POSITION(878,1095,1), LIGHT_OFF(879,1096,1), LIGHT_AUTO(880,1097,1),
        LIGHT_MODE(225,292), LIGHT_STATUS(76,160),
        DRIVE(328,545,1,2,3,4,5,6), STEERING(504,722,2,3), ACCELERATOR(563,782,1,2,3),
        HYBRID(740,957,1,2,3,4,5),
        AMBIENT_SWITCH(171,327,1,2), AMBIENT_COLOR(161,239,java.util.stream.IntStream.rangeClosed(1,64).toArray()),
        AMBIENT_BRIGHTNESS(162,240,java.util.stream.IntStream.rangeClosed(1,10).toArray()), AMBIENT_MUSIC(165,321,1,2);
        final int ordinalValue,id; final int[] allowed;
        Field(int o,int i,int... values){ordinalValue=o;id=i;allowed=values;}
        boolean accepts(int v){for(int a:allowed)if(a==v)return true;return false;}
    }
    private VoyahControls(){}
    private static native int sendAutoHighBeam(int target);
    private static void sendAutoHighBeamRequest(int target) throws IOException {
        if(target!=1 && target!=2)throw new IOException("Неизвестная команда автодальнего");
        try {
            System.loadLibrary("xcertplay_i2c");
            int result=sendAutoHighBeam(target);
            if(result!=0)throw new IOException("CAN-транспорт автодальнего недоступен ("+result+")");
        } catch(LinkageError e) {
            throw new IOException("Нативный CAN-транспорт автодальнего недоступен",e);
        }
    }
    public static Ticket request(Context context,String command,int side,int function,int value,Callback callback){
        if(!BUSY.compareAndSet(false,true)){callback.done(Collections.emptyMap(),"Предыдущий запрос ещё выполняется. Повторите чтение.");return ()->{};}
        Session s=new Session(context.getApplicationContext(),command,side,function,value,callback);
        MAIN.postDelayed(s.timeout,20000);WORK.execute(s::run);return s;
    }
    private static final class Session implements ServiceConnection,Ticket {
        final Context context;final String command;final int side,function,value;final Callback callback;
        final CountDownLatch ready=new CountDownLatch(1);final AtomicBoolean delivered=new AtomicBoolean();
        volatile boolean cancelled;boolean modeConfirmed;boolean cabinConfirmed;boolean bound;volatile IBinder binder;boolean heatedWheel;
        final Runnable timeout=()->{cancel();deliver(Collections.emptyMap(),"Тайм-аут. Состояние неизвестно; повторите чтение.");};
        Session(Context c,String cmd,int s,int f,int v,Callback cb){context=c;command=cmd;side=s;function=f;value=v;callback=cb;}
        public void cancel(){cancelled=true;ready.countDown();MAIN.post(this::release);}
        void live()throws IOException{if(cancelled)throw new IOException("Запрос отменён");}
        void deliver(Map<String,Integer> values,String message){if(delivered.compareAndSet(false,true))callback.done(values,message);}
        void run(){
            Map<String,Integer> values=new LinkedHashMap<>();String message="Кэш автомобиля прочитан.";
            try{
                verify();live();
                if(command.startsWith("wheel-") && !heatedWheel)throw new IOException("Подогрев руля отсутствует в этой комплектации");
                MAIN.post(()->{if(cancelled){ready.countDown();return;}try{
                    bound=context.bindService(new Intent().setComponent(new ComponentName(PKG,PKG+".CanBusService")),this,Context.BIND_AUTO_CREATE);
                    if(!bound)ready.countDown();
                }catch(RuntimeException e){ready.countDown();}});
                if(!ready.await(5,TimeUnit.SECONDS)||binder==null)throw new IOException("CAN-служба недоступна");
                live();if(!API.equals(binder.getInterfaceDescriptor()))throw new IOException("Неизвестный CAN-интерфейс");
                try{apply();if(!command.endsWith("read"))message=command.equals("light-mode") ? "Запрос света отправлен; текущий режим не подтверждён." : command.equals("auto-high") ? "Запрос автодальнего отправлен; текущий режим не подтверждён." : "Команда подтверждена кэшем автомобиля.";}
                catch(Exception e){message="Команда не подтверждена: "+e.getMessage();}
                for(Field f:Field.values()){
                    if(!heatedWheel && (f==Field.WHEEL || f==Field.WHEEL_FB))continue;
                    if(command.startsWith("sign") != (f.ordinal()<=Field.ISA_STATUS.ordinal()))continue;
                    live();
                    try { values.put(f.name(),get(f)); }
                    catch(Exception error) { if(!f.name().startsWith("AMBIENT_") || cancelled)throw error; }
                }
            }catch(Exception | LinkageError e){message="Ошибка: "+e.getMessage();}
            finally{
                if(command.equals("drive-mode") || command.equals("hybrid-mode")) values.put("MODE_CONFIRMED",modeConfirmed ? 1 : 0);
                if(command.startsWith("ambient-")||command.equals("aroma-level")||command.equals("aroma-select")||command.equals("aroma-duration"))values.put("CABIN_CONFIRMED",cabinConfirmed ? 1 : 0);
                final String result=message;
                MAIN.post(()->{MAIN.removeCallbacks(timeout);release();BUSY.set(false);if(!cancelled)deliver(values,result);});
            }
        }
        void apply()throws Exception{
            if(command.equals("sign-read")||command.equals("seat-read")||command.equals("comfort-read"))return;
            if(context.checkSelfPermission("com.qinggan.permission.WRITE_CANBUS")!=PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Нет разрешения WRITE_CANBUS");
            switch(command){
                case "drive-mode": {
                    int[] profile=value==5 ? VoyahIndividualDriveProfile.read(context) : new int[]{0,0};
                    Map<String,Integer> plan=VoyahDriveModePolicy.plan(value,profile[0],profile[1]);
                    if(plan==null)throw new IOException("Неизвестный режим или индивидуальный профиль");
                    sendModeBundle(plan); modeConfirmed=true; break;
                }
                case "hybrid-mode": {
                    if(value<1||value>4)throw new IOException("Неизвестный режим силовой установки");
                    sendModeBundle(Collections.singletonMap("IVI_SOC_MODESET",value)); modeConfirmed=true; break;
                }
                case "ambient-switch":case "ambient-brightness":case "ambient-color":
                case "aroma-level":case "aroma-select":case "aroma-duration":{
                    Map<String,Integer> current=new LinkedHashMap<>();
                    if(command.startsWith("ambient-")){
                        verifyAmbientSchema();
                        for(Field f:new Field[]{Field.AMBIENT_SWITCH,Field.AMBIENT_COLOR,Field.AMBIENT_BRIGHTNESS,Field.AMBIENT_MUSIC})current.put(f.name(),get(f));
                    }else{
                        for(Field f:new Field[]{Field.AROMA_SWITCH,Field.AROMA_INTENSITY,Field.AROMA_TASTE,Field.AROMA_TIME})current.put(f.name(),get(f));
                    }
                    List<VoyahCabinControlPolicy.Step> plan=VoyahCabinControlPolicy.plan(command,value,current);
                    if(plan==null)throw new IOException("Недоступное состояние или значение; для ручного цвета/яркости отключите музыкальный режим подсветки в штатном меню");
                    for(VoyahCabinControlPolicy.Step step:plan)ensure(Field.valueOf(step.field),step.value);
                    cabinConfirmed=true;
                    break;
                }
                case "sign-tsr":ensure(Field.TSR,value);break;
                case "sign-isa":
                    if(value==1){int mode=get(Field.MODE);if(!Field.MODE.accepts(mode))throw new IOException("Режим ISA неизвестен");}
                    ensure(Field.ISA,value);break;
                case "sign-mode":{
                    if(!Field.MODE.accepts(value))throw new IOException("Неизвестный режим ISA");
                    int mode=get(Field.MODE);if(!Field.MODE.accepts(mode))throw new IOException("Режим ISA неизвестен");
                    if(mode==value)break;
                    int enabled=get(Field.ISA);if(!Field.ISA.accepts(enabled))throw new IOException("Состояние ISA неизвестно");
                    if(enabled==1)ensure(Field.ISA,2);
                    ensure(Field.MODE,value);
                    if(enabled==1)ensure(Field.ISA,1);
                    break;
                }
                case "sign-warning":ensure(Field.WARNING,value);break;
                case "wheel-set":ensure(Field.WHEEL,value);break;
                case "wheel-toggle":{
                    int current=get(Field.WHEEL);
                    if(!Field.WHEEL.accepts(current))throw new IOException("Состояние руля неизвестно");
                    ensure(Field.WHEEL,current==2?1:2);break;
                }
                case "aroma-intensity":{
                    int enabled=get(Field.AROMA_SWITCH);
                    if(!Field.AROMA_SWITCH.accepts(enabled))throw new IOException("Состояние ароматизатора неизвестно");
                    if(enabled==1){ensure(Field.AROMA_INTENSITY,3);ensure(Field.AROMA_SWITCH,2);}
                    else {
                        int intensity=get(Field.AROMA_INTENSITY);
                        if(!Field.AROMA_INTENSITY.accepts(intensity))throw new IOException("Интенсивность ароматизатора неизвестна");
                        if(intensity==1)ensure(Field.AROMA_SWITCH,1);
                        else ensure(Field.AROMA_INTENSITY,intensity-1);
                    }
                    break;
                }
                case "aroma-taste":case "aroma-time":{
                    Field f=command.equals("aroma-taste")?Field.AROMA_TASTE:Field.AROMA_TIME;
                    int current=get(f);if(!f.accepts(current))throw new IOException("Состояние ароматизатора неизвестно");
                    int next=f==Field.AROMA_TIME?(current+1)%3:(current%3)+1;
                    ensure(f,next);break;
                }
                case "auto-high":
                    live();sendAutoHighBeamRequest(value);break;
                case "light-mode":{
                    if(value<0||value>3)throw new IOException("Неизвестный режим света");
                    // OEM commands are pulses, not a readable on/off toggle. Always send activation.
                    write(new Field[]{Field.LIGHT_OFF,Field.LIGHT_POSITION,Field.LIGHT_LOW,Field.LIGHT_AUTO}[value],1);break;
                }
                case "seat-cycle":case "seat-level":{
                    if(side<0||side>1||function<0||function>2||value<0||value>3)throw new IOException("Неверная команда сиденья");
                    String suffix=side==0?"_L":"_R";String prefix=new String[]{"HEAT","VENT","MASS"}[function];
                    Field sw=Field.valueOf(prefix+suffix),level=Field.valueOf(prefix+"_LEVEL"+suffix);
                    if(!sw.accepts(get(sw)))throw new IOException("Состояние сиденья неизвестно");
                    int target=value;
                    if(command.equals("seat-cycle")){
                        int enabled=get(sw), current=get(level);
                        if(enabled==1)target=3;
                        else if(enabled==2&&current>=1&&current<=3)target=current-1;
                        else throw new IOException("Уровень сиденья неизвестен");
                    }
                    if(target==0)ensure(sw,1);
                    else {ensure(level,target);ensure(sw,2);}
                    break;
                }
                case "seat-program-cycle":case "seat-program":
                    if(side<0||side>1)throw new IOException("Неизвестная сторона");
                    Field program=side==0?Field.MASS_PROGRAM_L:Field.MASS_PROGRAM_R;
                    int target=value;
                    if(command.equals("seat-program-cycle")){
                        int current=get(program);if(!program.accepts(current))throw new IOException("Программа массажа неизвестна");
                        target=current==1?2:1;
                    }
                    ensure(program,target);break;
                default:throw new IOException("Неизвестная команда");
            }
        }
        void verifyAmbientSchema()throws Exception{
            android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(PKG,0);
            Class<?> type=new dalvik.system.PathClassLoader(info.sourceDir,context.getClassLoader()).loadClass("com.qinggan.canbus.VehicleState");
            Object[] constants=type.getEnumConstants();
            Field[] fields={Field.AMBIENT_SWITCH,Field.AMBIENT_COLOR,Field.AMBIENT_BRIGHTNESS,Field.AMBIENT_MUSIC};
            String[] names={"VEHICLE_AMBIENT_LIGHT_SWITCH","VEHICLE_AMBIENT_LIGHT_COLOR","VEHICLE_AMBIENT_LIGHT_BRIGHTNESS_LEVEL","VEHICLE_AMBIENT_LIGHT_MUSICAL_RHYTHM_SWITCH"};
            for(int i=0;i<fields.length;i++){
                Field f=fields[i];
                if(constants==null||constants.length<=f.ordinalValue)throw new IOException("Неизвестная схема подсветки");
                Object constant=constants[f.ordinalValue];
                if(!((Enum<?>)constant).name().equals(names[i])||((Number)type.getMethod("getValue").invoke(constant)).intValue()!=f.id)
                    throw new IOException("Схема подсветки отличается от проверенного OEM SDK");
            }
        }
        void sendModeBundle(Map<String,Integer> plan)throws Exception{
            Map<String,Field> fields=new LinkedHashMap<>();
            fields.put("DRIVING_MODE_SET",Field.DRIVE); fields.put("EPS_MODE_SET",Field.STEERING);
            fields.put("PROP_MODE_SET",Field.ACCELERATOR); fields.put("IVI_SOC_MODESET",Field.HYBRID);
            android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(PKG,0);
            Class<?> type=new dalvik.system.PathClassLoader(info.sourceDir,context.getClassLoader())
                .loadClass("com.qinggan.canbus.VehicleState");
            Object[] entries=type.getEnumConstants();
            boolean changed=false; Bundle vehicle=new Bundle();
            for(Map.Entry<String,Integer> entry:plan.entrySet()){
                Field f=fields.get(entry.getKey());
                if(f==null || !f.accepts(entry.getValue()) || entries==null || f.ordinalValue>=entries.length)
                    throw new IOException("Неизвестная схема режима");
                Object constant=entries[f.ordinalValue];
                if(!((Enum<?>)constant).name().equals(entry.getKey()) ||
                    ((Number)type.getMethod("getValue").invoke(constant)).intValue()!=f.id)
                    throw new IOException("Схема режима не совпадает с OEM SDK");
                int before=get(f);
                if(!f.accepts(before))throw new IOException("Текущее состояние режима неизвестно");
                changed |= before!=entry.getValue(); vehicle.putInt(entry.getKey(),entry.getValue());
            }
            if(!changed)return;
            live(); Parcel d=Parcel.obtain(),r=Parcel.obtain();
            try{
                d.writeInterfaceToken(API);d.writeInt(0);d.writeInt(1);vehicle.writeToParcel(d,0);
                if(!binder.transact(77,d,r,0))throw new IOException("TX77 недоступен");
                r.readException();if(r.dataAvail()!=4 || r.readInt()!=0)throw new IOException("TX77 отклонён");
            }finally{d.recycle();r.recycle();}
            // Acceptance is asynchronous; every requested field must match the readback.
            for(int attempt=0;attempt<8;attempt++){
                live(); boolean matches=true;
                for(Map.Entry<String,Integer> entry:plan.entrySet())
                    if(get(fields.get(entry.getKey()))!=entry.getValue())matches=false;
                if(matches)return; Thread.sleep(250);
            }
            throw new IOException("Кэш не подтвердил выбранный режим");
        }
        int get(Field f)throws Exception{
            live();Parcel d=Parcel.obtain(),r=Parcel.obtain();
            try{d.writeInterfaceToken(API);d.writeInt(1);d.writeInt(f.ordinalValue);d.writeInt(f.id);
                if(!binder.transact(57,d,r,0))throw new IOException("TX57 недоступен");r.readException();
                if(r.dataAvail()!=4)throw new IOException("Неизвестный ответ TX57");return r.readInt();
            }finally{d.recycle();r.recycle();}
        }
        void ensure(Field f,int wanted)throws Exception{
            live();if(!f.accepts(wanted))throw new IOException("Недопустимое значение "+f.name());
            int before=get(f);if(!f.accepts(before))throw new IOException("Текущее значение неизвестно: "+f.name());
            if(before==wanted)return;
            write(f,wanted);
            for(int i=0;i<6;i++){live();if(get(f)==wanted)return;Thread.sleep(250);}
            throw new IOException("Кэш не подтвердил "+f.name()+"="+wanted+"; последующие команды остановлены");
        }
        void write(Field f,int wanted)throws Exception{
            if(!f.accepts(wanted))throw new IOException("Недопустимое значение "+f.name());
            live();Parcel d=Parcel.obtain(),r=Parcel.obtain();
            try{d.writeInterfaceToken(API);d.writeInt(1);d.writeInt(f.ordinalValue);d.writeInt(f.id);d.writeInt(wanted);
                if(!binder.transact(58,d,r,0))throw new IOException("TX58 недоступен");r.readException();
                if(r.dataAvail()!=0)throw new IOException("Неизвестный ответ TX58");
            }finally{d.recycle();r.recycle();}
        }
        void verify()throws Exception{
            android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(PKG,0);
            if(info.splitSourceDirs!=null&&info.splitSourceDirs.length>0)throw new IOException("Неизвестная split-сборка CAN");
            File file=new File(info.sourceDir);if(!file.isFile()||file.length()>128L*1024*1024)throw new IOException("Неизвестный CAN APK");
            String stamp=file.getAbsolutePath()+":"+file.length()+":"+file.lastModified();
            if(stamp.equals(verifiedApk)){heatedWheel=verifiedHeatedWheel;return;}
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){live();md.update(b,0,n);}}
            StringBuilder hash=new StringBuilder();for(byte b:md.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!VoyahOemBuilds.supportsCanApk(hash.toString()))throw new IOException("CAN APK отличается от проверенного. Команды заблокированы. SHA256="+hash);
            heatedWheel=VoyahOemBuilds.hasHeatedWheel(hash.toString());verifiedHeatedWheel=heatedWheel;
            verifiedApk=stamp;
        }
        public void onServiceConnected(ComponentName n,IBinder b){binder=b;ready.countDown();}
        public void onServiceDisconnected(ComponentName n){binder=null;ready.countDown();}
        public void onNullBinding(ComponentName n){ready.countDown();}
        public void onBindingDied(ComponentName n){binder=null;ready.countDown();}
        void release(){if(bound){bound=false;try{context.unbindService(this);}catch(RuntimeException ignored){}}}
    }
}
