# Bug Report Analyse — Findroid-CE crasht zonder internetverbinding

> Status: **alleen analyse**. Geen code gewijzigd, geen tests, geen commits.
> Datum: 2026-05-19 · Branch: `main`

## Samenvatting

Findroid-CE crasht volledig (proces sterft) bij het opstarten zodra er geen
internetverbinding is. De oorzaak is een CE-specifieke "performance"-wijziging in
`HomeViewModel.loadData()` die het laden van het Home-scherm parallelliseert met
`async { … }` + `awaitAll(…)`. Door de structured-concurrency-regels van Kotlin
propageert een exception uit een `async`-childcoroutine *direct* naar de
parent-`Job`, onafhankelijk van de `try/catch` rond `awaitAll`. De netwerkfout
(`UnknownHostException` e.d.) wordt daardoor een *uncaught exception* en de app
crasht. Wie vooraf handmatig Offline mode inschakelt raakt de online repository —
en dus de netwerk-call — nooit, en ontsnapt aan de crash.

## Root cause

**Bestand:** `modes/film/src/main/java/dev/jdtech/jellyfin/film/presentation/home/HomeViewModel.kt`
**Functie:** `loadData()` — regels 48–68
**Kern van het probleem:** regels 50–65

```kotlin
fun loadData() {
    Timber.i("Loading data")
    viewModelScope.launch(Dispatchers.Default) {          // regel 50  — parent Job
        _state.emit(_state.value.copy(isLoading = true, error = null))
        try {                                             // regel 52
            appPreferences.getValue(appPreferences.currentServer)?.let { serverId ->
                loadServerName(serverId)
            }

            awaitAll(                                     // regel 57
                async { loadSuggestions() },              // regel 58  — child Job
                async { loadResumeItems() },              // regel 59  — child Job
                async { loadNextUpItems() },              // regel 60  — child Job
                async { loadViews() },                    // regel 61  — child Job
            )
        } catch (e: Exception) {                          // regel 63  — vangt te laat
            _state.emit(_state.value.copy(error = e))
        }
        _state.emit(_state.value.copy(isLoading = false))
    }
}
```

### Waarom dit crasht

`HomeScreen` roept `loadData()` aan via `LaunchedEffect(true)` zodra het scherm
wordt samengesteld (`app/phone/.../presentation/film/HomeScreen.kt:61`). Voor een
ingelogde gebruiker is `HomeRoute` de start-destination (`NavigationRoot.kt:144`),
dus dit draait meteen bij app-start.

De vier `loadXxx()`-functies doen HTTP-calls naar de Jellyfin-server via de
*online* repository (`JellyfinRepositoryImpl`, zie hieronder). Zonder netwerk
gooit de Jellyfin-SDK / Ktor-client een exception (`UnknownHostException`,
`ConnectException`, of een wrapper daarvan).

Het beslissende punt is Kotlin's structured concurrency:

1. `viewModelScope.launch { … }` maakt een gewone (niet-supervisor) coroutine —
   de **parent-`Job`**.
2. Elke `async { … }` start een **child-coroutine** van die parent.
3. Wanneer een `async`-block faalt, propageert die exception **onmiddellijk naar
   de parent-`Job`** en annuleert die — *los van* of en wanneer `await()` wordt
   aangeroepen. Dit is exact het gedocumenteerde "`async` als child-coroutine"-
   gedrag: een falende child-coroutine wordt altijd aan de parent gemeld.
4. De parent-`launch` is op zijn beurt een directe child van de `SupervisorJob`
   van `viewModelScope`. Een supervisor "absorbeert" het falen van zijn child
   niet stilletjes — hij draagt het over aan de `CoroutineExceptionHandler`. Er
   is geen handler geïnstalleerd, dus de exception belandt bij de
   `Thread.defaultUncaughtExceptionHandler` → **app-crash**.
5. De `try/catch` rond `awaitAll` (regel 63) vangt weliswaar de exception die
   `await()` *herwerpt*, maar dat is te laat: de parent-`Job` is op dat moment al
   geannuleerd door stap 3. De catch maakt die annulering niet ongedaan. De
   exception wordt dus zowel "afgehandeld" door de catch als — onafhankelijk —
   als uncaught gerapporteerd.

