# VoyahPlay

CarPlay для мультимедиа **Voyah Free 2026 RU, EREV, Android 11** на основе DiPlay.
Этот автомобиль — проверенная конфигурация. Для другой прошивки, года выпуска
или модели совместимость автомобильных функций и root-helper проверяется отдельно.

**[Чистая установка на другой автомобиль, включая root-helper](docs/INSTALL_NEW_CAR.md)**

## Текущая сборка

Состояние локальных исходников на **26 октября 2026**:

| Компонент | Версия |
| --- | --- |
| Модуль / пакет | `automotive` / `com.shilapi.xcertplay` |
| Android versionName / versionCode | `0.2.19-voyahplay.0.1.24` / `1208` |
| Ревизия реализации Ultra | `opack-47-ultra-profile` |
| Root-helper | `1.2.1` |
| Миграция настроек | `5` |

У промежуточных APK одинаковые Android versionName/versionCode. Для различения
сборок используйте `WIRE_REV`, имя файла и SHA-256, а не только экран версии Android.
Последний подготовленный локальный APK:
`build/ultra-profile-20261026/voyahplay-opack-47-ultra-profile-debug.apk`.
Каталог `build/` не входит в исходный репозиторий; на другом компьютере нужен
готовый APK либо собственная сборка из этих исходников.

## Возможности

- Проводной и беспроводной CarPlay, локальная офлайн-аутентификация MFi,
  сохранённый выбор iPhone и штатные настройки подключения.
- CarPlay Ultra в **debug-сборке**: климат, данные автомобиля, свет, массаж
  передних сидений, штатная громкость и радио при наличии совместимого приёмника.
- Климат: температуры двух зон, вентилятор, A/C, AUTO, ручная рециркуляция,
  обогрев заднего стекла, подогрев и вентиляция передних сидений, подогрев руля.
  Команды и индикация используют обратное чтение штатных служб автомобиля.
- В разделе «Автомобиль»: возврат в штатное меню, вход в настройки VoyahPlay,
  свет, массаж и его программы. Данные: заряд батареи, электрический запас хода,
  топливо, запас хода на бензине, общий запас хода, режим движения, давление
  в шинах и пробег — в пределах доступных OEM-показаний.
- Радио: название станции и частота, RDS RadioText в нижней строке «Исполняется»,
  синхронизация со штатным приложением, включая пассажирский экран.
  Громкость меняется с первого нажатия; отключение звука восстанавливает прежний уровень.
- Скрытие водительского левого дока и верхней строки, расширение окна вверх
  и влево через совместимые Tweaks и root-helper. Между видимыми ручками шторок
  и контентом оставлено 3 px при штатной плотности; начало контента — 19,19.
- Регулировка размера CarPlay, включая «Очень маленький». На проверенном iPhone
  этот вариант дал три строки приложений; сетку окончательно выбирает iPhone.
- После временных ошибок запуска подключение восстанавливается автоматически:
  ожидание включения точки доступа, затем редкие повторы после исчерпания быстрых.
  Паузы учитывают время сна мультимедиа. Проверки идут на открытом экране подключения.
- Собственные кнопки автомобиля, TSR/ISA, названия треков и стрелки навигации
  на приборной панели. **Видеокарта CarPlay на приборку ещё не подключена.**

## Настройка Ultra

В «Настройки → Дополнительно → CarPlay Ultra: климат и комфорт» один переключатель
**«Климат и комфорт через CarPlay Ultra»** управляет Ultra, MFi4 и объявлением профиля вместе.
По умолчанию он выключен. Изменение применяется при следующем подключении;
работающий сеанс завершается только выбранной пользователем командой переподключения.

