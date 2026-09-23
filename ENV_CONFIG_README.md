# Env configuration over SMB

The root `.env` is the canonical configuration path. `shared.env` is a normal, visible NTFS hard link to the same file. Both names contain the same bytes, so an ordinary in-place save through either name is immediately visible through the other. There is no polling task or background synchronization service.

On the Desktop, open `C:\AbandonWare\demo-1\demo-1\src\shared.env`. On a Notebook whose existing verified project mapping is `Y:\`, open `Y:\shared.env`. Use the existing authenticated SMB share. The existing Windows NTFS and share permissions apply to both names; no world-writable ACL or share configuration was added.

Write one `KEY_NAME=VALUE` per line in UTF-8. Spring loads `.env` as a properties file through the existing `optional:file:.env[.properties]` import. Use unquoted values: Java properties retain quote characters. A backslash is a properties escape; use `\\` for a literal backslash. This file is not a shell script. Existing operating-system environment variables and command-line/system properties keep their higher priority. Restart an application to consume file changes; a file save does not refresh an already running Spring context or change another process's environment. If `APP_CONFIG_IMPORT` is overridden, its list must retain the `.env` import to use this file.

Some editors save by replacing the original file. This breaks a hard link and creates two independent files. Prefer in-place save. After replacement-save, check on the Desktop:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\shared_env.ps1
```

A `linked` result proves file identity, not merely equal hashes. A `repair-needed` result requires choosing which file's content to keep. Close both editors. To use the changes from `shared.env`, take both hashes from the preceding status and run:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\shared_env.ps1 -Apply -Source Shared -ExpectedDotEnvSha256 '<dotEnvSha256>' -ExpectedSharedSha256 '<sharedSha256>'
```

Use `-Source DotEnv` to keep `.env` instead. For an absent file, the expected hash is `missing`. The helper refuses a changed preimage or an unexpected link/path, preserves different destination bytes in a current-user DPAPI-encrypted `.env.recovery-*.dpapi` file, and recreates only the second filename. Run repairs on the Desktop's local NTFS path; remote client write proof is separate from Desktop repair proof.

The root `.env` and `shared.env` are ignored by Git. The existing Git secret guard also rejects `shared.env` by filename even for a credential format its pattern scanner does not recognize. `apikey.txt` now records environment references, and `.env.shared/zai.env` points here in a comment. The handoff report redactor covers suffix `KEY`, `KEYS`, and client-ID names so future environment reports do not reproduce the credential copies removed during migration.

The migration evidence, extracted variable-name list, synthetic verification results and scan scope are in `verification/env-smb-20260913/`. That directory's `preimages.dpapi` contains an encrypted rollback snapshot for this Desktop Windows user. No plaintext credential backup or credential value is included in the human-readable report. Keep both environment filenames private; do not attach them to issues, chat, or public pages.

Implementation references: [Microsoft NTFS hard links](https://learn.microsoft.com/en-us/windows/win32/fileio/hard-links-and-junctions) and [Spring external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html).
