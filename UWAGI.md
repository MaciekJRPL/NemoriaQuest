Uwagi techniczne
================

- Zadania blokowe (PLAYER_BLOCKS_*):
  - STRIP/MAKE_PATHS/FARM zliczane przy prawym kliku narzędziem – nawet jeśli blok faktycznie się nie zmieni.
  - FROST_WALK zliczane z EntityBlockFormEvent.
  - TREE_GROW liczone tylko, gdy StructureGrowEvent ma gracza.
  - Tracker allow_player_blocks zeruje się po restarcie serwera – po restarcie blok może być traktowany jako naturalny.

- Zadania entity (PLAYER_ENTITIES_*):
  - SPAWN liczone przy użyciu jajka spawn (SpawnEggMeta); nie weryfikujemy, czy mob faktycznie się pojawił.
  - DEATH_NEARBY wymaga ustawionego max_distance; jeśli brak w configu, zdarzenie jest ignorowane.
  - allow_same_entities działa tylko, gdy mamy UUID celu; w SPAWN (bez entity) brak deduplikacji.
  - TURTLES_BREED wykrywane po typie TURTLE w EntityBreedEvent.

- Zadania itemowe (PLAYER_ITEMS_*):
  - Dopasowanie itemów uproszczone do porównania materiału (ignorujemy nazwę/lore/NBT); jeśli to za mało, trzeba dopisać głębszy matcher.
  - REQUIRE sprawdza tylko posiadanie wymaganych itemów w ekwipunku; nie zabiera ich przy ukończeniu.
  - MELT/BREW: nie mamy pewnego gracza w zdarzeniach, więc aktualnie nie zliczamy tych akcji.
  - TRADE: deduplikacja po villager UUID, jeśli allow_same_villagers=false.

- Zadania ruchowe (PLAYER_MOVE_* i elytra/vehicle):
  - Dystans liczony na podstawie ticków PlayerMoveEvent/VehicleMoveEvent, bez wygładzania – może lekko zaniżać przy teleportach lub lagach.
  - WALK/SPRINT/FOOT/VEHICLE/SWIM/GLIDE rozróżniane po stanie gracza w danym ticku; możliwe częściowe nakładanie (np. sprint + foot).
  - FALL_DISTANCE liczone różnicą Y w tickach ruchu (nie “statyczny” fall distance z Vanilla) – krótko-chwilowe opadanie może się sumować.
  - HORSE_JUMP/JUMP/LAND liczone jako +1 zdarzenie (nie dystans).
  - POSITION: sprawdzanie pozycji przy każdym ruchu; brak cache – może być kosztowne przy dużej liczbie graczy/questów.

- Zadania fizyczne (bed/bucket/burn/damage/portal/sneak itp.):
  - BURN używa duration z EntityCombustEvent (sekundy) – może odbiegać od faktycznego czasu bycia w ogniu.
  - GAIN_HEALTH/TAKE_DAMAGE liczone surową wartością z eventów; brak uwzględniania redukcji/pancerza poza tym co podaje event.
  - GAIN_XP używa PlayerExpChangeEvent (amount), nie całkowitego poziomu.
  - PORTAL_ENTER/LEAVE zliczane z EntityPortalEvent/PlayerPortalEvent bez filtrowania typu portalu.
  - SHOOT_PROJECTILE: dopasowanie po nazwie typu pocisku; brak NBT.
  - VEHICLE_ENTER/LEAVE filtr po nazwie typu; deduplikacji brak.

- Zadania misc (chat/connect/disconnect/respawn/achievement/wait):
  - CHAT: walidacja whitelist/blacklist/regex/min/max; blokuje wiadomość/komendę przy błędzie; zapis do zmiennej tylko z tekstem wejściowym, bez maskowania.
  - ACHIEVEMENT używa PlayerAdvancementDoneEvent (nowe advancementy), nie stare API Achievement – nazwy kluczy muszą pasować advancementom.
  - WAIT: realizowane schedulerem; reset stop przy wylogowaniu (cancel task).
