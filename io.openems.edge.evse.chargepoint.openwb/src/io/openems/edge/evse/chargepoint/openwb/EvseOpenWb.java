package io.openems.edge.evse.chargepoint.openwb;

import io.openems.edge.bridge.mqtt.api.MqttComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.evse.api.chargepoint.EvseChargePoint;

public interface EvseOpenWb extends EvseChargePoint, OpenemsComponent, MqttComponent {

}
