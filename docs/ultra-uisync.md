# UI Sync — числовой wire-формат с opack-20

Итог проверки на автомобиле 24.10.2026: [климат, автомобиль и радио появились](ultra-bootstrap-20261024.md).
Решающим для центрального экрана оказалось объявление темы в iAP2 Identification и
CarPlayStartSession. Главный климат работает без открытия CarPlayClusterControl.
Обмен UI Sync с реальным iPhone пока не наблюдался; он проверен шифрованными
сокетными тестами, что не заменяет проверку на настоящем канале.

## Активный обмен

- `uiSync` согласовывается только при предложении телефона; /info содержит пустой uiSyncInfo.
- CarPlayClusterControl UUID `07D9F906-8D64-4B54-A808-A20BCA2C51C2` обслуживается отдельно от CAF и Asset Probe.
- На открытии обработчик ждёт RESET от телефона. Сообщение — OPACK с displayUUID и
  data=bplist(header); команды лежат ещё в одном bplist в header.data.
- Типы числовые: RESET=0, RESET_COMPLETE=1, COMMAND=2, COMMAND_ACK=3. Ответный RESET
  сохраняет id iPhone и предлагает версию 2. После завершения отправляются allowLayoutChange
  и giveFocus. Layout ID не придумываются и дополнительные потоки не создаются.
- Транспортные sync/rply и прикладные ACK разделены; noAck и повторы не вызывают циклы.
  requestFocus получает giveFocus. Команды UI не превращаются в записи в автомобиль.
- Закрытие, замена потока и TEARDOWN изолированы от CAF, аудио и видео.
- Реализация сверена с первичным ui_sync.rs LIVI, commit f999f557; источники и границы
  аппаратной проверки перечислены в связанном отчёте.

Старая реализация opack-13 использовала строковые Swift enum-имена в плоском plist.
Тесты ниже относятся к этому историческому этапу и не доказывали совместимость wire-формата.
CarPlayUiSyncProtocol.kt теперь явно помечен как историческая символическая модель;
действующий обработчик использует CarPlayUiSyncSession.

## Проверка

175 целевых тестов итоговой сборки opack-23 прошли. Среди них CarPlayUiSyncSessionTest,
VoyahUltraUiSyncTest и маршрутизация через настоящий openDataStream/HKDF.
Проверяются числовой RESET, согласование версии, сохранение маршрута/id, вложенный plist,
ACK/noAck, повторы, focus, неверные данные и независимость от CAF.

На машине в журнале нужно различать согласованный `UISYNC=1` и настоящий
`RCS SETUP client=CarPlayClusterControl` → `UI SYNC CONNECTED` → `UI SYNC STATE ready`.
Последняя цепочка 24.10 не наблюдалась. Климат, автомобиль и радио уже открыты без неё.

## Результат проверки 23.10.2026

- Офлайн-прогон `:shared:testDebugUnitTest --tests '*VoyahUltra*' --tests '*CarPlayUiSync*'`:
  67 тестов, 0 ошибок/падений. Включает 17 новых проверок сессии, сокета и маршрутизации
  через `openDataStream` с настоящим HKDF ключей AirPlay.
- `:automotive:assembleDebug` прошёл, APK: `automotive/build/outputs/apk/debug/automotive-debug.apk`.
- Полная команда из AGENTS.md запущена офлайн с `--continue` и дополнительным
  `:automotive:assembleDebug`. Общий результат — ошибка: у `mobile` minSdk 25,
  у `common` minSdk 28 (также в исходном HEAD); в существующих тестах common
  не разрешаются black_corner_overlay, sinkReleaseWaitMillis, statusBarHiddenByAnotherWindow,
  capture/restore и изменившиеся сигнатуры callback. В полном shared-прогоне 1621 тест,
  78 падений в ADB, BYD, audio и network; новые проверки UI Sync прошли.
- Полный shared-отчёт сохранён перед отдельным итоговым прогоном в
  `build/uisync-checks/full-shared-report/index.html`, XML — в `full-shared-results/` рядом.
  Общая сборка и CI не считаются прошедшими.
