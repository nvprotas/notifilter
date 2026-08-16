# notification-history Specification

## Purpose

Provide a consent-based, local corpus of filter-eligible notifications that users can review while preventing selected applications and sensitive matching content from being persisted.

## Requirements

### Requirement: History capture requires explicit consent
The system SHALL persist newly posted notifications only while notification history is explicitly enabled. Capture SHALL be independent of whether global notification filtering is enabled, and the system SHALL NOT persist a notification until the current history-consent state and enabled exclusion rules are available.

#### Scenario: History is disabled
- **WHEN** a notification is posted while notification history is disabled
- **THEN** the system does not create or update a history entry for that notification

#### Scenario: Filtering is disabled but history is enabled
- **WHEN** a filter-eligible notification is posted while history is enabled and global filtering is disabled
- **THEN** the system records the notification unless an enabled history exclusion matches it

#### Scenario: Capture policy is still loading
- **WHEN** a notification is posted before the system has loaded the current history consent and enabled exclusions
- **THEN** the system does not persist that notification

#### Scenario: History is disabled after use
- **WHEN** the user disables history after entries have been recorded
- **THEN** the system stops future history writes and retains existing entries until retention, individual deletion, or explicit clearing removes them

### Requirement: History contains only filter-eligible posted notifications
The system SHALL record only notifications that pass the same application and protected-notification eligibility policy used for newly posted notification filtering. Reading active notifications for preview or listener reconnection SHALL NOT by itself create received-notification history.

#### Scenario: Ordinary application notification is posted
- **WHEN** an eligible notification is newly posted while history is enabled and no exclusion matches
- **THEN** the system records its package, bounded extracted title and body, posting time, and current filtering outcome

#### Scenario: Protected notification is posted
- **WHEN** a system, non-clearable, call, alarm, media, ongoing, foreground-service, group-summary, full-screen, or self-authored notification is posted
- **THEN** the system does not record it in history

#### Scenario: Active notification is discovered without a posted event
- **WHEN** the listener connects or refreshes preview state and discovers an active notification that was not observed through a new posted event
- **THEN** the system does not create a received history entry solely because it discovered that notification

### Requirement: Unified history exclusions support application and content scope
The system SHALL allow an enabled history exclusion to specify an application package, a valid regular expression over notification content, or both. An application-only exclusion SHALL match every eligible notification from that package, a content-only exclusion SHALL match across all packages, and a combined exclusion SHALL require both its package and content criteria to match. Saving an exclusion with neither criterion or an invalid expression SHALL be rejected.

#### Scenario: Entire application is excluded
- **WHEN** an enabled exclusion names an application and has no content expression
- **THEN** the system does not retain history entries from that application

#### Scenario: Content is excluded globally
- **WHEN** an enabled exclusion has a content expression but no application and an eligible notification from any package matches it
- **THEN** the system does not retain that notification in history

#### Scenario: Content exclusion is scoped to one application
- **WHEN** an enabled exclusion specifies both an application and a content expression
- **THEN** the system excludes only matching notifications from that application

#### Scenario: Invalid exclusion cannot be saved
- **WHEN** the user attempts to save an exclusion with neither criterion or with an invalid regular expression
- **THEN** the system reports validation feedback and leaves the committed exclusions unchanged

#### Scenario: History exclusion matches a visible notification
- **WHEN** a notification matches a history exclusion but does not resolve to a blocking filtering decision
- **THEN** the system leaves the notification visible and omits only its history record

### Requirement: Standalone 4–6 digit values are excluded by default
The system SHALL initialize an enabled global history-content exclusion that matches a standalone sequence of 4 through 6 ASCII digits in the extracted title or body. The sequence SHALL be bounded by the start or end of text or by non-digit characters so that the exclusion does not match only a substring of a longer digit sequence.

#### Scenario: Six-digit value appears in text
- **WHEN** an eligible notification contains `Код: 123456` and history is enabled
- **THEN** the default exclusion prevents the notification from being retained

#### Scenario: Four-digit value appears in a title
- **WHEN** an eligible notification title contains a standalone value such as `4821`
- **THEN** the default exclusion prevents the notification from being retained

#### Scenario: Longer numeric sequence appears
- **WHEN** an eligible notification contains only a contiguous 7-or-more-digit sequence and no separate 4–6 digit sequence
- **THEN** the default exclusion does not match a substring of that longer sequence

