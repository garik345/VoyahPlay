# Радио Ultra: CAF Media и штатный RdsApp

Дополнение 25.10.2026: [названия станций в списке CarPlay, opack-33](ultra-radio-station-names-20261025.md).

Актуальные дополнения 24.10.2026: [появление интерфейса на машине](ultra-bootstrap-20261024.md)
и [согласование аудиоресурса iPhone в opack-25](ultra-radio-audio-20261024.md).
Ниже сохранён исторический отчёт opack-16.

## Состояние opack-16-radio-caf

Добавлена связка CAF Media / NowPlayingInformation со штатным радио `com.pateo.rdsapp`.
Она передаёт текущую станцию, RDS-имя, список станций и состояние воспроизведения, принимает
Play/Pause/Next/Previous/TuneToIdentifier/ChangeMediaSource и проверяет OEM readback.
Радио объявляется только в debug Ultra pilot, когда подтверждённый экспортированный
MediaBrowserService установлен и доступен. Остальные приёмники сохраняют прежнюю конфигурацию.

**Появление кнопки Radio и работа на реальном iPhone ещё требуют проверки.** Подтверждены
локальная реализация и тесты транспорта/состояния. В исходных журналах opack-13 вообще не было
Media accessory, а открытие UI Sync не зафиксировано; добавление радио само по себе не
доказывает готовность общего интерфейса Ultra.

## Источники

