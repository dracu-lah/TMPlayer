# ui/

Shared Compose screens for android and jvm (desktop), in `src/shared/kotlin`; `androidMain`, `jvmMain`
and `iosMain` hold the platform parts.

- Start from the design system (tokens, shared components) before a screen. Grep for an existing row
  or component first.
- A screen is done only with focus/D-pad tests for TV (`performKeyInput`, `assertIsFocused`) and a
  look at every form factor.
- Tests: `./gradlew :ui:jvmTest` (JDK 21, see core/AGENTS.md). `SyntheticAccount` (`src/synthetic`) is
  the large-account fixture for scale tests.
- No `!!`, `.first()` or `[0]` on data from outside; see docs/agents/engineering-rules.md.
