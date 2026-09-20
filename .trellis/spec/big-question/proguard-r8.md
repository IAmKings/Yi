# R8 / Minified Release

> **Severity**: P0 — debug works, minified release crashes (`ClassNotFoundException`,
> missing field, JSON errors, reflection failures).
>
> Symptom: crash reports only from release builds; stack traces are mangled.

---

## Cause

R8 strips or renames things that are only referenced reflectively:

1. `kotlinx.serialization` classes renamed → `SerializationException` / missing fields
2. Enums / sealed classes looked up reflectively
3. Retrofit interface generics erased
4. `-keep` rules placed in the app module for a library that owns its types (wrong
   place; consumers miss them)

---

## Fix Patterns

### Keep rules belong in the owning module

Serialization classes ship `consumer-rules.pro` in the module that declares them:

```proguard
# core-network/consumer-rules.pro
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class <pkg>.** {
    *** Companion;
}
-keepclasseswithmembers class <pkg>.** {
    kotlinx.serialization.KSerializer serializer(...);
}
```

With R8 full mode + kotlinx.serialization, `@Serializable` classes are generally kept
by the serializer's own rules — the failures come from *non-annotated* DTOs or custom
serializers. Audit those first.

### Smoke-test the real artifact

- A release-build install + login + first-run smoke test **before** tagging a release.
- Enable R8 mapping upload to the crash reporter so traces de-obfuscate
  (TODO(spec): record the mapping-upload setup).
- `./gradlew :app:assembleRelease` on CI for every merge to the release branch —
  R8-only failures must never be discovered by hand a day before release.

### Keep it debuggable

```kotlin
// app/build.gradle.kts
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}
```

Debuggable builds bypass R8 entirely — never validate release behavior on them.

---

## Prevention

- Every module that uses reflection/serialization declares its `consumer-rules.pro`
  (see [../shared/gradle.md](../shared/gradle.md))
- Release smoke test on a real device in the release checklist
- Crash reporter verifies mapping upload in CI; missing mapping = failed release
- New reflection usage (Room entities, serialization, Hilt metas) re-audited against
  shrink rules in the same PR

---

## Related

- [../backend/network.md](../backend/network.md) — serialization rules that R8
  interacts with
- [room-schema-migrations.md](./room-schema-migrations.md) — the other "works in
  debug, dies in prod" classic
