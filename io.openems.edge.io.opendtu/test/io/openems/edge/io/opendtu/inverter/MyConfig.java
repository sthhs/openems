package io.openems.edge.io.opendtu.inverter;

import io.openems.common.test.AbstractComponentConfig;
import io.openems.common.types.MeterType;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	protected static class Builder {
		private String id = null;
		private boolean initOutputFromHardware;

		private Builder() {
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setInitOutputFromHardware(boolean initOutputFromHardware) {
			this.initOutputFromHardware = initOutputFromHardware;
			return this;
		}

		public MyConfig build() {
			return new MyConfig(this);
		}
	}

	/**
	 * Create a Config builder.
	 *
	 * @return a {@link Builder}
	 */
	public static Builder create() {
		return new Builder();
	}

	private final Builder builder;

	private MyConfig(Builder builder) {
		super(Config.class, builder.id);
		this.builder = builder;
	}

	
	@Override
	public String username() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String password() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String serialNumberL1() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String serialNumberL2() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String serialNumberL3() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String ip() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int relativeLimit() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int absoluteLimit() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int threshold() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int delay() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public MeterType type() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean debugMode() {
		// TODO Auto-generated method stub
		return false;
	}
}