package io.openems.edge.evcs.openwb.series2;

import org.junit.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyConfigurationAdmin;

public class EvcsOpenWBSeries2ImplTest {

	@Test
	public void test() throws Exception {
		new ComponentTest(new EvcsOpenWBSeries2Impl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setModbusId("modbus0") //
						.setId("evcs0") //
						.setModbusUnitId(255) //
						.setMaxHwCurrent(16000) //
						.setMinHwCurrent(6000) //
						.build()); //
	}
}
