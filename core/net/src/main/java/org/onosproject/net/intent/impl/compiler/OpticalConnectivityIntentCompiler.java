/*
 * Copyright 2014-2015 Open Networking Laboratory
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
package org.onosproject.net.intent.impl.compiler;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.util.Frequency;
import org.onosproject.net.AnnotationKeys;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.IndexedLambda;
import org.onosproject.net.Link;
import org.onosproject.net.OchPort;
import org.onosproject.net.OchSignal;
import org.onosproject.net.OchSignalType;
import org.onosproject.net.OmsPort;
import org.onosproject.net.Path;
import org.onosproject.net.Port;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentCompiler;
import org.onosproject.net.intent.IntentExtensionService;
import org.onosproject.net.intent.OpticalConnectivityIntent;
import org.onosproject.net.intent.OpticalPathIntent;
import org.onosproject.net.intent.impl.IntentCompilationException;
import org.onosproject.net.newresource.ResourceAllocation;
import org.onosproject.net.newresource.ResourcePath;
import org.onosproject.net.newresource.ResourceService;
import org.onosproject.net.resource.link.LinkResourceAllocations;
import org.onosproject.net.topology.LinkWeight;
import org.onosproject.net.topology.Topology;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static org.onosproject.net.LinkKey.linkKey;

/**
 * An intent compiler for {@link org.onosproject.net.intent.OpticalConnectivityIntent}.
 */
// For now, remove component designation until dependency on the new resource manager is available.
@Component(immediate = true)
public class OpticalConnectivityIntentCompiler implements IntentCompiler<OpticalConnectivityIntent> {

    protected static final Logger log = LoggerFactory.getLogger(OpticalConnectivityIntentCompiler.class);

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IntentExtensionService intentManager;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ResourceService resourceService;

    @Activate
    public void activate() {
        intentManager.registerCompiler(OpticalConnectivityIntent.class, this);
    }

    @Deactivate
    public void deactivate() {
        intentManager.unregisterCompiler(OpticalConnectivityIntent.class);
    }

    @Override
    public List<Intent> compile(OpticalConnectivityIntent intent,
                                List<Intent> installable,
                                Set<LinkResourceAllocations> resources) {
        // Check if source and destination are optical OCh ports
        ConnectPoint src = intent.getSrc();
        ConnectPoint dst = intent.getDst();
        Port srcPort = deviceService.getPort(src.deviceId(), src.port());
        Port dstPort = deviceService.getPort(dst.deviceId(), dst.port());
        checkArgument(srcPort instanceof OchPort);
        checkArgument(dstPort instanceof OchPort);

        log.debug("Compiling optical connectivity intent between {} and {}", src, dst);

        // Reserve OCh ports
        ResourcePath srcPortPath = ResourcePath.discrete(src.deviceId(), src.port());
        ResourcePath dstPortPath = ResourcePath.discrete(dst.deviceId(), dst.port());
        List<org.onosproject.net.newresource.ResourceAllocation> allocation =
                resourceService.allocate(intent.id(), srcPortPath, dstPortPath);
        if (allocation.isEmpty()) {
            throw new IntentCompilationException("Unable to reserve ports for intent " + intent);
        }

        // Calculate available light paths
        Set<Path> paths = getOpticalPaths(intent);

        // Use first path that can be successfully reserved
        for (Path path : paths) {

            // Static or dynamic lambda allocation
            String staticLambda = srcPort.annotations().value(AnnotationKeys.STATIC_LAMBDA);
            OchPort srcOchPort = (OchPort) srcPort;
            OchPort dstOchPort = (OchPort) dstPort;
            OchSignal ochSignal;

            // FIXME: need to actually reserve the lambda for static lambda's
            if (staticLambda != null) {
                ochSignal = new OchSignal(Frequency.ofHz(Long.parseLong(staticLambda)),
                        srcOchPort.lambda().channelSpacing(),
                        srcOchPort.lambda().slotGranularity());
            } else if (!srcOchPort.isTunable() || !dstOchPort.isTunable()) {
                // FIXME: also check OCh port
                ochSignal = srcOchPort.lambda();
            } else {
                // Request and reserve lambda on path
                IndexedLambda lambda = assignWavelength(intent, path);
                if (lambda == null) {
                    continue;
                }
                OmsPort omsPort = (OmsPort) deviceService.getPort(path.src().deviceId(), path.src().port());
                ochSignal = new OchSignal((int) lambda.index(), omsPort.maxFrequency(), omsPort.grid());
            }

            // Create installable optical path intent
            // Only support fixed grid for now
            OchSignalType signalType = OchSignalType.FIXED_GRID;

            Intent newIntent = OpticalPathIntent.builder()
                    .appId(intent.appId())
                    .src(intent.getSrc())
                    .dst(intent.getDst())
                    .path(path)
                    .lambda(ochSignal)
                    .signalType(signalType)
                    .bidirectional(intent.isBidirectional())
                    .build();

            return ImmutableList.of(newIntent);
        }

        // Release port allocations if unsuccessful
        resourceService.release(intent.id());

        throw new IntentCompilationException("Unable to find suitable lightpath for intent " + intent);
    }

