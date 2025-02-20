/*
 * Copyright 2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.segmentrouting.grouphandler;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.slf4j.LoggerFactory.getLogger;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.MplsLabel;
import org.onlab.util.KryoNamespace;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultNextObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.NextObjective;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.flowobjective.ObjectiveContext;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.net.group.DefaultGroupKey;
import org.onosproject.net.group.GroupKey;
import org.onosproject.net.link.LinkService;
import org.onosproject.segmentrouting.config.DeviceConfigNotFoundException;
import org.onosproject.segmentrouting.config.DeviceProperties;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.slf4j.Logger;

/**
 * Default ECMP group handler creation module. This component creates a set of
 * ECMP groups for every neighbor that this device is connected to based on
 * whether the current device is an edge device or a transit device.
 */
public class DefaultGroupHandler {
    protected static final Logger log = getLogger(DefaultGroupHandler.class);

    protected final DeviceId deviceId;
    protected final ApplicationId appId;
    protected final DeviceProperties deviceConfig;
    protected final List<Integer> allSegmentIds;
    protected int nodeSegmentId = -1;
    protected boolean isEdgeRouter = false;
    protected MacAddress nodeMacAddr = null;
    protected LinkService linkService;
    protected FlowObjectiveService flowObjectiveService;

    protected ConcurrentHashMap<DeviceId, Set<PortNumber>> devicePortMap =
            new ConcurrentHashMap<>();
    protected ConcurrentHashMap<PortNumber, DeviceId> portDeviceMap =
            new ConcurrentHashMap<>();
    protected EventuallyConsistentMap<
        NeighborSetNextObjectiveStoreKey, Integer> nsNextObjStore = null;
    protected EventuallyConsistentMap<
            SubnetNextObjectiveStoreKey, Integer> subnetNextObjStore = null;

    protected KryoNamespace.Builder kryo = new KryoNamespace.Builder()
            .register(URI.class).register(HashSet.class)
            .register(DeviceId.class).register(PortNumber.class)
            .register(NeighborSet.class).register(PolicyGroupIdentifier.class)
            .register(PolicyGroupParams.class)
            .register(GroupBucketIdentifier.class)
            .register(GroupBucketIdentifier.BucketOutputType.class);

    protected DefaultGroupHandler(DeviceId deviceId, ApplicationId appId,
                                  DeviceProperties config,
                                  LinkService linkService,
                                  FlowObjectiveService flowObjService,
                                  EventuallyConsistentMap<
                                          NeighborSetNextObjectiveStoreKey,
                                          Integer> nsNextObjStore,
                                  EventuallyConsistentMap<SubnetNextObjectiveStoreKey,
                                          Integer> subnetNextObjStore) {
        this.deviceId = checkNotNull(deviceId);
        this.appId = checkNotNull(appId);
        this.deviceConfig = checkNotNull(config);
        this.linkService = checkNotNull(linkService);
        this.allSegmentIds = checkNotNull(config.getAllDeviceSegmentIds());
        try {
            this.nodeSegmentId = config.getSegmentId(deviceId);
            this.isEdgeRouter = config.isEdgeDevice(deviceId);
            this.nodeMacAddr = checkNotNull(config.getDeviceMac(deviceId));
        } catch (DeviceConfigNotFoundException e) {
            log.warn(e.getMessage()
                    + " Skipping value assignment in DefaultGroupHandler");
        }
        this.flowObjectiveService = flowObjService;
        this.nsNextObjStore = nsNextObjStore;
        this.subnetNextObjStore = subnetNextObjStore;

        populateNeighborMaps();
    }

