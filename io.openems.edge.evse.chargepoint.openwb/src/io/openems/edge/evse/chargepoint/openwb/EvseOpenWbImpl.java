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

import io.openems.edge.bridge.mqtt.api.BridgeMqtt;
import io.openems.edge.bridge.mqtt.api.MqttComponent;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.evse.api.chargepoint.EvseChargePoint;
import io.openems.edge.evse.api.chargepoint.Profile.ChargePointAbilities;
import io.openems.edge.evse.api.chargepoint.Profile.ChargePointActions;
import io.openems.edge.evse.chargepoint.openwb.common.AbstractOpenWbMqttHandler;
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
	private AbstractOpenWbMqttHandler<EvseOpenWbImpl> handler;

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

		this.handler = new AbstractOpenWbMqttHandler<EvseOpenWbImpl>(this, this.mqttBridge,
				config.chargePoint().value) {

			@Override
			protected void onPlugState(boolean plugged) {
				EvseOpenWbImpl.this.lastPlugState = plugged;
				EvseOpenWbImpl.this.updateIsReadyForCharging();
			}

			@Override
			protected void onChargeState(boolean charging) {
				EvseOpenWbImpl.this.lastChargeState = charging;
				EvseOpenWbImpl.this.updateIsReadyForCharging();
			}

			@Override
			protected void onImported(long energyTotalWh) {
				// The 'imported' topic carries the charge point's absolute lifetime energy
				// counter. It is intentionally NOT written to ActiveProductionEnergy: that
				// channel is integrated from ActivePower in handleEvent() (see
				// calculateProductionEnergy), so the device's pre-existing total is not
				// imported into the history as a one-time spike and does not reset on restart.
			}

			@Override
			protected void onPhasesInUse(int phases) {
				// Not modeled by EvseChargePoint; ignored.
			}
		};

		if (config.enabled()) {
			this.handler.subscribe();
		}
	}

	@Deactivate
	protected void deactivate() {
		if (this.handler != null) {
			this.handler.unsubscribe();
		}
		super.deactivate();
	}

	@Override
	public void retryMqttCommunication() {
		if (this.handler != null) {
			this.handler.retry();
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
		setValue(this, EvseChargePoint.ChannelId.IS_READY_FOR_CHARGING, this.lastPlugState && this.lastChargeState);
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
		// OpenWB is always read-only from OpenEMS: the internal chargepoint only
		// publishes '.../get/' topics and accepts no set-point.
		return true;
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
