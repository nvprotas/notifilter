## Why

Notifilter currently preserves only notifications that it tried to hide, so users cannot review ordinary notifications later and derive useful filtering rules from real examples. A privacy-aware local history is needed as the corpus for that future workflow, while application and content exclusions prevent sensitive notifications such as one-time codes from being persisted.

## What Changes

- Replace the opt-in blocked-notification journal with an opt-in local history of all newly posted notifications that are eligible for Notifilter filtering.
- Preserve the filtering outcome and matched-rule details on history entries when Notifilter requests or confirms removal, so the existing audit use case remains available.
- Add unified history-exclusion rules that can exclude an entire application, matching content across all applications, or matching content within one application.
- Seed an enabled global content exclusion for a standalone sequence of 4–6 ASCII digits so likely OTP values are not written to history.
- Remove already stored matching history when an exclusion is added or enabled, and prevent concurrent notification callbacks from reintroducing excluded records.
- Treat repeated posts that update one active Android notification as one logical history entry rather than an unbounded series of duplicates.
- Rename the user-facing journal to history while retaining search, individual deletion, full clearing, local-only storage, 30-day retention, and the 1000-entry limit.
- Require fresh, explicit history consent after upgrade; the previous journal preference does not authorize recording allowed notifications.

## Capabilities

### New Capabilities

- `notification-history`: Opt-in local capture, retention, browsing, and unified application/content exclusions for filter-eligible notification history.

### Modified Capabilities

- `active-notification-refiltering`: Retroactive filtering records its outcome in the unified history without creating duplicate received-notification entries or bypassing history exclusions.

## Impact

- Room schema and migration from the blocked-notification journal to generalized notification history and history-exclusion rules.
- Notification-listener startup, posted-event capture, cancellation confirmation, active-notification re-filtering, and write/cleanup race coordination.
- Shared preferences and service/UI observation for the new explicit history-consent state.
- Compose history and exclusion-management UI, view-model state, search, deletion, and clearing behavior.
- Tests for capture policy, OTP and application exclusions, deduplication, migration, retention, races, and separation from notification filtering.
- README and privacy policy descriptions of locally persisted allowed-notification content and exclusions.
- No new Android permission, network access, analytics, backup, or external data transfer.