    /**
     * Creates a group handler object based on the type of device. If device is
     * of edge type it returns edge group handler, else it returns transit group
     * handler.
     *
     * @param deviceId device identifier
     * @param appId application identifier
     * @param config interface to retrieve the device properties
     * @param linkService link service object
     * @param flowObjService flow objective service object
     * @param nsNextObjStore NeighborSet next objective store map
     * @param subnetNextObjStore subnet next objective store map
     * @throws DeviceConfigNotFoundException if the device configuration is not found
     * @return default group handler type
     */
    public static DefaultGroupHandler createGroupHandler(DeviceId deviceId,
                                                         ApplicationId appId,
                                                         DeviceProperties config,
                                                         LinkService linkService,
                                                         FlowObjectiveService flowObjService,
                                                         EventuallyConsistentMap<
                                                                 NeighborSetNextObjectiveStoreKey,
                                                                 Integer> nsNextObjStore,
                                                         EventuallyConsistentMap<SubnetNextObjectiveStoreKey,
                                                                 Integer> subnetNextObjStore)
                                                         throws DeviceConfigNotFoundException {
        // handle possible exception in the caller
        if (config.isEdgeDevice(deviceId)) {
            return new DefaultEdgeGroupHandler(deviceId, appId, config,
                                               linkService,
                                               flowObjService,
                                               nsNextObjStore,
                                               subnetNextObjStore);
        } else {
            return new DefaultTransitGroupHandler(deviceId, appId, config,
                                                  linkService,
                                                  flowObjService,
                                                  nsNextObjStore,
                                                  subnetNextObjStore);
        }
    }

    /**
     * Creates the auto created groups for this device based on the current
     * snapshot of the topology.
     */
    // Empty implementations to be overridden by derived classes
    public void createGroups() {
    }

    /**
     * Performs group creation or update procedures when a new link is
     * discovered on this device.
     *
     * @param newLink new neighbor link
     */
    public void linkUp(Link newLink, boolean isMaster) {

        if (newLink.type() != Link.Type.DIRECT) {
            log.warn("linkUp: unknown link type");
            return;
        }

        if (!newLink.src().deviceId().equals(deviceId)) {
            log.warn("linkUp: deviceId{} doesn't match with link src{}",
                     deviceId, newLink.src().deviceId());
            return;
        }

        log.info("* LinkUP: Device {} linkUp at local port {} to neighbor {}", deviceId,
                 newLink.src().port(), newLink.dst().deviceId());
        MacAddress dstMac;
        try {
            dstMac = deviceConfig.getDeviceMac(newLink.dst().deviceId());
        } catch (DeviceConfigNotFoundException e) {
            log.warn(e.getMessage() + " Aborting linkUp.");
            return;
        }

        addNeighborAtPort(newLink.dst().deviceId(),
                          newLink.src().port());
        /*if (devicePortMap.get(newLink.dst().deviceId()) == null) {
            // New Neighbor
            newNeighbor(newLink);
        } else {
            // Old Neighbor
            newPortToExistingNeighbor(newLink);
        }*/
        Set<NeighborSet> nsSet = nsNextObjStore.keySet()
                .stream()
                .filter((nsStoreEntry) -> (nsStoreEntry.deviceId().equals(deviceId)))
                .map((nsStoreEntry) -> (nsStoreEntry.neighborSet()))
                .filter((ns) -> (ns.getDeviceIds()
                        .contains(newLink.dst().deviceId())))
                .collect(Collectors.toSet());
        log.trace("linkUp: nsNextObjStore contents for device {}:",
                deviceId,
                nsSet);
        for (NeighborSet ns : nsSet) {
            // Create the new bucket to be updated
            TrafficTreatment.Builder tBuilder =
                    DefaultTrafficTreatment.builder();
            tBuilder.setOutput(newLink.src().port())
                    .setEthDst(dstMac)
                    .setEthSrc(nodeMacAddr);
            if (ns.getEdgeLabel() != NeighborSet.NO_EDGE_LABEL) {
                tBuilder.pushMpls()
                        .copyTtlOut()
                        .setMpls(MplsLabel.mplsLabel(ns.getEdgeLabel()));
            }

            Integer nextId = nsNextObjStore.
                    get(new NeighborSetNextObjectiveStoreKey(deviceId, ns));
            if (nextId != null) {
                NextObjective.Builder nextObjBuilder = DefaultNextObjective
                        .builder().withId(nextId)
                        .withType(NextObjective.Type.HASHED).fromApp(appId);

                nextObjBuilder.addTreatment(tBuilder.build());

                log.info("**linkUp in device {}: Adding Bucket "
                        + "with Port {} to next object id {} and amIMaster:{}",
                        deviceId,
                        newLink.src().port(),
                        nextId, isMaster);

                if (isMaster) {
                    NextObjective nextObjective = nextObjBuilder.
                            addToExisting(new SRNextObjectiveContext(deviceId));
                    flowObjectiveService.next(deviceId, nextObjective);
                }
            } else {
                log.warn("linkUp in device {}, but global store has no record "
                        + "for neighbor-set {}", deviceId, ns);
            }
        }
    }

