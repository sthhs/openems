package io.openems.edge.io.opendtu.inverter;

import org.junit.Test;

import io.openems.edge.common.test.ComponentTest;

public class OpendtuImplTest {

	@Test
	public void test() throws Exception {
		new ComponentTest(new OpendtuImpl()) //
		// .activate(MyConfig.create() //
		// .setId(COMPONENT_ID) //
		// .setInitOutputFromHardware(false) //
		// .build()) //
		;
	}

}
