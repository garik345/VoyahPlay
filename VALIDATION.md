# Проверка 0.1.24 / opack-5-hvac

Основа: `garik345/VoyahPlay main 7a7cd349d4929017a003a89586a88a629170979b`.
Проверено 20.10.2026. Файлы Activities и root helper не заменяются.
Из основного стека изменена одна строка AirPlaySession: `/info` использует расширение активной сессии.

## Выполнено

- Сборка выбранных настоящих исходников shared и debug-пилота компилятором Kotlin 2.2.10,
  JVM target 11. Протоколы, OPACK, bplist, шифрование и сокеты взяты из проекта.
- **26 shared JVM-тестов прошли**: прежний пилот и новый набор HVAC, включая реальный обработчик
  GET /info с отличающимися прототипом и расширением сессии; полный CAF-контракт и уникальные IID;
  две температурные зоны; ссылки displayPanels; readonly; недопустимые типы/диапазоны;
  запрет противоречивых запросов и записи без transactionID; подтверждённый/неподтверждённый результат;
  отсутствие оптимистической подмены состояния; независимую давность HVAC и комфорта;
  прямой OPACK/старый bplist; 64-битный идентификатор; шифрованный sync/rply обмен.
- Скомпилированы Java-компоненты CanSchema, VoyahClient, VoyahValues, VoyahComfortValues,
  VoyahComfortSchema, VoyahControls; Kotlin VoyahHvac, VoyahUltraSettings, DiagnosticExportStore.
- **15 common JVM-тестов прошли**: прежние проверки значений/комфорта и новые проверки
  OEM-контракта, масштаба температуры, импульсов, обратного декодирования, неизвестных значений,
  прогресса цикла рециркуляции и неизменяемого снимка.
- Для выбранной компиляции использован реальный Android API jar (android-all 15).
  В JVM-среде подменены только Android Log/Build для исполнения, R и декларации AndroidX API
  для компиляции. OEM Binder, мультимедиа и iPhone не эмулировались.
- Все 8 XML-локалей корректны. Исходники существующего payload синхронизированы с проектом;
  33 исходных payload-хеша совпадают. Новые файлы находятся непосредственно по путям проекта.
- Проверены SHA256 всех присланных OEM APK/JAR. Enum ordinal/id совпадают в QGVehicle и CAN APK.
  Реализации H97C и места штатных вызовов сверены по DEX-дизассемблированию.

## Полная Android-сборка

Обязательная команда AGENTS.md запущена с дополнительным automotive:

```sh
bash ./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
 :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
 :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug :automotive:assembleDebug
```

Остановилась до конфигурации проекта при загрузке Gradle 9.5.0:
`java.net.SocketException: Network is unreachable`.
Полная Gradle-сборка, Android lint и APK в этой среде не получены. Успешные JVM-проверки
не выдаются за успешную сборку всех Android-модулей.

Физическое выполнение HVAC-команд на автомобиле и появление климатического меню
на iPhone 16 Pro Max / iOS 26.6 этим набором проверок не подтверждаются.
Для проверки на машине следуйте TEST-ULTRA.md и сохраните отчёт новой сессии.
