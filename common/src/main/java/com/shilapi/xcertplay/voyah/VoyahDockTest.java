package com.shilapi.xcertplay.voyah;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.*;
import android.os.*;
import android.view.View;
import android.widget.*;

/** Explicit, short test of the main-display dock only. Never changes task bounds or SystemUI. */
public final class VoyahDockTest {
    private static Session active;
    private VoyahDockTest() {}
    public static void show(Activity a) {
        if (active!=null) { active.dialog.show(); return; }
        if (a.getWindowManager().getDefaultDisplay().getDisplayId()!=0) {
            new AlertDialog.Builder(a).setMessage("Тест доступен только на основном дисплее 0.")
                .setPositiveButton("OK",null).show(); return;
        }
        active=new Session(a); active.open();
    }
    private static final class Session implements ServiceConnection, Application.ActivityLifecycleCallbacks {
        final Activity a;
        final Handler handler=new Handler(Looper.getMainLooper());
        final StringBuilder report=new StringBuilder();
        final TextView text;
        final Button hide, restore, bindOnly, registerOnly;
        int stage;
        Thread.UncaughtExceptionHandler previousCrashHandler, crashHandler;
        final AlertDialog dialog;
        Messenger service;
        boolean bound, pending, hideSent, closed;
        final Runnable timeout=()->finishTest("Тайм-аут подключения");
        final Runnable deadline=()->finishTest("Истекли 15 секунд");
        final Messenger client=new Messenger(new Handler(Looper.getMainLooper(), message->{
            if(message.what==6 && service!=null) {
                try { service.send(Message.obtain(null,7)); }
                catch(RemoteException e) { note("Heartbeat: "+e); }
            }
            return true;
        }));
        Session(Activity activity) {
            a=activity;
            LinearLayout body=new LinearLayout(a); body.setOrientation(LinearLayout.VERTICAL);
            int pad=(int)(16*a.getResources().getDisplayMetrics().density); body.setPadding(pad,pad,pad,pad);
            bindOnly=new Button(a); bindOnly.setText("1. Только подключиться к QGBus"); body.addView(bindOnly);
            registerOnly=new Button(a); registerOnly.setText("2. Подключиться и зарегистрироваться"); body.addView(registerOnly);
            hide=new Button(a); hide.setText("3. Скрыть док на 15 секунд"); body.addView(hide);
            restore=new Button(a); restore.setText("Запросить показ дока"); body.addView(restore);
            text=new TextView(a); text.setTextSize(14); text.setTextIsSelectable(true);
            ScrollView scroll=new ScrollView(a); scroll.addView(text);
            body.addView(scroll,new LinearLayout.LayoutParams(-1,(int)(220*a.getResources().getDisplayMetrics().density)));
            dialog=new AlertDialog.Builder(a).setTitle("Voyah: тест дока QGBus").setView(body)
                .setNegativeButton("Закрыть",null).setNeutralButton("Скопировать отчёт",null).create();
            bindOnly.setOnClickListener(v->begin(1)); registerOnly.setOnClickListener(v->begin(2));
            hide.setOnClickListener(v->begin(3)); restore.setOnClickListener(v->begin(4));
            dialog.setOnDismissListener(d->close());
        }
        void open() {
            previousCrashHandler=Thread.getDefaultUncaughtExceptionHandler();
            crashHandler=(thread,error)->{
                VoyahDockJournal.append(a.getApplicationContext(),"UNCAUGHT "+VoyahDockJournal.stack(error));
                // Preserve normal Android crash processing; never swallow an uncaught exception.
                if(previousCrashHandler!=null) previousCrashHandler.uncaughtException(thread,error);
                else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
            };
            Thread.setDefaultUncaughtExceptionHandler(crashHandler);
            a.getApplication().registerActivityLifecycleCallbacks(this);
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                snapshot("При копировании");
                ((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("Voyah dock test",VoyahDockJournal.read(a)));
                Toast.makeText(a,"Отчёт скопирован",Toast.LENGTH_SHORT).show();
            });
            text.setText(VoyahDockJournal.read(a));
            note("=== Открыт тест QGBus v3. Журнал сохраняется между запусками ===");
            VoyahDockJournal.exitHistory(a);
            note("Сначала выполните этап 1, затем 2. Только если оба завершились — этап 3. Отчёт включает предыдущие запуски.\nНа стоянке. Сначала отключите скрытие дока в Tweaks.\nТест отправляет запрос только основному экрану. Верхняя строка не меняется.\nОтправка не подтверждает выполнение. При закрытии/уходе из приложения отправляется показ дока; при сбое процесса восстановление не гарантировано.");
            snapshot("До теста");
        }
        void note(String value) {
            VoyahDockJournal.append(a,value);
            android.util.Log.i("VoyahDockTest",value);
            report.append(SystemClock.elapsedRealtime()).append(": ").append(value).append('\n');
            text.setText(report.toString());
        }
        void snapshot(String label) {
            View decor=a.getWindow().getDecorView(); int[] xy=new int[2]; decor.getLocationOnScreen(xy);
            note(label+": Activity="+a.getComponentName().flattenToShortString()+
                ", task="+a.getTaskId()+", display="+a.getWindowManager().getDefaultDisplay().getDisplayId()+
                ", window="+decor.getWidth()+"x"+decor.getHeight()+", origin="+xy[0]+","+xy[1]);
        }
        void begin(int requestedStage) {
            try {
                if(hideSent || bound || pending) finishTest("Предыдущий запрос завершён");
                stage=requestedStage;
                note("BEGIN stage="+stage);
                connect(stage==3);
            } catch(RuntimeException | LinkageError e) {
                note("Ошибка запуска: "+VoyahDockJournal.stack(e)); finishTest("Запуск остановлен");
            }
        }
        void connect(boolean hiding) {
            if(closed) return;
            if(hideSent || bound || pending) finishTest("Предыдущий запрос завершён");
            handler.removeCallbacksAndMessages(null);
            pending=hiding; hide.setEnabled(false); restore.setEnabled(false); bindOnly.setEnabled(false); registerOnly.setEnabled(false);
            snapshot("Перед этапом "+stage);
            try {
                note("BEFORE bindService");
                bound=a.bindService(new Intent("com.qinggan.QGBus.QGBusService")
                    .setPackage("com.qinggan.QGBus"),this,Context.BIND_AUTO_CREATE);
                note("AFTER bindService result="+bound);
                if(!bound) { note("bindService=false: сервис недоступен"); finishTest("Нет подключения"); }
                else handler.postDelayed(timeout,5000);
            } catch(RuntimeException | LinkageError e) { note("Подключение: "+VoyahDockJournal.stack(e)); finishTest("Ошибка подключения"); }
        }
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            if(closed || !bound) return;
            handler.removeCallbacks(timeout);
            try {
                note("CONNECTED component="+name+" descriptor="+binder.getInterfaceDescriptor());
                service=new Messenger(binder);
                if(stage==1) { finishTest("Этап 1: подключение завершено, сообщений не отправляли"); return; }
                Message register=Message.obtain(null,5); Bundle identity=new Bundle();
                identity.putString("name",a.getPackageName()); register.setData(identity); register.replyTo=client;
                note("BEFORE register what=5 name="+a.getPackageName());
                service.send(register);
                note("AFTER register: отправлено, подтверждения сервиса нет");
                if(stage==2) { handler.postDelayed(()->finishTest("Этап 2 завершён"),3000); return; }
                if(pending) {
                    send(false); pending=false;
                    restore.setEnabled(true);
                    handler.postDelayed(()->{if(!closed && hideSent)snapshot("Через 2 секунды");},2000);
                    handler.postDelayed(()->{if(!closed && hideSent)snapshot("Через 10 секунд");},10000);
                    handler.postDelayed(deadline,15000);
                } else { send(true); finishTest("Показ запрошен вручную"); }
            } catch(Exception | LinkageError e) { note("QGBus: "+VoyahDockJournal.stack(e)); finishTest("Ошибка отправки"); }
        }
        Parcelable createEvent(Bundle data) throws ReflectiveOperationException {
            // QGAPI.jar supplies this class on the vehicle. Do not ship an identically named class.
            Class<?> type=Class.forName("com.qinggan.bus.QGBusEvent",true,a.getClassLoader());
            note("EVENT class="+type.getName()+" loader="+type.getClassLoader());
            Object event=type.getConstructor().newInstance();
            type.getMethod("setEventType",String.class).invoke(event,"navigation_bar_visibility");
            type.getMethod("setDestination",String.class).invoke(event,"com.qinggan.app.launcher");
            type.getMethod("setData",Bundle.class).invoke(event,data);
            // OEM QGBusImpl.publishImpl fills this public field with the caller's own package.
            type.getField("mSource").set(event,a.getPackageName());
            Object source=type.getMethod("getSource").invoke(event);
            if(!a.getPackageName().equals(source)) throw new IllegalStateException("QGBus source mismatch");
            if(!(event instanceof Parcelable)) throw new IllegalStateException("QGBusEvent is not Parcelable");
            note("EVENT ready: OEM default constructor, source="+source);
            return (Parcelable)event;
        }
        void send(boolean visible) throws RemoteException, ReflectiveOperationException {
            note("BEFORE event construction visible="+visible);
            Bundle data=new Bundle(); data.putString("package",a.getPackageName());
            data.putString("activity",a.getComponentName().getClassName());
            data.putBoolean("visible",visible); data.putInt("extra_navigation_bar_visible_type",2);
            Bundle payload=new Bundle(); payload.putParcelable("event",createEvent(data));
            Message message=Message.obtain(null,3); message.obj=payload;
            note("BEFORE publish what=3 visible="+visible);
            // Construction failures must not be mistaken for an already submitted hide request.
            if(!visible) hideSent=true;
            service.send(message);
            note("Отправлено visible="+visible+", type=2. Результат проверьте визуально.");
        }
        void finishTest(String reason) {
            handler.removeCallbacksAndMessages(null); pending=false;
            if(hideSent) {
                try { if(service==null) throw new IllegalStateException("Соединение потеряно"); send(true); }
                catch(Exception | LinkageError e) { note("Не удалось запросить восстановление: "+e+". Вернитесь на штатный главный экран или включите док через Tweaks."); }
            }
            hideSent=false; service=null;
            if(bound) { bound=false; try { a.unbindService(this); } catch(RuntimeException e) { note("unbind: "+e); } }
            note(reason); snapshot("После завершения этапа");
            hide.setEnabled(true); restore.setEnabled(true); bindOnly.setEnabled(true); registerOnly.setEnabled(true);
            if(!closed) handler.postDelayed(()->snapshot("Через 1 секунду после завершения"),1000);
        }
        void close() {
            if(closed)return; closed=true; finishTest("Диалог закрыт");
            a.getApplication().unregisterActivityLifecycleCallbacks(this);
            if(Thread.getDefaultUncaughtExceptionHandler()==crashHandler)
                Thread.setDefaultUncaughtExceptionHandler(previousCrashHandler);
            active=null;
        }
        @Override public void onServiceDisconnected(ComponentName name) { service=null; finishTest("QGBus отключён"); }
        @Override public void onBindingDied(ComponentName name) { service=null; finishTest("Binding уничтожен"); }
        @Override public void onNullBinding(ComponentName name) { finishTest("Пустой Binder"); }
        @Override public void onActivityPaused(Activity other) { if(other==a) { note("LIFECYCLE onPause finishing="+a.isFinishing()+" changingConfig="+a.isChangingConfigurations()); dialog.dismiss(); } }
        @Override public void onActivityDestroyed(Activity other) { if(other==a) { note("LIFECYCLE onDestroy"); close(); } }
        @Override public void onActivityCreated(Activity a,Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityResumed(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a,Bundle b) {}
    }
}
