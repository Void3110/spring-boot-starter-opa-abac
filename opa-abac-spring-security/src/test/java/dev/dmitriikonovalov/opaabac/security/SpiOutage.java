package dev.dmitriikonovalov.opaabac.security;

import dev.dmitriikonovalov.opaabac.core.DecisionIndeterminateException;

/**
 * An adopter SPI's own outage signal, opted into the "could not decide" family by subclassing (ADR 0037
 * §2) — what a resource resolver or an ancestor-chain supplier throws when its backing store is down and it
 * wants the decision to be indeterminate rather than degraded.
 */
final class SpiOutage extends DecisionIndeterminateException {

    SpiOutage(String message) {
        super(message);
    }
}
