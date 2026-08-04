# active-notification-refiltering Specification

## Purpose

Make rule changes understandable and immediate by previewing their effect on notifications already in the system shade and applying committed filtering decisions to those notifications.

## Requirements

### Requirement: Live draft preview on active notifications
The system SHALL evaluate each valid rule draft against notifications that are currently active and available to the notification-listener service. The preview SHALL update when the draft's application scope, regular expression, match target, case-sensitivity setting, action, or enabled state changes, and when the available active-notification set changes.

#### Scenario: New blocking rule matches an active notification
- **WHEN** the user enters a valid enabled `BLOCK` draft that matches an eligible notification currently present in the system shade
- **THEN** the editor reports that the active notification directly matches and will be hidden after the rule is saved

#### Scenario: Invalid regular expression
- **WHEN** the draft contains an invalid regular expression
- **THEN** the editor reports the validation error and does not report an effective filtering outcome for that draft

#### Scenario: Notification access is unavailable
- **WHEN** the notification-listener service cannot provide active notifications
- **THEN** the editor reports that active-notification preview is unavailable and continues to allow rule editing and validation

### Requirement: Preview reflects the complete proposed rule set
The system SHALL calculate the effective preview decision using the complete saved rule set with the draft added as a new rule or substituted for the rule being edited. The preview SHALL distinguish a direct draft match from an outcome that changes whether the notification will be hidden.

#### Scenario: Allow rule overrides a matching block draft
- **WHEN** an active notification directly matches the `BLOCK` draft and also matches an enabled `ALLOW` rule
- **THEN** the preview reports the direct match but reports that the notification will remain visible

#### Scenario: Editing replaces the saved rule in the preview
- **WHEN** the user edits an existing rule
- **THEN** the preview evaluates the proposed rule set with the draft replacing the saved version instead of evaluating both versions

#### Scenario: Disabled draft matches notification text
- **WHEN** a disabled draft directly matches an active notification
- **THEN** the preview may report the direct match but reports that the disabled draft will not change the filtering outcome

### Requirement: Preview is non-destructive
The system MUST NOT remove or otherwise alter an active notification solely because an unsaved rule draft matches it.

#### Scenario: User dismisses a matching draft
- **WHEN** the user creates a draft that matches an active notification and then closes the editor without saving
- **THEN** the system leaves that notification unchanged

### Requirement: Committed rule changes apply to active notifications
While global filtering is enabled, the system SHALL re-evaluate currently active notifications after a committed rule-set change has become the current rule set. Rule-set changes include creating, editing, enabling, disabling, and deleting a rule. The system SHALL also re-evaluate active notifications when global filtering changes from disabled to enabled.

#### Scenario: Saving a new enabled block rule removes an existing push
- **WHEN** the user saves an enabled `BLOCK` rule that makes an eligible notification already present in the system shade resolve to a blocked decision
- **THEN** the system requests removal of that notification without waiting for the source application to post it again

#### Scenario: Enabling an existing rule removes an existing push
- **WHEN** the user enables a saved rule and an eligible active notification resolves to a blocked decision under the updated rule set
- **THEN** the system requests removal of that notification

#### Scenario: Enabling global filtering applies current rules
- **WHEN** global filtering changes from disabled to enabled
- **THEN** the system re-evaluates eligible active notifications using the current enabled rules and requests removal of those that resolve to blocked decisions

#### Scenario: Notification listener reconnects
- **WHEN** the notification-listener service becomes connected while global filtering is enabled
- **THEN** the system re-evaluates the active notifications now available to the listener using the current enabled rules

#### Scenario: Filtering remains disabled
- **WHEN** a rule changes while global filtering is disabled
- **THEN** the system does not remove active notifications because of that rule change

### Requirement: Re-filtering preserves filtering protections and precedence
Re-filtering SHALL use the same application scope, text target, case-sensitivity behavior, rule precedence, and protected-notification exclusions as filtering a newly posted notification. A direct regular-expression match SHALL NOT by itself bypass these policies.

#### Scenario: Protected notification matches a blocking rule
- **WHEN** a protected active notification directly matches an enabled `BLOCK` rule
- **THEN** the system leaves the notification visible and the preview does not describe it as an effective removal

#### Scenario: Allow exception wins during re-filtering
- **WHEN** an eligible active notification matches both an enabled `BLOCK` rule and an enabled `ALLOW` rule
- **THEN** the system leaves the notification visible

#### Scenario: Rule is scoped to another application
- **WHEN** an active notification's package does not match the blocking rule's application scope
- **THEN** the system leaves the notification visible

### Requirement: Retroactive cancellation integrates with the journal
An active notification removed by re-filtering SHALL follow the same optional journal behavior as a notification removed when first posted. The system SHALL create at most one journal record for one removal event and SHALL NOT persist notifications that remain visible solely to support preview.

#### Scenario: Journal is enabled
- **WHEN** re-filtering removes an active notification while the journal is enabled
- **THEN** the system records the removed notification and the rule responsible for the effective blocked decision in the journal

#### Scenario: Journal is disabled
- **WHEN** re-filtering removes an active notification while the journal is disabled
- **THEN** the system does not create a journal record for that removal

#### Scenario: Posted-event filtering races with re-filtering
- **WHEN** normal posted-event filtering and active-notification re-filtering process the same notification concurrently
- **THEN** the system issues no more than one logical cancellation flow and creates no more than one journal record for that removal

### Requirement: Re-filtering tolerates active-notification races
The system SHALL base removal on the latest committed filtering state available to the listener and SHALL handle notifications appearing or disappearing during a re-filter pass without failing the pass or cancelling a notification based only on a superseded rule state.

#### Scenario: Notification disappears before cancellation
- **WHEN** an active notification is dismissed by another actor after the re-filter pass reads it but before removal is requested
- **THEN** the system completes the pass without presenting an error to the user

#### Scenario: Rule state changes during a pass
- **WHEN** a newer committed rule state supersedes the state used by an in-progress re-filter pass
- **THEN** the system does not remove a notification unless it still resolves to a blocked decision under the current committed state