Kort gezegd: **`try/catch` rond `awaitAll` kan een falende `async`-childcoroutine
niet inkapselen.** Daar is een `coroutineScope { }` of `supervisorScope { }` voor
nodig (zie fix-richtingen).

### Bewijs — de netwerk-calls die falen

`data/src/main/java/dev/jdtech/jellyfin/repository/JellyfinRepositoryImpl.kt`:

```kotlin
override suspend fun getSuggestions(): List<FindroidItem> =        // regel 219
    withContext(Dispatchers.IO) {
        jellyfinApi.suggestionsApi.getSuggestions( … )             // HTTP-call
    }

override suspend fun getResumeItems(): List<FindroidItem> =        // regel 232
    withContext(Dispatchers.IO) { jellyfinApi.itemsApi.getResumeItems( … ) }

override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> =  // regel 266
    withContext(Dispatchers.IO) { jellyfinApi.showsApi.getNextUp( … ) }

override suspend fun getUserViews(): List<BaseItemDto> =           // regel 70
    … jellyfinApi.viewsApi.getUserViews(currentUserId) …
```

Alle vier de `loadXxx()`-functies in `HomeViewModel` raken minstens één van deze
calls. Zonder netwerk faalt elke call.

### Bewijs — welke repository wordt gekozen

`core/src/main/java/dev/jdtech/jellyfin/di/RepositoryModule.kt:49–60`:

```kotlin
@Provides
fun provideJellyfinRepository( … ): JellyfinRepository {
    return when (appPreferences.getValue(appPreferences.offlineMode)) {
        true  -> jellyfinRepositoryOfflineImpl    // leest uit lokale Room-DB
        false -> jellyfinRepositoryImpl           // doet HTTP-calls
    }
}
```

De keuze online/offline hangt **uitsluitend** af van de preference
`appPreferences.offlineMode` — een handmatige toggle in Settings. Er is **geen
automatische connectivity-detectie**: een grep op `ConnectivityManager` /
`NetworkCapabilities` / `isNetworkAvailable` in de app-modules levert niets op.
`MainViewModel.checkIsOfflineMode()` (`core/.../viewmodels/MainViewModel.kt:84`)
leest dezelfde preference en geeft die door als `LocalOfflineMode`
(`MainActivity.kt:32`).

Dit verklaart de waargenomen workaround precies:

- **Geen Offline mode → crash.** `offlineMode = false`, dus
  `JellyfinRepositoryImpl` wordt geïnjecteerd, `HomeViewModel` doet HTTP-calls,
  de `async`-coroutines falen, de app crasht.
- **Wel Offline mode → geen crash.** `offlineMode = true`, dus
  `JellyfinRepositoryOfflineImpl` wordt geïnjecteerd. `getSuggestions()` e.d.
  lezen dan uit de lokale Room-database; er wordt geen HTTP-call gedaan, er valt
  niets te gooien.
- **De workaround** (hotspot aan → Offline mode aanzetten → downloads bekijken)
  is nodig omdat de gebruiker eerst internet moet hebben om het Home-scherm te
  overleven en zo bij Settings te komen om de Offline-toggle te zetten.

### Introducerende commit

```
commit 8f5daaf16f3e0a43d84b76f05a5cda0a2f65386f
Author: Midas van Oene
Date:   2026-04-04 21:28:23 +0200

    perf: parallelize home screen data loading with async/awaitAll
```

Deze commit zit **wel** in `main` (CE) en **niet** in `upstream/main`
(`git merge-base --is-ancestor 8f5daaf1 upstream/main` → niet in upstream). Het is
de enige plek in de hele codebase waar `async {` / `awaitAll` voorkomt.

## Vergelijking met upstream

Upstream Findroid (`upstream/main`) laadt het Home-scherm **sequentieel** binnen
één enkele coroutine:

```kotlin
// upstream/main — HomeViewModel.loadData()
viewModelScope.launch(Dispatchers.Default) {
    _state.emit(_state.value.copy(isLoading = true, error = null))
    try {
        appPreferences.getValue(appPreferences.currentServer)?.let { serverId ->
            loadServerName(serverId)
        }
        loadSuggestions()
        loadResumeItems()
        loadNextUpItems()
        loadViews()
    } catch (e: Exception) {
        _state.emit(_state.value.copy(error = e))
    }
    _state.emit(_state.value.copy(isLoading = false))
}
```

