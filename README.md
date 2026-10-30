# Sheetly

Android app for scanning documents, turning them into PDFs, and managing saved files on-device.

## What it does

- Scans documents with a built-in scanner: live edge detection, automatic capture, corner editing,
  perspective correction and shadow removal. Google ML Kit's scanner stays available as an option.
- Recognizes text while saving, so scanned PDFs are selectable and searchable.
- Saves PDFs to `Downloads/Sheetly`.
- Tools: merge, compress, split, password, extract text, page numbers, watermark, print,
  images to PDF, Word to PDF and import. All of them also work with PDFs from your files.
- Edits pages (reorder, rotate, delete) without re-rendering the PDF.
- Opens PDFs shared from other apps.
- Follows the look of Samsung's One UI, in German and English.

## Requirements

- Android 14+ (min SDK 34)
- Google Play services (for ML Kit scanner)

## Install (from release)

1. Open the [Releases](https://github.com/akuras22/Sheetly/releases) page.
2. Download the latest APK.
3. Install it on your Android device.

## Build from source

### Android Studio

1. Open the project in Android Studio.
2. Let Gradle sync finish.
3. Run the `app` configuration on a device/emulator.

### Command line

```bash
./gradlew assembleDebug
```

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## How it works (high level)

- `scan/DocumentDetector.kt` finds pages by their straight edges, which keeps working with a thumb
  on the page, cut-off corners or a white page on a white table. Each change is measured by
  `DocumentDetectorBenchmarkTest` against generated scenes.
- `ScanEnhancer.kt` flattens lighting by estimating the paper brightness across the page.
- PDFBox-Android handles merging and page edits, so text and image quality are kept.
- Stores files with Android MediaStore in `Downloads/Sheetly`.

## Notes

- `compileSdk` / `targetSdk`: 35
- GitHub Actions builds a test APK on every push to `main`, available as a workflow artifact.
- Release APKs are published on tags matching `v*`.

## License

Licensed under the [Apache License 2.0](LICENSE). You may use, change and share this project for
free, as long as you keep the copyright and the [NOTICE](NOTICE) file and mark your changes. It may
not be presented as your own work.
