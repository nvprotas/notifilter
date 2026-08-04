## Purpose

Allow users to preserve and restore their filtering configuration in a portable file before reinstalling the app, moving devices, or changing distribution channels.

## ADDED Requirements

### Requirement: Rules can be exported as a portable backup
The system SHALL export every saved filtering rule to a versioned JSON document chosen by the user. The backup SHALL preserve each rule's application scope, regular expression, match target, action, case-sensitivity setting, enabled state, and relative display order. It MUST NOT contain notification-journal records, internal database identifiers, credentials, or signing material.

#### Scenario: Export all rules
- **WHEN** the user chooses a writable document and confirms rule export
- **THEN** the system writes one supported-version backup containing all current rules and reports that export completed

#### Scenario: Export an empty rule set
- **WHEN** the user exports rules while no rules are saved
- **THEN** the system writes a valid supported-version backup with an empty rules collection

#### Scenario: Document cannot be written
- **WHEN** the selected document cannot be created or written completely
- **THEN** the system reports that export failed without changing saved rules

### Requirement: Backups are selected through system document access
The system SHALL use Android's system document selection interfaces for export and import and SHALL NOT require broad storage access.

#### Scenario: User cancels document selection
- **WHEN** the user closes the system document selector without choosing a document
- **THEN** the system returns to the rules screen without changing rules or reporting a successful operation

### Requirement: Imports are validated before changing rules
The system SHALL parse and validate the complete selected backup before changing the database. It MUST reject malformed JSON, an unrecognized backup format or version, missing required rule properties, unsupported property values, invalid regular expressions, and input exceeding documented safety limits. A failed validation MUST leave all saved rules unchanged.

#### Scenario: Valid backup is selected
- **WHEN** the selected document is a supported backup and every contained rule is valid
- **THEN** the system presents an import summary without changing saved rules

#### Scenario: Backup contains an invalid rule
- **WHEN** any rule in the selected document has an invalid regular expression or unsupported property value
- **THEN** the system identifies that the backup cannot be imported and leaves all saved rules unchanged

#### Scenario: Backup version is unsupported
- **WHEN** the selected document declares a format version the app does not support
- **THEN** the system reports the unsupported version and leaves all saved rules unchanged

#### Scenario: Backup exceeds a safety limit
- **WHEN** the selected document is larger than the supported size or contains more than the supported number of rules
- **THEN** the system reports that the backup is too large and leaves all saved rules unchanged

### Requirement: Import requires an explicit mode and confirmation
For a valid backup, the system SHALL show the number of rules found and require the user to choose either adding rules to the current set or replacing the current set. The replacement action SHALL explicitly state how many current rules will be removed. Closing the summary without confirming MUST leave the database unchanged.

#### Scenario: Add imported rules
- **WHEN** the user confirms adding a valid backup
- **THEN** the system preserves current rules, adds imported rules that are not exact functional duplicates, and reports the number added and skipped

#### Scenario: Replace current rules
- **WHEN** the user confirms replacing current rules with a valid backup
- **THEN** the system atomically removes all current rules, stores the imported rules in their exported order, and reports the number restored

#### Scenario: Cancel a valid import
- **WHEN** the user dismisses the import summary without confirming either mode
- **THEN** the system leaves all saved rules unchanged

### Requirement: Imported changes immediately affect filtering
After a successful import, the resulting committed rule set SHALL become the current rule set and SHALL trigger the same re-filtering behavior as other committed rule changes while global filtering is enabled.

#### Scenario: Imported block rule matches an active notification
- **WHEN** a successful import adds or restores an enabled blocking rule that makes an eligible active notification resolve to blocked
- **THEN** the system requests removal of that notification without waiting for it to be posted again

### Requirement: Import commits are atomic
The system MUST apply a confirmed import as one database transaction. If the transaction fails, it MUST preserve the complete pre-import rule set and report that import failed.

#### Scenario: Storage failure during replacement
- **WHEN** storing a confirmed replacement fails before the transaction commits
- **THEN** the system retains every rule that existed before the import and reports that no rules were imported
