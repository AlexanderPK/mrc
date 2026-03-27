package mrc;

import mrc.bench.RandomBaselineSuite;

/**
 * Entry point for running the MRC Phase 1 baseline test suite.
 */
public class Main {
    public static void main(String[] args) {
        RandomBaselineSuite.runAll(System.out);
    }
}
