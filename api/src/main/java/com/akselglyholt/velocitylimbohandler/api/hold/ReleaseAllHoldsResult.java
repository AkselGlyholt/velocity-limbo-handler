package com.akselglyholt.velocitylimbohandler.api.hold;

/** Result of releasing every hold owned by one controller. */
public record ReleaseAllHoldsResult(Status status, int releasedCount) {
    public ReleaseAllHoldsResult {
        if (status == null) throw new NullPointerException("status");
        if (releasedCount < 0) throw new IllegalArgumentException("releasedCount must be non-negative");
        if (status == Status.NOT_READY && releasedCount != 0) {
            throw new IllegalArgumentException("a not-ready result cannot report released holds");
        }
    }

    /** Creates a successful result, including when no holds existed. */
    public static ReleaseAllHoldsResult completed(int releasedCount) {
        return new ReleaseAllHoldsResult(Status.COMPLETED, releasedCount);
    }

    /** Creates a result indicating that VLH cannot currently process mutations. */
    public static ReleaseAllHoldsResult notReady() {
        return new ReleaseAllHoldsResult(Status.NOT_READY, 0);
    }

    public enum Status {
        /** All owner leases visible to this operation were processed. */
        COMPLETED,
        /** VLH is still starting or is stopping; no leases were released. */
        NOT_READY
    }
}
