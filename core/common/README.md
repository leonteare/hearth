# :core:common

The bottom of the stack: error handling and the few constants everything shares. Depends on nothing.

- `ErrorLog.kt` — `logHandledError(context, where, e)`, `crashLoggingHandler`, and the crash/error log files (`errors.log`, `last-crash.txt`).
- `Errors.kt` — `scrubSecrets` (strip tokens and URLs), `userMessage` (error text for the UI), `couldNot`.
- `ApiExceptions.kt` — the exception types the network layer throws.
- `ExtraRoute.kt` — `EXTRA_ROUTE`, the notification-tap route key.
- Resources: `ic_notification` (`im.flume.hearth.core.common.R`).

Tests: error-log rotation, message wording, and `RegexLiteralsTest` (regex literals must be legal on Android's ICU engine).