    /**
     * Performs group recovery procedures when a port goes down on this device.
     *
     * @param port port number that has gone down
     */
    public void portDown(PortNumber port) {
        if (portDeviceMap.get(port) == null) {
            log.warn("portDown: unknown port");
            return;
        }

        MacAddress dstMac;
        try {
            dstMac = deviceConfig.getDeviceMac(portDeviceMap.get(port));
        } catch (DeviceConfigNotFoundException e) {
            log.warn(e.getMessage() + " Aborting portDown.");
            return;
        }

        log.debug("Device {} portDown {} to neighbor {}", deviceId, port,
                  portDeviceMap.get(port));
        /*Set<NeighborSet> nsSet = computeImpactedNeighborsetForPortEvent(portDeviceMap
                                                                                .get(port),
                                                                        devicePortMap
                                                                                .keySet());*/
        Set<NeighborSet> nsSet = nsNextObjStore.keySet()
                .stream()
                .filter((nsStoreEntry) -> (nsStoreEntry.deviceId().equals(deviceId)))
                .map((nsStoreEntry) -> (nsStoreEntry.neighborSet()))
                .filter((ns) -> (ns.getDeviceIds()
                        .contains(portDeviceMap.get(port))))
                .collect(Collectors.toSet());
        log.trace("portDown: nsNextObjStore contents for device {}:",
                  deviceId,
                  nsSet);
        for (NeighborSet ns : nsSet) {
            // Create the bucket to be removed
            TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment
                    .builder();
            tBuilder.setOutput(port)
                    .setEthDst(dstMac)
                    .setEthSrc(nodeMacAddr);
            if (ns.getEdgeLabel() != NeighborSet.NO_EDGE_LABEL) {
                tBuilder.pushMpls()
                        .copyTtlOut()
                        .setMpls(MplsLabel.mplsLabel(ns.getEdgeLabel()));
            }

            Integer nextId = nsNextObjStore.
                    get(new NeighborSetNextObjectiveStoreKey(deviceId, ns));
            if (nextId != null) {
                NextObjective.Builder nextObjBuilder = DefaultNextObjective
                        .builder().withType(NextObjective.Type.SIMPLE).withId(nextId).fromApp(appId);

                nextObjBuilder.addTreatment(tBuilder.build());

                log.info("**portDown in device {}: Removing Bucket "
                        + "with Port {} to next object id {}",
                        deviceId,
                        port,
                        nextId);
                // should do removefromexisting and only if master
                /*NextObjective nextObjective = nextObjBuilder.
                        remove(new SRNextObjectiveContext(deviceId));

                flowObjectiveService.next(deviceId, nextObjective);*/
            }

        }

        devicePortMap.get(portDeviceMap.get(port)).remove(port);
        portDeviceMap.remove(port);
    }

