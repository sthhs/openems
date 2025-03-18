package io.openems.edge.evcs.openwb.series2;

import io.openems.common.types.OptionsEnum;

public enum PluggedState implements OptionsEnum {

	UNDEFINED(-1, "Undefined"), //
	NO_VEHICLE_ATTACHED(0, "No vehicle attached"), //
	VEHICLE_ATTACHED(1, "Vehicle attached"), //
	;

	private final int value;
	private final String name;

	private PluggedState(int value, String name) {
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
