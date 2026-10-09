package com.shilapi.xcertplay.voyah;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Reports the current Activity's window only; OEM overlay ownership requires dumpsys. */
public final class VoyahDisplayDiagnostics {
    private VoyahDisplayDiagnostics() {}
    public static void show(Activity a) {
        View decor=a.getWindow().getDecorView();
        WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(decor);
        String report="VoyahPlay 0.1.15\nActivity="+a.getClass().getSimpleName()+
            "\npackage="+a.getPackageName()+"\nSDK="+android.os.Build.VERSION.SDK_INT+
            "\nmultiWindow="+a.isInMultiWindowMode()+"\nwindow="+decor.getWidth()+"x"+decor.getHeight()+
            "\nlegacyFlags=0x"+Integer.toHexString(decor.getSystemUiVisibility())+
            "\nstatusVisible="+(insets==null?"unknown":insets.isVisible(WindowInsetsCompat.Type.statusBars()))+
            "\nnavigationVisible="+(insets==null?"unknown":insets.isVisible(WindowInsetsCompat.Type.navigationBars()))+
            "\ninsets="+(insets==null?"unknown":insets.getInsets(WindowInsetsCompat.Type.systemBars()).toString())+
            "\nЕсли visible=false, а полоса видна, нужен dumpsys window/display для проверки OEM-окон. Этот снимок сам по себе не устанавливает владельца полосы.";
        new AlertDialog.Builder(a).setTitle("Системные панели: снимок окна").setMessage(report)
            .setPositiveButton("Скопировать",(d,w)->((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("VoyahPlay window",report)))
            .setNeutralButton("Тест дока QGBus",(d,w)->VoyahDockTest.show(a))
            .setNegativeButton("Закрыть",null).show();
    }
}
