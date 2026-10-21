package io.openems.edge.evse.chargepoint.openwb.common;

/**
 * The internal OpenWB chargepoint, identified by its {@code duo_num}. Shared by
 * both the EVCS and the EVSE Charge-Point OpenWB components.
 */
public enum ChargePoint {
	CP0(0), //
	CP1(1);

	public final int value;

	private ChargePoint(int value) {
		this.value = value;
	}
}
