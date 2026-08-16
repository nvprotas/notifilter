## MODIFIED Requirements

### Requirement: Retroactive cancellation integrates with the journal
An active notification removed by re-filtering SHALL update its existing optional history entry when one exists. When no received entry exists, the system SHALL create at most one removal history entry only if history is enabled and no enabled history exclusion matches. Enumerating notifications for re-filtering or preview SHALL NOT by itself create history for notifications that remain visible.

#### Scenario: Existing history entry is re-filtered
- **WHEN** re-filtering removes an active notification that already has a history entry
- **THEN** the system updates that logical entry with the effective matched rule and removal outcome instead of creating another entry

#### Scenario: Unrecorded active notification is removed while history is enabled
- **WHEN** re-filtering removes an active notification that has no history entry while history is enabled and no exclusion matches
- **THEN** the system creates one removal history entry containing the notification and effective matched rule

#### Scenario: History is disabled
- **WHEN** re-filtering removes an active notification while history is disabled
- **THEN** the system does not create a history entry for that removal

#### Scenario: History exclusion matches
- **WHEN** re-filtering removes an active notification matched by an enabled history exclusion
- **THEN** the system does not retain a history entry for that notification

#### Scenario: Posted-event filtering races with re-filtering
- **WHEN** normal posted-event filtering and active-notification re-filtering process the same notification concurrently
- **THEN** the system issues no more than one logical cancellation flow and retains no more than one logical history entry for that notification
