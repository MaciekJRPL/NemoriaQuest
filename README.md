# NemoriaQuest

NemoriaQuest to nowoczesny, modularny system zadań (questów) dla serwerów Minecraft opartych o Paper 1.21.8+ (multiwersja: 1.21.8, 1.21.9, 1.21.10).
Projekt jest inspirowany QuestCreatorem, ale cała logika i formaty są napisane od nowa – prostsze, czytelniejsze i w pełni dostosowane do potrzeb serwera Nemoria.

## Najważniejsze cechy

- **Opis questów w YAML** – czytelne pliki w `content/quests`, łatwe do edycji i wersjonowania.
- **Zaawansowany system obiektów (quest objects)** - działanie kroków questa konfigurowane przez typy:
  - `SERVER_ACTIONS` (wiadomości, dźwięki, komendy, timery),
  - `SERVER_ITEMS_*` (give/take/drop/modify/clear),
  - `SERVER_ENTITIES_*` (spawn/kill/damage/teleport),
  - `SERVER_BLOCKS_*`, `SERVER_FIREWORKS_*`, `SERVER_LIGHTNING_*`,
  - `SERVER_LOGIC_*` (pieniądze, punkty, zmienne, XP),
  - `NONE`, `GROUP`, `RANDOM`, `LOGIC_SWITCH`, `CONDITIONS_SWITCH`,
  - `NPC_INTERACT` (Citizens), `DIVERGE_CHAT`, `DIVERGE_GUI`, `DIVERGE_OBJECTS`,
  - typy graczowe (pełna lista):
    - Bloki: `PLAYER_BLOCKS_BREAK/PLACE/INTERACT/IGNITE/STRIP`, `PLAYER_BLOCK_FARM`, `PLAYER_BLOCK_FROST_WALK`, `PLAYER_MAKE_PATHS`, `PLAYER_SPAWNER_PLACE`, `PLAYER_TREE_GROW`.
    - Entity: `PLAYER_ENTITIES_BREED/INTERACT/CATCH/DAMAGE/DEATH_NEARBY/DISMOUNT/GET_DAMAGED/KILL/MOUNT/SHEAR/SPAWN/TAME`, `PLAYER_TURTLES_BREED`.
    - Itemy: `PLAYER_ITEMS_ACQUIRE/BREW/CONSUME/CONTAINER_PUT/CONTAINER_TAKE/CRAFT/DROP/ENCHANT/FISH/INTERACT/MELT/PICKUP/REPAIR/REQUIRE/THROW/TRADE`.
    - Ruch: `PLAYER_MOVE`, `PLAYER_MOVE_BY_FOOT_DISTANCE`, `PLAYER_WALK_DISTANCE`, `PLAYER_SPRINT_DISTANCE`, `PLAYER_SWIM_DISTANCE`, `PLAYER_ELYTRA_FLY_DISTANCE`, `PLAYER_ELYTRA_LAND`, `PLAYER_FALL_DISTANCE`, `PLAYER_HORSE_JUMP`, `PLAYER_JUMP`, `PLAYER_VEHICLE_DISTANCE`, `PLAYER_POSITION`.
    - Fizyczne: `PLAYER_BED_ENTER/LEAVE`, `PLAYER_BUCKET_FILL`, `PLAYER_BURN`, `PLAYER_DIE`, `PLAYER_GAIN_HEALTH`, `PLAYER_GAIN_XP`, `PLAYER_PORTAL_ENTER/LEAVE`, `PLAYER_SHOOT_PROJECTILE`, `PLAYER_SNEAK`, `PLAYER_TAKE_DAMAGE`, `PLAYER_TOGGLE_SNEAK`, `PLAYER_VEHICLE_ENTER/LEAVE`.
    - Misc: `PLAYER_CHAT` (whitelist/blacklist/regex, zapis do zmiennej), `PLAYER_CONNECT`, `PLAYER_DISCONNECT`, `PLAYER_RESPAWN`, `PLAYER_WAIT`, `PLAYER_ACHIEVEMENT_AWARD`.
