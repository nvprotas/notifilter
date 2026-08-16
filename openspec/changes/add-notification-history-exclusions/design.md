## Context

See `proposal.md` for motivation and the delta specs for observable behavior. The app currently has one Room database at version 1 containing filter rules and `blocked_notifications`. The listener extracts notification text, evaluates an immutable matcher, requests cancellation, and optionally writes a separate journal row through an in-process coordinator. The UI observes that journal and exposes its opt-in preference, search, deletion, and clearing.

The listener and activity share one application process, Android backup and device transfer are disabled, and the app has no network permission. Active-notification snapshots used for rule preview remain ephemeral. Notification callbacks, Room observations, UI exclusion edits, clearing, re-filter passes, and cancellation confirmations can race, so privacy exclusions cannot depend only on eventually delivered database flows.

## Goals / Non-Goals

**Goals:**

- Use one durable history record per logical notification for both the future rule-building corpus and the existing removal audit.
- Make application and content exclusions take effect as one committed privacy operation over both future and already stored history.
- Keep notification filtering available even if history initialization or persistence fails.
- Preserve existing local-only, bounded-retention, deletion, and search guarantees through a non-destructive database migration.

**Non-Goals:**

- Creating or suggesting a filter rule from a history entry; this change prepares the corpus only.
- Exporting, synchronizing, backing up, or otherwise transmitting notification history or exclusions.
- Redacting only the matching substring; an exclusion removes the complete history entry.
- Making retention limits configurable or recording protected notifications that Notifilter cannot filter.
- Guaranteeing reconstruction of custom `RemoteViews` content or notifications missed while listener access, history consent, or the process was unavailable.

## Decisions

### 1. Evolve the journal into one generalized history instead of adding a second store

Room version 2 will replace `blocked_notifications` with a generalized notification-history table. A history row keeps a generated logical event id, a non-reversible source-identity value used for update correlation, package, bounded extracted title and body, posting time, outcome, and nullable matched-rule metadata. Outcomes distinguish received, removal requested, and removal confirmed.

One row avoids duplicating sensitive text when both ordinary history and the old journal behavior are desired. It also makes a later “create rule from this notification” flow independent of whether the notification happened to be blocked. Keeping a separate journal was considered but rejected because it creates duplicate records, divergent retention and deletion semantics, and ambiguous exclusion behavior.

### 2. Store unified exclusions as Room entities

A history-exclusion entity contains an id, nullable normalized package name, nullable normalized pattern, match target, case-sensitivity flag, enabled state, and ordering metadata. At least one of package or pattern must be present. Package-only rules exclude an application, pattern-only rules are global, and combined rules apply an AND between package scope and regex match. Patterns use the same RE2/J limits and validation conventions as notification filter rules, but a dedicated exclusion matcher is used so history privacy policy cannot alter notification filtering precedence or cancellation.

Room is preferred over a string set in preferences because exclusions are ordered, editable records with validation, optional regex fields, migration behavior, and cleanup side effects. A preference remains appropriate for the single explicit `history_enabled` consent flag because the listener must read it synchronously and observe it across `UserPreferences` instances.

### 3. Seed the numeric privacy rule exactly once

Fresh databases and the version-1-to-version-2 migration insert an enabled, global, all-text exclusion using:

```regex
(?:^|[^0-9])[0-9]{4,6}(?:[^0-9]|$)
```

The boundary form is RE2-compatible without lookaround and does not match merely a 4–6 digit substring inside a longer ASCII digit sequence. Seeding happens in database creation or migration, not on every service start, so a user's later edit, disable, or deletion is respected. The broad rule intentionally favors privacy and can exclude years, prices, or order numbers; making it visible and editable in the exclusions UI gives the user control over that trade-off.

### 4. Publish one immutable capture-policy snapshot and fail closed until ready

The history subsystem exposes an immutable snapshot containing explicit consent, a revision, and the compiled enabled exclusions. Its initial value is unavailable rather than “enabled with no exclusions.” The listener may continue normal filtering immediately, but it skips persistence until the complete history snapshot has loaded. Subsequent preference or exclusion changes replace the snapshot atomically.

This differs deliberately from the filtering matcher's fail-open startup behavior: missing a history sample is recoverable, while persisting content before privacy exclusions load cannot be undone reliably. Reading consent synchronously without exclusions was rejected because it creates a startup window in which OTP content could be stored.

### 5. Serialize history mutations with policy changes and destructive cleanup

A process-wide history operation coordinator owns the current policy revision, a write barrier, a mutex, and an IO scope. Posted-event work captures the current revision and is accepted only if that revision is still current when the serialized Room operation runs.

Creating or enabling an exclusion performs one logical operation:

1. suspend history writes and advance the revision;
2. validate and persist the exclusion;
3. compile and publish the new policy;
4. scan the bounded history inside the coordinated operation and delete matching rows;
5. resume writes under the new revision.