После разрешения пользователя на просмотр чужих проектов проверен
[LIVI vehicle_state.rs, 3fb5210f](https://github.com/f-io/LIVI/blob/3fb5210f71ebc4b15d9aed034df8f0c28eda4f54/native/livi-helperd/crates/livi-cp/src/vehicle_state.rs).
Схема радио также сверена с main на ревизии `f999f55735278acd76f0fa207218cac00a739047` и
[cp_radio.rs](https://github.com/f-io/LIVI/blob/f999f55735278acd76f0fa207218cac00a739047/native/livi-helperd/bin/livi-core/src/cp_radio.rs).
Взяты реальные типы services/characteristics, параметры controls и формы Media/NowPlaying.
LIVI связывает их со своим DAB-приёмником. Атрибуция GPL-3.0-or-later сохранена в исходнике
и THIRD_PARTY_NOTICES.md. Репозиторий VoyahPlay на GitHub не использовался.

Автомобильный контракт восстановлен локально из `rdsapp-release-signed.apk`:
30 740 278 байт, package `com.pateo.rdsapp`, versionName `1.0`, versionCode `1`.
SHA-256: `36B4D73A889AA78325F6A6FF0C8113C6B13605620A5A4B045209FBFF36449CCA`.
Использованы Android SDK aapt/dexdump. APK и его код не включаются и не загружаются в VoyahPlay.

## Автомобильная сторона

Экспортированный сервис без permission в манифесте APK:

```text
com.pateo.rdsapp/com.pateo.midrds.media.RdsMediaBrowserService
action = android.media.browse.MediaBrowserService
```

`onGetRoot()` возвращает `rds_empty_root_id` без проверки вызывающего пакета.
`onLoadChildren()` возвращает null. Работа идёт через MediaSession, а не browser children.

| Возможность | Подтверждённый код / данные |
| --- | --- |
| Play / Pause | RdsPlayer.onPlay/onPause → RdsManager.play/pause |
| Следующая / предыдущая | onSkipToNext/Previous → playNext/playPre; переход по текущему OEM-списку |
| Получение списка | sendCommand MEDIA_ACTION_GET_CURRENT_RDS_PLAY_LIST; resultCode=1, RESULT_DATA |
| Формат списка | ArrayList стандартных MediaSessionCompat.QueueItem; QinQueueItem преобразует vendor QinMediaInfo до отправки |
| Выбор станции | sendCustomAction COMMAND_PLAY_BY_INDEX с int DATA_INDEX |
| Диапазон | metadata_radio_type: FM или AM |
| Частота | metadata_radio_frequency, long из исходного int |
| FM | RadioStation.getFreqText делит raw на 100 MHz: 10170 → 101.7 MHz |
| AM | RdsInfoUtils использует raw kHz: 999 → 999 kHz |
| Заголовок / RDS PSName | android.media.metadata.TITLE / ARTIST |
| Избранное | KW_MEDIA_INFO_FAV, строка true/false |
| Воспроизведение | PLAYING=3, PAUSED=2; неопределённые/ошибочные состояния остаются неизвестными |

Общий Utils записывает GENRE=KW_MUSIC даже для радио и содержит другой formatter масштаба FM.
Клиент использует прямую цепочку RdsPlayer → RdsInfoUtils → RadioStation; журнал сохраняет
rawFrequency и frequencyHz для сверки на машине. Отсутствующее значение, -1, 0, дробное
значение и переполнение int не становятся частотой станции.

AndroidX media 1.6.0 подключён только к debug для чтения стандартного QueueItem Parcelable.
Внешний vendor Parcelable не нужен. В тесте ответ списка проходит через Parcel/Bundle,
после чего читается с classLoader совместимой MediaSessionCompat.

Клиент не отправляет команд при подключении. Связывание может запустить штатный процесс и
его foreground service. При потере сессии значения и список очищаются; закрытие CarPlay
не отправляет штатному радио Pause/Stop.

## CAF и подтверждение команд

| Объект | CAF type / IID |
| --- | --- |
| Media accessory | 0x03000001 / 4096 |
| Источник | service 0x14000006 / 4097; identifier VOYAH_RADIO, label FM / AM |
| Disabled / current item / items / images | IID 4100 / 4102 / 4103 / 4104 |
| NowPlayingInformation accessory | 0x0F000001 / 4352 |
| NowPlaying service | 0x14000001 / 4353 |
| Source / title / artist / album / artwork / playback | IID 4354–4359 |
| Play / Pause / Next / Previous | IID 4360–4363 |
| TuneToIdentifier | IID 4364; identifier + sourceIdentifier |
| ChangeMediaSource | IID 4366; identifier=VOYAH_RADIO, включает штатное воспроизведение |

`sender=0` соответствует отправке телефоном, как в LIVI; декодер принимает числовые 0/1
и прежние строковые device/accessory. Транзакция обязательна до обращения к автомобилю.
Чужой источник, неизвестная станция, неверные параметры и неподдерживаемые команды отклоняются.

Перед выбором станции список запрашивается заново: сохранённый индекс может уже указывать
на другую станцию. Исходный OEM-индекс сохраняется при фильтрации некорректных элементов.
Операция подтверждается только совпадением нового station ID и состояния воспроизведения.
Для Next/Previous требуется фактическая смена станции; для Play/Pause — известное совпадающее
playback state. Завершение вызова Android API не считается доставкой или OEM-подтверждением.
Тайм-аут возвращает ошибку и неизвестное состояние. Отложенная команда отменяется при закрытии.

Радио-снимок имеет собственную свежесть и обновляется независимо от CAN. Ранний radio callback
не завершает ожидание первого климатического чтения для /info. Подписчики CAF получают
изменения и инвалидирование через существующие updateNotify.

## Ограничения эталона и реализации

- В LIVI источник DAB+ с mediaSourceSemanticType=6 и mediaItemType=dab. Эти DAB-значения
  не копируются на FM/AM. Поле semantic type и DAB-теги опущены; используется явно
  подписанный источник FM / AM. На подключённом iPhone 23 октября источник не появился;
  это ещё не доказывает, что причиной является именно отсутствующий semantic type.
  Значения enum для FM/AM из просмотренных исходников не установлены.
- TuneToFrequency в LIVI объявлен, но не преобразуется в команду его DAB-приёмнику.
  В нашем CAF он не объявляется: у RdsPlayer нет абсолютной настройки частоты в MediaSession.
- Публикуется текущий OEM-список: это может быть FM, AM или выбранный штатным приложением
  список избранного. Переключение FM/AM и полное сканирование из CarPlay не реализованы.
  Выбор диапазона в штатном приложении обновляет доступный список.
- Обложек нет: публикуется пустой массив изображений и пустой artwork. Станции и RDS-имена реальные.
- Для Next/Previous без фактической смены станции, например при списке из одной станции,
  readback не подтверждает выполнение и возвращается ошибка.
- Команды проверены на автомобиле через установленный клиент (см. ниже).
  Отображение Radio и общей Ultra UI проверено: кнопки пока не появились.

## Локальные проверки

Тесты shared проверяют CAF-дерево, numeric sender, реальные станции в register/update,
свежесть/отключение, отказ для неверных команд, транзакции и выбор станции.
Отдельный тест проверяет, что радио не прерывает первоначальное ожидание климата.

Шесть Robolectric-тестов VoyahRadioClientTest проверяют:
подключение без Play, Parcel списка, отсутствие успеха только по возврату API,
OEM callback, свежий индекс перед Tune, удалённую станцию и закрытие перед отправкой команды.
Они выполнены отдельно с init script в build/uisync-checks, который исключает старые
common unit sources только для этого запуска. Прежние common tests не исправлялись.

Полная команда AGENTS.md запускается отдельно без init script и фильтров, с --offline --continue;
добавлена :automotive:assembleDebug. Полный CI имеет прежние блокеры: mobile minSdk 25 против
common minSdk 28, ошибки компиляции старых common tests и падения других shared tests.
Подробности финального прогона: build/uisync-checks/opack16-full.log.
Android-тесты: opack16-native-radio-tests.log и сохранённый opack16-native-radio-tests.xml.

Финальный результат: 89 тематических shared-тестов и 6 Android-тестов радио прошли.
Полный shared-прогон: 1638 тестов, 78 прежних падений; полный CI неуспешен по трём
перечисленным выше группам причин. automotive:assembleDebug, home/maphost assemble и lint
выполнены успешно.

APK: `build/uisync-checks/voyahplay-opack-16-radio-caf-debug.apk`, 57 903 346 байт.
SHA-256: `53A05A346D26EFD17D6C8BF5C9E040CDFA2FF483FB6EB5E177E41E704934869B`.
В DEX проверены WIRE_REV, VOYAH_RADIO, радио-клиент, обработчик controls и команда списка.

## Проверка на машине

1. Установить debug APK opack-16-radio-caf и переподключить CarPlay с включённым Ultra pilot.
2. В отчёте проверить WIRE_REV, RADIO_CAF=true, RADIO CONNECTED, RADIO LIST и RADIO STATE.
   CAF CONFIG получает два дополнительных аксессуара (обычно accessories=11).
   RADIO_CAF=false означает, что подтверждённый OEM-сервис не найден/не доступен.
3. Сверить станцию в штатном приложении с rawFrequency, frequencyHz и RDS-именем в журнале.
   Проверить изменение после выбора станции и Play/Pause в штатном приложении.
4. Если Radio появился на iPhone, проверить Play/Pause, Next/Previous и станцию из списка.
   Для каждого нажатия нужны RADIO CONTROL request, RADIO DISPATCH и
   RADIO CONTROL result confirmed=true, затем соответствующее обновление CAF.
5. Если кнопки не появились, экспортировать основной Ultra-отчёт и voyahplay-ultra-wire-*.
   Последний содержит точный исходящий /info, включая Media, NowPlaying и controls.
   Отдельно проверить UISYNC_NEGOTIATED, RCS SETUP/LISTEN и UI SYNC STATE ready:
   получение metadata радио ещё не доказывает готовность общего интерфейса iPhone.

## Проверка по ADB, 23–24 октября 2026

Последующий анализ с журналом iPhone:
[sysdiagnose от 24.10.2026](ultra-sysdiagnose-20261024.md).

На подключённой мультимедиа (Android 11) вместо opack-13-uisync установлен
`opack-16-radio-caf` через обновление APK с сохранением данных. Прежний APK сохранён
в `build/adb-ultra-radio-20261023/voyahplay-before.apk`. Ultra pilot и mutual auth
остались включены, Asset Probe выключен. Разрешение WRITE_CANBUS уже выдано.

Два новых подключения iPhone подтвердили VSP=1 и UISYNC_NEGOTIATED=1. В обоих
исходящих /info присутствует VOYAH_RADIO; сохранены точные бинарные тела в
`build/adb-ultra-radio-20261023/wire/`. Штатный MediaBrowser подключился, вернул
FM 87,5 МГц / BUSINESS и 30 станций. CAF configRequest получил 11 аксессуаров
и RADIO_CAF=true. Новых ошибок CAF или падения VoyahPlay не обнаружено.

Временный instrumentation-проверяющий APK вызвал **тот же VoyahRadioClient из
установленного приложения**, с настоящими MediaBrowser/MediaController и штатными
командами, без моков. Подтверждены пять операций:

| Операция | OEM readback | Время |
| --- | --- | --- |
| Play | FM:8750, playing=true | 29 мс |
| Next | FM:8830, playing=true | 643 мс |
| Previous | FM:10050, playing=true | 20 мс |
| TuneToIdentifier FM:8990 | FM:8990, playing=true | 20 мс |
| Pause | FM:8990, playing=false | 17 мс |

Это проверка Android-клиента; команд управления радио **от iPhone не поступало**.
Проверяющий APK получил `passed=6 failed=1`: пять операций выше и повторная Pause
успешны, а попытка возврата на FM:8750 отклонена, поскольку этой частоты нет в
текущем OEM-списке. Это ожидаемое ограничение TuneToIdentifier, а не подтверждение
успешного восстановления. Затем частота **87,5 МГц и playing=false восстановлены
через шкалу штатного приложения**, что подтверждено журналом в 23:59:10 и снимком
`screen-radio-restored-final.png`. Временный проверяющий APK удалён.

На проверенных страницах приложений CarPlay Radio, Climate и Vehicle Controls
не появились. Значок Voyah — существующая кнопка возврата в автомобиль.
Боковые климатические панели Android не подтверждают наличие климата на iPhone.
Запросы registerRequest по-прежнему относятся к климату, шинам и маршруту;
для новых radio IID 4098–4359 подписок и чтений нет. Нет RCS SETUP/LISTEN
CarPlayClusterControl и нет UI SYNC CONNECTED/STATE, несмотря на согласованный uiSync.

Следовательно, чтение и управление штатным радио работают. Незавершённым остаётся
появление интерфейса на iPhone. Две гипотезы остаются раздельными: неполное описание
общей Ultra UI/uiSyncInfo/displayPanels и обязательные, пока неизвестные FM/AM-поля
CAF Media. Отсутствие канала и подписок само по себе не позволяет выбрать между ними.
Подменять FM/AM подтверждённым в LIVI значением DAB=6 оснований нет.

Доказательства: `build/adb-ultra-radio-20261023/ultra-final.txt`,
`radio-device-probe.txt`, `instrumentation-result.txt`, `logcat-after-hardware-test.txt`
и снимки `screen-apps-final.png`, `screen-apps-page4.png`.
