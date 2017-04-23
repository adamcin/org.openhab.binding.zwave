/**
 * Copyright (c) 2014-2016 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.zwave.handler;

import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.config.core.status.ConfigStatusMessage;
import org.eclipse.smarthome.config.core.validation.ConfigValidationException;
import org.eclipse.smarthome.core.events.Event;
import org.eclipse.smarthome.core.events.EventPublisher;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.ConfigStatusThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.zwave.ZWaveBindingConstants;
import org.openhab.binding.zwave.event.BindingEventDTO;
import org.openhab.binding.zwave.event.BindingEventFactory;
import org.openhab.binding.zwave.event.BindingEventType;
import org.openhab.binding.zwave.handler.ZWaveThingChannel.DataType;
import org.openhab.binding.zwave.internal.ZWaveConfigProvider;
import org.openhab.binding.zwave.internal.ZWaveEventPublisher;
import org.openhab.binding.zwave.internal.ZWaveProduct;
import org.openhab.binding.zwave.internal.protocol.ZWaveAssociation;
import org.openhab.binding.zwave.internal.protocol.ZWaveAssociationGroup;
import org.openhab.binding.zwave.internal.protocol.ZWaveConfigurationParameter;
import org.openhab.binding.zwave.internal.protocol.ZWaveEventListener;
import org.openhab.binding.zwave.internal.protocol.ZWaveNode;
import org.openhab.binding.zwave.internal.protocol.ZWaveNodeState;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveCommandClass.CommandClass;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveConfigurationCommandClass;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveConfigurationCommandClass.ZWaveConfigurationParameterEvent;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveDoorLockCommandClass;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveNodeNamingCommandClass;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWavePlusCommandClass;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveSwitchAllCommandClass;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveUserCodeCommandClass;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveUserCodeCommandClass.UserIdStatusType;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveUserCodeCommandClass.ZWaveUserCodeValueEvent;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveWakeUpCommandClass;
import org.openhab.binding.zwave.internal.protocol.commandclass.ZWaveWakeUpCommandClass.ZWaveWakeUpEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveAssociationEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveCommandClassValueEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveDelayedPollEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveInclusionEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveInitializationStateEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveNetworkEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveNodeStatusEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveTransactionCompletedEvent;
import org.openhab.binding.zwave.internal.protocol.initialization.ZWaveNodeSerializer;
import org.openhab.binding.zwave.internal.protocol.transaction.ZWaveCommandClassTransactionPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

/**
 * Thing Handler for ZWave devices
 *
 * @author Chris Jackson - Initial contribution
 *
 */
public class ZWaveThingHandler extends ConfigStatusThingHandler implements ZWaveEventListener {
    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES = Sets.newHashSet();

    private Logger logger = LoggerFactory.getLogger(ZWaveThingHandler.class);

    private ZWaveControllerHandler controllerHandler;

    private boolean finalTypeSet = false;

    private int nodeId;
    private List<ZWaveThingChannel> thingChannelsCmd = Collections.emptyList();
    private List<ZWaveThingChannel> thingChannelsState = Collections.emptyList();

    private final Set<ChannelUID> thingChannelsPoll = new HashSet<ChannelUID>();

    private Map<Integer, ZWaveConfigSubParameter> subParameters = new HashMap<Integer, ZWaveConfigSubParameter>();
    private Map<String, Object> pendingCfg = new HashMap<String, Object>();

    private ScheduledFuture<?> pollingJob = null;
    private final long POLLING_PERIOD_MIN = 15;
    private final long POLLING_PERIOD_MAX = 86400;
    private final long POLLING_PERIOD_DEFAULT = 1800;
    private final long DELAYED_POLLING_PERIOD_MAX = 10;
    private final long REFRESH_POLL_DELAY = 50;
    private long pollingPeriod = POLLING_PERIOD_DEFAULT;

    public ZWaveThingHandler(Thing zwaveDevice) {
        super(zwaveDevice);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing ZWave thing handler {}.", getThing().getUID());

        final BigDecimal cfgNodeId = (BigDecimal) getConfig().get(ZWaveBindingConstants.CONFIGURATION_NODEID);
        if (cfgNodeId == null) {
            logger.error("NodeID is not set in {}", getThing().getUID());
            return;
        }

        nodeId = cfgNodeId.intValue();
        if (nodeId < 1 || nodeId > 232) {
            logger.error("NodeID ({}) out of range for {}", cfgNodeId, getThing().getUID());
            return;
        }

        // We need to set the status to OFFLINE so that the framework calls our notification handlers
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                ZWaveBindingConstants.getI18nConstant(ZWaveBindingConstants.OFFLINE_CTLR_OFFLINE));

        // Make sure the thingType is set correctly from the database
        if (updateThingType() == true) {
            // The thing will have been disposed of so let's exit!
            return;
        }

