## Description
<!-- Describe what this PR changes and why. If it resolves an issue, link it here: e.g. Closes #123 -->

## Type of change
- [ ] Bug fix
- [ ] New feature
- [ ] UI / visual update
- [ ] Performance improvement
- [ ] Refactoring / cleanup
- [ ] Translations
- [ ] Build / CI tooling

## UI changes
<!-- Required if this PR changes or adds UI. Please attach screenshots or a screen recording. -->

## Checklist
Before submitting, please make sure:
- [ ] The base branch is set to `develop` (not `main`).
- [ ] The app builds cleanly locally with `./gradlew assembleDebug`.
- [ ] Unit tests pass with `./gradlew testDebugUnitTest`.
- [ ] You tested the changes on a device or emulator (including Shizuku or Root if applicable).
- [ ] New preferences use `SettingsRepository` for keys and getter/setter methods.
- [ ] New user-facing settings are registered in `FeatureRegistry.kt` for search indexing.
- [ ] New UI follows the design system in `ui/core/` (`RoundedCardContainer`, `IconToggleItem`, etc.).
- [ ] Strings are added to `res/values/strings.xml` and drawables use rounded variants (`R.drawable.rounded_*`).
