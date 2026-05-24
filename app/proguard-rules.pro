# CineCalibrator ProGuard rules

# Keep Gson data classes (PlanckianTable, PlanckianEntry, etc.)
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep all our model/core data classes used with Gson serialisation
-keep class com.cinecalibrator.core.PlanckianSweepEngine$** { *; }
-keep class com.cinecalibrator.core.ColorScience$** { *; }
-keep class com.cinecalibrator.core.ScanEngine$** { *; }
-keep class com.cinecalibrator.core.SekonicMeasurementSource$** { *; }
-keep class com.cinecalibrator.core.ConversionEngine$** { *; }

# Keep Room entities
-keep class com.cinecalibrator.model.** { *; }

# Keep Navigation component
-keep class androidx.navigation.** { *; }

# Timber
-dontwarn org.jetbrains.annotations.**
