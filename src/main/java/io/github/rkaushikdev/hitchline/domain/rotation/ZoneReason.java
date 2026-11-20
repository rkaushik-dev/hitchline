package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.util.WireValue;

/** Why a day is measured on the clock it is: at the site, at home, or travelling either way. */
public enum ZoneReason implements WireValue {
    SITE("site"),
    HOME("home"),
    OUTBOUND("outbound"),
    INBOUND("inbound");

    private final String wire;

    ZoneReason(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }
}
