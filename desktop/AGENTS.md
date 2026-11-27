# desktop/

Compose Desktop for Windows, Linux and macOS. Plays through libmpv via mediamp; `TdMediaData` is the
twin of the app's `TdDataSource` over the same `TdByteWindow`.

- Screens are shared with Android from `:ui`; only the window, the player and OS integration live here.
- Paths and behaviour differ per OS: check Windows, Linux and macOS.
- Tests: `./gradlew :desktop:test` (JDK 21). `ScreenAuditShotsTest` feeds `ScreenInvariantsTest`; a
  screenshot test must assert something beyond the file size, or it does not belong here.
- Packaging and self-test run in CI: `desktop-check.yml` (docs/CI.md).
