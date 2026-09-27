# cycle-01 surgical patch for ChatWorkflow.java (evidence-0 release policy)
import io

p = 'main/java/com/example/lms/service/ChatWorkflow.java'
t = io.open(p, encoding='utf-8').read()
old = '''        if (state == EvidenceReleaseState.METADATA_INCOMPLETE) {
            return new FinalVerificationReleaseDecision(
                    "evidence_needed: attribution unavailable / verify retrieval evidence",
                    "HOLD",
                    "evidence_release_metadata_incomplete",
                    false,
                    true,
                    false);
        }
'''
new = '''        if (state == EvidenceReleaseState.METADATA_INCOMPLETE) {
            if (evidenceReleaseRequired) {
                return new FinalVerificationReleaseDecision(
                        "evidence_needed: attribution unavailable / verify retrieval evidence",
                        "HOLD",
                        "evidence_release_metadata_incomplete",
                        false,
                        true,
                        false);
            }
            // 근거 0·인용 메타데이터 불완전만으로 본문을 보류하지 않는다 — 명시적
            // evidence_needed 지시가 있을 때만 HOLD를 유지하고, 미검증 공개 답변은
            // 장기 기억 저장을 차단한다(knowledgeWriteAllowed=false).
            return new FinalVerificationReleaseDecision(
                    base.content(),
                    base.releaseStatus(),
                    "evidence_unverified_release",
                    true,
                    false,
                    false);
        }
'''
n = t.count(old)
assert n == 1, f"expected 1 occurrence, found {n}"
io.open(p, 'w', encoding='utf-8', newline='').write(t.replace(old, new))
print("patched")
