# PatchDrop Desktop Intake

`source_edit_session.ps1` is the source-edit lease guard for Desktop, Mac mini, and Notebook coordination.

- Desktop applies final source edits from the canonical root only.
- Producers must not use shared SMB/NAS roots for direct source edits; `source_edit_session.ps1` classifies that as `smb-direct-edit`.
- Active leases live under `source-edit-locks/` and are reported by `janitor_inventory.ps1`.
- Use `source_edit_session.ps1 -Action begin -Role desktop-consumer` before Desktop applies a PatchDrop bundle, and `-Action end` after verification.

