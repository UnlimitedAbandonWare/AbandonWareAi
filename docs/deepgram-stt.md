# Deepgram STT configuration and streaming

`DeepgramSttService` is a Spring service in the active backend source set. Its constructor receives `DeepgramProperties`; application startup does not contact Deepgram. A subscription to `transcribePcm16Mono` owns one WebSocket connection and is never automatically retried.

```java
public VoiceService(DeepgramSttService stt) {
    this.stt = stt;
}

public Flux<DeepgramSttService.Transcript> transcribe(Flux<byte[]> pcmChunks) {
    return stt.transcribePcm16Mono(pcmChunks, 16000, "ko");
}
```

Input is raw signed 16-bit little-endian mono PCM, without a WAV header. Each chunk must contain a nonzero even number of bytes, at most 65,536. Supply the actual sample rate and language. Completing the input sends `CloseStream`; the service continues receiving final `Results` and requires WebSocket close code 1000 before completing successfully. Input pauses generate `KeepAlive` messages. A 30-second gap in server messages fails the session. Cancelling the subscription cancels its transport.

`Transcript` carries the recognized text, `isFinal`, and `speechFinal`. Empty/interim results can occur; callers should use final, nonblank text for completed utterances. Transcript object rendering reports only character count and finality. Provider error bodies, credentials, and authorization headers are not included in service exceptions. Missing or template credentials produce `deepgram:missing_api_key` before any connection attempt.

## Local development

Use `DEEPGRAM_API_KEY` as the primary environment variable and `DEEPGRAM_API_KEY_SECONDARY` for a distinct backup key. `DeepgramProperties` supplies the primary key when configured, otherwise the secondary. An authentication or stream failure does not automatically retry another key.

On Desktop these names are registered in the Windows User environment. New shells inherit them. To refresh an existing PowerShell session from the ignored project secret store, run `. ./scripts/use_project_keys.ps1` from the repository root, then start the application from that shell. Restart an existing owned application process to load changed credentials. The loader uses the same two names from `config/project-resources.json` and does not print their values.

The optional `application-deepgram-local.yml` import still supports legacy local `.env` files. Process environment values take precedence; keep `.env.example` values empty. The local migration retains Deepgram recovery bytes only under the restricted `.secrets/recovery/` directory.

The local overlay is inactive whenever `prod`, `production`, or `verification` is active, even if `local` or `dev` is also active. `APP_CONFIG_IMPORT` continues to replace the default import list; setting it explicitly can disable local file loading.

## Deployment

Set `SPRING_PROFILES_ACTIVE=prod` and provide `DEEPGRAM_API_KEY` and, optionally, `DEEPGRAM_API_KEY_SECONDARY` through the deployment platform's secret binding. Do not package `.env` or use it as the deployment source of credentials.

For an existing Docker image, inherit already-injected environment variables without putting the value in the command line:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
# DEEPGRAM_API_KEY must already be populated by the CI or secret manager.
docker run --env SPRING_PROFILES_ACTIVE --env DEEPGRAM_API_KEY <existing-image>
```

A Kubernetes deployment can bind the variable from an existing, separately managed Secret:

```yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: prod
  - name: DEEPGRAM_API_KEY
    valueFrom:
      secretKeyRef:
        name: deepgram
        key: api-key
```

`deepgram` is an example Secret name, not a resource created by this change. A Vault or CI integration should populate the same process environment variable through its existing secret-delivery mechanism. Never commit literal credentials, print environment dumps, or pass a new key through a chat prompt.

## Rotation and proof

Revoke the exposed key in the Deepgram console and issue an equivalent replacement. Paste the new value directly into the local editor or deployment secret manager. After rotation, validate the old key with a single `/v1/auth/token` request expecting 401, then validate the replacement through one bounded WebSocket sample. A normal close without a nonblank `Results` transcript does not prove STT success. STT success proves that the request was accepted, not the remaining account balance.

Protocol references: [Deepgram streaming](https://developers.deepgram.com/reference/speech-to-text/listen-streaming), [authentication](https://developers.deepgram.com/guides/fundamentals/authenticating), and [errors](https://developers.deepgram.com/docs/errors).
