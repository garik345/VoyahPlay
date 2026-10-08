# Проверка DiPlay Voyah 0.1.1

- `:mobile:assembleStandaloneDebug`: **BUILD SUCCESSFUL**.
- Два runtime-файла (`identity.pk8`, `certificate.p7b`) побайтово совпадают
  с оригинальной опубликованной APK DiPlay 0.2.13.
- Перенос явно разрешён пользователем. Подключение через штатный
  `DIPLAY_AUTH_ASSETS_DIR`, без изменения логики аутентификации.
- SHA-256 оригинальной APK сверена с опубликованным SHA256SUMS.txt:
  `aed9eac786e7c0e80a2929dc2f0c2e1d0df318ca8fd83ae7c2988a98bb4cd7e6`.
- apksigner verification: PASS. Подпись совпадает с 0.1.0-preview.
- Пакет `dev.voyah.diplay.preview` сохранён; versionCode увеличен до 2.
- Изменений в исходниках CarPlay engine, transport и JNI относительно upstream нет.
- Код взаимодействия с Voyah не менялся относительно 0.1.0; повторный запуск
  шести тестов для изменения ресурсов авторизации не выполнялся.
- Ключи авторизации и подписи Android исключены из Git и исходного архива.
- Соединение с iPhone и работа форка на ГУ ещё требуют проверки пользователем.

SHA-256 `DiPlay-Voyah-0.1.1.apk`:

`25a9611906e861d612184544b037aeedab9d3f13781b83abfe664282c72b2ac9`

---

# Проверка DiPlay Voyah 0.1.0

- Полная `:mobile:assembleDebug`: **BUILD SUCCESSFUL**.
- Kotlin/Java кода DiPlay и нового адаптера: компиляция успешна.
- `VoyahValuesTest`: 4 теста, 0 ошибок.
- `VoyahParcelTest` (Robolectric, Android API 30): 2 теста, 0 ошибок.
- Схемы трёх getter в точности совпадают с диагностической APK 0.3.0,
  проверенной пользователем на автомобиле.
- Контракт ограничен CAN transactions 71/9/30 и CarSignal transaction 15.
- Проверены границы значений, отсутствие подстановки sentinel как измерения,
  отказ при лишних байтах ответа, отсутствие управляющих аргументов.
- `git diff --check` и `scripts/check_public_tree.py`: PASS.
- Подпись APK: apksigner verification PASS, scheme v2.
- Package `dev.voyah.diplay.preview`, versionCode 1,
  versionName `0.2.13-voyah.0.1.0-preview`, minSdk 28, targetSdk 37.
- Наличие шести нативных библиотек (два модуля, три ABI) в APK: PASS.
- Отсутствие `assets/offline-mfi/` в APK: подтверждено.
- Сбой GNU Make обойдён альтернативным воспроизводимым способом компиляции
  нативных исходников через NDK Clang; инструкция и скрипт включены.
- Полный набор upstream-тестов не запускался; выбраны 6 тестов интеграции Voyah.
- UI и связь с машиной внутри форка на реальном ГУ ещё не проверены.
- CarPlay-соединение не проверялось; runtime identity отсутствует.

SHA-256 `DiPlay-Voyah-0.1.0-preview.apk`:

`0ec9a12cea6af5e12f20c173934cb7d17f381873f799c5f433531dcb3cd28f11`
