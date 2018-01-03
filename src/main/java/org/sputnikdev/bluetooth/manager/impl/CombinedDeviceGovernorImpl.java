package org.sputnikdev.bluetooth.manager.impl;

/*-
 * #%L
 * org.sputnikdev:bluetooth-manager
 * %%
 * Copyright (C) 2017 Sputnik Dev
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.Filter;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.BluetoothObjectType;
import org.sputnikdev.bluetooth.manager.BluetoothObjectVisitor;
import org.sputnikdev.bluetooth.manager.BluetoothSmartDeviceListener;
import org.sputnikdev.bluetooth.manager.CharacteristicGovernor;
import org.sputnikdev.bluetooth.manager.CombinedGovernor;
import org.sputnikdev.bluetooth.manager.DeviceDiscoveryListener;
import org.sputnikdev.bluetooth.manager.DeviceGovernor;
import org.sputnikdev.bluetooth.manager.DiscoveredDevice;
import org.sputnikdev.bluetooth.manager.GattCharacteristic;
import org.sputnikdev.bluetooth.manager.GattService;
import org.sputnikdev.bluetooth.manager.GenericBluetoothDeviceListener;
import org.sputnikdev.bluetooth.manager.GovernorListener;
import org.sputnikdev.bluetooth.manager.NotReadyException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

class CombinedDeviceGovernorImpl implements DeviceGovernor, CombinedGovernor,
        BluetoothObjectGovernor, DeviceDiscoveryListener {

    private Logger logger = LoggerFactory.getLogger(DeviceGovernorImpl.class);

    private final BluetoothManagerImpl bluetoothManager;
    private final URL url;
    private final Map<URL, DeviceGovernorHandler> governors = new ConcurrentHashMap<>();
    private final List<GovernorListener> governorListeners = new CopyOnWriteArrayList<>();
    private final List<GenericBluetoothDeviceListener> genericBluetoothDeviceListeners = new CopyOnWriteArrayList<>();
    private final List<BluetoothSmartDeviceListener> bluetoothSmartDeviceListeners = new CopyOnWriteArrayList<>();

    private final AtomicLong ready = new AtomicLong();
    private final AtomicLong online = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong connected = new AtomicLong();
    private final AtomicLong servicesResolved = new AtomicLong();

    private final SortedSet<DeviceGovernorHandler> sortedByDistanceGovernors =
            new TreeSet<>(Comparator.comparingDouble(handler -> handler.distance));
    private DeviceGovernor nearest;
    private final ReentrantLock rssiLock = new ReentrantLock();

    private final AtomicInteger governorsCount = new AtomicInteger();
    private final AtomicReference<DeviceGovernor> connectedGovernor = new AtomicReference<>();

    private int bluetoothClass;
    private boolean bleEnabled;
    private String name;
    private String alias;
    private int onlineTimeout;
    private short rssi;
    private boolean rssiFilteringEnabled = true;
    private long rssiReportingRate = 1000;
    private short measuredTxPower;
    private double signalPropagationExponent = DeviceGovernorImpl.DEAFULT_SIGNAL_PROPAGATION_EXPONENT;
    private Date lastChanged;

    private boolean connectionControl;
    private boolean blockedControl;

    CombinedDeviceGovernorImpl(BluetoothManagerImpl bluetoothManager, URL url) {
        this.bluetoothManager = bluetoothManager;
        this.url = url;
    }

    @Override
    public int getBluetoothClass() throws NotReadyException {
        return bluetoothClass;
    }

    @Override
    public boolean isBleEnabled() throws NotReadyException {
        return bleEnabled;
    }

    @Override
    public String getName() throws NotReadyException {
        return name != null ? name : url.getDeviceAddress();
    }

    @Override
    public String getAlias() throws NotReadyException {
        return alias;
    }

    @Override
    public void setAlias(String alias) throws NotReadyException {
        governors.values().forEach(deviceGovernorHandler -> {
            if (deviceGovernorHandler.deviceGovernor.isReady()) {
                deviceGovernorHandler.deviceGovernor.setAlias(alias);
            }
        });
    }

    @Override
    public String getDisplayName() throws NotReadyException {
        String alias = getAlias();
        return alias != null ? alias : getName();
    }

    @Override
    public boolean isConnected() throws NotReadyException {
        return connected.get() > 0;
    }

    @Override
    public boolean getConnectionControl() {
        return connectionControl;
    }

    @Override
    public void setConnectionControl(boolean connected) {
        connectionControl = connected;
        if (connected) {
            DeviceGovernor nearest = this.nearest;
            if (nearest != null) {
                nearest.setConnectionControl(true);
            }
        } else {
            governors.values().forEach(deviceGovernorHandler -> deviceGovernorHandler
                    .deviceGovernor.setConnectionControl(false));
        }
    }

    @Override
    public boolean isBlocked() throws NotReadyException {
        return blocked.get() > 0;
    }

    @Override
    public boolean getBlockedControl() {
        return blockedControl;
    }

    @Override
    public void setBlockedControl(boolean blocked) {
        blockedControl = blocked;
        governors.values().forEach(
            deviceGovernorHandler -> deviceGovernorHandler.deviceGovernor.setBlockedControl(blocked));
    }

    @Override
    public boolean isOnline() {
        return online.get() > 0;
    }

    @Override
    public int getOnlineTimeout() {
        return onlineTimeout;
    }

    @Override
    public void setOnlineTimeout(int timeout) {
        onlineTimeout = timeout;
        governors.values().forEach(
            deviceGovernorHandler -> deviceGovernorHandler.deviceGovernor.setOnlineTimeout(timeout));
    }

    @Override
    public short getRSSI() throws NotReadyException {
        return rssi;
    }

    @Override
    public short getTxPower() {
        return 0;
    }

    @Override
    public short getMeasuredTxPower() {
        return measuredTxPower;
    }

    @Override
    public void setMeasuredTxPower(short txPower) {
        measuredTxPower = txPower;
        governors.values().forEach(
            deviceGovernorHandler -> deviceGovernorHandler.deviceGovernor.setMeasuredTxPower(txPower));
    }

    @Override
    public double getSignalPropagationExponent() {
        return signalPropagationExponent;
    }

    @Override
    public void setSignalPropagationExponent(double exponent) {
        signalPropagationExponent = exponent;
        governors.values().forEach(
            deviceGovernorHandler -> deviceGovernorHandler.deviceGovernor.setSignalPropagationExponent(exponent));
    }

    @Override
    public double getEstimatedDistance() {
        DeviceGovernor governor = nearest;
        return governor != null ? governor.getEstimatedDistance() : 0.0;
    }

    @Override
    public URL getLocation() {
        DeviceGovernor governor = nearest;
        return governor != null ? governor.getURL().getAdapterURL() : null;
    }

    @Override
    public void addBluetoothSmartDeviceListener(BluetoothSmartDeviceListener listener) {
        bluetoothSmartDeviceListeners.add(listener);
    }

    @Override
    public void removeBluetoothSmartDeviceListener(BluetoothSmartDeviceListener listener) {
        bluetoothSmartDeviceListeners.remove(listener);
    }

    @Override
    public void addGenericBluetoothDeviceListener(GenericBluetoothDeviceListener listener) {
        genericBluetoothDeviceListeners.add(listener);
    }

    @Override
    public void removeGenericBluetoothDeviceListener(GenericBluetoothDeviceListener listener) {
        genericBluetoothDeviceListeners.remove(listener);
    }

    @Override
    public void addGovernorListener(GovernorListener listener) {
        governorListeners.add(listener);
    }

    @Override
    public void removeGovernorListener(GovernorListener listener) {
        governorListeners.remove(listener);
    }

    @Override
    public Map<URL, List<CharacteristicGovernor>> getServicesToCharacteristicsMap() throws NotReadyException {
        return null;
    }

    @Override
    public List<URL> getCharacteristics() throws NotReadyException {
        return null;
    }

    @Override
    public List<CharacteristicGovernor> getCharacteristicGovernors() throws NotReadyException {
        return null;
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public boolean isReady() {
        return ready.get() > 0;
    }

    @Override
    public BluetoothObjectType getType() {
        return BluetoothObjectType.DEVICE;
    }

    @Override
    public Date getLastActivity() {
        return lastChanged;
    }

    @Override
    public void accept(BluetoothObjectVisitor visitor) throws Exception {
        visitor.visit(this);
    }

    @Override
    public void discovered(DiscoveredDevice discoveredDevice) {
        registerGovernor(discoveredDevice.getURL());
    }

    @Override
    public void deviceLost(URL url) { /* do nothing */ }

    @Override
    public void init() {
        bluetoothManager.addDeviceDiscoveryListener(this);
        bluetoothManager.getRegisteredGovernors().forEach(this::registerGovernor);
        bluetoothManager.getDiscoveredDevices().stream().map(DiscoveredDevice::getURL).forEach(this::registerGovernor);
    }

    @Override
    public void update() { /* do nothing */ }

    @Override
    public void reset() { /* do nothing */ }

    @Override
    public void dispose() {
        bluetoothManager.removeDeviceDiscoveryListener(this);
        governors.clear();
        governorListeners.clear();
        genericBluetoothDeviceListeners.clear();
        bluetoothSmartDeviceListeners.clear();
        sortedByDistanceGovernors.clear();
    }

    @Override
    public void setRssiFilter(Filter<Short> filter) {
        throw new IllegalStateException("Not supported by group governor");
    }

    @Override
    public Filter<Short> getRssiFilter() {
        return null;
    }

    @Override
    public boolean isRssiFilteringEnabled() {
        return rssiFilteringEnabled;
    }

    @Override
    public void setRssiFilteringEnabled(boolean enabled) {
        rssiFilteringEnabled = enabled;
        governors.values().forEach(
            deviceGovernorHandler -> deviceGovernorHandler.deviceGovernor.setRssiFilteringEnabled(enabled));
    }

    @Override
    public void setRssiReportingRate(long rate) {
        rssiReportingRate = rate;
        governors.values().forEach(
            deviceGovernorHandler -> deviceGovernorHandler.deviceGovernor.setRssiReportingRate(rate));
    }

    @Override
    public long getRssiReportingRate() {
        return rssiReportingRate;
    }

    private void registerGovernor(URL url) {
        if (governorsCount.get() > 63) {
            throw new IllegalStateException("Combined Device Governor can only span upto 63 device governors.");
        }
        if (url.isDevice() && this.url.getDeviceAddress().equals(url.getDeviceAddress())
                && !COMBINED_ADDRESS.equals(url.getAdapterAddress())) {
            governors.computeIfAbsent(url, newUrl -> {
                DeviceGovernor deviceGovernor = BluetoothManagerFactory.getManager().getDeviceGovernor(url);
                int index = governorsCount.getAndIncrement();
                return new DeviceGovernorHandler(deviceGovernor, index);
            });
        }
    }

    private void updateLastUpdated(Date lastActivity) {
        if (lastChanged == null || lastChanged.before(lastActivity)) {
            lastChanged = lastActivity;
            BluetoothManagerUtils.safeForEachError(governorListeners, listener -> {
                listener.lastUpdatedChanged(lastActivity);
            }, logger, "Execution error of a governor listener: last changed");
        }
    }

    private void updateRssi(short newRssi) {
        rssi = newRssi;
        BluetoothManagerUtils.safeForEachError(genericBluetoothDeviceListeners, listener -> {
            listener.rssiChanged(newRssi);
        }, logger, "Execution error of a RSSI listener");
    }

    private final class DeviceGovernorHandler
        implements GovernorListener, BluetoothSmartDeviceListener, GenericBluetoothDeviceListener {

        private final DeviceGovernor deviceGovernor;
        private final int index;
        private double distance = Double.MAX_VALUE;

        private DeviceGovernorHandler(DeviceGovernor deviceGovernor, int index) {
            this.deviceGovernor = deviceGovernor;
            this.index = index;
            this.deviceGovernor.addBluetoothSmartDeviceListener(this);
            this.deviceGovernor.addGenericBluetoothDeviceListener(this);
            this.deviceGovernor.addGovernorListener(this);

            if (deviceGovernor.isReady()) {
                name = deviceGovernor.getName();
                int deviceBluetoothClass = deviceGovernor.getBluetoothClass();
                if (deviceBluetoothClass != 0) {
                    bluetoothClass = deviceBluetoothClass;
                }
                bleEnabled |= deviceGovernor.isBleEnabled();

                String deviceAlias = deviceGovernor.getAlias();
                if (deviceAlias != null) {
                    alias = deviceAlias;
                }
                updateRssi(deviceGovernor.getRSSI());
                ready(true);
            }
            deviceGovernor.setOnlineTimeout(onlineTimeout);
            deviceGovernor.setConnectionControl(connectionControl);
            deviceGovernor.setBlockedControl(blockedControl);
            deviceGovernor.setRssiFilteringEnabled(rssiFilteringEnabled);
            deviceGovernor.setRssiReportingRate(rssiReportingRate);
            deviceGovernor.setSignalPropagationExponent(signalPropagationExponent);
            deviceGovernor.setMeasuredTxPower(measuredTxPower);

            Date lastActivity = deviceGovernor.getLastActivity();
            if (lastActivity != null) {
                updateLastUpdated(lastActivity);
            }
        }

        @Override
        public void connected() {
            notifyConnected(true);
        }

        @Override
        public void disconnected() {
            notifyConnected(false);
        }

        @Override
        public void servicesResolved(List<GattService> gattServices) {
            BluetoothManagerUtils.setState(servicesResolved, index, true,
                () -> {
                    notifyServicesResolved(gattServices);
                }, () -> {
                    notifyServicesUnresolved();
                    notifyServicesResolved(gattServices);
                }
            );
        }

        @Override
        public void servicesUnresolved() {
            BluetoothManagerUtils.setState(servicesResolved, index, false, () -> {
                BluetoothManagerUtils.safeForEachError(bluetoothSmartDeviceListeners,
                    BluetoothSmartDeviceListener::servicesUnresolved,
                    logger, "Execution error of a service resolved listener");
            });
        }

        @Override
        public void online() {
            notifyOnline(true);
        }

        @Override
        public void offline() {
            sortedByDistanceGovernors.remove(this);
            notifyOnline(false);
        }

        @Override
        public void blocked(boolean newState) {
            BluetoothManagerUtils.setState(blocked, index, newState, () -> {
                BluetoothManagerUtils.safeForEachError(genericBluetoothDeviceListeners, listener -> {
                    listener.blocked(newState);
                }, logger, "Execution error of a Blocked listener");
            });
        }

        @Override
        public void rssiChanged(short newRssi) {
            try {
                if (rssiLock.tryLock(50, TimeUnit.MILLISECONDS)) {
                    try {
                        sortedByDistanceGovernors.remove(this);
                        distance = deviceGovernor.getEstimatedDistance();
                        sortedByDistanceGovernors.add(this);
                        nearest = sortedByDistanceGovernors.first().deviceGovernor;
                        if (deviceGovernor == nearest) {
                            notifyRSSIChanged(newRssi);
                        }
                    } finally {
                        rssiLock.unlock();
                    }
                }
            } catch (InterruptedException ignore) {
                logger.warn("Could not aquire a lock to update RSSI");
            }
        }

        @Override
        public void ready(boolean isReady) {
            if (!isReady) {
                sortedByDistanceGovernors.remove(this);
            }
            notifyReady(isReady);
        }

        @Override
        public void lastUpdatedChanged(Date lastActivity) {
            updateLastUpdated(lastActivity);
        }

        private void notifyOnline(boolean newState) {
            BluetoothManagerUtils.setState(online, index, newState, () -> {
                BluetoothManagerUtils.safeForEachError(genericBluetoothDeviceListeners, listener -> {
                    if (newState) {
                        listener.online();
                    } else {
                        listener.offline();
                    }
                }, logger, "Execution error of an online listener");
            });
        }

        private void notifyReady(boolean newState) {
            BluetoothManagerUtils.setState(ready, index, newState, () -> {
                BluetoothManagerUtils.safeForEachError(governorListeners, listener -> {
                    listener.ready(newState);
                }, logger, "Execution error of a governor listener: ready");
            });
        }

        private void notifyConnected(boolean connected) {
            BluetoothManagerUtils.safeForEachError(bluetoothSmartDeviceListeners, listener -> {
                if (connected) {
                    listener.connected();
                } else {
                    listener.disconnected();
                }
            }, logger, "Execution error of a connection listener");
        }

        private void notifyServicesResolved(List<GattService> services) {
            List<GattService> combinedServices = new ArrayList<>(services.size());
            services.forEach(service -> {
                List<GattCharacteristic> combinedCharacteristics =
                        new ArrayList<>(service.getCharacteristics().size());

                service.getCharacteristics().forEach(characteristic -> {
                    GattCharacteristic combinedCharacteristic = new GattCharacteristic(
                            characteristic.getURL().copyWithAdapter(COMBINED_ADDRESS), characteristic.getFlags());
                    combinedCharacteristics.add(combinedCharacteristic);
                });

                GattService combinedService = new GattService(
                        service.getURL().copyWithAdapter(COMBINED_ADDRESS), combinedCharacteristics);
                combinedServices.add(combinedService);
            });
            BluetoothManagerUtils.safeForEachError(bluetoothSmartDeviceListeners, listener -> {
                listener.servicesResolved(combinedServices);
            }, logger, "Execution error of a service resolved listener");
        }

        private void notifyServicesUnresolved() {
            BluetoothManagerUtils.safeForEachError(bluetoothSmartDeviceListeners,
                BluetoothSmartDeviceListener::servicesUnresolved,
                logger, "Execution error of a service resolved listener");
        }

        private void notifyRSSIChanged(short rssi) {
            BluetoothManagerUtils.safeForEachError(genericBluetoothDeviceListeners, listener -> {
                listener.rssiChanged(rssi);
            }, logger, "Execution error of a RSSI listener");
        }
    }

}
