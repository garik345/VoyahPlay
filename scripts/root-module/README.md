# Root-помощник 1.1.2

Первичная установка/миграция после замены файлов:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\root-module\remove-legacy-sources.ps1
```

Соберите и установите `automotive`, затем:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\root-module\root-helper.ps1 -Mode Install
```

В приложении доступны полная ширина через Tweaks и автозапуск помощника.
Старый модуль больше не устанавливается и не запускается.

Порядок проверки, backup/восстановление и все режимы скрипта:
[../../docs/VOYAH_ROOT_HELPER.md](../../docs/VOYAH_ROOT_HELPER.md).

`collect-diagnostics.ps1` сохранён как архивный сборщик предыдущего исследования;
для текущего помощника используйте `-Mode Status` и отчёт из меню.
