package com.shilapi.xcertplay.voyah;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageInfo;
import android.os.*;
import android.util.Log;
import android.widget.NumberPicker;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manually requested, bounded display test; not automatic CarPlay route forwarding. */
public final class VoyahNavigationTest {
    private static final String TAG="VoyahNavigationTest";
    private static final String PACKAGE="com.qinggan.cluster";
    private static final String API="com.qinggan.cluster.IInstrumentClusterManagerService";
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private VoyahNavigationTest() {}

    public static void show(Activity activity) {
        boolean enabled=com.shilapi.xcertplay.hud.VoyahTrackOutput.enabled(activity);
        new AlertDialog.Builder(activity).setTitle("Приборная панель Voyah")
            .setItems(new String[]{"Текст без стрелки", "Стрелка по коду (0–31)", "Нижняя строка: входящий звонок", "Нижняя строка: разговор",
                "Тест бегущей строки", "Названия треков: "+(enabled?"включены":"выключены"), "Записать данные навигации (2 мин)", "Остановить и поделиться записью", "Настройки бегущей строки", "Знаки скорости: прочитать TSR / ISA"}, (dialog,which)->{
                if(which==9) { VoyahSignStateTest.show(activity); return; }
                if(which==8) { scrollSettings(activity); return; }
                if(which==6) {
                    new AlertDialog.Builder(activity).setMessage("Записать навигационные пакеты CarPlay на 2 минуты для поиска ограничения скорости. Запись может содержать названия улиц и сведения о маршруте. Запустите Яндекс Навигатор и сравните знак на экране с записью; управление автомобилем не меняется.")
                        .setPositiveButton("Записать",(d,w)->com.shilapi.xcertplay.hud.VoyahRouteCapture.start()).setNegativeButton("Отмена",null).show();return;
                }
                if(which==7) {
                    com.shilapi.xcertplay.hud.VoyahRouteCapture.stop();
                    Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,"VoyahPlay route capture")
                        .putExtra(Intent.EXTRA_TEXT,com.shilapi.xcertplay.hud.VoyahRouteCapture.report());
                    try { activity.startActivity(Intent.createChooser(send,"Сохранить / отправить запись")); }
                    catch(android.content.ActivityNotFoundException e) {
                        ((android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("VoyahPlay route capture",com.shilapi.xcertplay.hud.VoyahRouteCapture.report()));
                        android.widget.Toast.makeText(activity,"Запись скопирована",android.widget.Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                if(which==5) {
                    new AlertDialog.Builder(activity).setTitle("Названия треков в нижней строке")
                        .setMessage("Показывать название и исполнителя при смене трека, до 30 секунд. Останутся трубка и оформление разговора. Звонки, обнаруженные через CarPlay, останавливают прокрутку. Отдельные Bluetooth-звонки автомобиля пока не отслеживаются; при таком подключении оставьте функцию выключенной.")
                        .setPositiveButton(enabled?"Выключить":"Включить",(d,w)->com.shilapi.xcertplay.hud.VoyahTrackOutput.setEnabled(activity,!enabled))
                        .setNegativeButton("Отмена",null).show();
                    return;
                }
                if (com.shilapi.xcertplay.hud.VoyahNavigationOutput.isSessionActive() || BUSY.get() || com.shilapi.xcertplay.hud.VoyahTrackOutput.isBusy()) {
                    new AlertDialog.Builder(activity).setMessage("Отключите CarPlay и дождитесь завершения предыдущего теста.")
                        .setPositiveButton("OK",null).show();return;
                }
                if(which==4) { confirmScroll(activity); return; }
                if(which==0)confirm(activity,-1);else if(which==1)chooseArrow(activity);else confirm(activity,which==2?-2:-3);
            }).setNegativeButton("Закрыть",null).show();
    }
    private static void scrollSettings(Activity activity) {
        android.widget.LinearLayout layout=new android.widget.LinearLayout(activity);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding=(int)(20*activity.getResources().getDisplayMetrics().density);
        layout.setPadding(padding,padding,padding,padding);
        android.widget.TextView speedLabel=new android.widget.TextView(activity);
        android.widget.SeekBar speed=new android.widget.SeekBar(activity);
        speed.setMax(26);
        speed.setProgress((1500-com.shilapi.xcertplay.hud.VoyahTrackOutput.scrollInterval(activity))/50);
        android.widget.TextView widthLabel=new android.widget.TextView(activity);
        android.widget.SeekBar width=new android.widget.SeekBar(activity);
        width.setMax(24);
        width.setProgress(com.shilapi.xcertplay.hud.VoyahTrackOutput.visibleCharacters(activity)-6);
        Runnable labels=()->{
            speedLabel.setText("Скорость: сдвиг на символ каждые "+(1500-speed.getProgress()*50)+" мс (вправо — быстрее)");
            widthLabel.setText("Символов одновременно: "+(width.getProgress()+6));
        };
        android.widget.SeekBar.OnSeekBarChangeListener listener=new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar bar,int progress,boolean user){labels.run();}
            public void onStartTrackingTouch(android.widget.SeekBar bar){}
            public void onStopTrackingTouch(android.widget.SeekBar bar){}
        };
        speed.setOnSeekBarChangeListener(listener);width.setOnSeekBarChangeListener(listener);
        labels.run();layout.addView(speedLabel);layout.addView(speed);layout.addView(widthLabel);layout.addView(width);
        android.widget.TextView hint=new android.widget.TextView(activity);
        hint.setText("Настройки применяются к тесту и трекам. Если строка обрезается, уменьшите число символов. Лимит показа — 30 секунд.");
        layout.addView(hint);
        new AlertDialog.Builder(activity).setTitle("Бегущая строка").setView(layout)
            .setPositiveButton("Сохранить",(d,w)->com.shilapi.xcertplay.hud.VoyahTrackOutput.setScroll(activity,width.getProgress()+6,1500-speed.getProgress()*50))
            .setNegativeButton("Отмена",null)
            .setNeutralButton("По умолчанию",(d,w)->com.shilapi.xcertplay.hud.VoyahTrackOutput.setScroll(activity,10,650)).show();
    }
    private static void confirmScroll(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("Тест бегущей строки")
            .setMessage("На стоящем автомобиле отключите телефон от Bluetooth автомобиля. Тест прокрутит «ТЕСТ VOYAHPLAY — Название композиции — Исполнитель» в режиме разговора. Трубка и таймер останутся. Через 30 секунд будет отправлена очистка.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Начать",(d,w)->{
                if(!com.shilapi.xcertplay.hud.VoyahTrackOutput.test(activity)) {
                    new AlertDialog.Builder(activity).setMessage("Тест недоступен: активна сессия, звонок или предыдущий запрос.").setPositiveButton("OK",null).show();return;
                }
                AlertDialog progress=new AlertDialog.Builder(activity).setTitle("Бегущая строка")
                    .setMessage("Тест запрошен. Проверьте движение текста и последующую очистку на приборке. При ошибке сервиса строка может не появиться; подробности в журнале VoyahTrack.")
                    .setNegativeButton("Остановить / закрыть",(x,y)->com.shilapi.xcertplay.hud.VoyahTrackOutput.stopTest()).create();
                progress.setOnDismissListener(x->com.shilapi.xcertplay.hud.VoyahTrackOutput.stopTest());
                progress.show();
            }).show();
    }
    private static void chooseArrow(Activity activity) {
        NumberPicker picker=new NumberPicker(activity);
        picker.setMinValue(0);picker.setMaxValue(31);picker.setWrapSelectorWheel(false);
        int lastCode=activity.getSharedPreferences("voyah_arrow_test",Context.MODE_PRIVATE).getInt("last_code",1);
        picker.setValue(Math.max(0,Math.min(31,lastCode)));
        picker.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        new AlertDialog.Builder(activity).setTitle("Код стрелки — значение пока неизвестно")
            .setView(picker).setNegativeButton("Отмена",null)
            .setPositiveButton("Выбрать",(dialog,which)->{
                int code=picker.getValue();
                activity.getSharedPreferences("voyah_arrow_test",Context.MODE_PRIVATE).edit().putInt("last_code",code).apply();
                confirm(activity,code);
            }).show();
    }
    private static void confirm(Activity activity,int arrowCode) {
        new AlertDialog.Builder(activity).setTitle("Тест приборной панели Voyah")
            .setMessage("На стоящем автомобиле завершите маршрут в штатной навигации. " +
                (arrowCode <= -2 ? "Отключите телефон от Bluetooth автомобиля. Тест покажет вымышленное состояние звонка с текстом «ТЕСТ VOYAHPLAY». Он не совершает звонок. Проверьте нижнюю строку, значок телефона и таймер." :
                arrowCode < 0 ? "Тест отправит название дороги «ТЕСТ VOYAHPLAY» без стрелки." :
                "Тест покажет код " + arrowCode + " и расстояние 300 м. Направление стрелки пока неизвестно. Запишите код и сфотографируйте результат.") +
                " Через 10 секунд будет отправлено завершение. Тест может заменить текущую навигационную подсказку.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Начать тест",(dialog,which)->start(activity,arrowCode)).show();
    }
    private static void start(Activity activity,int arrowCode) {
        if(!BUSY.compareAndSet(false,true)) {
            new AlertDialog.Builder(activity).setMessage("Предыдущий запрос ещё не завершён.").setPositiveButton("OK",null).show();return;
        }
        Session session=new Session(activity,arrowCode);
        session.dialog=new AlertDialog.Builder(activity).setTitle("Тест приборной панели")
            .setMessage("Проверка версии сервиса…").setNegativeButton("Остановить",(d,w)->session.stop.countDown()).create();
        session.dialog.setOnCancelListener(d->session.stop.countDown());
        session.dialog.setOnDismissListener(d->session.stop.countDown());
        session.dialog.show();
        session.main.postDelayed(session.timeout,20000);
        WORKER.execute(session::run);
    }
    private static final class Session implements ServiceConnection {
        final Context context;
        final int arrowCode;
        final Handler main=new Handler(Looper.getMainLooper());
        final CountDownLatch connected=new CountDownLatch(1),stop=new CountDownLatch(1);
        volatile IBinder binder;
        volatile boolean expired;
        boolean bound;
        AlertDialog dialog;
        final Runnable timeout=()->{
            expired=true;stop.countDown();connected.countDown();release();
            status("Сервис не завершил запрос. Очистка подсказки не подтверждена; завершите её через штатную навигацию.");
            dialog=null;
        };
        Session(Activity activity,int arrowCode){context=activity.getApplicationContext();this.arrowCode=arrowCode;}
        void status(String message){
            Log.i(TAG,message);
            if(dialog!=null&&dialog.isShowing())dialog.setMessage(message);
        }
        void run(){
            Object proxy=null;Class<?> info=null;Method update=null;boolean attempted=false;
            String result="Тест отменён.";
            try {
                PackageInfo pkg=context.getPackageManager().getPackageInfo(PACKAGE,0);
                if(pkg.applicationInfo.splitSourceDirs!=null&&pkg.applicationInfo.splitSourceDirs.length>0)throw new IOException("Неподдерживаемая сборка сервиса");
                verify(new File(pkg.applicationInfo.sourceDir),"1a394bf793a467dd78b95ca1ed2004df62c280df4b58b043629257889d8e4f38");
                verify(new File("/system/framework/QGAPI.jar"),"e46d3e5db0cf932cd3a39654a2f95620f2668ada8fb32b904c1684f936eca510");
                if(stop.getCount()==0||expired)return;
                main.post(()->{
                    if(expired||stop.getCount()==0){connected.countDown();return;}
                    try {
                        Intent intent=new Intent("com.qinggan.cluster.InstrumentClusterService")
                            .setComponent(new ComponentName(PACKAGE,PACKAGE+".service.InstrumentClusterService"));
                        bound=context.bindService(intent,this,Context.BIND_AUTO_CREATE);
                        if(!bound)connected.countDown();
                    }catch(RuntimeException e){Log.e(TAG,"bind failed",e);connected.countDown();}
                });
                if(!connected.await(5,TimeUnit.SECONDS)||binder==null)throw new IOException("Нет подключения к сервису приборной панели");
                if(expired||stop.getCount()==0)return;
                if(!API.equals(binder.getInterfaceDescriptor()))throw new IOException("Неожиданный Binder-интерфейс");
                ClassLoader loader=context.getClassLoader();
                Class<?> contract=Class.forName(API,true,loader);
                proxy=Class.forName(API+"$Stub",true,loader).getMethod("asInterface",IBinder.class).invoke(null,binder);
                info=Class.forName(arrowCode<=-2?"com.qinggan.cluster.info.PhoneInfo":"com.qinggan.cluster.info.NaviInfo",true,loader);
                update=contract.getMethod(arrowCode<=-2?"updatePhoneInfo":"updateNaviInfo",info);
                if(expired||stop.getCount()==0)return;
                Object sample=value(info,arrowCode==-2?"COMING_CALL":arrowCode==-3?"CONNECTED":arrowCode<0?"ROAM":"GUIDING",arrowCode<0?"ТЕСТ VOYAHPLAY":"ТЕСТ КОД "+arrowCode);
                attempted=true;
                update.invoke(proxy,sample);
                main.post(()->{if(!expired)status(arrowCode<=-2 ?
                    "PhoneInfo отправлен: "+(arrowCode==-2?"входящий звонок":"разговор")+". Проверьте нижнюю строку. Очистка через 10 секунд." : arrowCode<0 ?
                    "Вызов выполнен. Проверьте надпись «ТЕСТ VOYAHPLAY» на приборке. Завершение через 10 секунд." :
                    "Отправлен код " + arrowCode + ", расстояние 300 м. Запишите вид стрелки или её отсутствие. Завершение через 10 секунд.");});
                stop.await(10,TimeUnit.SECONDS);
                result="Тест завершён.";
            }catch(Throwable e){
                Throwable cause=e instanceof InvocationTargetException?((InvocationTargetException)e).getTargetException():e;
                result="Ошибка теста: "+cause.getClass().getSimpleName()+": "+cause.getMessage();Log.e(TAG,result,cause);
            }finally{
                if(attempted&&proxy!=null&&update!=null) {
                    try {update.invoke(proxy,value(info,arrowCode<=-2?"NOTHING":"MAN_STOP",""));result+=" Команда завершения отправлена; проверьте очистку на приборке.";}
                    catch(Throwable e){result+=" Очистка НЕ подтверждена; завершите подсказку через штатную навигацию.";Log.e(TAG,"clear failed",e);}
                }
                String message=result;
                main.post(()->{main.removeCallbacks(timeout);release();status(message);dialog=null;});
                BUSY.set(false);
            }
        }
        Object value(Class<?> info,String state,String road)throws Exception {
            Object data=info.getConstructor().newInstance();
            if (arrowCode <= -2) {
                Class<?> phoneState=Class.forName("com.qinggan.cluster.info.PhoneState",true,context.getClassLoader());
                info.getMethod("setPhoneState",phoneState).invoke(data,phoneState.getField(state).get(null));
                info.getMethod("setName",String.class).invoke(data,road);
                info.getMethod("setPhoneNum",String.class).invoke(data,"");
                info.getMethod("setDuration",int.class).invoke(data,0);
                info.getMethod("setVehicleCall",int.class).invoke(data,0);
                return data;
            }
            Class<?> guide=Class.forName("com.qinggan.cluster.info.GuideState",true,context.getClassLoader());
            info.getMethod("setGuideState",guide).invoke(data,guide.getField(state).get(null));
            info.getMethod("setCurrentRoadName",String.class).invoke(data,road);
            info.getMethod("setTurnRoadName",String.class).invoke(data,"");
            info.getMethod("setDestName",String.class).invoke(data,"");
            if ("GUIDING".equals(state) && arrowCode >= 0) {
                info.getMethod("setTbtIconId",int.class).invoke(data,arrowCode);
                info.getMethod("setNextDistance",int.class).invoke(data,300);
                info.getMethod("setTurnRoadName",String.class).invoke(data,"ТЕСТ КОД "+arrowCode);
                info.getMethod("setDisplay",boolean.class).invoke(data,true);
            }
            return data;
        }
        public void onServiceConnected(ComponentName name,IBinder service){binder=service;connected.countDown();}
        public void onServiceDisconnected(ComponentName name){stop.countDown();}
        public void onNullBinding(ComponentName name){connected.countDown();}
        public void onBindingDied(ComponentName name){connected.countDown();stop.countDown();}
        void release(){if(bound){bound=false;try{context.unbindService(this);}catch(RuntimeException ignored){}}}
    }
    private static void verify(File file,String expected)throws Exception {
        if(!file.isFile()||file.length()<1||file.length()>64L*1024*1024)throw new IOException("Недоступен файл "+file.getName());
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[32768];
        try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!expected.contentEquals(hash))throw new IOException("Версия "+file.getName()+" не совпадает с проверенной");
    }
}
