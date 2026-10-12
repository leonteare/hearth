# :core:domain

Aggregator only. It `api`s the four `:domain:*` modules so the layers above get one dependency, and holds the little that spans areas.

- `DomainContainer` — extends `MusicContainer`, `SpokenContainer`, `RequestsContainer`, `UpdateContainer`.
- `SearchHits` — merges music, podcast and book results into Search's single list.
- `LegacyWorkers` — old worker class names to current ones, plus WorkManager's factory. WorkManager stores jobs by class name, so a renamed or moved worker gets an entry here (`LegacyWorkersTest` checks every target exists).

New logic belongs in its area's `:domain:*` module, not here.
