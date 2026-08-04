## Context

See `proposal.md` for motivation and `specs/active-notification-refiltering/spec.md` for the behavioral contract.

The notification-listener service currently evaluates only `onNotificationPosted` callbacks. It keeps an immutable compiled matcher updated from Room, applies a central safety check, cancels a matching notification, and optionally writes the result to the blocked-notification journal. The rule editor has only a manually entered test string. Journal entries cannot provide the required preview corpus because the journal is optional, contains only previously blocked notifications, and intentionally stores a narrower visible-text snapshot than the matcher evaluates.

Only a connected `NotificationListenerService` can authoritatively enumerate notifications currently in the system shade. The service and UI run in the same application process today. Android builds and verification must run through the existing GitHub Actions workflows; this machine must not perform Android builds. OpenClaw and its environment remain outside the scope of this project.

## Goals / Non-Goals

**Goals:**

- Give the rule editor a current, read-only view of active notification samples and preview availability.
- Use one definition of eligibility and one rule-decision path for preview, posted-event filtering, and re-filtering.
- Apply the newest rule state known to the listener to active notifications after rule or global-filtering changes.
- Deduplicate cancellation and journal work when callbacks and a re-filter pass overlap.
- Keep preview data ephemeral and bounded by the notifications currently exposed by Android.

**Non-Goals:**

- Persisting a history of notifications that remain visible.
- Reconstructing notification content that is no longer active or was never journaled.
- Restoring a notification after it has been cancelled.
- Cancelling notifications while the user is merely editing an unsaved draft.
- Changing protected-notification exclusions, rule precedence, regex semantics, Android permissions, or journal retention.

## Decisions

### 1. The listener owns active-notification state and exposes ephemeral domain snapshots

Introduce an application-process active-notification coordinator with a read-only `StateFlow`-style state for consumers. The connected listener publishes:

- availability (`connected` or `unavailable`);
- a stable notification key used only in memory;
- extracted `NotificationContent`;
- whether the notification is eligible for cancellation under the existing safety policy;
- minimal display metadata already available locally, such as post time.

The listener refreshes the snapshot on connection and when notifications are posted or removed, and clears it on disconnection. The UI never receives `StatusBarNotification` or `Notification` framework objects and cannot request cancellation through the preview state.

This keeps Android service ownership explicit and avoids persisting additional content. Using the journal was rejected because it omits visible notifications and may be disabled. Binding the activity directly to the notification-listener service was rejected because the service is system-managed and the UI only needs observable state, not a second command surface.

### 2. Preview is a pure comparison of baseline and proposed rule sets

Create a domain-level preview evaluator that accepts saved rules, an optional rule being edited, the draft, the global-filtering state, and active snapshots. For editing, it removes the saved version by id and inserts the draft once. A direct-match check evaluates an enabled copy of the draft so the UI can still explain a text match when the draft itself is disabled. Effective decisions use the actual enabled state and the same compiled `RuleMatcher` as runtime filtering.

For each sample, the evaluator records at least:

- whether the draft directly matches;
- the baseline decision under saved rules;
- the proposed decision under the substituted rule set;
- whether the notification is eligible;
- whether saving the draft will cause the currently active notification to be removed.

The final removal prediction requires global filtering to be enabled, eligibility to pass, and the proposed complete rule set to resolve to `BLOCK`. This correctly represents `ALLOW` precedence rather than presenting every regex hit as a removal.

Preview computation is debounced after text edits, compiles each valid rule set once per computation, runs off the UI thread, and is cancelled when newer draft or active-notification state arrives. The existing RE2 and input-length limits remain authoritative.

### 3. Rule updates and re-filter passes are serialized inside the listener

The listener remains the only component allowed to call `cancelNotification`. It represents the current runtime filter state as one versioned value containing the compiled matcher and its rule-set revision. Room rule emissions replace this value before scheduling a re-filter pass. Global-filtering changes and listener connection schedule the same pass.

