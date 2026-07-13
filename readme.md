# ZUTnik

ZUTnik to nieoficjalna aplikacja na Androida, która porządkuje codzienne sprawy studenckie w jednym miejscu. Łączy dane dostępne przez USOS z szybkim, czytelnym interfejsem przygotowanym przede wszystkim z myślą o telefonach.

Projekt stawia na krótki czas uruchamiania, działanie w oparciu o lokalny cache i ograniczanie ruchu sieciowego. Po pobraniu danych większość ekranów może wyświetlić ostatni zapis bez ponownego łączenia się z serwerem.

> ZUTnik jest projektem niezależnym i nie stanowi oficjalnej aplikacji uczelni ani systemu USOS. Dane mają charakter pomocniczy. Informacje wymagające potwierdzenia należy zawsze sprawdzić w oficjalnych systemach.

## Możliwości

- personalizowany ekran startowy z kafelkami, które można dodawać, edytować, przesuwać i skalować,
- plan zajęć w widoku dnia, tygodnia i miesiąca,
- filtrowanie planu, zapisane wyszukiwania, własne wydarzenia i eksport do kalendarza,
- oceny pogrupowane według semestrów i przedmiotów wraz z podsumowaniem średniej oraz punktów ECTS,
- informacje o przebiegu studiów i postępie punktowym,
- finanse, należności, wpłaty i terminy płatności,
- prosty licznik obecności powiązany z przedmiotami z planu,
- aktualności, przydatne strony i własne skróty URL,
- widżet planu dnia oraz konfigurowalne powiadomienia,
- obsługa wielu kierunków przypisanych do jednego konta,
- tryb ciemny i układy dopasowane do różnych rozmiarów ekranu.

## Podejście do danych

Aplikacja korzysta z podejścia cache-first. Dane zapisane lokalnie są pokazywane od razu, a połączenie sieciowe jest wykonywane dopiero wtedy, gdy odświeżenie jest potrzebne albo zostanie uruchomione ręcznie.

- ekrany nie pobierają ponownie świeżych danych bez powodu,
- synchronizacja w tle jest ograniczana zależnie od rodzaju danych,
- brak połączenia nie blokuje dostępu do wcześniej zapisanych informacji,
- ręczne odświeżenie pozostaje dostępne dla użytkownika,
- logowanie odbywa się przez OAuth, bez przekazywania aplikacji hasła do konta.

## Wymagania

- Android 8.0 (API 26) lub nowszy,
- aktywne konto w systemie USOS,
- dostęp do internetu podczas logowania i synchronizacji nowych danych.

## Uruchomienie projektu

Projekt wymaga Android Studio z JDK 11 lub nowszym oraz Android SDK 36.

1. Sklonuj repozytorium i otwórz jego główny katalog w Android Studio.
2. Dodaj dane klienta OAuth do lokalnego pliku `local.properties`:

```properties
usos.consumer_key=twoj_klucz
usos.consumer_secret=twoj_sekret
```

3. Zbuduj wersję debug:

```powershell
.\gradlew.bat assembleDebug
```

W systemach Linux i macOS użyj `./gradlew assembleDebug`.

Plik `local.properties` jest przeznaczony wyłącznie na lokalną konfigurację. Nie należy umieszczać kluczy ani sekretów OAuth w repozytorium.

## Testy

Testy jednostkowe można uruchomić poleceniem:

```powershell
.\gradlew.bat testDebugUnitTest
```

## Licencja

Projekt jest udostępniany na licencji Apache License 2.0. Pełna treść znajduje się w pliku [LICENSE.txt](LICENSE.txt).
