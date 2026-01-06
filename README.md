# NemoriaQuest

**Modularny system questów dla serwerów Minecraft Paper 1.21.x**

NemoriaQuest to zaawansowany plugin do tworzenia i zarządzania questami na serwerze Minecraft. Pozwala na tworzenie złożonych, nieliniowych misji z dialogami, wyborami gracza, systemem nagród i wieloma innymi funkcjami.

## Co potrafi NemoriaQuest?

### Podstawowe funkcje

- **Tworzenie questów** - Twórz własne misje używając plików YAML
- **System gałęzi** - Twórz nieliniowe questy z różnymi ścieżkami
- **Dialogi z NPC** - Interakcje z NPC z wyborami gracza
- **System nagród** - Nagradzaj graczy itemami, pieniędzmi, komendami
- **Zmienne** - Śledź postęp gracza i questa używając zmiennych
- **Warunki** - Wymagaj spełnienia warunków przed startem questa
- **Limity czasowe** - Ustaw czas na ukończenie misji
- **Cooldowny** - Ogranicz częstotliwość wykonywania questów
- **GUI** - Interaktywne menu do przeglądania questów
- **Scoreboard** - Wyświetlaj postęp na scoreboardzie
- **Actionbar i Title** - Powiadomienia o postępie

### Zaawansowane funkcje

- **Pools questów** - System puli z tokenami i limitami czasowymi
- **Grupy węzłów** - Równoległe wykonywanie zadań
- **Losowe ścieżki** - Element losowości w questach
- **Warunkowe przełączniki** - Różne ścieżki w zależności od warunków
- **Integracja z Citizens** - Pełna obsługa NPC z Citizens
- **Cząsteczki** - Efekty wizualne przy NPC
- **PlaceholderAPI** - Integracja z innymi pluginami
- **Wielojęzyczność** - Obsługa polskiego i angielskiego

## Jak to działa?

### Podstawowa struktura questa

Każdy quest to plik YAML w katalogu `content/quests/`. Quest składa się z:

1. **Informacji podstawowych** - ID, nazwa, opis
2. **Gałęzi (branches)** - Ścieżki wykonania questa
3. **Węzłów (nodes)** - Poszczególne kroki w quecie
4. **Warunków i wymagań** - Co musi być spełnione
5. **Nagród** - Co gracz otrzyma po ukończeniu

### Przykład prostego questa

```yaml
id: moj_quest
name: Mój Pierwszy Quest
display_name: "&aPrzynieś 10 kamieni"

branches:
  MAIN:
    starts_at: START
    objects:
      START:
        type: SERVER_ACTIONS
        actions:
          - "SEND_MESSAGE &aWitaj! Przynieś mi 10 kamieni."
        goto: OBJECT ZBIERANIE

      ZBIERANIE:
        objective_detail: "Zebrano {mvariable:kamienie} z 10"
        type: PLAYER_ITEMS_REQUIRE
        items:
          - type: STONE
            amount: 10
        goto: QUEST_SUCCESS

end_objects:
  SUCCESS:
    1:
      type: SERVER_ACTIONS
      actions:
        - "SEND_MESSAGE &aDziękuję! Oto twoja nagroda."
        - "SEND_TITLE 10 70 10 &aUkończono! &7Dobrze zrobione"
    2:
      type: SERVER_ITEMS_GIVE
      items:
        - type: DIAMOND
          amount: 5
```

### Jak gracz wykonuje quest?

1. **Start questa** - Gracz może rozpocząć quest przez:
   - Komendę `/nq start <questId>`
   - Interakcję z NPC (jeśli quest ma activator)
   - Automatyczny start (jeśli skonfigurowano)

2. **Wykonywanie** - Quest prowadzi gracza przez kolejne węzły:
   - Wykonywanie zadań (zabijanie mobów, zbieranie itemów, itp.)
   - Rozmowy z NPC
   - Wybory w dialogach
   - Dotarcie do określonych miejsc

3. **Ukończenie** - Po spełnieniu wszystkich warunków:
   - Quest automatycznie się kończy
   - Gracz otrzymuje nagrody
   - Postęp jest zapisywany

## Główne funkcje w praktyce

### 1. Dialogi z NPC

Quest może zawierać rozmowy z NPC, gdzie gracz wybiera odpowiedzi:

```yaml
DIVERGE_CHAT:
  type: DIVERGE_CHAT
  choices:
    1:
      text: "&aTak, pomogę!"
      goto: OBJECT POMOC
    2:
      text: "&cNie, nie mam czasu"
      goto: OBJECT ODMOWA
```

### 2. System zmiennych

Śledź postęp używając zmiennych:

```yaml
model_variables:
  zebrane_kamienie: 0

# W węźle:
type: SERVER_LOGIC_VARIABLE
variable: zebrane_kamienie
value_formula: "{value} + 1"
```

### 3. Warunki startu

Wymagaj spełnienia warunków przed startem:

```yaml
conditions:
  - type: ITEMS
    item: DIAMOND
    item_amount: 1
  - type: PERMISSION
    permission: questy.premium
```

### 4. Limity czasowe

Ustaw czas na ukończenie:

```yaml
time_limit:
  duration: 1 HOUR
  fail_goto: QUEST_FAIL
```

### 5. System pools

Twórz pule questów z tokenami:

```yaml
# W pliku pool:
id: dzienne_wyzwania
amount: 3
order: RANDOM
quests:
  quest1:
    quest_id: wyzwanie_1
    min_tokens: 1
    max_tokens: 1
```

## Komendy

### Dla graczy