    /**
     * Returns the next objective associated with the neighborset.
     * If there is no next objective for this neighborset, this API
     * would create a next objective and return. Optionally metadata can be
     * passed in for the creation of the next objective.
     *
     * @param ns neighborset
     * @param meta metadata passed into the creation of a Next Objective
     * @return int if found or -1 if there are errors in the creation of the
     *          neighbor set.
     */
    public int getNextObjectiveId(NeighborSet ns, TrafficSelector meta) {
        Integer nextId = nsNextObjStore.
                get(new NeighborSetNextObjectiveStoreKey(deviceId, ns));
        if (nextId == null) {
            log.trace("getNextObjectiveId in device{}: Next objective id "
                    + "not found for {} and creating", deviceId, ns);
            log.trace("getNextObjectiveId: nsNextObjStore contents for device {}: {}",
                      deviceId,
                      nsNextObjStore.entrySet()
                      .stream()
                      .filter((nsStoreEntry) ->
                      (nsStoreEntry.getKey().deviceId().equals(deviceId)))
                      .collect(Collectors.toList()));
            createGroupsFromNeighborsets(Collections.singleton(ns), meta);
            nextId = nsNextObjStore.
                    get(new NeighborSetNextObjectiveStoreKey(deviceId, ns));
            if (nextId == null) {
                log.warn("getNextObjectiveId: unable to create next objective");
                return -1;
            } else {
                log.debug("getNextObjectiveId in device{}: Next objective id {} "
                    + "created for {}", deviceId, nextId, ns);
            }
        } else {
            log.trace("getNextObjectiveId in device{}: Next objective id {} "
                    + "found for {}", deviceId, nextId, ns);
        }
        return nextId;
    }

    /**
     * Returns the next objective associated with the subnet.
     * If there is no next objective for this subnet, this API
     * would create a next objective and return.
     *
     * @param prefix subnet information
     * @return int if found or -1
     */
    public int getSubnetNextObjectiveId(IpPrefix prefix) {
        Integer nextId = subnetNextObjStore.
                get(new SubnetNextObjectiveStoreKey(deviceId, prefix));

        return (nextId != null) ? nextId : -1;
    }

    /**
     * Checks if the next objective ID (group) for the neighbor set exists or not.
     *
     * @param ns neighbor set to check
     * @return true if it exists, false otherwise
     */
    public boolean hasNextObjectiveId(NeighborSet ns) {
        Integer nextId = nsNextObjStore.
                get(new NeighborSetNextObjectiveStoreKey(deviceId, ns));
        if (nextId == null) {
            return false;
        }

        return true;
    }

    // Empty implementation
    protected void newNeighbor(Link newLink) {
    }

    // Empty implementation
    protected void newPortToExistingNeighbor(Link newLink) {
    }

    // Empty implementation
    protected Set<NeighborSet>
        computeImpactedNeighborsetForPortEvent(DeviceId impactedNeighbor,
                                               Set<DeviceId> updatedNeighbors) {
        return null;
    }

    private void populateNeighborMaps() {
        Set<Link> outgoingLinks = linkService.getDeviceEgressLinks(deviceId);
        for (Link link : outgoingLinks) {
            if (link.type() != Link.Type.DIRECT) {
                continue;
            }
            addNeighborAtPort(link.dst().deviceId(), link.src().port());
        }
    }

    protected void addNeighborAtPort(DeviceId neighborId,
                                     PortNumber portToNeighbor) {
        // Update DeviceToPort database
        log.debug("Device {} addNeighborAtPort: neighbor {} at port {}",
                  deviceId, neighborId, portToNeighbor);
        Set<PortNumber> ports = Collections
                .newSetFromMap(new ConcurrentHashMap<PortNumber, Boolean>());
        ports.add(portToNeighbor);
        Set<PortNumber> portnums = devicePortMap.putIfAbsent(neighborId, ports);
        if (portnums != null) {
            portnums.add(portToNeighbor);
        }

        // Update portToDevice database
        DeviceId prev = portDeviceMap.putIfAbsent(portToNeighbor, neighborId);
        if (prev != null) {
            log.warn("Device: {} port: {} has neighbor: {}. NOT updating "
                    + "to neighbor: {}", deviceId, portToNeighbor, prev, neighborId);
        }
    }

