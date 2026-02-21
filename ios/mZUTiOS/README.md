# mZUT iOS (port 1:1 - start)

Ten katalog zawiera start portu iOS dla `mzut-v2`:
- warstwa core przepisana z Androida (sesja, auth, API, oceny, info, news, kafelki),
- UI SwiftUI: login + home + podstawowe ekrany danych,
- testy jednostkowe dla najwazniejszych elementow core.

## Struktura
- `Sources/mZUTCore` - logika aplikacji niezalezna od UI.
- `Sources/mZUTiOSApp` - SwiftUI app.
- `Tests/mZUTCoreTests` - testy jednostkowe.

## Build i test (macOS)
Wymagania:
- macOS
- Xcode 16+
- XcodeGen (`brew install xcodegen`)

Kroki:
1. `cd ios/mZUTiOS`
2. `xcodegen generate`
3. `xcodebuild -scheme mZUTiOSApp -destination 'platform=iOS Simulator,name=iPhone 16' build`
4. `xcodebuild -scheme mZUTiOSApp -destination 'platform=iOS Simulator,name=iPhone 16' test`

## Co jest juz zrobione
- `MzutAPIClient` + wykrywanie wygaslej sesji.
- `MzutSessionStore` (persist 1:1 kluczy jak w Androidzie).
- `AuthRepository` z tokenem i trybem demo `Student/Test`.
- `GradesRepository`, `StudiesInfoRepository`, `NewsRepository`.
- `HomeRepository`, `AttendanceRepository`, `CustomPlanEventRepository`.

## Co zostalo
- pelny port modułu planu zajec (najwieksza czesc Androida),
- ekran obecnosci i ustawien 1:1,
- push/notification sync, widget i watch sync.