Hier zijn `loadSuggestions()` enz. *directe* `suspend`-aanroepen in de body van
de `launch`-coroutine — geen child-coroutines. Een exception wordt dus gewoon in
de coroutine-body geworpen en valt netjes in de `try/catch`. Geen
parent-`Job`-propagatie, geen uncaught exception, geen crash. Upstream toont in
plaats daarvan een foutmelding op het Home-scherm (`state.error`).

De rest van de offline-architectuur (de `offlineMode`-preference-switch in
`RepositoryModule`) is identiek aan CE — dat is dus géén regressie. **De enige
relevante divergentie is de `async`/`awaitAll`-parallelliseringscommit.**

## Validatie met geautomatiseerde tests

De diagnose is bevestigd met geautomatiseerde JVM-tests (geen toestel nodig). Het
project had geen test-infrastructuur; daarvoor is `junit` + `kotlinx-coroutines-test`
toegevoegd aan `gradle/libs.versions.toml` en een `testImplementation`-blok aan
`modes/film/build.gradle.kts`.

Draaien met: `./gradlew :modes:film:testDebugUnitTest`

**`LoadDataConcurrencyMechanismTest`** — speelt het coroutine-patroon van
`loadData()` na in isolatie (geen Android/SDK), met een `SupervisorJob`-scope +
`CoroutineExceptionHandler` zoals `viewModelScope`:

| Test | Patroon | Resultaat |
|---|---|---|
| `async child failure escapes the try-catch around awaitAll` | CE (`awaitAll(async{…})`) | exception **ontsnapt** naar de exception-handler → crash |
| `sequential suspend calls are fully contained by the try-catch` | upstream (sequentieel) | exception **ingekapseld**, geen crash |
| `coroutineScope wrapper keeps the async failure inside the try-catch` | fix-optie A | exception **ingekapseld**, geen crash |

**`HomeViewModelOfflineCrashTest`** — construeert de **echte** `HomeViewModel` met
een repository die elke call met `IOException` laat falen (≙ geen netwerk):

| Test | Resultaat |
|---|---|
| `loadData leaks an uncaught exception when the device is offline` | `loadData()` lekt een uncaught `IOException` naar de default uncaught-exception-handler → crash |

Alle vier de tests slagen: de mechanisme-test bewijst *waarom* het crasht én dat
zowel de upstream-variant als fix-optie A het wél inkapselen; de integratietest
bevestigt het op de productiecode.

## Voorgestelde fix-richtingen

> Nog niet geïmplementeerd — ter beoordeling. Fix-optie A is hieronder al
> mee-gevalideerd in `LoadDataConcurrencyMechanismTest`.

### Optie A — `coroutineScope { }` rond `awaitAll` (aanbevolen)

Wikkel het parallelle deel in een `coroutineScope { … }`. Een `coroutineScope`
levert het falen van een child af door de exception op de aanroepplek te
*herwerpen* in plaats van naar de `launch`-parent te propageren als uncaught
exception. De bestaande `try/catch` vangt hem dan correct.

```kotlin
try {
    …
    coroutineScope {
        awaitAll(
            async { loadSuggestions() },
            async { loadResumeItems() },
            async { loadNextUpItems() },
            async { loadViews() },
        )
    }
} catch (e: Exception) {
    _state.emit(_state.value.copy(error = e))
}
```

- **Voordeel:** behoudt de parallelle laadwinst van commit `8f5daaf1`; minimale
  wijziging; semantiek gelijk aan upstream (eerste fout → globale error-state).
- **Trade-off:** bij de eerste fout worden de overige calls geannuleerd; je ziet
  altijd één foutmelding voor het hele scherm.

### Optie B — terugdraaien naar sequentieel (upstream-gedrag)

Vervang `awaitAll(async { … })` door de vier directe aanroepen, exact zoals
upstream.

- **Voordeel:** bewezen-correct, simpel, identiek aan upstream.
- **Trade-off:** verliest de parallelle laadwinst die commit `8f5daaf1` beoogde.

