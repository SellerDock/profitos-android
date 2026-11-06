# ProfitOS Android

Aplikacja Android dla [ProfitOS](https://github.com/SellerDock/profitos) – panelu zysków sprzedaży na Allegro. To lekka nakładka WebView na aplikację webową, z jednym natywnym dodatkiem: opcjonalnym odblokowaniem Financial Vault odciskiem palca (klucz chroniony przez Android Keystore).

Domyślnie aplikacja otwiera `https://app.profitos.pl`. Na własnym serwerze ProfitOS zbudujesz ją z własnym adresem.

## Budowanie

Wymagany jest tylko Docker – JDK 17 i Android SDK są w obrazie budującym.

```bash
./build.sh            # APK debug: app/build/outputs/apk/debug/app-debug.apk
```

Własny serwer:

```bash
docker build -t profitos-apk-builder .
docker run --rm -v "$PWD:/workspace" -w /workspace profitos-apk-builder \
  ./gradlew --no-daemon assembleDebug \
  -Pprofitos.startUrl=https://app.example.com/dashboard \
  -Pprofitos.allowedDomain=example.com
```

- `profitos.startUrl` – adres otwierany po starcie (tylko `https://`).
- `profitos.allowedDomain` – domena (z subdomenami), która zostaje w aplikacji i może używać mostka odblokowania Vault. Inne linki otwierają się w przeglądarce.

### Wersja release

Release wymaga własnego klucza podpisu. Wskaż plik `keystore.properties` (pola `storeFile`, `storePassword`, `keyAlias`, `keyPassword`) zmienną `PROFITOS_KEYSTORE_PROPERTIES` albo właściwością `-Pprofitos.keystoreProperties=…`. Bez niego build release kończy się błędem zamiast podpisać APK kluczem debug. Klucza i haseł nigdy nie dodawaj do repo.

Zbudowaną aplikację instalujesz przez `adb install -r <plik.apk>` albo kopiując APK na telefon (Android zapyta o zgodę na instalację z nieznanego źródła).

## Bezpieczeństwo

- Tylko HTTPS, uprawnienia: `INTERNET` i biometria (`USE_BIOMETRIC`, na Androidzie 8 `USE_FINGERPRINT`), kopia zapasowa systemu wyłączona.
- Mostek Vault działa przez `WebViewCompat.addWebMessageListener` tylko dla `https://` z dozwolonej domeny i tylko w ramce głównej.
- Klucz Vault zapisany na telefonie jest zaszyfrowany kluczem Keystore (StrongBox, gdy dostępny). Każde użycie wymaga biometrii, a zmiana odcisków unieważnia zapisany klucz.

Podatności zgłaszaj prywatnie, jak opisano w [SECURITY.md](https://github.com/SellerDock/profitos/blob/main/SECURITY.md) repo głównego.

## Licencja i współtworzenie

Kod jest na licencji [AGPL-3.0-or-later](LICENSE). Zasady wkładu i CLA są wspólne z repo głównym: [CONTRIBUTING.md](https://github.com/SellerDock/profitos/blob/main/CONTRIBUTING.md).

Nazwa „ProfitOS”, logo i identyfikator `pl.profitos.app` nie są objęte licencją – fork publikowany dla innych musi mieć własną nazwę, logo i `applicationId`. Szczegóły: [TRADEMARKS.md](https://github.com/SellerDock/profitos/blob/main/TRADEMARKS.md).
