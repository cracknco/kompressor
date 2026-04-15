# Versioning & API Stability

## Semver commitment

Kompressor follows [Semantic Versioning 2.0.0](https://semver.org) **strictly** from version **1.0.0** onward.

| Bump | Meaning | Example |
|------|---------|---------|
| **MAJOR** | Breaking change to the public API | Removing a method, changing a return type, renaming a public class |
| **MINOR** | Additive, backward-compatible feature | New compression format, new preset, new optional parameter with a default |
| **PATCH** | Backward-compatible bug fix | Correcting compression ratio calculation, fixing a crash on specific input |

Consumers can safely use **compatible-with** version ranges in `libs.versions.toml`:

```toml
[versions]
kompressor = "1.2.0"

[libraries]
kompressor = { module = "co.crackn.kompressor:kompressor", version.ref = "kompressor" }
```

A MINOR or PATCH update will never break your build or change observable behavior in a way that violates the documented contract.

## What counts as public API

Only symbols that meet **all** of the following criteria are covered by the semver contract:

1. Declared `public` (Kotlin default visibility).
2. **Not** annotated with `@ExperimentalKompressorApi`.
3. **Not** declared `internal`.

### Experimental APIs (`@ExperimentalKompressorApi`)

APIs annotated with `@ExperimentalKompressorApi` are opt-in and may change or be removed in any MINOR release without a MAJOR version bump. Opt-in is required at the call site, so breakage is always explicit.

Once an experimental API is stabilized, the annotation is removed in a MINOR release — this is considered additive, not breaking.

### Internal APIs

Symbols declared `internal` are implementation details. They are invisible to consumers and may change at any time without notice.

## Binary compatibility guarantees

Kompressor ships as a Kotlin Multiplatform library with the following artifact types:

| Artifact | Format | Compatibility guarantee |
|----------|--------|------------------------|
| Android | AAR (JVM class files) | ABI-stable across MINOR/PATCH releases. We use [Kotlin Binary Compatibility Validator](https://github.com/Kotlin/binary-compatibility-validator) to detect accidental ABI changes. |
| Common (shared) | klib (Kotlin IR) | API-stable across MINOR/PATCH releases. klib compatibility follows the Kotlin compiler's ABI rules for `commonMain` targets. |
| iOS | Kotlin/Native framework | API-stable across MINOR/PATCH releases, following Kotlin/Native's Objective-C header compatibility model. |

### What can change in a MINOR or PATCH release

- Adding new public classes, functions, or properties.
- Adding new optional parameters with defaults to existing functions.
- Adding new enum entries (enums used as **inputs** by consumers, not as exhaustive `when` subjects).
- Internal implementation changes that do not alter public behavior.

### What requires a MAJOR release

- Removing or renaming a public class, function, or property.
- Changing the type signature of a public API (parameters, return type).
- Changing default values in a way that alters observable behavior.
- Removing enum entries.
- Changing the module's Maven coordinates.

## Pre-1.0 versions

Versions before 1.0.0 (e.g., 0.x.y) are development releases. The API may change between any two versions without a MAJOR bump. Pin exact versions during this phase.

## Cross-references

- **[CHANGELOG.md](../CHANGELOG.md)** — documents every user-facing change per release, organized by semver category (Added / Changed / Fixed / Removed).
- **[docs/api-inventory.md](api-inventory.md)** — exhaustive list of public API symbols, their stability tier, and experimental status.
