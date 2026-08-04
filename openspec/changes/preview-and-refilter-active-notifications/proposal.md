## Why

Rules currently affect notifications only when they are posted after the rule matcher has updated. A user who creates a rule in response to a notification already visible in the system shade expects to see that notification match before saving and disappear as soon as the enabled rule takes effect.

## What Changes

- Preview a valid rule draft against notifications that are currently active and eligible for filtering.
- Show both direct draft matches and the actual outcome after applying the complete rule set, including higher-priority `ALLOW` rules and protected-notification exclusions.
- After a rule is created, enabled, disabled, edited, or deleted, rebuild the matcher and re-evaluate active notifications so decisions from the committed rule set take effect immediately.
- Re-evaluate active notifications when global filtering is enabled.
- Route retroactively removed notifications through the existing cancellation and optional journal flow, without persisting notifications that remain visible solely for preview purposes.
- Keep rule editing non-destructive: notifications are not removed until the user saves or enables the rule.

## Capabilities

### New Capabilities

- `active-notification-refiltering`: Preview rule effects on active notifications and apply enabled rule changes to notifications already present in the system shade.

### Modified Capabilities

None.

## Impact

- Rule editor UI and view-model state for live preview results and availability feedback.
- Notification-listener coordination for active-notification snapshots, matcher updates, and re-filter triggers.
- Existing filtering safety policy, rule precedence, cancellation deduplication, and notification journal integration.
- Domain, service, and UI tests covering previews, rule-change triggers, protected notifications, and races with notification removal.
- No new Android permission, network access, or storage of allowed-notification history is required.
