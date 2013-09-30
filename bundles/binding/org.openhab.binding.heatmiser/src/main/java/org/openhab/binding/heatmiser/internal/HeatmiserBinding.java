/**
 * openHAB, the open Home Automation Bus.
 * Copyright (C) 2010-2013, openHAB.org <admin@openhab.org>
 *
 * See the contributors.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 * Additional permission under GNU GPL version 3 section 7
 *
 * If you modify this Program, or any covered work, by linking or
 * combining it with Eclipse (or a modified version of that library),
 * containing parts covered by the terms of the Eclipse Public License
 * (EPL), the licensors of this Program grant you additional permission
 * to convey the resulting work.
 */
package org.openhab.binding.heatmiser.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.EventObject;
import java.util.Iterator;
import java.util.List;

import org.openhab.binding.heatmiser.HeatmiserBindingProvider;
import org.openhab.binding.heatmiser.internal.thermostat.HeatmiserPRT;
import org.openhab.binding.heatmiser.internal.thermostat.HeatmiserPRTHW;
import org.openhab.binding.heatmiser.internal.thermostat.HeatmiserThermostat;
import org.openhab.binding.heatmiser.internal.thermostat.HeatmiserThermostat.Functions;
import org.apache.commons.lang.StringUtils;
import org.openhab.core.binding.AbstractActiveBinding;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

	

/**
 * This class implements the Heatmiser binding. It actively polls all thermostats and sets the item values.
 * 
 * The pollingTable is created from the item bindings, and a separate thermostat array is maintained from
 * the responses. The two are separated to allow the system to determine the thermostat type based on the response
 * rather than requiring this additional information in the binding string.
 * 
 *  The pollingTable is recreated after each complete poll cycle to allow for new bindings
 * 
 * @author Chris Jackson
 * @since 1.3.0
 */
public class HeatmiserBinding extends AbstractActiveBinding<HeatmiserBindingProvider> implements ManagedService {

	private static final Logger logger = 
		LoggerFactory.getLogger(HeatmiserBinding.class);

	private String ipAddress;
	private int ipPort;
	
	// Polling and receiving are separated so that we can automatically detect the type of thermostat via the receive packet.
	private Iterator<Integer> pollIterator = null;
	private List<Integer> pollingTable = new ArrayList<Integer>();
	private List<HeatmiserThermostat> thermostatTable = new ArrayList<HeatmiserThermostat>();
	
	private MessageListener eventListener = new MessageListener();
	private HeatmiserConnector connector = null;


	/** 
	 * the refresh interval which is used to poll values from the Heatmiser
	 * system (optional, defaults to 5000ms)
	 */
	private long refreshInterval = 5000;

	public HeatmiserBinding() {
	}
	
	public void activate() {
		logger.debug("Heatmiser binding activated");
		super.activate();
	}
	
	public void deactivate() {
		logger.debug("Heatmiser binding deactivated");

		stopListening();
	}

