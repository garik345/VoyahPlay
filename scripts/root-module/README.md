# Root-помощник 1.2.1

Полный порядок для новой машины:
[чистая установка VoyahPlay и root-helper](../../docs/INSTALL_NEW_CAR.md).
Нужны automotive APK текущей сборки, существующий `su 0` через ADB и проверенные
компоненты Tweaks/прошивки. Helper не устанавливает root или Tweaks.

Очистка исходников требуется только при переходе со старого ZIP с legacy-модулем:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\root-module\remove-legacy-sources.ps1
```

Соберите и установите `automotive`, затем:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\root-module\root-helper.ps1 -Mode Install
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\root-module\root-helper.ps1 -Mode Status
```

Если устройств несколько, укажите `-Serial SERIAL`; для своего пути ADB — `-Adb C:\path\adb.exe`.
В приложении доступны полная ширина через Tweaks, расширение вверх и автозапуск помощника.
Старый модуль больше не устанавливается и не запускается.

Порядок проверки, backup/восстановление и все режимы скрипта:
[../../docs/VOYAH_ROOT_HELPER.md](../../docs/VOYAH_ROOT_HELPER.md).

`collect-diagnostics.ps1` сохранён как архивный сборщик предыдущего исследования;
для текущего помощника используйте `-Mode Status` и отчёт из меню.