    protected Set<Set<DeviceId>> getPowerSetOfNeighbors(Set<DeviceId> neighbors) {
        List<DeviceId> list = new ArrayList<>(neighbors);
        Set<Set<DeviceId>> sets = new HashSet<>();
        // get the number of elements in the neighbors
        int elements = list.size();
        // the number of members of a power set is 2^n
        // including the empty set
        int powerElements = (1 << elements);

        // run a binary counter for the number of power elements
        // NOTE: Exclude empty set
        for (long i = 1; i < powerElements; i++) {
            Set<DeviceId> neighborSubSet = new HashSet<>();
            for (int j = 0; j < elements; j++) {
                if ((i >> j) % 2 == 1) {
                    neighborSubSet.add(list.get(j));
                }
            }
            sets.add(neighborSubSet);
        }
        return sets;
    }

    private boolean isSegmentIdSameAsNodeSegmentId(DeviceId deviceId, int sId) {
        int segmentId;
        try {
            segmentId = deviceConfig.getSegmentId(deviceId);
        } catch (DeviceConfigNotFoundException e) {
            log.warn(e.getMessage() + " Aborting isSegmentIdSameAsNodeSegmentId.");
            return false;
        }

        return segmentId == sId;
    }

    protected List<Integer> getSegmentIdsTobePairedWithNeighborSet(Set<DeviceId> neighbors) {

        List<Integer> nsSegmentIds = new ArrayList<>();

        // Always pair up with no edge label
        // If (neighbors.size() == 1) {
        nsSegmentIds.add(-1);
        // }

        // Filter out SegmentIds matching with the
        // nodes in the combo
        for (Integer sId : allSegmentIds) {
            if (sId.equals(nodeSegmentId)) {
                continue;
            }
            boolean filterOut = false;
            // Check if the edge label being set is of
            // any node in the Neighbor set
            for (DeviceId deviceId : neighbors) {
                if (isSegmentIdSameAsNodeSegmentId(deviceId, sId)) {
                    filterOut = true;
                    break;
                }
            }
            if (!filterOut) {
                nsSegmentIds.add(sId);
            }
        }
        return nsSegmentIds;
    }

    /**
     * Creates Groups from a set of NeighborSet given.
     *
     * @param nsSet a set of NeighborSet
     * @param meta metadata passed into the creation of a Next Objective
     */
    public void createGroupsFromNeighborsets(Set<NeighborSet> nsSet,
                                             TrafficSelector meta) {
        for (NeighborSet ns : nsSet) {
            int nextId = flowObjectiveService.allocateNextId();
            NextObjective.Builder nextObjBuilder = DefaultNextObjective
                    .builder().withId(nextId)
                    .withType(NextObjective.Type.HASHED).fromApp(appId);
            for (DeviceId neighborId : ns.getDeviceIds()) {
                if (devicePortMap.get(neighborId) == null) {
                    log.warn("Neighbor {} is not in the port map yet for dev:{}",
                             neighborId, deviceId);
                    return;
                } else if (devicePortMap.get(neighborId).size() == 0) {
                    log.warn("There are no ports for "
                            + "the Device {} in the port map yet", neighborId);
                    return;
                }

                MacAddress neighborMac;
                try {
                    neighborMac = deviceConfig.getDeviceMac(neighborId);
                } catch (DeviceConfigNotFoundException e) {
                    log.warn(e.getMessage() + " Aborting createGroupsFromNeighborsets.");
                    return;
                }

                for (PortNumber sp : devicePortMap.get(neighborId)) {
                    TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment
                            .builder();
                    tBuilder.setEthDst(neighborMac)
                            .setEthSrc(nodeMacAddr);
                    if (ns.getEdgeLabel() != NeighborSet.NO_EDGE_LABEL) {
                        tBuilder.pushMpls()
                                .copyTtlOut()
                                .setMpls(MplsLabel.mplsLabel(ns.getEdgeLabel()));
                    }
                    tBuilder.setOutput(sp);
                    nextObjBuilder.addTreatment(tBuilder.build());
                }
            }
            if (meta != null) {
                nextObjBuilder.setMeta(meta);
            }
            NextObjective nextObj = nextObjBuilder.
                    add(new SRNextObjectiveContext(deviceId));
            log.info("**createGroupsFromNeighborsets: Submited "
                    + "next objective {} in device {}",
                    nextId, deviceId);
            flowObjectiveService.next(deviceId, nextObj);
            nsNextObjStore.put(new NeighborSetNextObjectiveStoreKey(deviceId, ns),
                               nextId);
        }
    }