	private void listen() {
		stopListening();

		connector = new HeatmiserConnector();
		if (connector != null) {
			// Initialise the IP connection
			connector.addEventListener(eventListener);
			try {
				connector.connect(ipAddress, ipPort);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void stopListening() {
		if(connector != null) {
			connector.disconnect();
			connector.removeEventListener(eventListener);
			connector = null;
		}
	}
	
	/**
	 * @{inheritDoc}
	 */
	@Override
	protected long getRefreshInterval() {
		return refreshInterval;
	}

	/**
	 * @{inheritDoc}
	 */
	@Override
	protected String getName() {
		return "Heatmiser Refresh Service";
	}

	/**
	 * @{inheritDoc}
	 */
	@Override
	protected void execute() {
		// the frequently executed code (polling) goes here ...
		logger.debug("HEATMISER execute() method is called!");
		
		if(pollingTable == null)
			return;
		
		if(pollIterator == null) {			
			// Rebuild the polling table
			pollingTable = new ArrayList<Integer>();
			if(pollingTable == null) {
				logger.error("HEATMISER error creating pollingTable");
				return;
			}

			// Detect all thermostats from the items and add them to the polling table
			for(int address = 0; address < 16; address++) {
				for (HeatmiserBindingProvider provider : providers) {
					if(provider.getBindingItemsAtAddress(address).size() != 0)
						pollingTable.add(address);
				}
			}

			pollIterator = pollingTable.iterator();
		}

		if(pollIterator.hasNext() == false) {
			pollIterator = null;
			return;
		}

		int pollAddress = (int) pollIterator.next();
		HeatmiserThermostat pollThermostat = new HeatmiserThermostat();
		logger.debug("HEATMISER: polling {}", pollAddress);
		pollThermostat.setAddress((byte)pollAddress);

		if(pollIterator.hasNext() == false)
			pollIterator = null;

		connector.sendMessage(pollThermostat.pollThermostat());
	}

	/**
	 * @{inheritDoc}
	 */
	@Override
	protected void internalReceiveCommand(String itemName, Command command) {
		// the code being executed when a command was sent on the openHAB
		// event bus goes here. This method is only called if one of the 
		// BindingProviders provide a binding for the given 'itemName'.
		logger.debug("Heatmiser Command: {} to {}", itemName, command);

		HeatmiserBindingProvider providerCmd = null;
		for (HeatmiserBindingProvider provider : this.providers) {
			int address = provider.getAddress(itemName);
			if (address != -1) {
				providerCmd = provider;
				break;
			}
		}

		if(providerCmd == null)
			return;
		
		int address = providerCmd.getAddress(itemName);
		Functions function = providerCmd.getFunction(itemName);
		
		for (HeatmiserThermostat thermostat: thermostatTable) {
			if(thermostat.getAddress() == address) {
				// Found the thermostat
				byte[] commandPacket = thermostat.formatCommand(function, command);
				connector.sendMessage(commandPacket);
				return;
			}	
		}
		
		
		
	}
	
	/**
	 * @{inheritDoc}
	 */
	@Override
	protected void internalReceiveUpdate(String itemName, State newState) {
		// the code being executed when a state was sent on the openHAB
		// event bus goes here. This method is only called if one of the 
		// BindingProviders provide a binding for the given 'itemName'.
		logger.debug("internalReceiveStatus() is called!");
	}

	/**
	 * @{inheritDoc}
	 */
	@Override
	public void updated(Dictionary<String, ?> config) throws ConfigurationException {
		logger.debug("HEATMISER updated() method is called!");

		if (config != null) {
			// to override the default refresh interval one has to add a 
			// parameter to openhab.cfg like <bindingName>:refresh=<intervalInMs>
			String refreshIntervalString = (String) config.get("refresh");
			if (StringUtils.isNotBlank(refreshIntervalString)) {
				refreshInterval = Long.parseLong(refreshIntervalString);
			}

			String localAddress = (String) config.get("address");
			if (StringUtils.isNotBlank(localAddress)) {
				ipAddress = localAddress;
			}
			
			String portConfig = (String) config.get("port");
			if (StringUtils.isNotBlank(portConfig)) {
				ipPort = Integer.parseInt(portConfig);
			} else {
				ipPort = 1024;
			}

			// start the listener
			listen();

			// Tell the system we're good!
			setProperlyConfigured(true);
		}
	}


	private class MessageListener implements HeatmiserEventListener {
		HeatmiserThermostat thermostatPacket = null;

		@Override
		public void packetReceived(EventObject event, byte[] packet) {
			thermostatPacket = new HeatmiserThermostat();
			if(thermostatPacket.setData(packet) == false)
				return;

			for (HeatmiserThermostat thermostat: thermostatTable) {
				if(thermostat.getAddress() == thermostatPacket.getAddress()) {
					// Found the thermostat
					thermostat.setData(packet);
					processItems(thermostat);
					return;
				}	
			}
			
			// Thermostat not found in the list of known devices
			// Create a new thermostat and add it to the array
			HeatmiserThermostat newThermostat = null;
			switch(thermostatPacket.getModel()) {
				case PRT:
				case PRTE:
					newThermostat = new HeatmiserPRT();
					break;
				case PRTHW:
					newThermostat = new HeatmiserPRTHW();
					break;
				default:
					logger.error("Unknown heatmiser thermostat type {} at address {}", thermostatPacket.getModel(), thermostatPacket.getAddress());
					break;
			}
			
			// Add the new thermostat to the list
			if(newThermostat != null) {
				newThermostat.setData(packet);
				thermostatTable.add(newThermostat);
				processItems(newThermostat);
			}
		}

		private void processItems(HeatmiserThermostat thermostat) {
			for (HeatmiserBindingProvider provider : providers) {
				for (String itemName : provider.getBindingItemsAtAddress(thermostat.getAddress())) {
					State state = null;
					switch(provider.getFunction(itemName)) {
					case FROSTTEMP:
						state = thermostat.getFrostTemperature(provider.getItemType(itemName));
						break;
					case FLOORTEMP:
						state = thermostat.getFloorTemperature(provider.getItemType(itemName));
						break;
					case ONOFF:
						state = thermostat.getState(provider.getItemType(itemName));
						break;
					case HEATSTATE:
						state = thermostat.getHeatState(provider.getItemType(itemName));
						break;
					case ROOMTEMP:
						state = thermostat.getTemperature(provider.getItemType(itemName));
						break;
					case SETTEMP:
						state = thermostat.getSetTemperature(provider.getItemType(itemName));
						break;
					case WATERSTATE:
						state = thermostat.getWaterState(provider.getItemType(itemName));
						break;
					default:
						break;
					}

					if (state != null) {
						eventPublisher.postUpdate(itemName, state);
					} else {
//						logger.debug(
//								"'{}' couldn't be parsed to a State. Valid State-Types are String and Number",
//								variable.toString());
					}
				}
			}
		}
	}
}
