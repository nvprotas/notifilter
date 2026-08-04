## Context

Rules are Room entities exposed through `RuleRepository` and edited from a single Compose screen backed by `RulesViewModel`. Android backup is intentionally disabled, and the app currently has no user-controlled data-transfer path. See `proposal.md` for motivation and `specs/rule-backup/spec.md` for the observable contract.

The implementation must not require local Android builds, broad storage permission, or exposure of rule data to another app-owned location. Imports can replace the complete rule table, so validation and persistence boundaries must prevent partial state.

## Goals / Non-Goals

**Goals:**

- Define a stable version-1 JSON envelope that is independent of Room IDs.
- Keep parsing and validation deterministic and unit-testable.
- Apply add and replace imports transactionally.
- Use Android's Storage Access Framework and retain no document access after an operation completes.
- Make replacement consequences explicit and keep cancellation non-destructive.

**Non-Goals:**

- Exporting or importing journal entries, preferences, APK signing keys, or application credentials.
- Recovering data from an already installed build that cannot be updated and exposes no export API.
- Automatic cloud synchronization or background backup.
- Importing formats produced by unrelated notification-filtering applications.

## Decisions

### Versioned JSON envelope

The document will use UTF-8 JSON with a fixed marker and integer version:

```json
{
  "format": "notifilter-rule-backup",
  "version": 1,
  "rules": [
    {
      "packageName": "com.example.app",
      "pattern": "sale|discount",
      "target": "ALL_TEXT",
      "action": "BLOCK",
      "ignoreCase": true,
      "enabled": true
    }
  ]
}
```

`packageName` may be JSON null for an all-applications rule. Array order is the displayed rule order. Database IDs and timestamps are omitted and regenerated during import. A marker plus version is preferred over accepting any object containing a `rules` array because it prevents an unrelated JSON file from being treated as a backup.

### Bounded, complete validation

`RuleBackupCodec` will read at most 1 MiB and accept at most 5,000 rules. It validates the envelope, every required property, enum names, non-blank patterns up to the matcher's existing 512-character limit, package-name shape when present, and RE2/J regular-expression compilation before returning an immutable decoded backup. The ViewModel keeps a validated pending backup in memory only until it is confirmed or dismissed.

Parsing the whole document before exposing confirmation is preferred over streaming inserts because all validation failures then occur before mutation and the expected backup size is deliberately bounded.

### System document contracts

Compose launchers will use `CreateDocument("application/json")` for export and `OpenDocument` restricted to JSON-compatible MIME types for import. The returned URI is handed to the ViewModel, which performs content-resolver I/O on `Dispatchers.IO`. No storage permission or persistable URI grant is needed because operations complete while the temporary grant is valid.

### Atomic repository operations

The DAO will expose transaction-scoped add and replace operations. Replace clears and inserts inside one Room transaction. Add reads the current functional rule keys, removes duplicates both against the database and within the backup, and inserts only unique rules in the same transaction. A functional key comprises package scope, pattern, target, action, case sensitivity, and enabled state; IDs and timestamps do not participate.

Imported entities receive fresh IDs and monotonic timestamps derived from their array order, preserving relative display order. Any transaction exception is reported as failure and Room rolls back the complete operation.

### Explicit import state

The ViewModel exposes an import-preview state containing the file rule count, current rule count, and duplicate count. The dialog provides `Добавить правила`, `Заменить правила`, and `Отмена`. Replacement copy states the exact number of current rules that will be removed. Buttons use native Material semantics and descriptive visible labels; progress disables repeat submission, and results are announced through the screen's existing message channel.

Successful import uses the same rule-table observation path as normal edits, so the active-notification coordinator sees the committed set and re-filters without a separate import-only mechanism.

## Risks / Trade-offs

- **[A future schema needs new fields]** → Increment the envelope version when a required semantic field changes; reject unsupported versions instead of guessing defaults.
- **[A crafted file consumes memory or CPU]** → Enforce byte/rule limits before expensive validation and cap regex/input fields to documented implementation limits.
- **[Replace could erase a working configuration]** → Validate first, show exact impact, require an explicit verb-labelled action, and commit in one transaction.
- **[Exact duplicate detection can retain semantically overlapping regexes]** → Deduplicate only byte-for-byte functional equality; broader regex equivalence is undecidable and would be surprising.
- **[System providers report imprecise MIME types]** → Validate content rather than trusting MIME type, while keeping the picker filter broad enough for `.json` files.

## Migration Plan

No database schema migration is required. Deploy the codec, DAO operations, and UI together. Existing rules remain untouched until the user explicitly imports a valid backup. Rollback removes the UI entry points and code while leaving the existing rule table unchanged; previously exported JSON files remain user-owned documents.
