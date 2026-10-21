package io.openems.edge.evse.chargepoint.openwb.common;

import static io.openems.edge.common.channel.ChannelUtils.setValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import io.openems.edge.bridge.mqtt.api.BridgeMqtt;
import io.openems.edge.bridge.mqtt.api.BridgeMqtt.MqttSubscription;
import io.openems.edge.bridge.mqtt.api.MqttComponent;
import io.openems.edge.bridge.mqtt.api.MqttMessage;
import io.openems.edge.bridge.mqtt.api.QoS;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * Shared MQTT plumbing for the OpenWB Series2 internal chargepoint, used by both
 * the EVCS and the EVSE Charge-Point OpenWB components.
 *
 * <p>
 * Subscribes to {@code openWB/internal_chargepoint/<cp>/get/#}, parses the
 * common metering topics ({@code voltages}, {@code currents}, {@code power},
 * {@code powers}) into the {@link ElectricityMeter} channels and maintains the
 * {@link MqttComponent.ChannelId#MQTT_COMMUNICATION_FAILED} channel. The
 * component-specific topics ({@code plug_state}, {@code charge_state},
 * {@code imported}, {@code phases_in_use}) are forwarded to abstract hooks.
 *
 * @param <PARENT> the owning component
 */
public abstract class AbstractOpenWbMqttHandler<PARENT extends OpenemsComponent & ElectricityMeter & MqttComponent> {

	private static final String TOPIC_PREFIX = "openWB/internal_chargepoint/";

	private final Logger log = LoggerFactory.getLogger(AbstractOpenWbMqttHandler.class);

	protected final PARENT parent;

	private final BridgeMqtt mqttBridge;
	private final String topicPrefix;
	private MqttSubscription subscription;

	protected AbstractOpenWbMqttHandler(PARENT parent, BridgeMqtt mqttBridge, int chargePoint) {
		this.parent = parent;
		this.mqttBridge = mqttBridge;
		this.topicPrefix = TOPIC_PREFIX + chargePoint + "/get/";
	}

	/**
	 * Subscribes to the OpenWB MQTT topics via the bridge.
	 */
	public final void subscribe() {
		try {
			var pattern = this.topicPrefix + "#";
			this.subscription = this.mqttBridge.subscribe(pattern, QoS.AT_LEAST_ONCE, this::handleMqttMessage);
			this.log.info("[{}] Subscribed to: {}", this.parent.id(), pattern);
			this.setCommunicationFailed(false);
		} catch (Exception e) {
			this.log.error("[{}] Failed to subscribe to MQTT topics: {}", this.parent.id(), e.getMessage());
			this.setCommunicationFailed(true);
		}
	}

	/**
	 * Unsubscribes from the OpenWB MQTT topics. Called on deactivate.
	 */
	public final void unsubscribe() {
		if (this.subscription != null) {
			try {
				this.subscription.unsubscribe();
			} catch (Exception e) {
				this.log.warn("[{}] Error unsubscribing from MQTT: {}", this.parent.id(), e.getMessage());
			}
			this.subscription = null;
		}
	}

	/**
	 * Re-subscribes, used by {@link MqttComponent#retryMqttCommunication()}.
	 */
	public final void retry() {
		this.unsubscribe();
		this.subscribe();
	}

	private void handleMqttMessage(MqttMessage message) {
		try {
			var json = JsonParser.parseString(message.payloadAsString());
			this.handleMessage(message.topic(), json);
			this.setCommunicationFailed(false);
		} catch (Exception e) {
			this.log.warn("[{}] Failed to parse MQTT message on topic {}: {}", this.parent.id(), message.topic(),
					e.getMessage());
		}
	}

	private void handleMessage(String topic, JsonElement json) {
		var subtopic = topic.replace(this.topicPrefix, "");
		switch (subtopic) {
		case "voltages" -> this.handleVoltages(json);
		case "currents" -> this.handleCurrents(json);
		case "power" -> this.parent._setActivePower(Math.round(json.getAsFloat()));
		case "powers" -> this.handlePowers(json);
		case "imported" -> this.onImported(Math.round(json.getAsFloat()));
		case "plug_state" -> this.onPlugState(json.getAsBoolean());
		case "charge_state" -> this.onChargeState(json.getAsBoolean());
		case "phases_in_use" -> this.onPhasesInUse(json.getAsInt());
		case "fault_state" -> this.handleFaultState(json);
		default -> {
			// Ignore other topics
		}
		}
	}

	private void handleVoltages(JsonElement json) {
		if (json.isJsonArray()) {
			var arr = json.getAsJsonArray();
			this.parent._setVoltageL1(Math.round(arr.get(0).getAsFloat() * 1000)); // V -> mV
			this.parent._setVoltageL2(Math.round(arr.get(1).getAsFloat() * 1000));
			this.parent._setVoltageL3(Math.round(arr.get(2).getAsFloat() * 1000));
		}
	}

	private void handleCurrents(JsonElement json) {
		if (json.isJsonArray()) {
			var arr = json.getAsJsonArray();
			this.parent._setCurrentL1(Math.round(arr.get(0).getAsFloat() * 1000)); // A -> mA
			this.parent._setCurrentL2(Math.round(arr.get(1).getAsFloat() * 1000));
			this.parent._setCurrentL3(Math.round(arr.get(2).getAsFloat() * 1000));
		}
	}

	private void handlePowers(JsonElement json) {
		if (json.isJsonArray()) {
			var arr = json.getAsJsonArray();
			this.parent._setActivePowerL1(Math.round(arr.get(0).getAsFloat()));
			this.parent._setActivePowerL2(Math.round(arr.get(1).getAsFloat()));
			this.parent._setActivePowerL3(Math.round(arr.get(2).getAsFloat()));
		}
	}

	private void handleFaultState(JsonElement json) {
		// 0: No error, 1: Warning, 2: Error
		if (json.getAsInt() >= 2) {
			this.setCommunicationFailed(true);
		}
	}

	private void setCommunicationFailed(boolean value) {
		setValue(this.parent, MqttComponent.ChannelId.MQTT_COMMUNICATION_FAILED, value);
	}

	/**
	 * The vehicle plug-state ({@code plug_state}) was received.
	 *
	 * @param plugged whether a vehicle is plugged in
	 */
	protected abstract void onPlugState(boolean plugged);

	/**
	 * The charge-state ({@code charge_state}) was received.
	 *
	 * @param charging whether the vehicle is currently charging
	 */
	protected abstract void onChargeState(boolean charging);

	/**
	 * The absolute lifetime import-energy counter ({@code imported}) was received.
	 *
	 * @param energyTotalWh the counter value in [Wh]
	 */
	protected abstract void onImported(long energyTotalWh);

	/**
	 * The number of phases in use ({@code phases_in_use}) was received.
	 *
	 * @param phases the number of phases
	 */
	protected abstract void onPhasesInUse(int phases);
}