- **Pełne wsparcie zmiennych**:
  - zmienne modelu (`model_variables`) - trwają tylko w danym quescie (`{mvariable:nazwa}`),
  - zmienne użytkownika - trwałe per gracz (`{variable:nazwa}`),
  - zmienne serwerowe - współdzielone (`{server_variable:nazwa}`),
  - zmienne globalne – makra (`{gvariable:nazwa}`).
- **Integracje**:
  - PlaceholderAPI – placeholder `%nemoriaquest_objective_detail%`,
  - Citizens – `NPC_INTERACT` po kliknięciu w NPC,
  - kompatybilność z innymi pluginami przez komendy.
- **I18n + kolory**:
  - teksty w `texts/en_US` i `texts/pl_PL`,
  - wspólny system kolorów (`Colors` + MiniMessage),
  - wsparcie `<prefix>`, `<primary>`, `<secondary>`, `<success>`, `<error>` itd.
- **GUI questów**:
  - lista questów (`/nq gui`),
  - widok aktywnych (`/nq gui active`),
  - itemy statusu (`status_items`), `objective_detail` w opisie.
- **Scoreboard**:
  - prosty scoreboard boczny z bieżącym celem,
  - włączany globalnie w `config.yml` i per-quest w `progress_notify.scoreboard`.
- **Quest demo**: w katalogu `content/quests/mega_test_all.yml` znajduje się przykładowy quest pokrywający wszystkie typy obiektów dodane w pluginie (do szybkich testów).
- **Uwagi implementacyjne**: szczegółowe ograniczenia i niuanse działania (np. sposób liczenia dystansu, walidacja czatu, brak pełnego matchera NBT) są opisane w `UWAGI.md`.

## Wymagania

- **Serwer**: Paper 1.21.8+ (testowane na 1.21.8)
- **Java**: 21
- **Zewnętrzne pluginy (opcjonalne, ale wspierane)**:
  - PlaceholderAPI (hook na placeholdery),
  - Citizens (obsługa `NPC_INTERACT`),
  - Ekonomia (np. EssentialsX / inny, jeśli korzystasz z komend typu `eco`).

## Instalacja

1. Pobierz najnowszą wersję `NemoriaQuest.jar` z zakładki **Releases** GitHuba.
2. Wrzuć jar do folderu `plugins` serwera.
3. Uruchom serwer, aby wygenerować domyślne pliki:
   - `config.yml`,
   - `storage.yml`,
   - `texts/en_US/...`, `texts/pl_PL/...`,
   - `content/quest_template.yml` i inne zasoby.
4. Skonfiguruj bazę (SQLite domyślnie) w `storage.yml`, jeśli chcesz zmienić lokalizację.
5. Zrestartuj serwer po konfiguracji.

## Komendy

Główna komenda: `/nemoriaquest` (alias `/nq`).

- `/nq` – wyświetla pomoc i listę dostępnych komend.
- `/nq start <id>` – start questa o podanym ID.
- `/nq stop <id>` – zatrzymanie aktywnego questa.
- `/nq active` – lista aktywnych questów gracza.
- `/nq list` – lista wszystkich zarejestrowanych questów.
- `/nq info <id>` – szczegóły questa (opis, cele, wymagania).
- `/nq complete <id>` – ręczne oznaczenie questa jako ukończonego (administracyjne).
- `/nq progress <questId> <objectiveId>` – ręczne ukończenie konkretnego celu.
- `/nq debug <on|off>` – włączenie/wyłączenie szczegółowego logowania debug.
- `/nq gui` – otwiera główne GUI questów.
- `/nq gui active` – otwiera GUI tylko z aktywnymi questami.

Uprawnienia są rozbite per komendę (np. `nemoriaquest.command.start`, `nemoriaquest.command.list` itd.).

## Struktura plików

### Konfiguracja główna

`plugins/NemoriaQuest/config.yml`:

```yml
config-version: '1.0'
core:
  multi_version: ["1.21.8", "1.21.9", "1.21.10"]
  locale: en_US   # lub pl_PL
  debug: false
  scoreboard_enabled: true
logging:
  level: INFO
```

