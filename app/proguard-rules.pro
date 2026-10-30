# PDFBox looks up parts of itself and its bundled resources by name.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }

# Optional JPEG 2000 decoder that PDFBox only uses when it is on the classpath.
-dontwarn com.gemalto.jp2.**
