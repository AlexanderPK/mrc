package mrc.evolution;

/**
 * Monitors the EvolutionMonitor's convergence rate and adaptively adjusts
 * the MutationEngine's mutation probabilities at runtime.
 *
 * <p>Strategy:
 * <ul>
 *   <li><b>Stalling</b> (convergence rate ≈ 0 for {@code stallWindow} generations):
 *       increase mutation to explore new space (up to {@code maxMutationProb}).</li>
 *   <li><b>Improving rapidly</b> (convergence rate {@literal >} threshold for
 *       {@code boostWindow} consecutive generations): lower mutation to exploit
 *       the current trajectory (down to {@code minMutationProb}).</li>
 *   <li><b>Normal</b>: drift back toward the config baseline over time.</li>
 * </ul>
 *
 * <p>Call {@link #tick()} once per generation from the harness loop.
 */
public class AdaptiveController {

    public record Config(
        double minMutationProb,   // floor for rule mutation (default 0.005)
        double maxMutationProb,   // ceiling (default 0.40)
        double stallThreshold,    // convergence rate below this = stalling (default 0.00005)
        double boostThreshold,    // convergence rate above this = improving fast (default 0.005)
        int    stallWindow,       // consecutive stall gens before boosting (default 30)
        int    boostWindow,       // consecutive improvement gens before dampening (default 10)
        double stepUp,            // factor to multiply prob when stalling (default 1.20)
        double stepDown,          // factor to multiply prob when improving (default 0.90)
        double driftFactor        // per-gen drift toward baseline (default 0.995)
    ) {
        public static Config defaults() {
            return new Config(0.005, 0.40, 0.00005, 0.005, 30, 10, 1.20, 0.90, 0.995);
        }
    }

    private final EvolutionMonitor monitor;
    private final MutationEngine mutationEngine;
    private final EvolutionConfig baseConfig;
    private final Config ctrl;

    private double currentRuleProb;
    private double currentChromProb;
    private int stallCount = 0;
    private int boostCount = 0;

    // Event counters for reporting
    private long totalStallBoosts = 0;
    private long totalImproveDamps = 0;

    public AdaptiveController(EvolutionMonitor monitor, MutationEngine mutationEngine,
                               EvolutionConfig baseConfig, Config ctrl) {
        this.monitor = monitor;
        this.mutationEngine = mutationEngine;
        this.baseConfig = baseConfig;
        this.ctrl = ctrl;
        this.currentRuleProb = baseConfig.ruleMutationProb();
        this.currentChromProb = baseConfig.chromosomeMutationProb();
    }

    public AdaptiveController(EvolutionMonitor monitor, MutationEngine mutationEngine,
                               EvolutionConfig baseConfig) {
        this(monitor, mutationEngine, baseConfig, Config.defaults());
    }

    /**
     * Called once per generation. Reads convergence rate and adjusts mutation probs.
     */
    public void tick() {
        double rate = monitor.convergenceRate();

        if (rate < ctrl.stallThreshold()) {
            stallCount++;
            boostCount = 0;
            if (stallCount >= ctrl.stallWindow()) {
                // Boost mutation to escape stall
                currentRuleProb  = Math.min(currentRuleProb  * ctrl.stepUp(), ctrl.maxMutationProb());
                currentChromProb = Math.min(currentChromProb * ctrl.stepUp(), ctrl.maxMutationProb());
                totalStallBoosts++;
                stallCount = 0; // reset so we don't boost every single gen
            }
        } else if (rate > ctrl.boostThreshold()) {
            boostCount++;
            stallCount = 0;
            if (boostCount >= ctrl.boostWindow()) {
                // Dampen mutation to exploit the good trajectory
                currentRuleProb  = Math.max(currentRuleProb  * ctrl.stepDown(), ctrl.minMutationProb());
                currentChromProb = Math.max(currentChromProb * ctrl.stepDown(), ctrl.minMutationProb());
                totalImproveDamps++;
                boostCount = 0;
            }
        } else {
            stallCount = 0;
            boostCount = 0;
        }

        // Gentle drift back toward baseline each generation
        double baseRule  = baseConfig.ruleMutationProb();
        double baseChrom = baseConfig.chromosomeMutationProb();
        currentRuleProb  = currentRuleProb  + (baseRule  - currentRuleProb)  * (1.0 - ctrl.driftFactor());
        currentChromProb = currentChromProb + (baseChrom - currentChromProb) * (1.0 - ctrl.driftFactor());

        mutationEngine.setRuleMutationProb(currentRuleProb);
        mutationEngine.setChromosomeMutationProb(currentChromProb);
    }

    /** Current effective rule mutation probability. */
    public double currentRuleMutationProb() { return currentRuleProb; }

    /** Current effective chromosome mutation probability. */
    public double currentChromosomeMutationProb() { return currentChromProb; }

    /** Total number of times the controller boosted mutation due to stalling. */
    public long totalStallBoosts() { return totalStallBoosts; }

    /** Total number of times the controller dampened mutation due to fast improvement. */
    public long totalImproveDamps() { return totalImproveDamps; }
}