- `/nq` - Lista dostępnych komend
- `/nq list` - Lista wszystkich questów
- `/nq active` - Twoje aktywne questy
- `/nq info <questId>` - Informacje o quecie
- `/nq gui` - Otwórz menu questów
- `/nq actionbar` - Włącz/wyłącz powiadomienia na actionbar
- `/nq title` - Włącz/wyłącz powiadomienia w tytule

### Dla administratorów

- `/nq start <questId>` - Wymuś start questa
- `/nq stop <questId>` - Zatrzymaj quest
- `/nq complete <questId>` - Oznacz quest jako ukończony
- `/nq progress <questId> <objectiveId>` - Ukończ cel
- `/nq reload` - Przeładuj konfigurację
- `/nq debug <on|off>` - Tryb debugowania
- `/nq goto <questId> <branchId> <nodeId>` - Skocz do węzła (debug)

## Konfiguracja

### Plik config.yml

Główne ustawienia pluginu:

```yaml
core:
  locale: pl_PL  # Język (pl_PL lub en_US)
  debug: false   # Tryb debugowania
  scoreboard_enabled: true  # Włącz scoreboard

progress_notify_loop_actionbar: true  # Powiadomienia actionbar
progress_notify_loop_title: true       # Powiadomienia tytuł
```

### Plik storage.yml

Konfiguracja bazy danych:

```yaml
backend: SQLITE  # SQLITE lub MYSQL
sqlite:
  file: "plugins/NemoriaQuest/nemoriaquest.db"
```

## Struktura katalogów

```
NemoriaQuest/
├── config.yml              # Główna konfiguracja
├── storage.yml             # Konfiguracja bazy danych
├── scoreboard.yml          # Konfiguracja scoreboard
├── content/
│   ├── quests/            # Pliki YAML z questami
│   ├── pools/             # Konfiguracje pul questów
│   ├── groups/            # Grupy questów
│   ├── activators/        # Aktywatory NPC
│   ├── particle_scripts/  # Skrypty cząsteczek
│   └── variables/         # Dodatkowe zmienne
├── gui/
│   ├── default.yml        # Główne menu GUI
│   └── active.yml         # Menu aktywnych questów
└── texts/
    ├── pl_PL/            # Teksty polskie
    └── en_US/            # Teksty angielskie
```

## Przykłady questów

### Przykład 1: Prosty quest zbierania

```yaml
id: zbierz_drewno
name: Zbierz Drewno
display_name: "&6Zbierz 20 drewna"

branches:
  MAIN:
    starts_at: START
    objects:
      START:
        type: SERVER_ACTIONS
        actions:
          - "SEND_MESSAGE &eZbierz 20 kawałków drewna!"
        goto: OBJECT ZBIERANIE

      ZBIERANIE:
        objective_detail: "Zebrano {mvariable:drewno} z 20"
        type: PLAYER_ITEMS_REQUIRE
        items:
          - type: OAK_LOG
            amount: 20
        goto: QUEST_SUCCESS

end_objects:
  SUCCESS:
    1:
      type: SERVER_ITEMS_GIVE
      items:
        - type: GOLD_INGOT
          amount: 10
```

### Przykład 2: Quest z NPC i dialogiem

```yaml
id: rozmowa_z_kupcem
name: Rozmowa z Kupcem

branches:
  MAIN:
    starts_at: START
    objects:
      START:
        type: NPC_INTERACT
        npc: 1
        click_types: [RIGHT_CLICK]
        goto: OBJECT DIALOG

      DIALOG:
        type: DIVERGE_CHAT
        choices:
          1:
            text: "&aKupię coś"
            goto: OBJECT KUPNO
          2:
            text: "&cNie, dziękuję"
            goto: QUEST_FAIL

      KUPNO:
        type: SERVER_ACTIONS
        actions:
          - "SEND_MESSAGE &aDziękuję za zakup!"
        goto: QUEST_SUCCESS
```

## Wsparcie i pomoc

### Częste pytania

**Q: Jak stworzyć swój pierwszy quest?**  
A: Skopiuj szablon z `content/templates/quest_template.yml` do `content/quests/` i zmodyfikuj go.

**Q: Quest nie startuje, co robić?**  
A: Sprawdź czy spełniasz wszystkie warunki i wymagania. Użyj `/nq debug on` aby zobaczyć szczegóły.

**Q: Jak dodać nagrodę?**  
A: Użyj sekcji `end_objects.SUCCESS` i dodaj węzły typu `SERVER_ITEMS_GIVE`, `SERVER_LOGIC_MONEY` itp.

**Q: Czy mogę używać kolorów w tekstach?**  
A: Tak! Używaj kodów kolorów `&a`, `&c` itp. lub hex colors `<#RRGGBB>`.

## Wymagania

- **Minecraft**: Paper 1.21.x
- **Java**: 21+
- **Opcjonalne integracje**:
  - Citizens (dla NPC)
  - PlaceholderAPI (dla placeholderów)
  - Vault (dla ekonomii)
  - PacketEvents (dla zaawansowanych funkcji)

## Instalacja

1. Pobierz plugin z releases
2. Umieść plik `.jar` w folderze `plugins/`
3. Uruchom serwer
4. Skonfiguruj `config.yml` i `storage.yml`
5. Stwórz swoje pierwsze questy w `content/quests/`
6. Użyj `/nq reload` aby załadować questy

## Autorzy

NemoriaQuest został stworzony przez **Nemoria / MaciekJRPL**

## Licencja

Sprawdź plik `LICENSE` w repozytorium.

---

**Gotowy do tworzenia niesamowitych questów? Zacznij od prostego przykładu i rozwijaj swoje umiejętności!**

