package io.openems.edge.evcs.openwb;

public enum ChargeMode {
	instant("instant"), //
	pv("pv"),
	eco("eco"),
	stop("stop"),
	target("target");

	public final String value;

	private ChargeMode(String value) {
		this.value = value;
	}
}
