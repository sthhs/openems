package io.openems.edge.evcs.openwb;

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

import io.openems.common.channel.AccessMode;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.mqtt.api.BridgeMqtt;
import io.openems.edge.bridge.mqtt.api.MqttComponent;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.evcs.api.ChargingType;
import io.openems.edge.evcs.api.Evcs;
import io.openems.edge.evcs.api.Status;
import io.openems.edge.evse.chargepoint.openwb.common.AbstractOpenWbMqttHandler;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.meter.api.PhaseRotation;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Evcs.OpenWB", //
		immediate = true, //
		configurationPolicy = REQUIRE)
@EventTopics({ //
		TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class EvcsOpenWbImpl extends AbstractOpenemsComponent implements EvcsOpenWb, ElectricityMeter, OpenemsComponent,
		Evcs, TimedataProvider, ModbusSlave, MqttComponent, EventHandler {

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Timedata timedata;

	private volatile BridgeMqtt mqttBridge;

	/**
	 * Calculates {@link ElectricityMeter.ChannelId#ACTIVE_PRODUCTION_ENERGY} by
	 * integrating the locally measured {@code ActivePower}. Energy is always tracked
	 * in ACTIVE_PRODUCTION_ENERGY (the channel the FEMS UI/sum reads for the
	 * charger). The absolute {@code imported} counter published by the OpenWB meter
	 * is intentionally NOT passed through: importing it would appear as a one-time
	 * spike in the history on first start and would drop back on restart. The base
	 * value is instead restored from Timedata, so the counter continues where it
	 * left off.
	 */
	private final CalculateEnergyFromPower calculateProductionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);

	private Config config;
	private AbstractOpenWbMqttHandler<EvcsOpenWbImpl> handler;
	private Integer energyStartSession = null;

	// State tracking for status calculation
	private Boolean lastPlugState = null;
	private Boolean lastChargeState = null;

	public EvcsOpenWbImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				Evcs.ChannelId.values(), //
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

		this._setStatus(Status.NOT_READY_FOR_CHARGING);
		this._setChargingType(ChargingType.AC);

		this.handler = new AbstractOpenWbMqttHandler<EvcsOpenWbImpl>(this, this.mqttBridge,
				config.chargePoint().value) {

			@Override
			protected void onPlugState(boolean plugged) {
				EvcsOpenWbImpl.this.lastPlugState = plugged;
				EvcsOpenWbImpl.this.updateStatus();
			}

			@Override
			protected void onChargeState(boolean charging) {
				EvcsOpenWbImpl.this.lastChargeState = charging;
				EvcsOpenWbImpl.this.updateStatus();
			}

			@Override
			protected void onImported(long energyTotalWh) {
				// The absolute 'imported' meter reading is intentionally NOT passed through
				// to the energy channel; consumption energy is integrated from power in
				// handleEvent() (see calculateConsumptionEnergy).
			}

			@Override
			protected void onPhasesInUse(int phases) {
				EvcsOpenWbImpl.this._setPhases(phases);
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

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> this.calculateEnergy();
		}
	}

	/**
	 * Updates the energy channel from the locally measured power (never from the
	 * OpenWB {@code imported} meter reading).
	 */
	private void calculateEnergy() {
		// Energy is always tracked in ACTIVE_PRODUCTION_ENERGY, integrated from power.
		this.calculateProductionEnergy.update(this.getActivePower().get());
		this.updateEnergySession();
	}

	/**
	 * Updates {@link Evcs.ChannelId#ENERGY_SESSION} as the delta of the
	 * (power-integrated) {@code ActiveProductionEnergy} since the session started.
	 */
	private void updateEnergySession() {
		var totalEnergy = this.getActiveProductionEnergy().get();
		if (totalEnergy == null) {
			return;
		}
		// Lazily start a session if charging began before an energy value existed
		// (e.g. the component was activated while already charging).
		if (this.energyStartSession == null && Boolean.TRUE.equals(this.lastChargeState)) {
			this.energyStartSession = totalEnergy.intValue();
		}
		if (this.energyStartSession != null) {
			this._setEnergySession((int) Math.max(0, totalEnergy - this.energyStartSession));
		}
	}

	/**
	 * Updates the EVCS status based on plug and charge states.
	 */
	private void updateStatus() {
		if (this.lastPlugState == null || this.lastChargeState == null) {
			return;
		}

		Status previousStatus = this.getStatus();
		Status newStatus;

		if (this.lastChargeState) {
			newStatus = Status.CHARGING;
			if (previousStatus == Status.NOT_READY_FOR_CHARGING) {
				this.startNewSession();
			}
		} else if (this.lastPlugState) {
			newStatus = Status.READY_FOR_CHARGING;
			if (previousStatus == Status.NOT_READY_FOR_CHARGING) {
				this.startNewSession();
			}
		} else {
			newStatus = Status.NOT_READY_FOR_CHARGING;
		}

		this._setStatus(newStatus);
	}

	/**
	 * Starts a new charging session by recording the current energy total.
	 */
	private void startNewSession() {
		var totalEnergy = this.getActiveProductionEnergy().get();
		if (totalEnergy != null) {
			this.energyStartSession = totalEnergy.intValue();
		}
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActivePower().asString();
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.CONSUMPTION_METERED;
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
				Evcs.getModbusSlaveNatureTable(accessMode) //
		);
	}

	@Override
	public PhaseRotation getPhaseRotation() {
		return this.config.phaseRotation();
	}
}
