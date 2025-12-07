# Potencjalne problemy w kodzie

## 1. Zadania czasowe nie wznawiają odliczania po reloadzie
Timed objectives dostają licznik poprzez `scheduleTimer(...)` wywołany tylko przy rozpoczynaniu questa. Po `reloadAll()` aktywne zadania są wznawiane, ale w `resumeActiveBranches()` nie ma ponownego zaplanowania liczników dla celów typu `TIMER`, więc po restarcie/reloadzie ograniczenie czasu przestaje działać i zadanie może już nigdy nie zakończyć się automatycznie.

- Harmonogram tworzony przy starcie zadania: `scheduleTimer(...)` dla objective typu `TIMER` 【F:src/main/kotlin/net/nemoria/quest/quest/QuestService.kt†L26-L55】
- Podczas `reloadAll()` aktywne zadania są tylko wznawiane przez `resumeActiveBranches()` bez zaplanowania timerów 【F:src/main/kotlin/net/nemoria/quest/NemoriaQuestPlugin.kt†L167-L176】

## 2. Błędna klasyfikacja przy niespełnionych warunkach startowych
`startQuest` zwraca `StartResult.PERMISSION_FAIL` dla każdej niespełnionej `startConditions`, nawet gdy przyczyną jest brak przedmiotów lub niespełnione zmienne, a nie uprawnienia. Może to wprowadzać w błąd (np. komunikaty o braku permisji, gdy chodzi o itemy) i utrudniać diagnostykę.

- Warunek zwracający `PERMISSION_FAIL` dla dowolnego niespełnionego `conditionsMet(...)` 【F:src/main/kotlin/net/nemoria/quest/quest/QuestService.kt†L26-L35】
- Funkcja `checkConditions` obsługuje m.in. itemy i zmienne, więc niepowodzenie nie musi oznaczać braku permisji 【F:src/main/kotlin/net/nemoria/quest/quest/QuestService.kt†L386-L417】

## 3. Wąskie gardła wydajnościowe

- **Scoreboard aktualizowany z dysku co sekundę.** `ScoreboardManager.update` przy każdym ticku zadania odczytuje `userRepo.load(...)` dla każdego online gracza, przez co co sekundę wykonuje blokujące zapytanie do SQLite na głównym wątku serwera. Przy większej liczbie graczy może to powodować lagi scoreboardu i całego serwera. 【F:src/main/kotlin/net/nemoria/quest/gui/ScoreboardManager.kt†L21-L40】
- **Częste odczyty/zapisy w `QuestService` podczas zdarzeń.** Metody jak `incrementObjective`, `completeObjective` czy `updateUserVariable` każdorazowo ładują i zapisują pełne `UserData` do repozytorium. Przy celach wymagających wielu iteracji (np. niszczenie bloków, zabijanie mobów) oznacza to serię synchronicznych operacji bazodanowych na głównym wątku i może stać się wąskim gardłem. 【F:src/main/kotlin/net/nemoria/quest/quest/QuestService.kt†L53-L133】【F:src/main/kotlin/net/nemoria/quest/quest/QuestService.kt†L250-L339】【F:src/main/kotlin/net/nemoria/quest/quest/QuestService.kt†L360-L382】
- **Odtwarzanie stanu po reloadzie w pętli po graczach.** `resumeActiveBranches` przy przeładowaniu iteruje po wszystkich online graczach i wczytuje ich dane z repozytorium po jednym, co w przypadku dużych serwerów opóźnia proces `reloadAll()` i ponownie wykonuje synchroniczne I/O na wątku serwera. 【F:src/main/kotlin/net/nemoria/quest/NemoriaQuestPlugin.kt†L96-L139】

## 4. Podwajanie listenerów po `reloadAll()`

`reloadAll()` rejestruje wszystkie listenery ponownie, ale nie wyrejestrowuje wcześniejszych instancji. Po każdym przeładowaniu zdarzenia (bloków, ruchu, GUI itd.) trafiają do wielu kopii handlerów, co skutkuje wielokrotnym zaliczaniem progresu, duplikacją komunikatów czy zwiększonym zużyciem CPU. W `onDisable` istnieje jedynie zamknięcie storage/scoreboardu, brak tam globalnego `HandlerList.unregisterAll`. 【F:src/main/kotlin/net/nemoria/quest/NemoriaQuestPlugin.kt†L96-L137】

## 5. Operacje na danych w wątku asynchronicznego czatu

Obsługa `AsyncPlayerChatEvent` wywołuje `handlePlayerMiscEvent`, które modyfikuje progres questa (`updateVariable`, `incrementMisc`) i wysyła komunikaty przy pomocy API Bukkita. Cała ścieżka działa w wątku asynchronicznego czatu, więc zapis do repozytorium i używanie Bukkit API nie są thread-safe i mogą powodować race condition lub ostrzeżenia „async task caused entity tracking issue”. **Przykład:** `PlayerMiscListener.onChat` przekazuje event do `handleMiscEvent`, które wywołuje `MessageFormatter.send` i `questService.updateVariable` bez przełączenia na główny wątek. 【F:src/main/kotlin/net/nemoria/quest/listener/PlayerMiscListener.kt†L14-L59】【F:src/main/kotlin/net/nemoria/quest/runtime/BranchRuntimeManager.kt†L1608-L1665】

## 6. Limit czasu questa resetuje się po reloadzie

`BranchRuntimeManager.start` zawsze planuje `timeLimitTask` na pełną długość `model.timeLimit`, nie uwzględniając ile czasu minęło przed `reloadAll()`. W trakcie przeładowania storagy są zamykane i tworzone na nowo, a aktywne gałęzie są tylko wznawiane, więc gracze dostają pełny limit jeszcze raz – limity czasu nie są egzekwowane po reloadzie. 【F:src/main/kotlin/net/nemoria/quest/runtime/BranchRuntimeManager.kt†L136-L155】【F:src/main/kotlin/net/nemoria/quest/NemoriaQuestPlugin.kt†L106-L176】
