package me.bechberger.testorder.ops.detection;

import java.util.List;
import java.util.Set;

/** Interface for order-dependent test detection algorithms. */
public interface DetectionAlgorithm {

    String name();

    /** What data this algorithm requires to function. */
    Set<Prerequisite> prerequisites();

    /** Estimated runs needed for the given test count / conflict edge count. */
    int estimatedRuns(int testCount, int conflictEdges);

    /** Execute detection within the given budget, return found OD bugs. */
    List<ODResult> detect(DetectionContext ctx);
}
