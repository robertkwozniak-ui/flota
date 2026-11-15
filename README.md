# Flota firmowa — aplikacja na Androida

Aplikacja do rejestracji przebiegu pojazdu, współpracująca z wtyczką
WordPress **Flota firmowa**. Robi dokładnie jedną rzecz, której nie potrafi
przeglądarka telefonu: **nagrywa trasę po zgaszeniu ekranu**.

Ekran, który widzi kierowca, to ta sama strona ewidencji co w przeglądarce.
Aplikacja podstawia pod nią natywną usługę GPS. Każda poprawka wtyczki trafia
do aplikacji od razu — bez nowego pliku APK.

## Co daje aplikacja, czego nie da przeglądarka

| | Przeglądarka | Aplikacja |
|---|---|---|
| Trasa przy zgaszonym ekranie | urywa się | nagrywa dalej |
| Trasa po przełączeniu na inną aplikację | urywa się | nagrywa dalej |
| Witryna na HTTP (bez certyfikatu) | brak dostępu do GPS | działa |
| Punkty przy braku zasięgu | bufor w przeglądarce | bufor na dysku, odporny na ubicie procesu |
| Zużycie baterii | ekran musi świecić | ekran może być zgaszony |

Wiążący przebieg nadal pochodzi ze stanu licznika potwierdzonego przez
kierowcę. GPS służy do opisu trasy, wykrywania postojów i kontroli, czy
wpisany stan licznika ma sens.

## Jak to działa

Repozytorium buduje aplikację samo. Każda zmiana na gałęzi `main` uruchamia
GitHub Actions: powstaje podpisany plik APK i trafia do
[Releases](../../releases/latest) jako najnowsze wydanie.

Aplikacja jest ustawiona na witrynę **https://kultura.bialeblota.pl**
(plik `gradle.properties`).

### Jednorazowa konfiguracja: hasło do klucza podpisu

**Settings → Secrets and variables → Actions → New repository secret**

| Nazwa | Wartość |
|---|---|
| `KEYSTORE_HASLO` | hasło do klucza podpisu |

Klucz (`podpis/flota.p12`) leży w repozytorium w postaci zaszyfrowanej;
bez hasła jest bezużyteczny. Hasło trzeba przechować także poza GitHubem —
jego utrata oznacza, że kolejnych wersji nie da się zainstalować na wierzch
i aplikację trzeba będzie odinstalować na każdym telefonie.

Bez tego sekretu budowanie kończy się wersją testową i **nic nie jest
publikowane**.

### Pierwsza instalacja na telefonie

1. Na telefonie otwórz stronę [najnowszego wydania](../../releases/latest)
   i pobierz **flota.apk**.
2. Otwórz pobrany plik. Android zapyta o zgodę na instalację z tego źródła —
   to normalne przy aplikacji spoza Google Play.
3. Wklej link do ewidencji otrzymany mailem. Aplikacja go zapamięta.

### Aktualizacje

Aplikacja przy otwarciu sprawdza, czy jest nowsze wydanie, pobiera je
i weryfikuje sumę kontrolną.

| Android | Jak przebiega aktualizacja |
|---|---|
| 12 i nowszy | pierwsza: jedno dotknięcie „Zaktualizuj"; kolejne po cichu, z powiadomieniem „Aplikacja zaktualizowana" |
| 8–11 | za każdym razem jedno dotknięcie „Zaktualizuj" |

W pełni niewidoczna aktualizacja na każdym telefonie jest możliwa tylko
przez Google Play albo system MDM — to ograniczenie Androida, nie aplikacji.

Aktualizacja nigdy nie instaluje się w trakcie przejazdu: instalacja
zatrzymuje aplikację, a z nią nagrywanie trasy. Pobrana paczka czeka do
zakończenia przejazdu.

## Uprawnienia, o które pyta aplikacja

Android pyta o nie etapami i nie da się tego skrócić — prośba o wszystko
naraz kończy się cichą odmową dla tła.

| Uprawnienie | Po co |
|---|---|
| Lokalizacja (dokładna) | zapis trasy |
| Lokalizacja **„Zezwalaj zawsze"** | nagrywanie przy zgaszonym ekranie; bez tego trasa urywa się po kilkudziesięciu sekundach |
| Powiadomienia | powiadomienie „Trasa nagrywana"; Android wymaga go dla usługi pierwszego planu |

Powiadomienia nie da się ukryć — to warunek, na jakim system pozwala
czytać GPS w tle. Pokazuje przebieg i liczbę punktów czekających na
wysłanie, więc kierowca widzi, czy zapis działa.

> **Obowiązek informacyjny.** Aplikacja zapisuje położenie służbowego
> pojazdu w czasie pracy. Pracownik musi o tym wiedzieć — art. 22³ Kodeksu
> pracy wymaga poinformowania o monitorowaniu najpóźniej dwa tygodnie przed
> wdrożeniem. W ustawieniach wtyczki jest przełącznik zgody wyświetlanej
> przy pierwszym uruchomieniu i oznaczanie jazd prywatnych.

## Rozwiązywanie problemów

**Trasa urywa się po zablokowaniu telefonu** — brakuje uprawnienia
„Zezwalaj zawsze" albo system oszczędzania energii usypia aplikację.
W ustawieniach telefonu: *Aplikacje → Flota firmowa → Bateria →
Bez ograniczeń*. Na telefonach Xiaomi, Huawei, Samsunga i Oppo trzeba
dodatkowo wyłączyć automatyczną optymalizację dla tej aplikacji.

**„Link stracił ważność"** — token poleceń wyjazdu jest ważny do zakończenia
polecenia. Dla zwykłej ewidencji użyj stałego linku pojazdu z listy pojazdów.

**Budowanie kończy się błędem** — otwórz nieudany przebieg w Actions
i sprawdź pierwszą czerwoną linię.

## Wymagania

- Android 8.0 (API 26) lub nowszy
- Usługi Google Play (lokalizacja scalona)
- Wtyczka Flota firmowa 0.27.0 lub nowsza — starsze wersje nie znają mostu
  do aplikacji i będą używać GPS przeglądarki

## Co jest w środku

| Plik | Rola |
|---|---|
| `MainActivity.kt` | WebView ze stroną ewidencji, uprawnienia, pomiar pozycji na żądanie |
| `TrackingService.kt` | usługa pierwszego planu: GPS, wykrywanie postojów, wysyłka punktów |
| `MostJS.kt` | most `window.FlotaNatywna` widziany przez stronę |
| `Bufor.kt` | punkty na dysku, odporne na brak zasięgu i ubicie procesu |
| `Api.kt` | klient REST `flota/v1`, uwierzytelnienie nagłówkiem `X-FF-Token` |
| `Ustawienia.kt` | adres witryny, token, nagrywana trasa |
| `Aktualizacja.kt` | sprawdzanie, pobieranie i instalacja nowych wydań |