    public void createGroupsFromSubnetConfig() {
        Map<Ip4Prefix, List<PortNumber>> subnetPortMap =
                this.deviceConfig.getSubnetPortsMap(this.deviceId);
        // Construct a broadcast group for each subnet
        subnetPortMap.forEach((subnet, ports) -> {
            SubnetNextObjectiveStoreKey key =
                    new SubnetNextObjectiveStoreKey(deviceId, subnet);

            if (subnetNextObjStore.containsKey(key)) {
                log.debug("Broadcast group for device {} and subnet {} exists",
                          deviceId, subnet);
                return;
            }

            int nextId = flowObjectiveService.allocateNextId();

            NextObjective.Builder nextObjBuilder = DefaultNextObjective
                    .builder().withId(nextId)
                    .withType(NextObjective.Type.BROADCAST).fromApp(appId);

            ports.forEach(port -> {
                TrafficTreatment.Builder tBuilder = DefaultTrafficTreatment.builder();
                tBuilder.popVlan();
                tBuilder.setOutput(port);
                nextObjBuilder.addTreatment(tBuilder.build());
            });

            NextObjective nextObj = nextObjBuilder.add();
            flowObjectiveService.next(deviceId, nextObj);
            log.debug("createGroupFromSubnetConfig: Submited "
                              + "next objective {} in device {}",
                      nextId, deviceId);

            subnetNextObjStore.put(key, nextId);
        });
    }

    public GroupKey getGroupKey(Object obj) {
        return new DefaultGroupKey(kryo.build().serialize(obj));
    }

    /**
     * Removes groups for the next objective ID given.
     *
     * @param objectiveId next objective ID to remove
     * @return true if succeeds, false otherwise
     */
    public boolean removeGroup(int objectiveId) {

        if (nsNextObjStore.containsValue(objectiveId)) {
            NextObjective.Builder nextObjBuilder = DefaultNextObjective
                    .builder().withId(objectiveId)
                    .withType(NextObjective.Type.HASHED).fromApp(appId);
            NextObjective nextObjective = nextObjBuilder.
                    remove(new SRNextObjectiveContext(deviceId));
            log.info("**removeGroup: Submited "
                    + "next objective {} in device {}",
                    objectiveId, deviceId);
            flowObjectiveService.next(deviceId, nextObjective);

            for (Map.Entry<NeighborSetNextObjectiveStoreKey, Integer> entry: nsNextObjStore.entrySet()) {
                if (entry.getValue().equals(objectiveId)) {
                    nsNextObjStore.remove(entry.getKey());
                    break;
                }
            }
            return true;
        }

        return false;
    }

    protected static class SRNextObjectiveContext implements ObjectiveContext {
        final DeviceId deviceId;

        SRNextObjectiveContext(DeviceId deviceId) {
            this.deviceId = deviceId;
        }
        @Override
        public void onSuccess(Objective objective) {
            log.info("Next objective {} operation successful in device {}",
                      objective.id(), deviceId);
        }

        @Override
        public void onError(Objective objective, ObjectiveError error) {
            log.warn("Next objective {} operation failed with error: {} in device {}",
                     objective.id(), error, deviceId);
        }
    }
}