Because history is capped at 1000 rows, evaluating a content regex in Kotlin during this operation is bounded and avoids depending on SQLite regex support. Clearing history uses the same timestamp/write-barrier semantics as the current journal so an older callback cannot repopulate data after the user's action. Disabling or deleting an exclusion publishes a new policy but does not restore deleted rows.

An eventually consistent database observer alone was considered and rejected: the listener could insert a matching record after cleanup but before receiving the updated exclusion flow.

### 6. Extract once, then keep history and filtering decisions independent

For a posted callback, the service determines safety eligibility and extracts the bounded `NotificationContent` once. The filtering matcher and history exclusion matcher evaluate that same snapshot independently. A history match only suppresses persistence; it never changes a filtering decision. A Room failure is logged without notification content and never prevents cancellation.

If the notification is retained, the service enqueues an insert/update containing the effective filtering outcome already known for that callback. A later listener-cancellation confirmation updates the same logical row. For re-filtering, the service updates an existing row when correlated; if none exists, it may create one removal row only under the current enabled, non-matching history policy. Merely enumerating active notifications never writes received rows.

### 7. Correlate callbacks across the active-notification lifecycle

The listener tracks the Android notification key to a generated logical event id while that notification remains active. Repeated posted callbacks for that active key update the same row; removal closes the mapping, so later key reuse allocates a new event. The table retains a non-reversible representation of the Android source identity and lifecycle metadata to support service restart reconciliation without exposing the raw key in the UI or logs.

On listener connection, the service reconciles persisted open identities against the active set before accepting update correlations. Android does not provide a durable globally unique event id, so an indistinguishable key reuse across a period in which the process missed both removal and repost remains a platform ambiguity. Combining lifecycle tracking, active-set reconciliation, and posting metadata minimizes that case while preserving deterministic behavior in normal operation.

Using a random id for every callback was rejected because common notification updates would flood the 1000-row history. Using the Android key forever was rejected because applications reuse keys for later notifications.

### 8. Reuse the existing history UI surface and make exclusions first-class

The `Journal` tab becomes `History`. Existing search, detail, delete, clear, retention copy, and outcome presentation adapt to nullable matched-rule metadata and the received outcome. The settings card contains explicit history consent and a route to exclusion management.

The exclusion editor reuses the rule editor's application picker, target choices, regex validation, and case-sensitivity conventions but has privacy-specific labels. The picker must also accept a package name not present in the launcher query and offer packages observed in retained history, because notification-producing components are not always launcher applications. The seeded numeric rule appears as an ordinary editable global exclusion.

### 9. Migrate data without inheriting broader consent

Migration 1-to-2 creates the history and exclusion tables, copies old journal rows with their requested or confirmed outcomes, seeds the numeric exclusion, removes copied rows matched by that exclusion, and then removes the obsolete table. Existing rule ids and pattern snapshots remain nullable audit metadata. The new `history_enabled` preference defaults to false and is not initialized from `journal_enabled`.

The migration is verified from a version-1 fixture and must not use destructive fallback. Downgrading to an older APK after opening version 2 is not supported because the old schema cannot interpret the new tables; rollout recovery is a forward migration or corrected APK, not database destruction.

## Risks / Trade-offs

- **[The broad 4–6 digit rule hides legitimate samples such as years, prices, and order numbers]** → Make the seeded exclusion visible, editable, disableable, and covered by preview/validation before commit.
- **[Persisting allowed notifications increases the amount of sensitive local data]** → Require fresh consent, capture only filter-eligible notifications, enforce exclusions before write, retain the 30-day/1000-row bounds, exclude backup/transfer, and update privacy disclosures.
- **[Fail-closed policy loading can miss early notifications]** → Keep loading small and local, expose no misleading completeness guarantee, and prioritize non-persistence over a complete corpus.
- **[Regex cleanup blocks other history mutations]** → Keep the corpus capped, perform matching on IO, serialize only history operations, and leave notification filtering independent.
- **[Android notification identity is imperfect across missed lifecycle callbacks]** → Track active lifecycles, reconcile on listener connection, and test key update/reuse behavior; accept the irreducible platform ambiguity rather than storing unbounded duplicates.
- **[Migration or history writes fail]** → Preserve filtering behavior, test migration from a real version-1 schema, avoid destructive fallback, and report only generic UI/log errors without notification content.

## Migration Plan

1. Add the version-2 schema and explicit migration with the seeded exclusion and old-journal transformation.
2. Initialize the unavailable history-policy state and keep capture disabled until migration, consent, and exclusions load successfully.
3. Ship the renamed History UI with `history_enabled` off and migrated entries still deletable even while capture is disabled.
4. Update README and privacy policy before distributing a build that can persist allowed notifications.
5. Verify tests, lint, debug, and release builds through the existing GitHub Actions Android workflow.