Only the newest pending pass is retained. Processing uses a single serialized service-owned execution context so a matcher replacement, the final decision check, and the cancellation request cannot interleave with an older pass inside the process. Immediately before cancellation, the candidate is evaluated against the current matcher state, not a matcher captured only at the beginning of the scan.

The pass obtains `activeNotifications`, skips candidates that fail the existing safety policy, extracts text with the existing extractor, and sends blocked decisions to the common cancellation path. A notification disappearing between enumeration and cancellation is treated as a normal race. When filtering is disabled, the pass refreshes preview state but performs no cancellations.

Observing the committed Room rule flow in the service is preferred over an activity-originated “apply this rule” command: database state remains the source of truth, changes from toggles and deletes behave consistently, and cancellation cannot run before persistence succeeds. Global filtering must become observable across `UserPreferences` instances, for example through a shared preference-change flow or an application-scoped preference source; a view-model-only in-memory flow is insufficient.

### 4. Posted events and re-filtering share one deduplicated cancellation operation

Extract the existing decision-to-cancellation sequence into one service-owned operation used by both `onNotificationPosted` and active re-filtering. Maintain an in-flight entry keyed by the Android notification key regardless of whether journal storage is enabled. Creating the in-flight entry, taking the journal snapshot, requesting cancellation, and associating journal metadata form one logical operation.

Concurrent attempts for the same key join or skip the existing operation. Removal callbacks confirm the same operation, and timeout cleanup releases keys whose callbacks never arrive. This extends the current pending-cancellation protection, which otherwise exists only when journal recording is enabled, and prevents duplicate journal rows with different generated fingerprints.

### 5. The editor shows outcomes, not a second notification journal

Replace or supplement the manual test-string area with an “Active notifications” preview section. For a valid draft it shows a compact summary such as direct matches and notifications that will be hidden after saving, followed by a bounded list of relevant samples. Each sample explains exceptional outcomes such as an `ALLOW` override, a protected category, disabled filtering, or a disabled draft.

An unavailable listener, empty active set, and invalid regex each receive distinct UI states. The editor remains usable in all three states. Raw active-notification content is not written to Room, preferences, logs, saved-instance state, or analytics; only the rule fields keep their existing saveable behavior. The optional journal continues to store only notifications actually cancelled by Notifilter.

## Risks / Trade-offs

- **[Android reports active state asynchronously]** → Treat disappearance during a pass as success, refresh after notification callbacks, and avoid promising a synchronous UI animation.
- **[A broad regex can remove many existing notifications immediately]** → Show the predicted removal count and relevant samples before save; do not cancel during draft editing.
- **[Rule changes can arrive faster than a scan completes]** → Cancel superseded work, version runtime state, serialize final evaluation and cancellation, and re-check with the current matcher.
- **[Preview extraction adds CPU work while typing]** → Debounce draft changes, reuse bounded extracted text, compile once per computation, cap rendered samples, and run evaluation off the UI thread.
- **[Holding notification text in process increases transient exposure]** → Retain only current active snapshots, clear on listener disconnection, avoid persistence and logging, and keep existing extraction limits.
- **[Service process death loses preview state]** → Publish `unavailable` until reconnection, then rebuild from `activeNotifications` and re-filter if filtering is enabled.
- **[A cancellation callback may never arrive]** → Keep bounded timeout cleanup for in-flight keys while preserving database-level journal uniqueness.

## Migration Plan

1. Add the ephemeral coordinator and preview domain models without changing the Room schema.
2. Refactor posted-event cancellation behind the shared deduplicated operation while preserving existing behavior.
3. Publish listener availability and active snapshots, then add the pure preview evaluator and editor UI.
4. Enable versioned re-filter triggers for committed rule changes, global-filtering enablement, and listener connection.
5. Verify unit, lint, and Android build jobs exclusively through GitHub Actions.

Rollback removes the preview and re-filter triggers while leaving the existing rule and journal database unchanged; no stored-data migration is required.