С opack-47 приложение само объявляет профиль темы, идентификаторы экрана и панели
при включённом Ultra. Для чистой установки не нужны скрытые флаги, редактирование
настроек через ADB или root-helper. Профиль использует диагностический пример LIVI,
а не готовую тему Voyah. При выключении Ultra профиль также выключается.
Порядок включения описан в
[инструкции установки](docs/INSTALL_NEW_CAR.md#4-профиль-carplay-ultra).

Переключатель **«Радио»** оставьте выключенным на автомобиле без приёмника.
Он отключает радиосервисы со следующего подключения. На проверенном iPhone значок
«Радио» при этом сохранялся и открывал пустое окно; скрытие значка пока не решено.
Запись пакетов ресурсов удалена из настроек и отключена, включая старые сохранённые значения.

С открытого CarPlay настройки доступны через **«Автомобиль → Настройки VoyahPlay»**.
При видимом штатном доке работает и повторное нажатие значка VoyahPlay.
Для первоначальной настройки можно открыть приложение из штатного меню автомобиля.

## Установка и обновление

Для новой машины следуйте [пошаговой инструкции](docs/INSTALL_NEW_CAR.md).
Для обычного обновления установите automotive APK поверх приложения с той же подписью:

```powershell
adb -s SERIAL install -r .\automotive\build\outputs\apk\debug\automotive-debug.apk
```

`SERIAL` берётся из `adb devices -l`. На первом автомобиле и на втором будут свои UID
и сопряжения; не переносите каталог данных приложения и root-helper с одной машины на другую.

Root-helper устанавливается **после APK**, из скриптов той же версии:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\root-module\root-helper.ps1 -Mode Install -Serial SERIAL
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\root-module\root-helper.ps1 -Mode Status -Serial SERIAL
```

Скрипт требует уже работающий `adb shell su 0 id`; root он не устанавливает.
Для полного экрана нужны также проверенные компоненты Tweaks 1.1, injector и OEM-файлы.
Здесь 1.1 — версия добавки/моста полной ширины, а не версия приложения VoyahTweaks.
Установщика этого стороннего моста в данном проекте нет; чистого APK и root
недостаточно для воспроизведения полного экрана на неподготовленной машине.
Сам по себе `HELPER_READY=1` не подтверждает готовность полноэкранного режима.
При отличающейся прошивке проверки SHA-256 останавливают несовместимую операцию.

Установленный helper работает из приватной копии APK и имеет собственный автозапуск
и supervisor. Совместимые обновления APK с прежней подписью не требуют переустановки
helper каждый раз. При обновлении версии helper, несовместимости или смене привязки
выполните `-Mode Install` и повторно проверьте состояние.
Подробности и восстановление: [root-helper](docs/VOYAH_ROOT_HELPER.md),
[полноэкранный режим](docs/VOYAH_TWEAKS_FULLSCREEN.md).

## Сборка из исходников

Основной модуль для Voyah — **automotive/debug**. Release не содержит пилот Ultra.
Используются JDK 25, Android SDK 37, Build Tools 36.0.0 и NDK 28.2.13676358
из [локальной конфигурации CI](.github/workflows/android.yml).
SDK задаётся через `ANDROID_HOME` или `local.properties`.

Локальная офлайн-аутентификация требует предназначенные для неё runtime assets.
При отдельной сборке укажите внешний каталог с `offline-mfi/identity.pk8`
и `offline-mfi/certificate.p7b`; ключи и сертификаты в документацию не копируются.

```powershell
$env:JAVA_HOME = 'C:\Path\To\JDK25'
$env:ANDROID_HOME = 'C:\Path\To\Android\Sdk'
$env:DIPLAY_AUTH_ASSETS_DIR = 'D:\Private\runtime-assets'
.\gradlew.bat :automotive:assembleDebug
```

Результат: `automotive/build/outputs/apk/debug/automotive-debug.apk`.
Подробности исходной сборки: [BUILD.md](docs/BUILD.md); для Voyah выбирайте automotive,
даже если примеры upstream используют mobile. Для обновления уже установленного APK
нужна та же Android-подпись.

## Проверки и ограничения

- Возможности OEM ограничены проверенными версиями CAN/Signal/Radio-служб.
  Управлению автомобилем нужен доступ `com.qinggan.permission.WRITE_CANBUS`.
  Установка root-helper этот доступ автоматически не выдаёт.
- Профиль темы Ultra экспериментальный. Совместимость с другим iPhone, iOS
  и автомобилем проверяется отдельно; выбора собственных тем Voyah пока нет.
- Микрофон звонка проверен разговором с человеком; отсутствие распознавания
  автоответчиком само по себе не является проверкой отсутствия голоса.
- В opack-47 прошли 284 профильных теста. Настоящий запуск после ночной стоянки
  с этим исправлением ещё не проверен. Общий CI не зелёный: 78 известных падений
  shared-тестов, старые несовместимые common-тесты и minSdk mobile=25/common=28.
  Automotive APK собирается; подробности — [отчёт opack-47](docs/ultra-integrated-profile-20261026.md).

Обязательная проверка проекта:

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

Журналы сохраняются через «Настройки → Диагностика». Там же можно получить отчёт
на телефоне. Для helper используется «Скопировать отчёт root-помощника»;
скрипт сохраняет отчёт на компьютере в `build/voyah-root-helper/<запуск>/report.txt`.

## Источники и подробные отчёты

Основа — [DiPlay](https://github.com/shihabal3amri/DiPlay). Лицензия в этом
репозитории — [GNU GPL v3](LICENSE); лицензии компонентов и авторство указаны
в [THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md).

Использованы исследования и реализации [VoyahTune](https://github.com/nexron171/VoyahTune):
[TSR/ISA](https://github.com/nexron171/VoyahTune/blob/922e582fa6225ded18861ac57494af56cf7ad4ab/Docs/speed-sign-recognition.md),
[SeatCommand](https://github.com/nexron171/VoyahTune/blob/922e582fa6225ded18861ac57494af56cf7ad4ab/Native/app/src/main/java/ru/big/town/anative/SeatCommand.java),
[VoiceOemTransport](https://github.com/nexron171/VoyahTune/blob/922e582fa6225ded18861ac57494af56cf7ad4ab/Native/app/src/main/java/ru/big/town/anative/VoiceOemTransport.java),
[ApolloRestorePolicy](https://github.com/nexron171/VoyahTune/blob/922e582fa6225ded18861ac57494af56cf7ad4ab/Native/app/src/main/java/ru/big/town/anative/ApolloRestorePolicy.java),
[CanBusEventHub](https://github.com/nexron171/VoyahTune/blob/master/Native/app/src/main/java/ru/big/town/anative/CanBusEventHub.java)
и [vd_bypass](https://github.com/nexron171/VoyahTune/blob/a4c1f60c07e3547dcad9b82762b05638e0d08918/Packaging/od/inject/vd_bypass.js).
CAF Media/NowPlaying и объявление темы сверены с
[LIVI](https://github.com/f-io/LIVI/tree/3fb5210f71ebc4b15d9aed034df8f0c28eda4f54).

- [Появление климата и роль темы Ultra](docs/ultra-bootstrap-20261024.md).
- [Настройки в автомобиле, отступы и отключение радио](docs/ultra-controls-radio-20261026.md).
- [Общий переключатель Ultra и MFi4](docs/ultra-unified-switch-20261026.md).
- [Профиль Ultra без ADB](docs/ultra-integrated-profile-20261026.md).
- [Запуск после стоянки](docs/ultra-cold-start-20261026.md).
- [История изменений](CHANGELOG.md) и [границы адаптации Voyah](docs/VOYAH-CLEAN-FORK.md).