    /**
     * Request and reserve first available wavelength across path.
     *
     * @param path path in WDM topology
     * @return first available lambda allocated
     */
    private IndexedLambda assignWavelength(Intent intent, Path path) {
        Set<IndexedLambda> lambdas = findCommonLambdasOverLinks(path.links());
        if (lambdas.isEmpty()) {
            return null;
        }

        IndexedLambda minLambda = findFirstLambda(lambdas);
        List<ResourcePath> lambdaResources = path.links().stream()
                .map(x -> ResourcePath.discrete(linkKey(x.src(), x.dst())))
                .map(x -> x.child(minLambda))
                .collect(Collectors.toList());

        List<ResourceAllocation> allocations = resourceService.allocate(intent.id(), lambdaResources);
        if (allocations.isEmpty()) {
            log.info("Resource allocation for {} failed (resource request: {})", intent, lambdaResources);
            return null;
        }

        return minLambda;
    }

    private Set<IndexedLambda> findCommonLambdasOverLinks(List<Link> links) {
        return links.stream()
                .map(x -> ResourcePath.discrete(linkKey(x.src(), x.dst())))
                .map(resourceService::getAvailableResources)
                .map(x -> Iterables.filter(x, r -> r.last() instanceof IndexedLambda))
                .map(x -> Iterables.transform(x, r -> (IndexedLambda) r.last()))
                .map(x -> (Set<IndexedLambda>) ImmutableSet.copyOf(x))
                .reduce(Sets::intersection)
                .orElse(Collections.emptySet());
    }

    private IndexedLambda findFirstLambda(Set<IndexedLambda> lambdas) {
        return lambdas.stream()
                .findFirst()
                .get();
    }

    private ConnectPoint staticPort(ConnectPoint connectPoint) {
        Port port = deviceService.getPort(connectPoint.deviceId(), connectPoint.port());

        String staticPort = port.annotations().value(AnnotationKeys.STATIC_PORT);

        // FIXME: need a better way to match the port
        if (staticPort != null) {
            for (Port p : deviceService.getPorts(connectPoint.deviceId())) {
                if (staticPort.equals(p.number().name())) {
                    return new ConnectPoint(p.element().id(), p.number());
                }
            }
        }

        return null;
    }

    /**
     * Calculates optical paths in WDM topology.
     *
     * @param intent optical connectivity intent
     * @return set of paths in WDM topology
     */
    private Set<Path> getOpticalPaths(OpticalConnectivityIntent intent) {
        // Route in WDM topology
        Topology topology = topologyService.currentTopology();
        LinkWeight weight = edge -> {
            // Disregard inactive or non-optical links
            if (edge.link().state() == Link.State.INACTIVE) {
                return -1;
            }
            if (edge.link().type() != Link.Type.OPTICAL) {
                return -1;
            }
            // Adhere to static port mappings
            DeviceId srcDeviceId = edge.link().src().deviceId();
            if (srcDeviceId.equals(intent.getSrc().deviceId())) {
                ConnectPoint srcStaticPort = staticPort(intent.getSrc());
                if (srcStaticPort != null) {
                    return srcStaticPort.equals(edge.link().src()) ? 1 : -1;
                }
            }
            DeviceId dstDeviceId = edge.link().dst().deviceId();
            if (dstDeviceId.equals(intent.getDst().deviceId())) {
                ConnectPoint dstStaticPort = staticPort(intent.getDst());
                if (dstStaticPort != null) {
                    return dstStaticPort.equals(edge.link().dst()) ? 1 : -1;
                }
            }

            return 1;
        };

        ConnectPoint start = intent.getSrc();
        ConnectPoint end = intent.getDst();
        Set<Path> paths = topologyService.getPaths(topology, start.deviceId(),
                end.deviceId(), weight);

        return paths;
    }
}
