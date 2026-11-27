# core/

Kotlin Multiplatform, shared by every app. Read [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) (Modules).

- `src/pureMain/kotlin` is the real common code: pure Kotlin, no JDK, no TDLib. It also builds for
  the browser (js). The web player in `webapp/` runs the rules from it: `pnpm -C webapp rules`
  (`./gradlew :core:webRules`) writes Kotlin/JS into `webapp/src/rules`. A change to a rule here
  needs the web checked too.
- `src/commonMain/kotlin` is only the android and jvm half (TDLib, the JDK). Its name is misleading.
- `src/jsMain`, `webMain`, `iosMain`: platform parts. `i18n/*.json` under resources is read by the
  web build too (`webapp/vite.config.ts`).
- Gradle needs JDK 21: `export JAVA_HOME=$HOME/.jdks/jdk-21.0.12+8` (the system default fails).
- Tests: `./gradlew :core:jvmTest`. A new parser of outside data gets its line in `GarbageInputTest`.
- New user text goes into `en.json` (keys sorted). Do not run LangSync or edit the other locales.
- Mirrored in the web app: `webapp/src/skipTable.ts` (generated from Kotlin by
  `scripts/gen-skip-table.py`) and `formatline.ts`. Change both sides together.
