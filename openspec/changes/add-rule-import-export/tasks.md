## 1. Backup format and validation

- [x] 1.1 Implement the versioned rule-backup model and JSON codec with complete field, regex, byte-size, and rule-count validation
- [x] 1.2 Add codec tests for round trips, empty exports, malformed data, unsupported versions, invalid rules, and safety limits

## 2. Atomic persistence

- [x] 2.1 Add transactional DAO and repository operations for replacing all rules and adding non-duplicate imported rules
- [x] 2.2 Add persistence tests covering order preservation, duplicate skipping, replacement, and transaction rollback behavior

## 3. Import and export interface

- [x] 3.1 Add ViewModel state and content-resolver operations for export preparation, import validation, confirmation, cancellation, and result messages
- [x] 3.2 Add system document launchers, export/import actions, and an explicit add-or-replace confirmation dialog to the rules screen
- [x] 3.3 Verify descriptive control labels, replacement consequences, cancellation behavior, progress states, and accessible result announcements

## 4. Release documentation

- [x] 4.1 Document the backup contents, limits, privacy behavior, and reinstall workflow in README and privacy documentation
- [x] 4.2 Increment the application version for the rule-backup release

## 5. Verification

- [x] 5.1 Run strict OpenSpec validation and static source checks without performing a local Android build
- [ ] 5.2 Publish through `gh`, run GitHub Actions, fix any defects, and confirm the APK artifact is produced
