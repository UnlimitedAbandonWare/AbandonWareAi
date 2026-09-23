package ai.abandonware.nova.boot.exec.zombie;

public interface ZombieBreederDetector {
    ContaminationVerdict assess(String ownerHint, int currentInflight);

    void recordVerdict(ContaminationVerdict verdict, String ownerHint);
}
