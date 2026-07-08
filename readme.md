# ZUTnik

Mobilna, nieoficjalna aplikacja kliencka dla studentów Zachodniopomorskiego Uniwersytetu Technologicznego. Integruje się z **USOS API** i udostępnia dane akademiowania na Androidzie.
<br>
<!--- <img width="auto" height="500" alt="zutnik_promotional_banner" src="https://github.com/user-attachments/assets/facda314-2843-4ea4-ab95-7722bdc3d1bf" /> --->

<br>
Aplikacja skupia się na:
- czytelnym interfejsie w trybie ciemnym,
- szybkim dostępie do kluczowych danych (plan, oceny, studia, aktualności),
- wygodnej nawigacji gestami i menu bocznym.

Więcej informacji o projekcie: https://zutnik.endozero.pl

---


## Funkcje

### Pulpit główny
- Powitanie użytkownika z krótkim opisem działania aplikacji.
- Szybkie skróty do:
  - planu zajęć,
  - ocen,
  - informacji o studiach,
  - aktualności.
- Blok z najważniejszymi wskazówkami dotyczącymi korzystania z aplikacji.

### Plan zajęć
- Widok dnia, tygodnia i miesiąca.
- Godziny w zakresie 06:00–22:00.
- Kolorowanie typów zajęć (wykłady, ćwiczenia, laboratoria, zajęcia zdalne, odwołane, zaliczenia/egzaminy).
- Układ bloków odzwierciedlający zachowanie planu z plan.zut.edu.pl (dzielone kolumny, nakładanie się zajęć).
- Panel z detalami zajęć.
- Filtr przedmiotów z zapisem ustawień w pamięci lokalnej.
- Szybkie oznaczanie zajęć jako egzamin/zaliczenie/kolokwium.
- Dodawanie własnych wydarzeń kliknięciem w puste pole (domyślnie 1,5h).

### Obecności
- Lista przedmiotów z planu z licznikiem nieobecności.
- Szybkie +/- oraz ustawianie łącznej liczby godzin dla przedmiotu.
- Podsumowanie łącznej liczby nieobecności.

### Oceny
- Zestawienie ocen w podziale na semestry (USOS: `services/grades/terms2`, `services/courses/user`).
- Wyświetlanie: nazwy przedmiotu, rodzaju zaliczenia, oceny, daty.
- Liczenie średniej ważonej (w oparciu o ECTS).
- Sumowanie punktów ECTS dla wybranego semestru.
- Prosty, tabelaryczny widok dopasowany do ekranu telefonu.

### Dane studenta
- Dane bieżących studiów z USOS: wydział, kierunek, forma, poziom, specjalność, rok akademicki, semestr, status.
- Numer albumu i identyfikator użytkownika (`services/users/user`).
- Przebieg studiów (`services/progs/student`, `services/progs/student_programme`).
- Obsługa wielu kierunków studiów zgodnie z profilem USOS.

### Aktualności ZUT
- Pobieranie komunikatów z modułu news USOS (`services/news/search`).
- Lista artykułów z tytułem, datą, krótkim opisem i miniaturą.
- Szczegółowy widok treści ogłoszenia.

---

## Nawigacja

- Wspólny dla całej aplikacji panel boczny (Navigation Drawer) z odnośnikami do:
  - pulpitu,
  - planu,
  - ocen,
  - danych studenta,
  - aktualności,
  - ekranu „O aplikacji”.
- Gesty:
  - przesunięcie w prawo – otwarcie menu,
  - przesunięcie w lewo – zamknięcie menu.
- Powrót systemowym przyciskiem zawsze prowadzi do ekranu głównego, a dopiero z niego zamyka aplikację.

---

## Architektura i technikalia

- Język: Java (Android).
- Autoryzacja: OAuth 1.0a wobec USOS API (`UsosOAuth`, `UsosApi`).
- Warstwa danych:
  - zapamiętywanie tokenów sesji i danych studiów (`ZutnikSession`),
  - repozytoria do obsługi planu, ocen i aktualności (`PlanRepository`, `GradesRepository`, `NewsRepository`),
  - lokalne cache’owanie odpowiedzi API.
- Widoki:
  - osobne Activity dla planu, ocen, danych i aktualności,
  - wspólny układ nawigacji i stylów.
- Widget:
  - widżet planu dnia, pobierający dane z cache’u oraz z API,
  - filtruje po tych samych ustawieniach, co główny widok planu,
  - pokazuje tylko nadchodzące lub trwające zajęcia.

---

## Wymagania

- Android 8.0 (API 26) lub nowszy.
- Aktywne konto studenta w USOS.
- Połączenie z internetem do synchronizacji danych z USOS API i plan.zut.edu.pl.

---

## Licencja

Projekt udostępniany na licencji **Apache 2.0**. Szczegóły w pliku `LICENSE` w repozytorium.