### Requirement: Enabling an exclusion removes matching history atomically
When an exclusion is created or changes from disabled to enabled, the system SHALL remove existing history entries matched by its committed criteria and SHALL prevent an older in-flight capture from restoring a matching entry after cleanup completes. Disabling or deleting an exclusion SHALL affect only future capture and SHALL NOT restore deleted history.

#### Scenario: Application is newly excluded
- **WHEN** the user enables an application-only exclusion for a package that already has history
- **THEN** all existing history entries from that package are removed and future matching notifications are omitted

#### Scenario: Content expression is newly enabled
- **WHEN** the user enables a content exclusion that matches existing entries
- **THEN** the matching entries are removed while non-matching entries remain

#### Scenario: Capture races with exclusion cleanup
- **WHEN** a matching posted-event capture began before an exclusion was committed and completes after exclusion cleanup
- **THEN** no matching history entry remains after the exclusion operation completes

#### Scenario: Exclusion is removed
- **WHEN** the user disables or deletes an exclusion
- **THEN** future notifications no longer matched by another exclusion may be recorded, but previously deleted entries are not recreated

### Requirement: Notification updates remain one logical history entry
The system SHALL represent repeated posted callbacks that update the same logical active Android notification as one history entry. The retained entry SHALL contain the latest observed bounded title and body without weakening a filtering outcome already recorded for that logical notification.

#### Scenario: Visible notification is updated
- **WHEN** an application updates the title or body of the same active notification
- **THEN** the system updates the existing history entry instead of adding a duplicate entry

#### Scenario: Update becomes excluded
- **WHEN** an update to an existing recorded notification causes an enabled content exclusion to match
- **THEN** the system removes the existing logical history entry

#### Scenario: Android notification identity is reused later
- **WHEN** an application posts a new logical notification after the prior notification with the same reusable identifier is no longer active
- **THEN** the system records a new history entry rather than overwriting the earlier event

### Requirement: History preserves filtering outcomes
Each retained history entry SHALL distinguish a received notification from a requested or Android-confirmed Notifilter removal. When a blocking decision applies, the system SHALL associate the effective matched rule with that logical history entry without creating a second audit record.

#### Scenario: Notification remains visible
- **WHEN** an eligible recorded notification does not resolve to a blocking decision
- **THEN** its history outcome reports that it was received without a Notifilter removal request

#### Scenario: Notifilter requests removal
- **WHEN** an eligible recorded notification resolves to a blocking decision
- **THEN** its history entry records the removal request and effective matched rule

#### Scenario: Android confirms removal
- **WHEN** Android reports that a recorded notification was removed because of the listener cancellation request
- **THEN** the same history entry changes to a confirmed-removal outcome

### Requirement: Users can inspect and erase bounded local history
The system SHALL provide a History interface that supports search by application label, package, title, body, and matched-rule pattern; inspection and individual deletion; and explicit clearing of all entries. History SHALL remain local, excluded from Android backup and device transfer, limited to entries from the most recent 30 days, and capped at 1000 entries.

#### Scenario: User searches history
- **WHEN** the user searches with text present in a retained entry or its application identity
- **THEN** the history displays the matching entries in reverse chronological order

#### Scenario: User clears history
- **WHEN** the user confirms clearing history
- **THEN** all entries committed no later than that action are removed without disabling future capture

#### Scenario: Retention limits are exceeded
- **WHEN** history maintenance runs after an entry is older than 30 days or the history contains more than 1000 entries
- **THEN** the system removes expired entries and retains no more than the 1000 most recent entries

### Requirement: Upgrade does not broaden prior journal consent
On upgrade from the blocked-notification journal schema, the system SHALL migrate retained journal records into history with their removal outcomes, except for records matched by enabled history exclusions. The system MUST initialize history capture as disabled regardless of the previous journal preference and MUST require the user to enable the broader history behavior explicitly.

#### Scenario: Previous journal was enabled
- **WHEN** an existing installation with journal saving enabled upgrades
- **THEN** prior eligible journal entries remain inspectable subject to active exclusions, but newly allowed notifications are not recorded until the user explicitly enables history

#### Scenario: Migrated record matches the default digit exclusion
- **WHEN** a migrated journal record contains a standalone 4–6 digit value
- **THEN** the system removes or omits that record before exposing the migrated history
