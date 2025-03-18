package io.openems.edge.evcs.openwb.series2;

import static io.openems.edge.evcs.api.Evcs.addCalculatePowerLimitListeners;
import static io.openems.edge.evcs.api.Evcs.calculateUsedPhasesFromCurrent;
import static io.openems.edge.meter.api.ElectricityMeter.calculateSumCurrentFromPhases;

import java.util.function.Consumer;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.evcs.api.ChargeStateHandler;
import io.openems.edge.evcs.api.ChargingType;
import io.openems.edge.evcs.api.Evcs;
import io.openems.edge.evcs.api.EvcsPower;
import io.openems.edge.evcs.api.ManagedEvcs;
import io.openems.edge.evcs.api.PhaseRotation;
import io.openems.edge.evcs.api.Phases;
import io.openems.edge.evcs.api.Status;
import io.openems.edge.evcs.api.WriteHandler;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Evcs.OpenWB.Series2", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE //
})
public class EvcsOpenWBSeries2Impl extends AbstractOpenemsModbusComponent implements EvcsOpenWBSeries2, Evcs,
		ManagedEvcs, ModbusComponent, OpenemsComponent, EventHandler, ElectricityMeter, TimedataProvider {

	private final Logger log = LoggerFactory.getLogger(EvcsOpenWBSeries2Impl.class);
	private final ChargeStateHandler chargeStateHandler = new ChargeStateHandler(this);
	private final WriteHandler writeHandler = new WriteHandler(this);

	private final CalculateEnergyFromPower calculateEnergyL1 = new CalculateEnergyFromPower(this, ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY_L1);
	private final CalculateEnergyFromPower calculateEnergyL2 = new CalculateEnergyFromPower(this, ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY_L1);
	private final CalculateEnergyFromPower calculateEnergyL3 = new CalculateEnergyFromPower(this, ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY_L1);

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	@Reference
	private EvcsPower evcsPower;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config = null;

	public EvcsOpenWBSeries2Impl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				Evcs.ChannelId.values(), //
				ManagedEvcs.ChannelId.values(), //
				EvcsOpenWBSeries2.ChannelId.values()//
		);
		calculateUsedPhasesFromCurrent(this);
		calculateSumCurrentFromPhases(this);
		/*
		 * Calculates the maximum and minimum hardware power dynamically by listening on
		 * the fixed hardware limits used for charging
		 */
		addCalculatePowerLimitListeners(this);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this.applyConfig(config);
	}

	@Modified
	private void modified(ComponentContext context, Config config) throws OpenemsNamedException {
		if (super.modified(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this.applyConfig(config);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		final var modbusProtocol = new ModbusProtocol(this,
				new FC3ReadRegistersTask(10114, Priority.HIGH,
						m(EvcsOpenWBSeries2.ChannelId.PLUGGED_STATE, new SignedWordElement(10114)),
						m(EvcsOpenWBSeries2.ChannelId.CHARGING_ACTIVE, new SignedWordElement(10115)),
						m(EvcsOpenWBSeries2.ChannelId.ACTUAL_CURRENT_CONFIGURED, new SignedWordElement(10116))),

			

				new FC3ReadRegistersTask(10107, Priority.LOW,
						m(ElectricityMeter.ChannelId.CURRENT_L1, new SignedWordElement(10107)),

						m(ElectricityMeter.ChannelId.CURRENT_L2, new SignedWordElement(10108)),

						m(ElectricityMeter.ChannelId.CURRENT_L3, new SignedWordElement(10109))),

				new FC3ReadRegistersTask(10100, Priority.HIGH,
						m(ElectricityMeter.ChannelId.ACTIVE_POWER, new SignedDoublewordElement(10100)),

						m(Evcs.ChannelId.ENERGY_SESSION, new SignedDoublewordElement(10102)), //

						//m(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY, new SignedDoublewordElement(10102)),
						// TODO whats the difference between 1020 and 1022?
						new DummyRegisterElement(10104, 10129),

						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, new SignedWordElement(10130)),

						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, new SignedWordElement(10131)),

						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, new SignedWordElement(10132))),


				new FC3ReadRegistersTask(10141, Priority.HIGH,
						m(EvcsOpenWBSeries2.ChannelId.CHARGE_ENERGY_SESSION, new SignedDoublewordElement(10141))),

				new FC16WriteRegistersTask(10171,
						m(EvcsOpenWBSeries2.ChannelId.APPLY_CURRENT_LIMIT, new SignedWordElement(10171)))

/*
				new FC3ReadRegistersTask(6000, Priority.LOW,
						m(EvcsOpenWBSeries2.ChannelId.LIFE_BIT, new SignedWordElement(6000))),

				new FC6WriteRegisterTask(6000,
						m(EvcsOpenWBSeries2.ChannelId.LIFE_BIT, new SignedWordElement(6000)))
						*/
						);

		this.addStatusCallback();
		this.addPowerConsumptionCallback();
		return modbusProtocol;
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.MANAGED_CONSUMPTION_METERED;
	}

	@Override
	public PhaseRotation getPhaseRotation() {
		// TODO implement handling for rotated Phases
		return PhaseRotation.L1_L2_L3;
	}

	@Override
	public String debugLog() {
		return "Status: " + getStatus().getName() + " | " + "Charging Power: " + this.channel(EvcsOpenWBSeries2.ChannelId.ACTUAL_CURRENT_CONFIGURED).value().asString();
//		return "Status: " + getStatus().getName() + " | " + "Charging Power: " + getChargePowerTotal();
	}

	@Override
	public void logDebug(String message) {
		if (this.config.debugMode()) {
			this.logInfo(this.log, message);
		}
	}

	@Override
	public EvcsPower getEvcsPower() {
		return this.evcsPower;
	}

	@Override
	public int getConfiguredMinimumHardwarePower() {
		return this.currentToPower(this.config.minHwCurrent());
	}

	@Override
	public int getConfiguredMaximumHardwarePower() {
		return this.currentToPower(this.config.maxHwCurrent());
	}

	@Override
	public boolean getConfiguredDebugMode() {
		return this.config.debugMode();
	}

	/*
	 * 	@Override

	 
		setApplyChargePowerLimit(power);
		return true;
	}
	*/
	
	@Override
	public boolean applyChargePowerLimit(int power) throws Exception {
		if (this.isReadOnly()) {
			return false;
		}
		var phases = this.getPhasesAsInt();
		var current = Math.round(power / phases / Evcs.DEFAULT_VOLTAGE);

		/*
		 * Limits the charging value because Mennekes knows only values between 6 and 32
		 * A
		 */
		current = Math.min(current, Evcs.DEFAULT_MAXIMUM_HARDWARE_CURRENT / 1000);

		if (current < Evcs.DEFAULT_MINIMUM_HARDWARE_CURRENT / 1000) {
			current = 0;
		}

		this.setApplyCurrentLimit(current);
		return true;
	}


	@Override
	public boolean pauseChargeProcess() throws Exception {
		//this.applyChargePowerLimit(0);
		this.setApplyCurrentLimit(0);
		return true;
	}

	@Override
	public boolean applyDisplayText(String text) throws OpenemsException {
		return false;
	}

	@Override
	public int getMinimumTimeTillChargingLimitTaken() {
		return 30;
	}

	@Override
	public ChargeStateHandler getChargeStateHandler() {
		return this.chargeStateHandler;
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE:
			this.writeHandler.run();
			break;
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:
			this.calculateEnergyL1.update(this.getActivePowerL1Channel().getNextValue().get());
			this.calculateEnergyL2.update(this.getActivePowerL2Channel().getNextValue().get());
			this.calculateEnergyL3.update(this.getActivePowerL3Channel().getNextValue().get());
			break;

		}
	}

	private void applyConfig(Config config) {
		this.config = config;
		this._setChargingType(ChargingType.AC);
		this._setPhases(Phases.THREE_PHASE.getValue());
		this._setFixedMinimumHardwarePower(this.currentToPower(config.minHwCurrent()));
		this._setFixedMaximumHardwarePower(this.currentToPower(config.maxHwCurrent()));
		this._setPowerPrecision(DEFAULT_POWER_RECISION); // 1A steps
	}

	private Integer currentToPower(Integer current) {
		return Math.round(current / 1000f) * DEFAULT_VOLTAGE * getPhasesAsInt();
	}

	private void addStatusCallback() {
		this.channel(EvcsOpenWBSeries2.ChannelId.CHARGING_ACTIVE).onSetNextValue(s -> {

			PluggedState plugged = this.channel(EvcsOpenWBSeries2.ChannelId.PLUGGED_STATE).value().asEnum();
			
			ChargingActiveState state = s.asEnum();

			/**
			 * Maps the EVCS state to a {@link Status}.
			 */
			switch (state) {
			case NOT_CHARGING -> this._setStatus((plugged == PluggedState.VEHICLE_ATTACHED ) ?  Status.READY_FOR_CHARGING : Status.NOT_READY_FOR_CHARGING);
			case CHARGING -> this._setStatus(Status.CHARGING);//, CHARGING_STATION_RESERVED, CHARGING_PAUSED
			default -> this._setStatus(Status.UNDEFINED);
			}

			this._setModbusCommunicationFailed(!this.getLifeBit().isDefined());
		});
		
		this.channel(EvcsOpenWBSeries2.ChannelId.PLUGGED_STATE).onSetNextValue(s -> {
			ChargingActiveState state = this.channel(EvcsOpenWBSeries2.ChannelId.CHARGING_ACTIVE).value().asEnum();

			PluggedState plugged = s.asEnum();

			/**
			 * Maps the EVCS state to a {@link Status}.
			 */
			switch (plugged) {
			case NO_VEHICLE_ATTACHED -> this._setStatus(Status.NOT_READY_FOR_CHARGING);
			
			case VEHICLE_ATTACHED -> this._setStatus((state == ChargingActiveState.CHARGING) ? Status.CHARGING : Status.READY_FOR_CHARGING);//, CHARGING_STATION_RESERVED, CHARGING_PAUSED
			default -> this._setStatus(Status.UNDEFINED);
			}

			this._setModbusCommunicationFailed(!this.getLifeBit().isDefined());
		});

	}

	private void addPowerConsumptionCallback() {
		final Consumer<Value<Integer>> setEnergyConsumptionCallback = ignore -> {
			this._setEnergySession(getChargeEnergySession().get());
		};

		this.getChargeEnergySessionChannel().onUpdate(setEnergyConsumptionCallback);
	}

	@Override
	public Timedata getTimedata() {
		// TODO Auto-generated method stub
		return this.timedata;
	}
}