        // TODO: Shouldn't the framework do this for us???
        Bridge bridge = getBridge();
        if (bridge != null) {
            ThingHandler handler = bridge.getHandler();
            if (handler instanceof ZWaveControllerHandler) {
                ZWaveControllerHandler bridgeHandler = (ZWaveControllerHandler) handler;
                if (bridgeHandler.getOwnNodeId() != 0) {
                    bridgeStatusChanged(bridge.getStatusInfo());
                }
            }
        }
    }

    void initialiseNode() {
        logger.debug("NODE {}: Initialising Thing Node...", nodeId);

        // Note that for dynamic channels, it seems that defaults can either be not set, or set with the incorrect
        // type. So, we read back as an Object to avoid casting problems.
        pollingPeriod = POLLING_PERIOD_DEFAULT;

        final Object pollParm = getConfig().get(ZWaveBindingConstants.CONFIGURATION_POLLPERIOD);
        if (pollParm instanceof BigDecimal) {
            try {
                pollingPeriod = ((BigDecimal) pollParm).intValue();
            } catch (final NumberFormatException ex) {
                logger.warn("NODE {}: pollingPeriod ({}) cannot be parsed - using default", nodeId, pollParm);
            }
        }

        // Create the channels list to simplify processing incoming events
        // synchronized (thingChannelsState) {
        thingChannelsCmd = new ArrayList<ZWaveThingChannel>();
        thingChannelsState = new ArrayList<ZWaveThingChannel>();
        for (Channel channel : getThing().getChannels()) {
            // Process the channel properties and configuration
            Map<String, String> properties = channel.getProperties();
            Configuration configuration = channel.getConfiguration();

            for (String key : properties.keySet()) {
                String[] bindingType = key.split(":");
                if (bindingType.length != 3) {
                    continue;
                }
                if (!ZWaveBindingConstants.CHANNEL_CFG_BINDING.equals(bindingType[0])) {
                    continue;
                }

                String[] bindingProperties = properties.get(key).split(";");

                // TODO: Check length???

                // Get the command classes - comma separated
                String[] cmdClasses = bindingProperties[0].split(",");

                // Convert the arguments to a map
                // - comma separated list of arguments "arg1=val1, arg2=val2"
                Map<String, String> argumentMap = new HashMap<String, String>();
                if (bindingProperties.length == 2) {
                    String[] arguments = bindingProperties[1].split(",");
                    for (String arg : arguments) {
                        String[] prop = arg.split("=");
                        argumentMap.put(prop[0].trim(), prop[1] == null ? null : prop[1].trim());
                    }
                }

                // Process the user configuration and add it to the argument map
                for (String configName : configuration.getProperties().keySet()) {
                    argumentMap.put(configName, configuration.get(configName).toString());
                }

                // Add all the command classes...
                boolean first = true;
                for (String cc : cmdClasses) {
                    String[] ccSplit = cc.split(":");
                    int endpoint = 0;

                    if (ccSplit.length == 2) {
                        endpoint = Integer.parseInt(ccSplit[1]);
                    }

                    // Get the data type
                    DataType dataType = DataType.DecimalType;
                    try {
                        dataType = DataType.valueOf(bindingType[2]);
                    } catch (IllegalArgumentException e) {
                        logger.warn("NODE {}: Invalid item type defined ({}). Assuming DecimalType", nodeId, dataType);
                    }

                    ZWaveThingChannel chan = new ZWaveThingChannel(controllerHandler, channel.getUID(), dataType,
                            ccSplit[0], endpoint, argumentMap);

                    // First time round, and this is a command - then add the command
                    if (first && ("*".equals(bindingType[1]) || "Command".equals(bindingType[1]))) {
                        thingChannelsCmd.add(chan);
                        logger.debug("NODE {}: Initialising cmd channel {} for {}", nodeId, channel.getUID(), dataType);
                    }

                    // First time round, then add the polling class
                    // TODO: Probably should check for duplicates
                    // if (first) {
                    // thingChannelsPoll.add(chan);
                    // logger.debug("NODE {}: Initialising poll channel {} for {}", nodeId, channel.getUID(),
                    // dataType);
                    // }

                    // Add the state and polling handlers
                    if ("*".equals(bindingType[1]) || "State".equals(bindingType[1])) {
                        logger.debug("NODE {}: Initialising state channel {} for {}", nodeId, channel.getUID(),
                                dataType);
                        thingChannelsState.add(chan);
                    }

                    first = false;
                }
            }
            // }
        }

        startPolling();
    }

    /**
     * Check the thing type and change it if it's wrong
     */
    private boolean updateThingType() {
        // If the thing type is still the default, then see if we can change
        if (getThing().getThingTypeUID().equals(ZWaveBindingConstants.ZWAVE_THING_UID) == false) {
            finalTypeSet = true;
            return false;
        }

        // Get the properties for the comparison
        String parmManufacturer = this.getThing().getProperties().get(ZWaveBindingConstants.PROPERTY_MANUFACTURER);
        if (parmManufacturer == null) {
            logger.debug("NODE {}: MANUFACTURER not set", nodeId);
            return false;
        }
        String parmDeviceType = this.getThing().getProperties().get(ZWaveBindingConstants.PROPERTY_DEVICETYPE);
        if (parmDeviceType == null) {
            logger.debug("NODE {}: TYPE not set", nodeId);
            return false;
        }
        String parmDeviceId = this.getThing().getProperties().get(ZWaveBindingConstants.PROPERTY_DEVICEID);
        if (parmDeviceId == null) {
            logger.debug("NODE {}: ID not set", nodeId);
            return false;
        }
        String parmVersion = this.getThing().getProperties().get(ZWaveBindingConstants.PROPERTY_VERSION);
        if (parmVersion == null) {
            logger.debug("NODE {}: VERSION not set {}", nodeId);
            return false;
        }

        int deviceType;
        int deviceId;
        int deviceManufacturer;

        try {
            deviceManufacturer = Integer.parseInt(parmManufacturer);
            deviceType = Integer.parseInt(parmDeviceType);
            deviceId = Integer.parseInt(parmDeviceId);
        } catch (final NumberFormatException ex) {
            logger.debug("NODE {}: Error parsing device parameters", nodeId);
            return false;
        }

        ZWaveProduct foundProduct = null;
        for (ZWaveProduct product : ZWaveConfigProvider.getProductIndex()) {
            if (product == null) {
                continue;
            }
            logger.debug("Checking {}", product.getThingTypeUID());
            if (product.match(deviceManufacturer, deviceType, deviceId, parmVersion) == true) {
                foundProduct = product;
                break;
            }
        }

        // Did we find the thing type?
        if (foundProduct == null) {
            logger.debug("NODE {}: Unable to find thing type ({}:{}:{}:{})", nodeId,
                    String.format("%04X", deviceManufacturer), String.format("%04X", deviceType),
                    String.format("%04X", deviceId), parmVersion);
            return false;
        }

        // We need a change...
        changeThingType(foundProduct.getThingTypeUID(), getConfig());

        return true;
    }

    /**
     * Start polling with an initial delay
     *
     * @param initialPeriod time to start in milliseconds
     */
    private void startPolling(long initialPeriod) {
        if (pollingJob != null) {
            pollingJob.cancel(true);
            pollingJob = null;
        }

        if (pollingPeriod < POLLING_PERIOD_MIN) {
            logger.debug("NODE {}: Polling period was set below minimum value. Using minimum.", nodeId);

            pollingPeriod = POLLING_PERIOD_MIN;
        }

        if (pollingPeriod > POLLING_PERIOD_MAX) {
            logger.debug("NODE {}: Polling period was set above maximum value. Using maximum.", nodeId);

            pollingPeriod = POLLING_PERIOD_MAX;
        }

        Runnable pollingRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    // TODO: If/when this code changes, we should only poll channels that are linked.
                    logger.debug("NODE {}: Polling...", nodeId);
                    ZWaveNode node = controllerHandler.getNode(nodeId);
                    if (node == null || node.isInitializationComplete() == false) {
                        logger.debug("NODE {}: Polling deferred until initialisation complete", nodeId);
                        return;
                    }

                    List<ZWaveCommandClassTransactionPayload> messages = new ArrayList<ZWaveCommandClassTransactionPayload>();
                    for (ZWaveThingChannel channel : thingChannelsState) {
                        if (!thingChannelsPoll.contains(channel.getUID())) {
                            // Don't poll if this channel isn't linked
                            continue;
                        }

                        logger.debug("NODE {}: Polling {}", nodeId, channel.getUID());
                        if (channel.converter == null) {
                            logger.debug("NODE {}: Polling aborted as no converter found for {}", nodeId,
                                    channel.getUID());
                        } else {
                            List<ZWaveCommandClassTransactionPayload> poll = channel.converter.executeRefresh(channel,
                                    node);
                            if (poll != null) {
                                messages.addAll(poll);
                            }
                        }
                    }

                    // If this is a battery device, then we want to check if it stops responding
                    // If no message received in twice the wakeup period, then we're DEAD
                    ZWaveWakeUpCommandClass wakeupCommandClass = (ZWaveWakeUpCommandClass) node
                            .getCommandClass(CommandClass.COMMAND_CLASS_WAKE_UP);
                    if (wakeupCommandClass != null && wakeupCommandClass.getInterval() != 0) {
                        if (node.getLastReceived()
                                .getTime() < (System.currentTimeMillis() - (wakeupCommandClass.getInterval() * 2000))) {
                            node.setNodeState(ZWaveNodeState.DEAD);
                        }
                    }

                    // Send all the messages
                    for (ZWaveCommandClassTransactionPayload message : messages) {
                        controllerHandler.sendData(message);
                    }
                } catch (Exception e) {
                    logger.warn(String.format("NODE %d: Polling aborted due to exception", nodeId), e);
                }
            }
        };

        pollingJob = scheduler.scheduleAtFixedRate(pollingRunnable, initialPeriod, pollingPeriod * 1000,
                TimeUnit.MILLISECONDS);
        logger.debug("NODE {}: Polling intialised at {} seconds - start in {} milliseconds.", nodeId, pollingPeriod,
                initialPeriod);
    }

    private void startPolling() {
        startPolling(pollingPeriod * 1000);
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        // We keep track of what channels are used and only poll channels that the framework is using
        thingChannelsPoll.add(channelUID);
    }

    @Override
    public void channelUnlinked(ChannelUID channelUID) {
        // We keep track of what channels are used and only poll channels that the framework is using
        thingChannelsPoll.remove(channelUID);
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        logger.debug("NODE {}: Controller status changed to {}.", nodeId, bridgeStatusInfo.getStatus());

        if (bridgeStatusInfo.getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                    ZWaveBindingConstants.getI18nConstant(ZWaveBindingConstants.OFFLINE_CTLR_OFFLINE));
            logger.debug("NODE {}: Controller is not online.", nodeId, bridgeStatusInfo.getStatus());
            return;
        }

        logger.debug("NODE {}: Controller is ONLINE. Starting device initialisation.", nodeId);

        ZWaveControllerHandler bridgeHandler = (ZWaveControllerHandler) getBridge().getHandler();
        // We might not be notified that the controller is online until it's completed a lot of initialisation, so
        // make sure we know the device state.
        ZWaveNode node = bridgeHandler.getNode(nodeId);
        if (node == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE,
                    ZWaveBindingConstants.getI18nConstant(ZWaveBindingConstants.OFFLINE_NODE_NOTFOUND));
        } else {
            switch (node.getNodeState()) {
                case INITIALIZING:
                case ALIVE:
                    updateStatus(ThingStatus.ONLINE);
                    break;
                case DEAD:
                case FAILED:
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            ZWaveBindingConstants.getI18nConstant(ZWaveBindingConstants.OFFLINE_NODE_DEAD));
                    break;
            }
        }

        // If we already know the controller, then we don't want to initialise again
        if (controllerHandler != null) {
            logger.debug("NODE {}: Controller already initialised.", nodeId);
            return;
        }

        controllerHandler = bridgeHandler;
        if (node != null) {
            updateNodeNeighbours();
            updateNodeProperties();
        }

        // Add the listener for ZWave events.
        // This ensures we get called whenever there's an event we might be interested in
        if (bridgeHandler.addEventListener(this) == false) {
            logger.debug("NODE {}: Controller failed to register event handler.", nodeId);
            return;
        }

        // Initialise the node - create all the channel links
        initialiseNode();
        logger.debug("NODE {}: Device initialisation complete.", nodeId);
    }

    @Override
    public void dispose() {
        logger.debug("Handler disposed. Unregistering listener.");
        if (nodeId != 0) {
            if (controllerHandler != null) {
                // Save the XML so that any changes to configuration is saved
                ZWaveNodeSerializer nodeSerializer = new ZWaveNodeSerializer();
                ZWaveNode node = controllerHandler.getNode(nodeId);
                if (node != null) {
                    nodeSerializer.SerializeNode(node);
                }

                // Remove the event listener
                controllerHandler.removeEventListener(this);
            }
            nodeId = 0;
        }

        if (pollingJob != null) {
            pollingJob.cancel(true);
            pollingJob = null;
        }

        controllerHandler = null;
    }

    @Override
    public void handleConfigurationUpdate(Map<String, Object> configurationParameters)
            throws ConfigValidationException {
        logger.debug("NODE {}: Configuration update received", nodeId);

        // Perform checking on the configuration
        validateConfigurationParameters(configurationParameters);

        if (controllerHandler == null) {
            logger.debug("NODE {}: Configuration update not processed as no controller found", nodeId);
            return;
        }

        ZWaveNode node = controllerHandler.getNode(nodeId);
        if (node == null) {
            logger.debug("NODE {}: Configuration update not processed as nonode not found", nodeId);
            return;
        }

        Configuration configuration = editConfiguration();
        for (Entry<String, Object> configurationParameter : configurationParameters.entrySet()) {
            Object valueObject = configurationParameter.getValue();
            logger.debug("NODE {}: Configuration update {} to {}", nodeId, configurationParameter.getKey(),
                    valueObject);
            String[] cfg = configurationParameter.getKey().split("_");
            switch (cfg[0]) {
                case "config":
                    if (cfg.length < 3) {
                        logger.warn("NODE {}: Configuration invalid {}", nodeId, configurationParameter.getKey());
                        continue;
                    }

                    ZWaveConfigurationCommandClass configurationCommandClass = (ZWaveConfigurationCommandClass) node
                            .getCommandClass(CommandClass.COMMAND_CLASS_CONFIGURATION);
                    if (configurationCommandClass == null) {
                        logger.debug("NODE {}: Error getting configurationCommandClass", nodeId);
                        continue;
                    }

                    // Get the size
                    int size = Integer.parseInt(cfg[2]);
                    if (size == 0 || size > 4) {
                        logger.debug("NODE {}: Size error ({}) from {}", nodeId, size, configurationParameter.getKey());
                        continue;
                    }

                    // Convert to integer
                    Integer value;
                    if (configurationParameter.getValue() instanceof BigDecimal) {
                        value = ((BigDecimal) configurationParameter.getValue()).intValue();
                    } else if (configurationParameter.getValue() instanceof String) {
                        value = Integer.parseInt((String) configurationParameter.getValue());
                    } else {
                        logger.debug("NODE {}: Error converting config value from {}", nodeId,
                                configurationParameter.getValue().getClass());
                        continue;
                    }

                    Integer parameterIndex = Integer.valueOf(cfg[1]);

                    boolean writeOnly = false;
                    if (Arrays.asList(cfg).contains("wo")) {
                        writeOnly = true;
                    }

                    // If we have specified a bitmask, then we need to process this and save for later
                    if (cfg.length >= 4 && cfg[3].length() == 8) {
                        int bitmask = 0xffffffff;
                        try {
                            bitmask = Integer.parseInt(cfg[3], 16);
                        } catch (NumberFormatException e) {
                            logger.debug("NODE {}: Error parsing bitmask for {}", nodeId,
                                    configurationParameter.getKey());
                        }

                        boolean requestUpdate = false;
                        ZWaveConfigSubParameter subParameter = subParameters.get(parameterIndex);
                        if (subParameter == null) {
                            subParameter = new ZWaveConfigSubParameter();
                            requestUpdate = true;
                        }

                        logger.debug("NODE {}: Set sub-parameter {} from {} / {}", nodeId, parameterIndex, value,
                                String.format("%08X", bitmask));

                        logger.debug("NODE {}: Parameter {} set value {} mask {}", nodeId, parameterIndex,
                                String.format("%08X", value), String.format("%08X", bitmask));

                        subParameter.addBitmask(bitmask, value);
                        subParameters.put(parameterIndex, subParameter);

                        // Request the value. When this is received, we'll update the relevant bits
                        // and send the SET command.
                        // Only send the request if there's not already a request outstanding
                        if (requestUpdate == true) {
                            node.sendMessage(configurationCommandClass.getConfigMessage(parameterIndex));
                        }
                    } else {
                        ZWaveConfigurationParameter cfgParameter = configurationCommandClass
                                .getParameter(parameterIndex);
                        if (cfgParameter == null) {
                            cfgParameter = new ZWaveConfigurationParameter(parameterIndex, value, size);
                        } else {
                            cfgParameter.setValue(value);
                        }

                        // Set the parameter and request a read-back if it's not a write only parameter
                        node.sendMessage(configurationCommandClass.setConfigMessage(cfgParameter));
                        if (writeOnly == false) {
                            node.sendMessage(configurationCommandClass.getConfigMessage(parameterIndex));
                        }
                    }

                    pendingCfg.put(configurationParameter.getKey(), valueObject);
                    break;

                case "group":
                    if (cfg.length < 2) {
                        logger.debug("NODE{}: Association invalid {}", nodeId, configurationParameter.getKey());
                        continue;
                    }

                    Integer groupIndex = Integer.valueOf(cfg[1]);

                    // Get the configuration information.
                    // This should be an array of nodes, and/or nodes and endpoints
                    ArrayList<String> paramValues = new ArrayList<String>();
                    Object parameter = configurationParameter.getValue();
                    if (parameter instanceof List) {
                        paramValues.addAll((List) configurationParameter.getValue());
                    } else if (parameter instanceof String) {
                        paramValues.add((String) parameter);
                    }

                    ZWaveAssociationGroup currentMembers = node.getAssociationGroup(groupIndex);
                    ZWaveAssociationGroup newMembers = new ZWaveAssociationGroup(groupIndex);

                    int totalMembers = currentMembers.getAssociationCnt();

                    // Loop over all the parameters
                    for (String paramValue : paramValues) {
                        String[] groupCfg = paramValue.split("_");

                        // Make sure this is a correctly formatted option
                        if (!"node".equals(groupCfg[0])) {
                            continue;
                        }

                        // Get the node Id and endpoint Id
                        int associationNodeId = Integer.parseInt(groupCfg[1]);
                        int associationEndpointId = Integer.parseInt(groupCfg[2]);

                        newMembers.addAssociation(associationNodeId, associationEndpointId);
                        totalMembers++;
                    }

                    // Loop through the current members and remove anything that's not in the new members list
                    for (ZWaveAssociation member : currentMembers.getAssociations()) {
                        // Is the current association still in the newMembers list?
                        if (newMembers.isAssociated(member.getNode(), member.getEndpoint()) == false) {
                            // No - so it needs to be removed
                            node.sendMessage(
                                    node.removeAssociation(groupIndex, member.getNode(), member.getEndpoint()));
                            totalMembers--;
                        }
                    }

                    // Now loop through the new members and add anything not in the current members list
                    for (ZWaveAssociation member : newMembers.getAssociations()) {
                        // Is the new association still in the currentMembers list?
                        if (currentMembers.isAssociated(member.getNode(), member.getEndpoint()) == false) {
                            // No - so it needs to be added
                            // TODO: This needs to handle the sending endpoint not being root.
                            node.sendMessage(
                                    node.setAssociation(null, groupIndex, member.getNode(), member.getEndpoint()));
                            totalMembers++;
                        }
                    }

                    // If there are no known associations in the group, then let's clear the group completely
                    // This ensures we don't end up with strange ghost associations
                    if (totalMembers == 0) {
                        node.sendMessage(node.clearAssociation(groupIndex));
                    }

                    // Request an update to the association group
                    node.sendMessage(node.getAssociation(groupIndex));
                    pendingCfg.put(configurationParameter.getKey(), valueObject);
                    break;

                case "wakeup":
                    ZWaveWakeUpCommandClass wakeupCommandClass = (ZWaveWakeUpCommandClass) node
                            .getCommandClass(CommandClass.COMMAND_CLASS_WAKE_UP);
                    if (wakeupCommandClass == null) {
                        logger.debug("NODE {}: Error getting wakeupCommandClass", nodeId);
                        continue;
                    }

                    Integer wakeupValue;
                    try {
                        wakeupValue = ((BigDecimal) configurationParameter.getValue()).intValue();
                    } catch (NumberFormatException e) {
                        logger.debug("NODE {}: Error converting wakeup value from {}", nodeId,
                                configurationParameter.getValue());
                        continue;
                    }

                    logger.debug("NODE {}: Set wakeup interval to '{}'", nodeId, wakeupValue);

                    // Set the wake-up interval
                    node.sendMessage(wakeupCommandClass.setInterval(wakeupValue));
                    // And request a read-back
                    node.sendMessage(wakeupCommandClass.getIntervalMessage());
                    pendingCfg.put(configurationParameter.getKey(), valueObject);
                    break;

                case "nodename":
                    ZWaveNodeNamingCommandClass nameCommandClass = (ZWaveNodeNamingCommandClass) node
                            .getCommandClass(CommandClass.COMMAND_CLASS_NODE_NAMING);
                    if (nameCommandClass == null) {
                        logger.debug("NODE {}: Error getting NodeNamingCommandClass", nodeId);
                        continue;
                    }

                    if ("name".equals(cfg[1])) {
                        controllerHandler.sendData(node.encapsulate(
                                nameCommandClass.setNameMessage(configurationParameter.getValue().toString()), 0));
                    }
                    if ("location".equals(cfg[1])) {
                        controllerHandler.sendData(node.encapsulate(
                                nameCommandClass.setLocationMessage(configurationParameter.getValue().toString()), 0));
                    }
                    pendingCfg.put(configurationParameter.getKey(), valueObject);
                    break;
                case "switchall":
                    ZWaveSwitchAllCommandClass switchallCommandClass = (ZWaveSwitchAllCommandClass) node
                            .getCommandClass(CommandClass.COMMAND_CLASS_SWITCH_ALL);
                    if (switchallCommandClass == null) {
                        logger.debug("NODE {}: Error getting SwitchAllCommandClass", nodeId);
                        continue;
                    }

                    if ("mode".equals(cfg[1])) {
                        controllerHandler.sendData(node.encapsulate(switchallCommandClass
                                .setValueMessage(Integer.parseInt(configurationParameter.getValue().toString())), 0));
                    }
                    pendingCfg.put(configurationParameter.getKey(), valueObject);
                    break;

                case "powerlevel":
                    ZWavePowerLevelCommandClass powerlevelCommandClass = (ZWavePowerLevelCommandClass) node
                            .getCommandClass(CommandClass.COMMAND_CLASS_POWERLEVEL);
                    if (powerlevelCommandClass == null) {
                        logger.debug("NODE {}: Error getting PowerLevelCommandClass", nodeId);
                        continue;
                    }

                    // Since both level and timeout are set in a single command, we first check if the value exists in
                    // the
                    // pending list, and if not, use the value already stored in the command class
                    if ("level".equals(cfg[1])) {
                        Integer timeout = (Integer) pendingCfg
                                .get(ZWaveBindingConstants.CONFIGURATION_POWERLEVEL_TIMEOUT);
                        if (timeout == null) {
                            timeout = powerlevelCommandClass.getTimeout();
                        }
                        controllerHandler.sendData(node.encapsulate(powerlevelCommandClass.setValueMessage(
                                (Integer.parseInt(configurationParameter.getValue().toString())), timeout), 0));
                    }
                    if ("timeout".equals(cfg[1])) {
                        Integer level = (Integer) pendingCfg.get(ZWaveBindingConstants.CONFIGURATION_POWERLEVEL_LEVEL);
                        if (level == null) {
                            level = powerlevelCommandClass.getLevel();
                        }
                        controllerHandler
                                .sendData(
                                        node.encapsulate(
                                                powerlevelCommandClass
                                                        .setValueMessage(level,
                                                                (Integer.parseInt(
                                                                        configurationParameter.getValue().toString()))),
                                                0));
                    }
                    controllerHandler.sendData(node.encapsulate(powerlevelCommandClass.getValueMessage(), 0));
                    pendingCfg.put(configurationParameter.getKey(), valueObject);
                case "doorlock":
                    ZWaveDoorLockCommandClass commandClass = (ZWaveDoorLockCommandClass) node
                            .getCommandClass(CommandClass.COMMAND_CLASS_DOOR_LOCK);
                    if (commandClass == null) {
                        logger.debug("NODE {}: Error getting ZWaveDoorLockCommandClass", nodeId);
                        continue;
                    }

                    if ("timeout".equals(cfg[1])) {
                        boolean timeoutEnabled;

                        try {
                            int doorlockValue = ((BigDecimal) valueObject).intValue();
                            if (doorlockValue == 0) {
                                timeoutEnabled = false;
                            } else {
                                timeoutEnabled = true;
                            }
                            controllerHandler.sendData(
                                    node.encapsulate(commandClass.setConfigMessage(timeoutEnabled, doorlockValue), 0));
                            controllerHandler.sendData(node.encapsulate(commandClass.getConfigMessage(), 0));
                            pendingCfg.put(ZWaveBindingConstants.CONFIGURATION_DOORLOCKTIMEOUT, valueObject);
                        } catch (NumberFormatException e) {
                            logger.debug("Number format exception parsing doorlock_timeout '{}'", valueObject);
                        }
                    }
                    break;
                case "usercode":
                    ZWaveUserCodeCommandClass userCodeCommandClass = (ZWaveUserCodeCommandClass) node
                            .getCommandClass(CommandClass.COMMAND_CLASS_USER_CODE);
                    if (userCodeCommandClass == null) {
                        logger.debug("NODE {}: Error getting ZWaveUserCodeCommandClass", nodeId);
                        continue;
                    }

                    try {
                        int code = Integer.parseInt(cfg[2]);
                        if (code == 0 || code > userCodeCommandClass.getNumberOfSupportedCodes()) {
                            logger.debug("NODE {}: Attempt to set code ID outside of range", nodeId);
                            continue;
                        }
                        controllerHandler.sendData(
                                node.encapsulate(userCodeCommandClass.setUserCode(code, (String) valueObject), 0));
                        controllerHandler.sendData(node.encapsulate(userCodeCommandClass.getUserCode(code), 0));
                        pendingCfg.put(configurationParameter.getKey(), valueObject);
                    } catch (NumberFormatException e) {
                        logger.error("Number format exception parsing user code ID '{}'",
                                configurationParameter.getKey());
                    }
                    break;
                case "binding":
                    if ("pollperiod".equals(cfg[1])) {
                        pollingPeriod = POLLING_PERIOD_DEFAULT;
                        try {
                            pollingPeriod = ((BigDecimal) configurationParameter.getValue()).intValue();
                        } catch (final NumberFormatException ex) {
                            logger.debug("NODE {}: pollingPeriod ({}) cannot be set - using default", nodeId,
                                    configurationParameter.getValue().toString());
                        }
                        if (pollingPeriod < POLLING_PERIOD_MIN) {
                            pollingPeriod = POLLING_PERIOD_MIN;
                        }
                        if (pollingPeriod > POLLING_PERIOD_MAX) {
                            pollingPeriod = POLLING_PERIOD_MAX;
                        }
                        valueObject = new BigDecimal(pollingPeriod);

                        // Restart polling so we use the new value
                        startPolling();
                    }
                    break;

                case "action":
                    if ("failed".equals(cfg[1]) && valueObject instanceof BigDecimal
                            && ((BigDecimal) valueObject).intValue() == ZWaveBindingConstants.ACTION_CHECK_VALUE) {
                        controllerHandler.replaceFailedNode(nodeId);
                    }
                    if ("remove".equals(cfg[1]) && valueObject instanceof BigDecimal
                            && ((BigDecimal) valueObject).intValue() == ZWaveBindingConstants.ACTION_CHECK_VALUE) {
                        controllerHandler.removeFailedNode(nodeId);
                        controllerHandler.checkNodeFailed(nodeId);
                    }
                    if ("reinit".equals(cfg[1]) && valueObject instanceof BigDecimal
                            && ((BigDecimal) valueObject).intValue() == ZWaveBindingConstants.ACTION_CHECK_VALUE) {
                        logger.debug("NODE {}: Re-initialising node!", nodeId);

                        // Delete the saved XML
                        ZWaveNodeSerializer nodeSerializer = new ZWaveNodeSerializer();
                        nodeSerializer.DeleteNode(node.getHomeId(), nodeId);

                        controllerHandler.reinitialiseNode(nodeId);
                    }

                    if ("heal".equals(cfg[1]) && valueObject instanceof BigDecimal
                            && ((BigDecimal) valueObject).intValue() == ZWaveBindingConstants.ACTION_CHECK_VALUE) {
                        logger.debug("NODE {}: Starting heal on node!", nodeId);

                        controllerHandler.healNode(nodeId);
                    }

                    // Don't save the value
                    valueObject = "";
                    break;

                default:
                    logger.debug("NODE{}: Configuration invalid {}", nodeId, configurationParameter.getKey());
            }

            configuration.put(configurationParameter.getKey(), valueObject);
        }

        // Persist changes
        updateConfiguration(configuration);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("NODE {}: Command received {} --> {}", nodeId, channelUID, command);
        if (controllerHandler == null) {
            logger.debug("Controller handler not found. Cannot handle command without ZWave controller.");
            return;
        }

        if (command == RefreshType.REFRESH) {
            startPolling(REFRESH_POLL_DELAY);
            return;
        }

        DataType dataType;
        try {
            dataType = DataType.valueOf(command.getClass().getSimpleName());
        } catch (IllegalArgumentException e) {
            logger.error("NODE {}: Command received with no implementation ({}).", nodeId,
                    command.getClass().getSimpleName());
            return;
        }

        // Find the channel
        ZWaveThingChannel cmdChannel = null;
        for (ZWaveThingChannel channel : thingChannelsCmd) {
            if (channel.getUID().equals(channelUID) && channel.getDataType() == dataType) {
                cmdChannel = channel;
                break;
            }
        }

        if (cmdChannel == null) {
            logger.debug("NODE {}: Command for unknown channel {} with {}", nodeId, channelUID, dataType);
            return;
        }

        ZWaveNode node = controllerHandler.getNode(nodeId);
        if (node == null) {
            logger.debug("NODE {}: Node is not found for {}", nodeId, channelUID);
            return;
        }

        if (cmdChannel.converter == null) {
            logger.warn("NODE {}: No command converter set for command {} type {}", nodeId, channelUID, dataType);
            return;
        }

        List<ZWaveCommandClassTransactionPayload> messages = null;
        messages = cmdChannel.converter.receiveCommand(cmdChannel, node, command);

        if (messages == null) {
            logger.debug("NODE {}: No messages returned from converter", nodeId);
            return;
        }

        // Send all the messages
        for (ZWaveCommandClassTransactionPayload message : messages) {
            controllerHandler.sendData(message);
        }
    }

    @Override
    public void ZWaveIncomingEvent(ZWaveEvent incomingEvent) {
        // Check if this event is for this device
        if (incomingEvent.getNodeId() != nodeId) {
            return;
        }

        logger.debug("NODE {}: Got an event from Z-Wave network: {}", nodeId, incomingEvent.getClass().getSimpleName());

        // Handle command class value events.
        if (incomingEvent instanceof ZWaveCommandClassValueEvent) {
            // Cast to a command class event
            ZWaveCommandClassValueEvent event = (ZWaveCommandClassValueEvent) incomingEvent;

            String commandClass = event.getCommandClass().toString();

            logger.debug(
                    "NODE {}: Got a value event from Z-Wave network, endpoint = {}, command class = {}, value = {}",
                    nodeId, event.getEndpoint(), commandClass, event.getValue());

            // If this is a configuration parameter update, process it before the channels
            Configuration configuration = editConfiguration();
            boolean cfgUpdated = false;
            switch (event.getCommandClass()) {
                case COMMAND_CLASS_CONFIGURATION:
                    ZWaveConfigurationParameter parameter = ((ZWaveConfigurationParameterEvent) event).getParameter();
                    if (parameter == null) {
                        return;
                    }

                    logger.debug("NODE {}: Update CONFIGURATION {}/{} to {}", nodeId, parameter.getIndex(),
                            parameter.getSize(), parameter.getValue());

                    // Check for any sub parameter processing...
                    // If we have requested the current state of a parameter and t's waiting to be updated, then we
                    // check this here, update the value and send the request...
                    // Do this first so we only process the data if we're not waiting to send
                    ZWaveConfigSubParameter subParameter = subParameters.get(parameter.getIndex());

                    if (subParameter != null) {
                        // Get the new value based on the sub-parameter bitmask
                        int value = subParameter.getValue(parameter.getValue());
                        logger.debug("NODE {}: Updating sub-parameter {} to {}", nodeId, parameter.getIndex(), value);

                        // Remove the sub parameter so we don't loop forever!
                        subParameters.remove(parameter.getIndex());

                        ZWaveNode node = controllerHandler.getNode(nodeId);
                        if (node == null) {
                            logger.error("NODE {}: Error getting node for config update", nodeId);
                            return;
                        }
                        ZWaveConfigurationCommandClass configurationCommandClass = (ZWaveConfigurationCommandClass) node
                                .getCommandClass(CommandClass.COMMAND_CLASS_CONFIGURATION);
                        if (configurationCommandClass == null) {
                            logger.debug("NODE {}: Error getting configurationCommandClass", nodeId);
                            return;
                        }

                        ZWaveConfigurationParameter cfgParameter = configurationCommandClass
                                .getParameter(parameter.getIndex());
                        if (cfgParameter == null) {
                            cfgParameter = new ZWaveConfigurationParameter(parameter.getIndex(), value,
                                    parameter.getSize());
                        } else {
                            cfgParameter.setValue(value);
                        }

                        logger.debug("NODE {}: Setting parameter {} to {}", nodeId, cfgParameter.getIndex(),
                                cfgParameter.getValue());
                        node.sendMessage(configurationCommandClass.setConfigMessage(cfgParameter));
                        node.sendMessage(configurationCommandClass.getConfigMessage(parameter.getIndex()));

                        // Don't process the data - it hasn't been updated yet!
                        break;
                    }

                    updateConfigurationParameter(configuration, parameter.getIndex(), parameter.getSize(),
                            parameter.getValue());
                    break;

                case COMMAND_CLASS_ASSOCIATION:
                    int groupId = ((ZWaveAssociationEvent) event).getGroupId();
                    List<ZWaveAssociation> groupMembers = ((ZWaveAssociationEvent) event).getGroupMembers();
                    if (groupMembers != null) {
                        logger.debug("NODE {}: Update ASSOCIATION group_{}", nodeId, groupId);

                        List<String> group = new ArrayList<String>();

                        // Build the configuration value
                        for (ZWaveAssociation groupMember : groupMembers) {
                            logger.debug("NODE {}: Update ASSOCIATION group_{}: Adding node_{}_{}", nodeId, groupId,
                                    groupMember.getNode(), groupMember.getEndpoint());
                            group.add("node_" + groupMember.getNode() + "_" + groupMember.getEndpoint());
                        }
                        logger.debug("NODE {}: Update ASSOCIATION group_{}: {} members", nodeId, groupId, group.size());

                        cfgUpdated = true;
                        configuration.put("group_" + groupId, group);
                        pendingCfg.remove("group_" + groupId);
                    }
                    break;

                case COMMAND_CLASS_SWITCH_ALL:
                    cfgUpdated = true;
                    configuration.put(ZWaveBindingConstants.CONFIGURATION_SWITCHALLMODE, event.getValue());
                    pendingCfg.remove(ZWaveBindingConstants.CONFIGURATION_SWITCHALLMODE);
                    break;

                case COMMAND_CLASS_NODE_NAMING:
                    switch ((ZWaveNodeNamingCommandClass.Type) event.getType()) {
                        case NODENAME_LOCATION:
                            cfgUpdated = true;
                            configuration.put(ZWaveBindingConstants.CONFIGURATION_NODELOCATION, event.getValue());
                            pendingCfg.remove(ZWaveBindingConstants.CONFIGURATION_NODELOCATION);
                            break;
                        case NODENAME_NAME:
                            cfgUpdated = true;
                            configuration.put(ZWaveBindingConstants.CONFIGURATION_NODENAME, event.getValue());
                            pendingCfg.remove(ZWaveBindingConstants.CONFIGURATION_NODENAME);
                            break;
                    }
                    break;

                case COMMAND_CLASS_DOOR_LOCK:
                    switch ((ZWaveDoorLockCommandClass.Type) event.getType()) {
                        case DOOR_LOCK_TIMEOUT:
                            cfgUpdated = true;
                            configuration.put(ZWaveBindingConstants.CONFIGURATION_DOORLOCKTIMEOUT, event.getValue());
                            pendingCfg.remove(ZWaveBindingConstants.CONFIGURATION_DOORLOCKTIMEOUT);
                            break;
                        default:
                            break;
                    }
                    break;

                case COMMAND_CLASS_POWERLEVEL:
                    ZWavePowerLevelCommandClassChangeEvent powerEvent = (ZWavePowerLevelCommandClassChangeEvent) event;
                    cfgUpdated = true;
                    configuration.put(ZWaveBindingConstants.CONFIGURATION_POWERLEVEL_LEVEL, powerEvent.getLevel());
                    pendingCfg.remove(ZWaveBindingConstants.CONFIGURATION_POWERLEVEL_LEVEL);
                    configuration.put(ZWaveBindingConstants.CONFIGURATION_POWERLEVEL_TIMEOUT, powerEvent.getTimeout());
                    pendingCfg.remove(ZWaveBindingConstants.CONFIGURATION_POWERLEVEL_TIMEOUT);
                    break;

                case COMMAND_CLASS_USER_CODE:
                    ZWaveUserCodeValueEvent codeEvent = (ZWaveUserCodeValueEvent) event;
                    cfgUpdated = true;
                    String codeParameterName = ZWaveBindingConstants.CONFIGURATION_USERCODE_CODE + codeEvent.getId();
                    if (codeEvent.getStatus() == UserIdStatusType.OCCUPIED) {
                        configuration.put(codeParameterName, codeEvent.getCode());
                    } else {
                        configuration.put(codeParameterName, "");
                    }
                    pendingCfg.remove(codeParameterName);
                    break;

                default:
                    break;
            }
            if (cfgUpdated == true) {
                logger.debug("NODE {}: Config updated", nodeId);
                updateConfiguration(configuration);
            }

            if (thingChannelsState == null) {
                logger.debug("NODE {}: No state handlers!", nodeId);
                return;
            }

            // Process the channels to see if we're interested
            for (ZWaveThingChannel channel : thingChannelsState) {
                // logger.debug("NODE {}: Checking channel {}", nodeId, channel.getUID());

                if (channel.getEndpoint() != event.getEndpoint()) {
                    continue;
                }

                // Is this command class associated with this channel?
                if (!channel.getCommandClass().equals(commandClass)) {
                    continue;
                }

                if (channel.converter == null) {
                    logger.warn("NODE {}: No state converter set for channel {}", nodeId, channel.getUID());
                    return;
                }

                // logger.debug("NODE {}: Processing event as channel {} {}", nodeId, channel.getUID(),
                // channel.dataType);
                State state = channel.converter.handleEvent(channel, event);
                if (state != null) {
                    logger.debug("NODE {}: Updating channel state {} to {} [{}]", nodeId, channel.getUID(), state,
                            state.getClass().getSimpleName());

                    updateState(channel.getUID(), state);
                }
            }

            return;
        }

        // Handle transaction complete events.
        if (incomingEvent instanceof ZWaveTransactionCompletedEvent) {
            return;
        }

        // Handle wakeup notification events.
        if (incomingEvent instanceof ZWaveWakeUpEvent) {
            ZWaveNode node = controllerHandler.getNode(nodeId);
            if (node == null) {
                return;
            }

            switch (((ZWaveWakeUpEvent) incomingEvent).getEvent()) {
                case ZWaveWakeUpCommandClass.WAKE_UP_INTERVAL_REPORT:
                    ZWaveWakeUpCommandClass commandClass = (ZWaveWakeUpCommandClass) node
                            .getCommandClass(CommandClass.COMMAND_CLASS_WAKE_UP);
                    Configuration configuration = editConfiguration();
                    configuration.put(ZWaveBindingConstants.CONFIGURATION_WAKEUPINTERVAL, commandClass.getInterval());
                    pendingCfg.remove(ZWaveBindingConstants.CONFIGURATION_WAKEUPINTERVAL);
                    configuration.put(ZWaveBindingConstants.CONFIGURATION_WAKEUPNODE, commandClass.getTargetNodeId());
                    pendingCfg.remove(ZWaveBindingConstants.CONFIGURATION_WAKEUPNODE);
                    updateConfiguration(configuration);
                    break;
            }
            return;
        }

        // Handle node state change events.
        if (incomingEvent instanceof ZWaveNodeStatusEvent) {
            // Cast to a command class event
            ZWaveNodeStatusEvent event = (ZWaveNodeStatusEvent) incomingEvent;

            switch (event.getState()) {
                case AWAKE:
                    Map<String, String> properties = editProperties();
                    properties.put(ZWaveBindingConstants.PROPERTY_LASTWAKEUP, getISO8601StringForCurrentDate());
                    updateProperties(properties);
                    break;
                case ASLEEP:
                    break;
                case INITIALIZING:
                case ALIVE:
                    logger.debug("NODE {}: Setting ONLINE", nodeId);
                    updateStatus(ThingStatus.ONLINE);
                    break;
                case DEAD:
                case FAILED:
                    logger.debug("NODE {}: Setting OFFLINE", nodeId);
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            ZWaveBindingConstants.getI18nConstant(ZWaveBindingConstants.OFFLINE_NODE_DEAD));
                    break;
            }

            return;
        }

        if (incomingEvent instanceof ZWaveInitializationStateEvent) {
            ZWaveInitializationStateEvent initEvent = (ZWaveInitializationStateEvent) incomingEvent;
            switch (initEvent.getStage()) {
                case STATIC_END:
                    // Update some properties first...
                    updateNodeProperties();

                    // Do we need to change type?
                    if (finalTypeSet == false) {
                        if (updateThingType() == true) {
                            // We updated the type.
                            // The thing will have already been disposed of so let's get the hell out of here!
                            return;
                        }
                    }

                    // Set ourselves online if we have the final thing type set
                    if (finalTypeSet) {
                        logger.debug("NODE {}: Setting ONLINE", nodeId);
                        updateStatus(ThingStatus.ONLINE);

                        // Now that this node is initialised, we want to re-process all channels
                        initialiseNode();
                    }
                    break;
                case HEAL_START:
                    EventPublisher epHealStart = ZWaveEventPublisher.getEventPublisher();
                    if (epHealStart != null) {
                        BindingEventDTO dto = new BindingEventDTO(BindingEventType.INFO, ZWaveBindingConstants
                                .getI18nConstant(ZWaveBindingConstants.EVENT_HEAL_START, new Integer(nodeId)));
                        Event notification = BindingEventFactory.createBindingEvent(ZWaveBindingConstants.BINDING_ID,
                                "network", "", dto);
                        epHealStart.post(notification);
                    }
                    break;
                case HEAL_END:
                    updateNodeNeighbours();
                    EventPublisher epHealDone = ZWaveEventPublisher.getEventPublisher();
                    if (epHealDone != null) {
                        BindingEventDTO dto = new BindingEventDTO(BindingEventType.INFO, ZWaveBindingConstants
                                .getI18nConstant(ZWaveBindingConstants.EVENT_HEAL_DONE, new Integer(nodeId)));
                        Event notification = BindingEventFactory.createBindingEvent(ZWaveBindingConstants.BINDING_ID,
                                "network", "", dto);
                        epHealDone.post(notification);
                    }

                    Map<String, String> properties = editProperties();
                    properties.put(ZWaveBindingConstants.PROPERTY_LASTHEAL, getISO8601StringForCurrentDate());
                    updateProperties(properties);
                    break;

                // Don't update the thing state for dynamic updates - this is just polling
                case DYNAMIC_VALUES:
                case DYNAMIC_END:
                    break;
                // Don't update the thing state when doing a heal
                case UPDATE_NEIGHBORS:
                case GET_NEIGHBORS:
                case DELETE_SUC_ROUTES:
                case SUC_ROUTE:
                case DELETE_ROUTES:
                case RETURN_ROUTES:
                case DONE:
                    break;
                default:
                    if (finalTypeSet) {
                        updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE,
                                "Node initialising: " + initEvent.getStage().toString());
                    }
                    break;
            }
        }

        if (incomingEvent instanceof ZWaveNetworkEvent) {
            ZWaveNetworkEvent networkEvent = (ZWaveNetworkEvent) incomingEvent;

            if (networkEvent.getEvent() == ZWaveNetworkEvent.Type.NodeRoutingInfo) {
                updateNodeNeighbours();
            }

            if (networkEvent.getEvent() == ZWaveNetworkEvent.Type.DeleteNode) {
                updateStatus(ThingStatus.REMOVED);
            }
        }

        if (incomingEvent instanceof ZWaveDelayedPollEvent) {
            long delay = ((ZWaveDelayedPollEvent) incomingEvent).getDelay();
            TimeUnit unit = ((ZWaveDelayedPollEvent) incomingEvent).getUnit();

            // Don't create a poll beyond our max value
            if (unit.toSeconds(delay) > DELAYED_POLLING_PERIOD_MAX) {
                delay = DELAYED_POLLING_PERIOD_MAX;
                unit = TimeUnit.SECONDS;
            }

            startPolling(unit.toMillis(delay));
        }

        // Handle exclusion of this node
        if (incomingEvent instanceof ZWaveInclusionEvent) {
            ZWaveInclusionEvent incEvent = (ZWaveInclusionEvent) incomingEvent;
            if (incEvent.getNodeId() != nodeId) {
                return;
            }

            switch (incEvent.getEvent()) {
                case ExcludeDone:
                    // Let our users know we're gone!
                    updateStatus(ThingStatus.REMOVED, ThingStatusDetail.NONE, "Node was excluded from the controller");

                    // Stop polling
                    if (pollingJob != null) {
                        pollingJob.cancel(true);
                        pollingJob = null;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private void updateNodeNeighbours() {
        if (controllerHandler == null) {
            logger.debug("NODE {}: Updating node neighbours. Controller not found.", nodeId);
            return;
        }

        ZWaveNode node = controllerHandler.getNode(nodeId);
        if (node == null) {
            logger.debug("NODE {}: Updating node neighbours. Node not found.", nodeId);
            return;
        }

        String neighbours = "";
        for (Integer neighbour : node.getNeighbors()) {
            if (neighbours.length() != 0) {
                neighbours += ',';
            }
            neighbours += neighbour;
        }
        updateProperty(ZWaveBindingConstants.PROPERTY_NEIGHBOURS, neighbours);
    }

    private void updateNodeProperties() {
        if (controllerHandler == null) {
            logger.debug("NODE {}: Updating node properties. Controller not found.", nodeId);
            return;
        }

        ZWaveNode node = controllerHandler.getNode(nodeId);
        if (node == null) {
            logger.debug("NODE {}: Updating node properties. Node not found.", nodeId);
            return;
        }

        logger.debug("NODE {}: Updating node properties.", nodeId);

        // Update property information about this device
        Map<String, String> properties = editProperties();

        updateProperty(ZWaveBindingConstants.PROPERTY_NODEID, Integer.toString(nodeId));

        logger.debug("NODE {}: Updating node properties. MAN={}", nodeId, node.getManufacturer());
        if (node.getManufacturer() != Integer.MAX_VALUE) {
            logger.debug("NODE {}: Updating node properties. MAN={}. SET. Was {}", nodeId, node.getManufacturer(),
                    properties.get(ZWaveBindingConstants.PROPERTY_MANUFACTURER));
            properties.put(ZWaveBindingConstants.PROPERTY_MANUFACTURER, Integer.toString(node.getManufacturer()));
        }
        if (node.getDeviceType() != Integer.MAX_VALUE) {
            properties.put(ZWaveBindingConstants.PROPERTY_DEVICETYPE, Integer.toString(node.getDeviceType()));
        }
        if (node.getDeviceId() != Integer.MAX_VALUE) {
            properties.put(ZWaveBindingConstants.PROPERTY_DEVICEID, Integer.toString(node.getDeviceId()));
        }
        properties.put(ZWaveBindingConstants.PROPERTY_VERSION, node.getApplicationVersion());

        properties.put(ZWaveBindingConstants.PROPERTY_CLASS_BASIC,
                node.getDeviceClass().getBasicDeviceClass().toString());
        properties.put(ZWaveBindingConstants.PROPERTY_CLASS_GENERIC,
                node.getDeviceClass().getGenericDeviceClass().toString());
        properties.put(ZWaveBindingConstants.PROPERTY_CLASS_SPECIFIC,
                node.getDeviceClass().getSpecificDeviceClass().toString());
        properties.put(ZWaveBindingConstants.PROPERTY_LISTENING, Boolean.toString(node.isListening()));
        properties.put(ZWaveBindingConstants.PROPERTY_FREQUENT, Boolean.toString(node.isFrequentlyListening()));
        properties.put(ZWaveBindingConstants.PROPERTY_BEAMING, Boolean.toString(node.isBeaming()));
        properties.put(ZWaveBindingConstants.PROPERTY_ROUTING, Boolean.toString(node.isRouting()));
        properties.put(ZWaveBindingConstants.PROPERTY_USINGSECURITY, Boolean.toString(node.isSecure()));

        // If this is a Z-Wave Plus device, then also add its class
        ZWavePlusCommandClass cmdClassZWavePlus = (ZWavePlusCommandClass) node
                .getCommandClass(CommandClass.COMMAND_CLASS_ZWAVEPLUS_INFO);
        if (cmdClassZWavePlus != null) {
            properties.put(ZWaveBindingConstants.PROPERTY_ZWPLUS_DEVICETYPE, cmdClassZWavePlus.getNodeType());
            properties.put(ZWaveBindingConstants.PROPERTY_ZWPLUS_ROLETYPE, cmdClassZWavePlus.getRoleType());
        }

        // Must loop over the new properties since we might have added data
        boolean update = false;
        Map<String, String> originalProperties = editProperties();
        for (String property : properties.keySet()) {
            if ((originalProperties.get(property) == null
                    || originalProperties.get(property).equals(properties.get(property)) == false)) {
                update = true;
                break;
            }
        }

        properties.remove("zwave_lastheat");
        update = true;

        if (update == true) {
            logger.debug("NODE {}: Properties synchronised", nodeId);
            updateProperties(properties);
        }

        // We need to synchronise the configuration between the ZWave library and ESH.
        // This is especially important when the device is first added as the ESH representation of the config
        // will be set to defaults. We will also not have any defaults for association groups, wakeup etc.
        Configuration config = editConfiguration();

        // Process CONFIGURATION
        ZWaveConfigurationCommandClass configurationCommandClass = (ZWaveConfigurationCommandClass) node
                .getCommandClass(CommandClass.COMMAND_CLASS_CONFIGURATION);
        if (configurationCommandClass != null) {
            // Iterate over all parameters and process
            for (int paramId : configurationCommandClass.getParameters().keySet()) {
                ZWaveConfigurationParameter parameter = configurationCommandClass.getParameter(paramId);
                updateConfigurationParameter(config, parameter.getIndex(), parameter.getSize(), parameter.getValue());
            }
        }

        // Process ASSOCIATION
        for (ZWaveAssociationGroup group : node.getAssociationGroups().values()) {
            List<String> members = new ArrayList<String>();

            // Build the configuration value
            for (ZWaveAssociation groupMember : group.getAssociations()) {
                logger.debug("NODE {}: Update ASSOCIATION group_{}: Adding node_{}_{}", nodeId, group.getIndex(),
                        groupMember.getNode(), groupMember.getEndpoint());
                members.add("node_" + groupMember.getNode() + "_" + groupMember.getEndpoint());
            }

            config.put("group_" + group.getIndex(), members);
        }

        // Process WAKE_UP
        ZWaveWakeUpCommandClass wakeupCommandClass = (ZWaveWakeUpCommandClass) node
                .getCommandClass(CommandClass.COMMAND_CLASS_WAKE_UP);
        if (wakeupCommandClass != null) {
            config.put(ZWaveBindingConstants.CONFIGURATION_WAKEUPINTERVAL, wakeupCommandClass.getInterval());
            config.put(ZWaveBindingConstants.CONFIGURATION_WAKEUPNODE, wakeupCommandClass.getTargetNodeId());
        }

        // Process SWITCH_ALL
        ZWaveSwitchAllCommandClass switchallCommandClass = (ZWaveSwitchAllCommandClass) node
                .getCommandClass(CommandClass.COMMAND_CLASS_SWITCH_ALL);
        if (switchallCommandClass != null) {
            if (switchallCommandClass.getMode() != null) {
                config.put(ZWaveBindingConstants.CONFIGURATION_SWITCHALLMODE,
                        switchallCommandClass.getMode().getMode());
            }
        }

        // Process NODE_NAMING
        ZWaveNodeNamingCommandClass nodenamingCommandClass = (ZWaveNodeNamingCommandClass) node
                .getCommandClass(CommandClass.COMMAND_CLASS_NODE_NAMING);
        if (nodenamingCommandClass != null) {
            if (nodenamingCommandClass.getLocation() != null) {
                config.put(ZWaveBindingConstants.CONFIGURATION_NODELOCATION, nodenamingCommandClass.getLocation());
            }
            if (nodenamingCommandClass.getName() != null) {
                config.put(ZWaveBindingConstants.CONFIGURATION_NODENAME, nodenamingCommandClass.getName());
            }
        }

        // Only update if configuration has changed
        Configuration originalConfig = editConfiguration();

        update = false;
        for (String property : config.getProperties().keySet()) {
            if (config.get(property) != null && config.get(property).equals(originalConfig.get(property)) == false) {
                update = true;
                break;
            }
        }

        if (update == true) {
            logger.debug("NODE {}: Properties synchronised", nodeId);
            updateConfiguration(config);
        }
    }

    private boolean updateConfigurationParameter(Configuration configuration, int paramIndex, int paramSize,
            int paramValue) {

        boolean cfgUpdated = false;

        // logger.debug("NODE {}: Config about to update {} parameters...", nodeId, configuration.keySet().size());
        for (String key : configuration.keySet()) {
            // logger.debug("NODE {}: Processing {}", nodeId, key);
            String[] cfg = key.split("_");
            // Check this is a config parameter
            if (!"config".equals(cfg[0])) {
                continue;
            }
            // logger.debug("NODE {}: Processing {} len={}", nodeId, key, cfg.length);

            if (cfg.length < 3) {
                // logger.debug("NODE {}: Configuration invalid {}", nodeId, key);
                continue;
            }

            // logger.debug("NODE {}: Processing {} - id = '{}'", nodeId, key, cfg[1]);

            // Check this is for the right parameter
            if (Integer.parseInt(cfg[1]) != paramIndex) {
                continue;
            }

            // logger.debug("NODE {}: Processing {} - size = '{}'", nodeId, key, cfg[2]);

            // Get the size
            int size = Integer.parseInt(cfg[2]);
            if (size != paramSize) {
                // logger.debug("NODE {}: Size error {}<>{} from {}", nodeId, size, paramSize, key);
                continue;
            }

            // Get the bitmask
            int bitmask = 0xffffffff;
            if (cfg.length >= 4 && cfg[3].length() == 8) {
                // logger.debug("NODE {}: Processing {} - bitmask = '{}'", nodeId, key, cfg[3]);
                try {
                    bitmask = Integer.parseInt(cfg[3], 16);
                    // logger.debug("NODE {}: Processing {} - dec = '{}'", nodeId, key, bitmask);

                } catch (NumberFormatException e) {
                    // logger.error("NODE {}: Error parsing bitmask for {}", nodeId, key);
                }
            }

            int value = paramValue & bitmask;
            // logger.debug("NODE {}: Sub-parameter {} is {}", nodeId, key, String.format("%08X", value));

            // logger.debug("NODE {}: Pre-processing {}>>{}", nodeId, String.format("%08X", value),
            // String.format("%08X", bitmask));

            // Shift the value
            int bits = bitmask;
            while ((bits & 0x01) == 0) {
                value = value >> 1;
                bits = bits >> 1;
            }

            // And the bitmask to get rid of any sign extension
            // value &= bits;

            // logger.debug("NODE {}: Post-processing {}>>{}", nodeId, String.format("%08X", value),
            // String.format("%08X", bitmask));

            // logger.debug("NODE {}: Sub-parameter setting {} is {} [{}]", nodeId, key, String.format("%08X", value),
            // value);

            cfgUpdated = true;
            configuration.put(key, value);
            pendingCfg.remove(key);
        }

        return cfgUpdated;
    }

    private class ZWaveConfigSubParameter {
        private int bitmask = 0;
        private int value = 0;

        public void addBitmask(int bitmask, int value) {
            if (bitmask == 0) {
                return;
            }

            // Clear the relevant bits
            this.value &= this.value & ~bitmask;

            // Shift the value
            int bits = bitmask;
            while ((bits & 0x01) == 0) {
                value = value << 1;
                bits = bits >> 1;
            }

            // Add the new sub-parameter value
            this.value |= value & bitmask;
            this.bitmask |= bitmask;
        }

        /**
         * Get the updated value, given the current value, and updating it based on the internal bitmask/value
         *
         * @param value
         * @return
         */
        public int getValue(int value) {
            return (value & ~this.bitmask) + this.value;
        }
    }

    @Override
    public Collection<ConfigStatusMessage> getConfigStatus() {
        Collection<ConfigStatusMessage> configStatus = new ArrayList<>();

        // Loop through the pending list
        // TODO: Do we want to handle other states?????
        for (String config : pendingCfg.keySet()) {
            configStatus.add(ConfigStatusMessage.Builder.pending(config).build());
        }

        return configStatus;
    }

    /**
     * Return an ISO 8601 combined date and time string for current date/time
     *
     * @return String with format "yyyy-MM-dd'T'HH:mm:ss'Z'"
     */
    private static String getISO8601StringForCurrentDate() {
        Date now = new Date();
        return getISO8601StringForDate(now);
    }

    /**
     * Return an ISO 8601 combined date and time string for specified date/time
     *
     * @param date
     *            Date
     * @return String with format "yyyy-MM-dd'T'HH:mm:ss'Z'"
     */
    private static String getISO8601StringForDate(Date date) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        return dateFormat.format(date);
    }
}
