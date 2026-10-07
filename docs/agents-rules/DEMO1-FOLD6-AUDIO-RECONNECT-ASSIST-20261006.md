# Fold6 audio reconnect assist (2026-10-06)

Pointer only. Codex owns the product patch. The live display reconnect owner at assist open is `codex-fold6-reconnect-fold6-reconnect-stop-20261006-cacd59cf`.

SSOT: `var/codex-assist-fold6-audio-reconnect-20261006/README.md`

```
python -B scripts/fold6_audio_reconnect_assist.py pin --root .
python -B scripts/fold6_audio_reconnect_assist.py cover --root .
python -B scripts/fold6_audio_reconnect_assist.py scope --root .
python -B scripts/fold6_audio_reconnect_assist.py product-gate --root . --diff <owned.diff>
```

A scan exit 0 is not a product PASS. Do not edit product source from this rail. Do not force-release the Fold6 reconnect lease.
