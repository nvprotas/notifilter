## 1. Domain and Shared State

- [x] 1.1 Add ephemeral active-notification availability and sample models plus an application-process coordinator that never persists notification content.
- [x] 1.2 Add a pure preview evaluator that substitutes an edited draft into the saved rule set and reports direct matches, effective decisions, eligibility, and predicted removals.
- [x] 1.3 Add domain tests for new and edited drafts, package and target matching, case sensitivity, disabled drafts, `ALLOW` precedence, protected samples, global filtering off, and unavailable active state.
- [x] 1.4 Make global-filtering preference changes observable by both the UI and listener instances, with tests covering cross-instance updates.

## 2. Listener Re-filtering and Cancellation

- [x] 2.1 Refactor posted-event removal into one service-owned cancellation operation with in-flight deduplication independent of whether the journal is enabled.
- [x] 2.2 Publish bounded active-notification snapshots and availability on listener connection, post, removal, and disconnection while reusing the current extraction and safety policies.
- [x] 2.3 Introduce versioned current matcher state and a latest-only serialized re-filter pass that re-checks each candidate with the current committed rules immediately before cancellation.
- [x] 2.4 Trigger re-filtering after committed Room rule emissions, global filtering becomes enabled, and listener connection; keep all cancellations disabled while global filtering is off.
- [x] 2.5 Route re-filtered removals through existing journal snapshot, status confirmation, retention, and cleanup behavior without recording notifications that remain visible.
- [x] 2.6 Add service-level tests for existing-push removal, protected notifications, `ALLOW` overrides, listener reconnect, disappearing notifications, superseded rule states, posted-event/re-filter races, and single journal insertion.

## 3. Rule Editor Preview

- [x] 3.1 Expose active-notification state and debounced, latest-only preview computation from the rule screen state holder without placing notification text in saved-instance state.
- [x] 3.2 Update the rule editor to show Russian-language states for unavailable access, empty active notifications, invalid regex, direct matches, predicted removals, protected matches, `ALLOW` overrides, disabled rules, and disabled global filtering.
- [x] 3.3 Show a bounded list of relevant active notification samples and make it clear that editing is non-destructive while saving an enabled blocking rule applies it to the current system shade.
- [x] 3.4 Add UI/state tests proving the preview updates for all rule fields and active-set changes, replaces an edited rule instead of duplicating it, and never issues cancellation from an unsaved draft.

## 4. Documentation and Remote Verification

- [x] 4.1 Update user-facing documentation and privacy copy to describe ephemeral active-notification preview, immediate application to existing pushes, and the unchanged journal opt-in behavior.
- [x] 4.2 Perform static review for accidental notification-content persistence or logging and confirm that no Room migration or new Android permission was introduced.
- [x] 4.3 Publish implementation changes to GitHub using only `gh`, without running an Android build on this machine.
- [ ] 4.4 Run and inspect the existing GitHub Actions test, lint, debug APK, and unsigned release APK/AAB jobs; resolve any failures before completing the change.
