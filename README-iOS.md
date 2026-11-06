# Mensa Deutz – iOS (Web-App / PWA)

Die Android-App ist als **Progressive Web App** im Ordner [`docs/`](docs/) nachgebaut.
Auf dem iPhone wird sie über Safari zum Home-Bildschirm hinzugefügt und startet dann
im Vollbild mit eigenem Icon, genau wie eine normale App. Mac, Xcode oder Apple-Developer-Account
sind dafür nicht nötig.

## Funktionen (wie in der Android-App)
- Live-Speiseplan aller KStW-Mensen (Standardstandort: Mensa IWZ Deutz), 14 Tage im Voraus
- Tagesauswahl (Wischen nach links/rechts wechselt den Tag), Filter Vegan / Vegetarisch / Fleisch & Fisch
- Preisstufen Studierende / Bedienstete / Gäste
- Detailansicht mit Allergenen, englischem Titel und Foto
- Tablett-Rechner mit Summen für alle Preisstufen
- Hell/Dunkel-Modus, Offline-Cache (der zuletzt geladene Speiseplan bleibt verfügbar)

> Einschränkung: iOS erlaubt für Web-Apps **keine Homescreen-Widgets**. Das Android-Widget gibt es hier daher nicht.

## Veröffentlichen mit GitHub Pages

1. Auf GitHub ein neues Repository anlegen (z. B. `mensa-deutz`).
2. In diesem Ordner:
   ```powershell
   git init
   git add .
   git commit -m "Mensa Deutz PWA für iOS"
   git branch -M main
   git remote add origin https://github.com/<DEIN-NAME>/mensa-deutz.git
   git push -u origin main
   ```
3. Auf GitHub: **Settings → Pages → Build and deployment**
   - Source: `Deploy from a branch`
   - Branch: `main`, Ordner: `/docs` → **Save**
4. Nach ca. 1 Minute ist die App erreichbar unter
   `https://<DEIN-NAME>.github.io/mensa-deutz/`

## Auf dem iPhone installieren
1. Die URL in **Safari** öffnen (nicht Chrome, nur Safari kann auf iOS installieren).
2. Unten auf **Teilen** (□↑) tippen.
3. **„Zum Home-Bildschirm"** wählen → **Hinzufügen**.

## Lokal testen
```powershell
cd docs
python -m http.server 8000
```
Dann `http://localhost:8000` im Browser öffnen. Mit den Entwicklertools (F12) kannst du die iPhone-Ansicht simulieren.

## Struktur
| Datei | Inhalt |
|---|---|
| `docs/index.html` | Grundgerüst, iOS-/PWA-Meta-Tags |
| `docs/api.js` | Port von `MensaApiClient.java` + `MensaModels.java` (API, Parsing, Cache) |
| `docs/app.js` | Port von `MainActivity.java` (UI, Filter, Tablett, Dialoge) |
| `docs/styles.css` | Farben aus `ThemePalette.java` (hell/dunkel) |
| `docs/sw.js` | Service Worker für den Offline-Start |
| `docs/manifest.webmanifest`, `docs/icons/` | App-Name und Icons |
