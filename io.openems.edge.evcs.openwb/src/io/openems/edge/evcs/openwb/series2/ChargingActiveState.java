package io.openems.edge.evcs.openwb.series2;

import io.openems.common.types.OptionsEnum;

public enum ChargingActiveState implements OptionsEnum {

	UNDEFINED(-1, "Undefined"), //
	NOT_CHARGING(0, "No cable attached"), //
	CHARGING(1, "Cable attached, no car attached"), //
;
	
	private final int value;
	private final String name;

	private ChargingActiveState(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}
}
