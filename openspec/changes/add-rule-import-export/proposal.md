## Why

Rules currently live only in the app's private database, so reinstalling the app or moving to a build signed with a different key can destroy a configuration that may have taken significant effort to create. Users need a portable backup that they control before uninstalling, changing devices, or changing distribution channels.

## What Changes

- Add export of all filtering rules to a versioned JSON document selected through Android's system file picker.
- Add import from a JSON document with full validation before any database mutation.
- Show an import summary and require the user to choose whether imported rules are added to the existing set or replace it.
- Reject unsupported, malformed, or invalid backups without partially importing data.
- Document that rule backups do not contain notification-journal entries or application secrets.

## Capabilities

### New Capabilities
- `rule-backup`: Portable, versioned export and safe import of filtering rules.

### Modified Capabilities

None.

## Impact

- Rule persistence and repository operations.
- A JSON backup codec and import validation model.
- Rules screen actions, system document picker integration, and confirmation UI.
- Unit tests, privacy documentation, and user documentation.
