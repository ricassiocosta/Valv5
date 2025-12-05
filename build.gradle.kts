// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.github.ben-manes.versions") version "0.53.0"
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.about.libraries) apply false
    alias(libs.plugins.kotlin.android) apply false
}