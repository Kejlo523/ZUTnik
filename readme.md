# mZUT v2

Mobilna, nieoficjalna aplikacja dla studentów Zachodniopomorskiego Uniwersytetu Technologicznego, ułatwiająca korzystanie z systemu mZUT na Androidzie.
<br>
<img width="auto" height="500" alt="mzut_promotional_banner_v2" src="https://github.com/user-attachments/assets/facda314-2843-4ea4-ab95-7722bdc3d1bf" />

<br>
Aplikacja skupia się na:
- czytelnym interfejsie w trybie ciemnym,
- szybkim dostępie do kluczowych danych (plan, oceny, studia, aktualności),
- wygodnej nawigacji gestami i menu bocznym.

Więcej informacji o projekcie: https://mzut.endozero.pl

---


## Funkcje

### Pulpit główny
- Powitanie użytkownika z krótkim opisem działania aplikacji.
- Szybkie skróty do:
  - planu zajęć,
  - ocen,
  - informacji o studiach,
  - aktualności ZUT.
- Blok z najważniejszymi wskazówkami dotyczącymi korzystania z aplikacji.

### Plan zajęć
- Widok dnia, tygodnia i miesiąca.
- Godziny w zakresie 06:00–22:00.
- Kolorowanie typów zajęć (wykłady, ćwiczenia, laboratoria, zajęcia zdalne, odwołane, zaliczenia/egzaminy).
- Układ bloków odzwierciedlający zachowanie planu z plan.zut.edu.pl (dzielone kolumny, nakładanie się zajęć).
- Panel z detalami zajęć.
- Filtr przedmiotów z zapisem ustawień w pamięci lokalnej.

### Oceny
- Zestawienie ocen w podziale na semestry.
- Wyświetlanie: nazwy przedmiotu, rodzaju zaliczenia, oceny, daty.
- Liczenie średniej ważonej (w oparciu o ECTS).
- Sumowanie punktów ECTS dla wybranego semestru.
- Prosty, tabelaryczny widok dopasowany do ekranu telefonu.

### Dane studenta
- Dane bieżących studiów: wydział, kierunek, forma, poziom, specjalność, rok akademicki, semestr, status.
- Numer albumu i identyfikator użytkownika.
- Przebieg studiów, jeśli jest dostępny.
- Obsługa wielu kierunków studiów tak jak w mZUT.

### Aktualności ZUT
- Pobieranie komunikatów z oficjalnego kanału RSS dla studentów.
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
- Warstwa danych:
  - zapamiętywanie sesji użytkownika i danych studiów (`MzutSession`),
  - repozytoria do obsługi planu, ocen i aktualności (`PlanRepository`, `GradesRepository`, `NewsRepository`),
  - lokalne cache’owanie odpowiedzi.
- Widoki:
  - osobne ekrany dla planu, ocen, danych i aktualności,
  - wspólny układ nawigacji i stylów.
- Widget:
  - widżet planu dnia, pobierający dane bezpośrednio z cache’u oraz z API,
  - filtruje po tych samych ustawieniach, co główny widok planu,
  - pokazuje tylko nadchodzące lub trwające zajęcia.

---

## Wymagania

- Android 8.0 lub nowszy.
- Aktywne konto w systemach ZUT (e-dziekanat / wirtualna uczelnia).
- Połączenie z internetem do synchronizacji danych z mZUT i plan.zut.edu.pl.

---

## Licencja

Projekt udostępniany na licencji **Apache 2.0**. Szczegóły w pliku `LICENSE` w repozytorium.