### Optie C — per-sectie afvangen (meest robuust qua UX)

Geef elke `async` een eigen `try/catch` (of `supervisorScope` + per-`await()`
afvangen) zodat één falende sectie de andere niet sloopt. Het Home-scherm kan dan
gedeeltelijk laden (de secties die wél lukten).

- **Voordeel:** robuustst; bij wisselende connectiviteit verschijnen de secties
  die wél lukken.
- **Trade-off:** meer code; gedragsverandering t.o.v. upstream (geen enkele
  globale error meer); vereist een keuze hoe een gedeeltelijke fout te tonen.

### Aanvullende overweging (geen regressie, wel product-vraag)

Ook na de fix krijgt een offline gebruiker bij opstart een foutmelding op Home en
moet hij zelf naar Settings → Offline mode. Dat is identiek aan upstream-gedrag
en dus géén CE-regressie. Wil je dit verbeteren, dan is een aparte feature nodig:
automatische connectivity-detectie (`ConnectivityManager`) die bij geen netwerk
automatisch de offline repository kiest. Dat valt **buiten** de scope van deze
bugfix en is een bewuste productkeuze.

## Reproductiestappen (handmatige verificatie)

Vooraf: een toestel/emulator met Findroid-CE, ingelogd op een server, Offline
mode **uit**.

**Crash reproduceren**
1. Zet het toestel volledig offline (vliegtuigmodus, of Wi-Fi uit op een
   Wi-Fi-only tablet).
2. Sluit Findroid-CE volledig af (uit recents vegen).
3. Start Findroid-CE.
4. **Verwacht (bug):** de app crasht tijdens/na het opstarten op het
   Home-scherm.
5. Controleer `adb logcat` op een `FATAL EXCEPTION` met een
   `UnknownHostException` / `ConnectException` afkomstig uit een
   `DefaultDispatcher` / `kotlinx.coroutines`-frame, getriggerd vanuit
   `HomeViewModel`.

**Workaround / contrast bevestigen**
6. Zet internet aan, start de app, ga naar Settings en schakel Offline mode in.
7. Zet internet weer uit en herstart de app.
8. **Verwacht:** geen crash; de app opent en downloads zijn zichtbaar
   (`JellyfinRepositoryOfflineImpl` actief).

**Na de fix (verwacht resultaat)**
9. Met Offline mode **uit** en internet **uit**: de app start zonder crash en
   toont een foutmelding/retry op het Home-scherm (zoals upstream).

## Open vragen

1. **Exact SDK-exceptiontype op een echt toestel.** Het crash-mechanisme is
   bevestigd met tests (zie *Validatie*), maar de tests simuleren de netwerkfout
   met een generieke `IOException`. Een echte `adb logcat`-stacktrace zou nog
   bevestigen welk type de Jellyfin-SDK precies gooit (`UnknownHostException`
   direct, of gewrapt door Ktor/de SDK). Dit verandert de root cause niet —
   elk `Exception`-type uit een `async`-child geeft dezelfde crash.
2. **Niet-ingelogde gebruiker.** Deze analyse gaat uit van een ingelogde
   gebruiker (start-destination = `HomeRoute`). Of een gebruiker zonder
   geldige sessie ook crasht is niet onderzocht; de setup-/login-schermen
   (`WelcomeRoute` → `LoginRoute`) hebben hun eigen netwerklogica buiten
   `HomeViewModel`. Voor de gemelde gebruiker (heeft downloads, dus ingelogd) is
   `HomeRoute` het relevante pad.
3. **TV-module.** De analyse betreft `app/phone`. Of `app/tv` een eigen
   `HomeViewModel`-equivalent of hetzelfde patroon heeft, is niet geverifieerd
   (de reproductie is een tablet met de phone-UI).
4. **Andere schermen, andere VM's.** `async`/`awaitAll` komt alleen voor in
   `HomeViewModel`. Andere ViewModels die bij CE zijn aangepast (`MovieViewModel`,
   `EpisodeViewModel`, `SeasonViewModel`, …) gebruiken dit patroon niet en zijn
   daarom niet als crash-bron meegenomen — maar zijn niet regel-voor-regel
   geaudit op andere offline-gevoelige paden.
