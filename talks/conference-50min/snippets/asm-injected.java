// before
public Money total() {
    return items.stream()
        .map(Item::price)
        .reduce(ZERO, Money::add);
}

// after instrumentation (CLASS mode)
public Money total() {
    UsageStore.recordUsageIdFast(4711);   // <-- injected
    return items.stream()
        .map(Item::price)
        .reduce(ZERO, Money::add);
}