### Teksty / I18n

- `texts/en_US/messages.yml`
- `texts/pl_PL/messages.yml`

Zawierają:

- `prefix` – bazowy prefix, np. `<prefix> <secondary>`,
- `command.*` – komunikaty komend,
- `scoreboard.*` – teksty używane w scoreboardzie,
- `help.*` – teksty pomocy.

W wiadomościach możesz używać:

- `<prefix>` → `Colors.PREFIX_TEXT`,
- `<primary>`, `<secondary>`, `<success>`, `<error>`, `<info>`, `<admin>` – zdefiniowane w `Colors.kt`,
- pełnego MiniMessage (np. `<gold>`, `<#71478A>`, `<bold>`, `<center>`).

### Template questa

`content/templates/quest_template.yml` – przykład questa demonstrujący większość funkcji:

- `model_variables` (np. `progress`, `dp`),
- `branches` z typami: `SERVER_ACTIONS`, `NONE`, `GROUP`, `DIVERGE_CHAT`, `DIVERGE_GUI`, `DIVERGE_OBJECTS`, `RANDOM`, `SERVER_ITEMS_*`, `SERVER_ENTITIES_*`, itd.,
- `objective_detail` z placeholderami `{mvariable:dp}`.

## System zmiennych

### Zmienne modelu (questu)

Zdefiniowane w pliku questa:

```yml
model_variables:
  dp: 0
  progress: 0
```

Użycie:

- `SERVER_LOGIC_MODEL_VARIABLE` – modyfikacja zmiennej modelu,
- `{mvariable:dp}` – w tekstach, GUI, scoreboardzie.

### Zmienne użytkownika

Trwałe per gracz, z domyślnymi wartościami w `default_variables.yml`:

```yml
example_var: 0
```

Użycie:

- `SERVER_LOGIC_VARIABLE` – modyfikacja,
- `{variable:example_var}` w tekstach/logic.

### Zmienne serwerowe

Globalne, w `server_variables.yml`:

```yml
server_counter: 0
```

Użycie:

- `SERVER_LOGIC_SERVER_VARIABLE` – modyfikacja,
- `{server_variable:server_counter}` w tekstach.

### Zmienne globalne (makra)

W `global_variables.yml`:

```yml
message_prefix: '&6[Quest] &7'
```

Użycie:

- `{gvariable:message_prefix}` w configach.

## GUI i scoreboard

### GUI

Konfiguracja GUI w `gui/default.yml` i `gui/active.yml`:

- `type` – rozmiar (np. `CHEST_6_ROW`),
- `show_status` – jakie statusy pokazywać (`AVAILABLE`, `PROGRESS`),
- `order_quests`, `sort_quests_by_status`.

Itemy questów:

- bazują na `status_items` z pliku modelu,
- w lore dostępny jest `objective_detail` oraz (jeśli chcesz) `detailed_progression`,
- kolory i placeholdery MiniMessage działają.

### Scoreboard

Sterowany globalnie (`core.scoreboard_enabled`) i per quest (`progress_notify.scoreboard: true`).

Wyświetla:

- nazwę questa,
- bieżący `objective_detail` (renderowany z placeholderami).

Przy braku aktywnego questa lub wyłączonym scoreboardzie pokazuje tekst z `scoreboard.empty`.

## PlaceholderAPI

Jeśli PlaceholderAPI jest zainstalowany, plugin rejestruje ekspansję:

- `%nemoriaquest_objective_detail%` – aktualny cel (opis węzła) dla pierwszego aktywnego questa gracza.

Możesz używać tego placeholdera w:

- BetterHud,
- tablistach,
- scoreboardach zewnętrznych,
- innych pluginach obsługujących PlaceholderAPI.

## Licencja

Projekt NemoriaQuest jest udostępniany na otwartej licencji (np. MIT lub Apache 2.0 – zgodnie z plikiem `LICENSE` w repozytorium).

Zewnętrzne zależności (Paper API, PlaceholderAPI, itp.) mają swoje własne licencje – korzystając z NemoriaQuest na serwerze masz obowiązek respektować także ich warunki.
