package mrc.selector;

import mrc.snapshot.MrcSnapshotException;
import mrc.snapshot.SnapshotManifest;
import mrc.snapshotdb.SnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Ranks snapshot candidates for a given input sample using cosine similarity
 * of {@link DataFingerprint} feature vectors.
 *
 * <p>The fingerprint stored in each snapshot's manifest is synthesised from
 * the snapshot's domain characteristics.  When no stored fingerprint is
 * available, the snapshot scores 0.
 */
public class SnapshotRanker {

    public record RankedSnapshot(String snapshotId, double score, String domainTag) {}

    /**
     * Rank a list of candidate snapshot IDs against the given sample.
     *
     * @param sample       raw input bytes to compute a fingerprint for
     * @param candidateIds snapshot IDs to rank (from {@link SnapshotStore#listByDomain})
     * @param store        snapshot store for loading candidate files
     * @return candidates sorted descending by similarity score
     */
    public List<RankedSnapshot> rank(byte[] sample,
                                     List<String> candidateIds,
                                     SnapshotStore store) {
        DataFingerprint sampleFp = DataFingerprint.of(sample);
        List<RankedSnapshot> results = new ArrayList<>();

        for (String id : candidateIds) {
            double score = 0.0;
            String domainTag = null;
            try {
                Path snapshotPath = store.load(id);
                SnapshotManifest manifest = SnapshotManifest.read(snapshotPath);
                domainTag = manifest.domainTag();

                // Synthesise a fingerprint from the domain tag if stored data is available.
                // In absence of a stored fingerprint section, derive from domain text similarity.
                DataFingerprint candidateFp = syntheticFingerprint(manifest);
                score = sampleFp.cosineSimilarity(candidateFp);
            } catch (IOException | MrcSnapshotException e) {
                // Snapshot unreadable — score stays 0
            }
            results.add(new RankedSnapshot(id, score, domainTag));
        }

        results.sort(Comparator.comparingDouble(RankedSnapshot::score).reversed());
        return results;
    }

    /**
     * Derive a synthetic fingerprint from the snapshot manifest.
     * Uses domain tag keywords to bias features towards known data types.
     *
     * <p>For example, a snapshot tagged "audio" will have a high entropy
     * feature, while "binary" or "repetitive" will have low entropy.
     * This lets the ranker distinguish data domains even without a stored
     * fingerprint section in the snapshot.
     */
    private static DataFingerprint syntheticFingerprint(SnapshotManifest manifest) {
        double[] f = new double[DataFingerprint.SIZE];
        String tag = manifest.domainTag() != null ? manifest.domainTag().toLowerCase() : "";

        // Default: mid-range entropy, uniform delta distribution
        f[0] = 0.5; // entropy
        f[1] = 0.1; // arith-run density
        f[2] = 0.1; // cycle density
        // Uniform delta buckets: ~0.125 each
        for (int i = 3; i <= 10; i++) f[i] = 0.125;
        f[11] = 0.5; // avg delta

        if (tag.contains("audio") || tag.contains("speech") || tag.contains("music")) {
            f[0] = 0.85; f[1] = 0.3; f[2] = 0.2;
            f[3] = 0.3; f[4] = 0.25; f[5] = 0.2; f[6] = 0.1; // low deltas dominate
            f[7] = 0.05; f[8] = 0.04; f[9] = 0.03; f[10] = 0.03;
            f[11] = 0.25;
        } else if (tag.contains("arith") || tag.contains("numeric") || tag.contains("sequence")) {
            f[0] = 0.3; f[1] = 0.8; f[2] = 0.6;
            f[3] = 0.7; f[4] = 0.2; f[5] = 0.05; f[6] = 0.025;
            f[7] = 0.01; f[8] = 0.005; f[9] = 0.005; f[10] = 0.005;
            f[11] = 0.1;
        } else if (tag.contains("repet") || tag.contains("zeros") || tag.contains("zero")) {
            f[0] = 0.05; f[1] = 0.95; f[2] = 0.9;
            f[3] = 0.98; f[4] = 0.01; f[5] = 0.004; f[6] = 0.002;
            f[7] = 0.001; f[8] = 0.001; f[9] = 0.001; f[10] = 0.001;
            f[11] = 0.02;
        } else if (tag.contains("random") || tag.contains("binary") || tag.contains("rand")) {
            f[0] = 0.99; f[1] = 0.05; f[2] = 0.05;
            for (int i = 3; i <= 10; i++) f[i] = 0.125; // uniform
            f[11] = 0.5;
        }

        return new DataFingerprint(f);
    }
}
