# GitHub Actions Setup für Automatische APK-Releases

## Übersicht
Der Workflow `android_build.yml` baut automatisch Release-APKs und erstellt GitHub Releases auf zwei Wegen:

1. **Bei jedem Push auf `main` oder `master`** → Auto-Release mit versionCode=run_number
2. **Bei Tag-Push** (z.B. `git tag v1.0.1 && git push origin v1.0.1`) → Release mit benutzerdefinerter Version

## Funktionalität

### Optional: Keystore-Signing
Wenn du Keystore-Secrets setzt, werden APKs mit deinem eigenen Zertifikat signiert.  
Falls nicht, wird automatisch mit Debug-Key signiert (für Tests ok, aber nicht für Play Store).

### Required: GitHub Token
GitHub Actions nutzt automatisch `secrets.GITHUB_TOKEN` – **kein Setup nötig**.

---

## Setup (Optional, aber Empfohlen)

### Schritt 1: Android Keystore erstellen (falls nicht vorhanden)

```bash
# Neue Keystore-Datei erzeugen (interaktiv)
keytool -genkey -v -keystore ~/release.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias pdfscanner
```

Wichtige Informationen notieren:
- **Keystore-Datei**: `~/release.keystore`
- **Keystore-Passwort**: (aus `keytool` Step)
- **Key Alias**: `pdfscanner`
- **Key Passwort**: (aus `keytool` Step)

### Schritt 2: Keystore in Base64 konvertieren

```bash
base64 -i ~/release.keystore | pbcopy
# Oder auf Linux:
base64 -w0 ~/release.keystore | xclip -selection clipboard
```

### Schritt 3: GitHub Repository Secrets setzen

Gehe zu: **Settings → Secrets and variables → Actions → New repository secret**

Erstelle folgende Secrets:

| Secret Name | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | (Base64 aus Schritt 2) |
| `ANDROID_KEYSTORE_PASSWORD` | (Keystore Passwort) |
| `ANDROID_KEY_ALIAS` | `pdfscanner` |
| `ANDROID_KEY_PASSWORD` | (Key Passwort) |

---

## Auslösung

### Automatischer Build (Push auf main/master)
```bash
git push origin main
# → Workflow startet automatisch
# → Release mit Tag v1.0.<run_number> wird erstellt
```

### Manueller Build (Git Tags)
```bash
git tag v1.0.5
git push origin v1.0.5
# → Workflow startet automatisch
# → Release mit Tag v1.0.5 wird erstellt
```

### Manueller Trigger (über GitHub UI)
Gehe zu: **Actions → Build And Release APK → Run workflow → Main → Run workflow**

---

## Workflow-Ablauf

1. 🔄 **Checkout** → Code wird gedownloadet
2. ☕ **JDK 17 Setup** → Java compiler bereit
3. 🔐 **Keystore** → Falls Secrets vorhanden, wird Keystore dekodiert
4. 🔨 **Gradle Build** → `./gradlew :app:assembleRelease`
   - Wenn Keystore-Secrets → signierte APK
   - Sonst → Debug-Key-signierte APK (für Tests)
5. 📝 **Version Info** → versionCode & versionName werden bestimmt
6. 🚀 **Release** → GitHub Release wird erstellt mit APK als Asset

---

## Troubleshooting

### ❌ Build fehlgeschlagen?
Prüfe die **Actions Logs**: https://github.com/akuras22/Sheetly/actions

### ❌ Signing-Fehler?
- Secrets korrekt gesetzt? (Settings → Secrets)
- Base64 korrekt? → `base64 -w0 ~/release.keystore` ohne Umbrüche
- Keystore-Passwörter exakt?

### ❌ APK zu groß?
- App mit zu vielen Abhängigkeiten?
- Minify nicht aktiviert? (nur für Production nötig)

### ✅ APK signiert aber die Version ist identisch?
- Tag-Push benutzen z.B. `git tag v1.0.5` für explizite Version
- Sonst wird `run_number` als versionCode genutzt

---

## Tipps

- Speichere deine `release.keystore` **sicher auf deinem Rechner**, nicht im Repo!
- Die `.keystore` Datei kann mit einem Git-Hook `.gitignore` gesichert sein
- Für CI/CD sollte der Keystore nur im GitHub Secret gespeichert sein
