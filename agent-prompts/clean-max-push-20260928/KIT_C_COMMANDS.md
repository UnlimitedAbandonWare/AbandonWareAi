# Kit C — F08, F09, F07, F10

Activate when `CURRENT_KIT.md` is `C`. One item per diff.
Proposed test names come from the maiaswsn v2 directive. They are not live
classes until Codex adds them.

## F08 — do not load every message for settings restore

Settings restore must not call `getSessionWithMessages` and then read the whole
message list. Keep the recent-window loader used for model context, and keep
the full export path. If a metadata-only read already exists, use it. Do not
add a second loader. The three controller restore sites are the only call
sites to switch. A golden check is: request settings, then saved settings,
then the existing default, including a null session.

Proposed tests: `SettingsRestoreDoesNotSelectMessageHistoryTest`,
`SessionMetaResolutionIsUnchangedTest`, `RestartRestoresSessionSettingsTest`,
`RecentContextAndRollingSummaryRemainAvailableTest`,
`ForeignSessionCannotExposeMetadataTest`, `FullExportStillContainsAllMessagesTest`.

Original files: `ChatHistoryServiceImpl.java`, `ChatApiController.java`.

## F09 — lazy diagnostic HTML

On the ordinary answer path, do not call `buildSplitPanel` before the public
text is sent. Render only when debug or exposeTrace is already the gate.
Keep the existing lazy HTML endpoint that builds from an approved pointer.
Do not add an admin endpoint. Structured diagnostics required for release and
recovery stay. HTML null must not drop those fields.

Proposed tests: `NormalChatDoesNotEagerlyRenderTraceHtmlTest`,
`AuthorizedTraceStillRendersFromDurableProjectionTest`,
`UnauthorizedTraceRemainsDeniedTest`,
`RequiredReleaseAndRecoveryTraceSurvivesLazyHtmlTest`.

## F07 — DPP incremental, same order

`DppDiversityReranker` keeps exact greedy order and the current similarity
formula (character 3-gram overlap over sqrt of the product of set sizes).
Do not switch that formula to Jaccard or to embedding cosine.

The bundle verification
`C:\Users\nninn\Downloads\maiaswsn_performance_verification_v2_2026-09-28.md`
ran an independent JDK probe (`probes/run.sh`, 135+400 comparisons, exit 0).
That probe is not application GREEN. It did not run Spring, this Gradle build,
or a Java 17 runtime. Recorded medians from that file (60/10, 100/16, 160/20)
are probe timings, not an app promise.

Live connection, for Codex:

1. Leave the ZIP probe outside `main/java`.
2. Add one focused test beside `DppDiversityReranker` that freezes ordered
   document identity from the current method, then compares the incremental
   method to that order.
3. On mismatch, return to the original method. Do not widen a tie tolerance.
4. `.\gradlew.bat test --tests <that class>` is the app check. Probe exit 0
   does not replace it.

## F10 — requested-model cache bound

`PolicyBasedModelRouter` `requestedCache` needs a bound and one build per cold
key. The effect is the non-exact branch. Do not describe it as a change to
every manual model selection. Do not invent a new property name. Initial
measurement values in the directive (size 256, 30 minutes) stay inside the
existing settings boundary when Codex adopts them.

Proposed tests: `ConcurrentRequestedCacheMissBuildsOnceTest`,
`RequestedCacheBoundedAfterHighCardinalityTrafficTest`,
`ConfigRevisionInvalidatesOldRequestedClientTest`,
`ExactRequestedModelSelectionUnchangedTest`.

## glm counter-prompt

After the first Codex patch, use `GLM_COUNTERPROMPT.md`. Agreement from glm is
not GREEN. The focused test exit code is the result.
