package me.bechberger.testorder.ops.detection;

/** Classification of an order-dependent test. */
public enum ODType {
    /** Test fails when a polluter runs before it (default passing order has polluter after). */
    VICTIM,
    /** Test fails when its required state-setter is removed or runs after it. */
    BRITTLE
}
