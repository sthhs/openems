package io.openems.edge.evse.chargepoint.openwb;

import static io.openems.edge.common.channel.ChannelUtils.setValue;
import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import io.openems.edge.bridge.mqtt.api.BridgeMqtt;
import io.openems.edge.bridge.mqtt.api.BridgeMqtt.MqttSubscription;
import io.openems.edge.bridge.mqtt.api.MqttComponent;
import io.openems.edge.bridge.mqtt.api.MqttMessage;
import io.openems.edge.bridge.mqtt.api.QoS;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.evse.api.chargepoint.EvseChargePoint;
import io.openems.edge.evse.api.chargepoint.Profile.ChargePointAbilities;
import io.openems.edge.evse.api.chargepoint.Profile.ChargePointActions;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.PhaseRotation;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Evse.ChargePoint.OpenWB", //
		immediate = true, //
		configurationPolicy = REQUIRE)
@EventTopics({ //
		TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class EvseOpenWbImpl extends AbstractOpenemsComponent implements EvseOpenWb, EvseChargePoint, ElectricityMeter,
		OpenemsComponent, TimedataProvider, MqttComponent, EventHandler {

	private final Logger log = LoggerFactory.getLogger(EvseOpenWbImpl.class);

	private static final String TOPIC_PREFIX = "openWB/internal_chargepoint/";

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Timedata timedata;

	private volatile BridgeMqtt mqttBridge;

	/**
	 * Calculates {@link ElectricityMeter.ChannelId#ACTIVE_PRODUCTION_ENERGY} by
	 * integrating the locally measured {@code ActivePower} (the charge point
	 * delivers energy towards the vehicle, tracked as production energy). The base
	 * value is restored from Timedata on start, so the energy counter continues
	 * where it left off instead of importing the absolute OpenWB meter reading
	 * (which would otherwise appear as a one-time spike in the history on first
	 * start, and would drop back on restart).
	 */
	private final CalculateEnergyFromPower calculateProductionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);

	private Config config;
	private String topicPrefix;
	private MqttSubscription subscription;

	// State tracking for the IS_READY_FOR_CHARGING channel
	private Boolean lastPlugState = null;
	private Boolean lastChargeState = null;

	public EvseOpenWbImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				EvseChargePoint.ChannelId.values(), //
				MqttComponent.ChannelId.values() //
		);

		ElectricityMeter.calculateSumActivePowerFromPhases(this);
		ElectricityMeter.calculateSumCurrentFromPhases(this);
		ElectricityMeter.calculateAverageVoltageFromPhases(this);
	}

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(enabled=true)")
	protected void setMqttBridge(BridgeMqtt mqttBridge) {
		this.mqttBridge = mqttBridge;
	}

	@Activate
	protected void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;

		this.topicPrefix = TOPIC_PREFIX + config.chargePoint().value + "/get/";

		if (config.enabled()) {
			this.subscribeToMqtt();
		}
	}

	/**
	 * Subscribes to the OpenWB MQTT topics via the bridge.
	 */
	private void subscribeToMqtt() {
		try {
			var subscribeTopicPattern = this.topicPrefix + "#";
			this.subscription = this.mqttBridge.subscribe(subscribeTopicPattern, QoS.AT_LEAST_ONCE,
					this::handleMqttMessage);
			this.logInfo(this.log, "Subscribed to: " + subscribeTopicPattern);
			this._setMqttCommunicationFailed(false);
		} catch (Exception e) {
			this.logError(this.log, "Failed to subscribe to MQTT topics: " + e.getMessage());
			this._setMqttCommunicationFailed(true);
		}
	}

	/**
	 * Handles incoming MQTT messages from the bridge.
	 *
	 * @param message the received {@link MqttMessage}
	 */
	private void handleMqttMessage(MqttMessage message) {
		var payload = message.payloadAsString();

		try {
			var json = JsonParser.parseString(payload);
			this.handleMessage(message.topic(), json);
			this._setMqttCommunicationFailed(false);
		} catch (Exception e) {
			this.logWarn(this.log, "Failed to parse MQTT message on topic " + message.topic() + ": " + e.getMessage());
		}
	}

	@Deactivate
	protected void deactivate() {
		if (this.subscription != null) {
			try {
				this.subscription.unsubscribe();
			} catch (Exception e) {
				this.logWarn(this.log, "Error unsubscribing from MQTT: " + e.getMessage());
			}
			this.subscription = null;
		}
		super.deactivate();
	}

	@Override
	public void retryMqttCommunication() {
		// Unsubscribe if there's an existing subscription
		if (this.subscription != null) {
			try {
				this.subscription.unsubscribe();
			} catch (Exception e) {
				// Ignore errors during cleanup
			}
			this.subscription = null;
		}
		// Re-subscribe
		this.subscribeToMqtt();
	}

	/**
	 * Handles an incoming MQTT message.
	 *
	 * @param topic the MQTT topic
	 * @param json  the parsed JSON payload
	 */
	private void handleMessage(String topic, JsonElement json) {
		// Extract the subtopic (e.g., "voltages", "currents", etc.)
		var subtopic = topic.replace(this.topicPrefix, "");

		switch (subtopic) {
		case "voltages" -> this.handleVoltages(json);
		case "currents" -> this.handleCurrents(json);
		case "power" -> this.handlePower(json);
		case "powers" -> this.handlePowers(json);
		case "imported" -> this.handleImported(json);
		case "plug_state" -> this.handlePlugState(json);
		case "charge_state" -> this.handleChargeState(json);
		case "phases_in_use" -> this.handlePhasesInUse(json);
		case "fault_state" -> this.handleFaultState(json);
		default -> {
			// Ignore other topics
		}
		}
	}

	private void handleVoltages(JsonElement json) {
		if (json.isJsonArray()) {
			var arr = json.getAsJsonArray();
			this._setVoltageL1(Math.round(arr.get(0).getAsFloat() * 1000)); // V -> mV
			this._setVoltageL2(Math.round(arr.get(1).getAsFloat() * 1000));
			this._setVoltageL3(Math.round(arr.get(2).getAsFloat() * 1000));
		}
	}

	private void handleCurrents(JsonElement json) {
		if (json.isJsonArray()) {
			var arr = json.getAsJsonArray();
			this._setCurrentL1(Math.round(arr.get(0).getAsFloat() * 1000)); // A -> mA
			this._setCurrentL2(Math.round(arr.get(1).getAsFloat() * 1000));
			this._setCurrentL3(Math.round(arr.get(2).getAsFloat() * 1000));
		}
	}

	private void handlePower(JsonElement json) {
		this._setActivePower(Math.round(json.getAsFloat()));
	}

	private void handlePowers(JsonElement json) {
		if (json.isJsonArray()) {
			var arr = json.getAsJsonArray();
			this._setActivePowerL1(Math.round(arr.get(0).getAsFloat()));
			this._setActivePowerL2(Math.round(arr.get(1).getAsFloat()));
			this._setActivePowerL3(Math.round(arr.get(2).getAsFloat()));
		}
	}

	private void handleImported(JsonElement json) {
		// The 'imported' topic carries the charge point's absolute lifetime energy
		// counter. It is intentionally NOT written to ActiveProductionEnergy: that
		// channel is integrated from ActivePower in handleEvent() (see
		// calculateProductionEnergy), so the device's pre-existing total is not
		// imported into the history as a one-time spike and does not reset on restart.
	}

	private void handlePlugState(JsonElement json) {
		this.lastPlugState = json.getAsBoolean();
		this.updateIsReadyForCharging();
	}

	private void handleChargeState(JsonElement json) {
		this.lastChargeState = json.getAsBoolean();
		this.updateIsReadyForCharging();
	}

	private void handlePhasesInUse(JsonElement json) {
		// Not modeled by EvseChargePoint; ignored.
	}

	private void handleFaultState(JsonElement json) {
		var faultState = json.getAsInt();
		// 0: No error, 1: Warning, 2: Error
		if (faultState >= 2) {
			this._setMqttCommunicationFailed(true);
		}
	}

	/**
	 * Updates {@link EvseChargePoint.ChannelId#IS_READY_FOR_CHARGING} from the last
	 * received plug- and charge-state.
	 */
	private void updateIsReadyForCharging() {
		if (this.lastPlugState == null || this.lastChargeState == null) {
			return;
		}
		var isReady = this.lastPlugState && this.lastChargeState;
		setValue(this, EvseChargePoint.ChannelId.IS_READY_FOR_CHARGING, isReady);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link MqttComponent.ChannelId#MQTT_COMMUNICATION_FAILED} Channel.
	 *
	 * @param value the next value
	 */
	private void _setMqttCommunicationFailed(boolean value) {
		this.channel(MqttComponent.ChannelId.MQTT_COMMUNICATION_FAILED).setNextValue(value);
	}

	@Override
	public ChargePointAbilities getChargePointAbilities() {
		// The OpenWB internal chargepoint currently only publishes '.../get/' topics,
		// so this ChargePoint can never apply a set-point or a phase switch.
		return ChargePointAbilities.create() //
				.setIsEvConnected(Boolean.TRUE.equals(this.lastPlugState)) //
				.setIsReadyForCharging(this.getIsReadyForCharging()) //
				.build();
	}

	@Override
	public void apply(ChargePointActions actions) {
		// No-op: read only, see getChargePointAbilities().
	}

	@Override
	public boolean isReadOnly() {
		return this.config.readOnly();
	}

	@Override
	public PhaseRotation getPhaseRotation() {
		return this.config.phaseRotation();
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> //
			this.calculateProductionEnergy.update(this.getActivePower().get());
		}
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}
}
