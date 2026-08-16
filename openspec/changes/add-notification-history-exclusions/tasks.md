## 1. Data Model and Migration

- [x] 1.1 Add generalized notification-history and unified history-exclusion Room entities, outcome types, DAOs, indices, and mapping helpers; support nullable matched-rule metadata and logical-entry upserts without outcome downgrade.
- [x] 1.2 Add the explicit Room 1-to-2 migration that copies blocked journal rows into history, seeds the enabled global `(?:^|[^0-9])[0-9]{4,6}(?:[^0-9]|$)` exclusion exactly once, removes migrated matches, and drops the obsolete journal table without destructive fallback.
- [x] 1.3 Replace the journal preference with a separately named `history_enabled` preference that defaults to false after upgrade and remains observable across UI and listener instances.
- [x] 1.4 Add DAO and migration tests for old-row preservation and outcome mapping, seeded-rule uniqueness, OTP-row removal, nullable rule metadata, update idempotency, deletion, clearing, 30-day pruning, and the 1000-entry cap.

## 2. Exclusion Policy and Write Coordination

- [x] 2.1 Implement the history-exclusion domain model, validation, normalization, and compiled RE2 matcher for application-only, global-content, and application-plus-content semantics, including title/body/all-text targets and existing regex limits.
- [x] 2.2 Add matcher tests for package scope, combined criteria, invalid/empty rules, case behavior, and standalone 4–6 digit boundaries that reject substrings of longer numeric sequences.
- [x] 2.3 Implement an immutable revisioned history-capture policy whose unavailable startup state fails closed for persistence while leaving notification filtering operational.
- [x] 2.4 Replace journal-only coordination with a history operation coordinator that serializes capture, confirmation, clear, exclusion commit, policy publication, and matching-row cleanup; reject stale-revision writes after privacy or clear barriers.
- [x] 2.5 Add concurrency tests showing that enabling an application or regex exclusion deletes existing matches, an older in-flight callback cannot restore them, disabling/deleting an exclusion does not restore rows, and clear does not remove later events.

## 3. Notification Listener Integration

- [x] 3.1 Refactor posted-event processing to determine filtering eligibility and extract bounded content once, then evaluate filtering and history exclusion independently without allowing history failures to change cancellation behavior.
- [x] 3.2 Record eligible non-excluded posted notifications even when global filtering is off, omit protected/self/system notifications, and keep preview/listener enumeration from creating received-only history rows.
- [x] 3.3 Track active Android notification lifecycles so updates reuse one logical history entry, excluded updates remove an existing entry, removal closes correlation, and later key reuse creates a new entry; reconcile open identities on listener connection.
- [x] 3.4 Route blocking decisions and listener-cancellation confirmations into the same logical history entry without outcome downgrade or duplicate audit rows.
- [x] 3.5 Update active re-filtering to modify an existing history entry or conditionally create one removal entry under the current history policy, while preserving cancellation deduplication during posted-event/re-filter races.
- [x] 3.6 Extend service and pure-logic tests for consent disabled/enabled, filtering disabled, policy loading, exclusions, protected notifications, update/reuse correlation, re-filter insertion/update, cancellation confirmation, races, pruning, and persistence failure isolation.

## 4. History and Exclusion UI

- [x] 4.1 Update the view model to expose history, explicit consent, exclusion CRUD, validation, enable/disable, coordinated matching-row cleanup, deletion, clearing, and generic failure messages without notification content.
- [x] 4.2 Rename the Journal tab and copy to History, retain search/detail/delete/clear behavior, and render received, removal-requested, and removal-confirmed outcomes with matched-rule details only when present.
- [x] 4.3 Add exclusion management and editing for application-only, global regex, and application-scoped regex rules, showing the seeded 4–6 digit rule as an ordinary editable enabled exclusion.
- [x] 4.4 Extend application selection with retained-history packages and manual package-name entry so non-launcher notification sources can be excluded before or after capture.
- [x] 4.5 Add UI/view-model tests for fresh consent, empty and invalid exclusions, combined scope, seeded-rule presentation, nullable matched-rule rendering, exclusion cleanup feedback, and history search/status presentation.

## 5. Privacy Documentation and Verification

- [x] 5.1 Update README, privacy policy, and interface explanations for allowed-notification persistence, explicit history consent, filter-eligible capture scope, unified exclusions, the broad 4–6 digit default, retention, deletion, and lack of backup/network transfer.
- [x] 5.2 Perform a static privacy review confirming that excluded or allowed notification content is not written to logs, preferences, saved-instance state, analytics, backup, exports, or any store outside the bounded Room history.
- [x] 5.3 Run the existing GitHub Actions Android workflow and verify JVM/Robolectric tests, lint, debug build, and release build; record any platform-level lifecycle ambiguity found during verification without weakening the specified privacy barriers.
