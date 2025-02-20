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
package org.onosproject.vtnrsc.flowclassifier.impl;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.util.KryoNamespace;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.MultiValuedTimestamp;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.service.WallClockTimestamp;
import org.onosproject.vtnrsc.FlowClassifierId;
import org.onosproject.vtnrsc.FlowClassifier;
import org.onosproject.vtnrsc.flowclassifier.FlowClassifierService;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;

/**
 * Provides implementation of the Flow Classifier Service.
 */
@Component(immediate = true)
@Service
public class FlowClassifierManager implements FlowClassifierService {

    private final Logger log = getLogger(FlowClassifierManager.class);

    private static final String FLOW_CLASSIFIER_NOT_NULL = "Flow Classifier cannot be null";
    private static final String FLOW_CLASSIFIER_ID_NOT_NULL = "Flow Classifier Id cannot be null";

    private EventuallyConsistentMap<FlowClassifierId, FlowClassifier> flowClassifierStore;
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StorageService storageService;

    @Activate
    protected void activate() {
        KryoNamespace.Builder serializer = KryoNamespace.newBuilder()
                .register(KryoNamespaces.API)
                .register(MultiValuedTimestamp.class)
                .register(FlowClassifier.class);
        flowClassifierStore = storageService
                .<FlowClassifierId, FlowClassifier>eventuallyConsistentMapBuilder()
                .withName("flowclassifierstore").withSerializer(serializer)
                .withTimestampProvider((k, v) -> new WallClockTimestamp()).build();
        log.info("Flow Classifier service activated");
    }

    @Deactivate
    protected void deactivate() {
        flowClassifierStore.destroy();
        log.info("Flow Classifier service deactivated");
    }

    @Override
    public boolean exists(FlowClassifierId id) {
        checkNotNull(id, FLOW_CLASSIFIER_ID_NOT_NULL);
        return flowClassifierStore.containsKey(id);
    }

    @Override
    public int getFlowClassifierCount() {
        return flowClassifierStore.size();
    }

    @Override
    public Iterable<FlowClassifier> getFlowClassifiers() {
        return ImmutableList.copyOf(flowClassifierStore.values());
    }

    @Override
    public FlowClassifier getFlowClassifier(FlowClassifierId id) {
        checkNotNull(id, FLOW_CLASSIFIER_ID_NOT_NULL);
        return flowClassifierStore.get(id);
    }

    @Override
    public boolean createFlowClassifier(FlowClassifier flowClassifier) {
        log.debug("createFlowClassifier");
        checkNotNull(flowClassifier, FLOW_CLASSIFIER_NOT_NULL);
        FlowClassifierId id = flowClassifier.flowClassifierId();

        flowClassifierStore.put(id, flowClassifier);
        if (!flowClassifierStore.containsKey(id)) {
            log.debug("Flow Classifier creation is failed whose identifier is {}.", id.toString());
            return false;
        }
        return true;
    }

    @Override
    public boolean updateFlowClassifier(FlowClassifier flowClassifier) {
        checkNotNull(flowClassifier, FLOW_CLASSIFIER_NOT_NULL);

        if (!flowClassifierStore.containsKey(flowClassifier.flowClassifierId())) {
            log.debug("The flowClassifier is not exist whose identifier was {} ", flowClassifier.flowClassifierId()
                    .toString());
            return false;
        }

        flowClassifierStore.put(flowClassifier.flowClassifierId(), flowClassifier);

        if (!flowClassifier.equals(flowClassifierStore.get(flowClassifier.flowClassifierId()))) {
            log.debug("Updation of flowClassifier is failed whose identifier was {} ", flowClassifier
                    .flowClassifierId().toString());
            return false;
        }
        return true;
    }

    @Override
    public boolean removeFlowClassifier(FlowClassifierId id) {
        checkNotNull(id, FLOW_CLASSIFIER_ID_NOT_NULL);
        flowClassifierStore.remove(id);
        if (flowClassifierStore.containsKey(id)) {
            log.debug("The Flow Classifier removal is failed whose identifier is {}", id.toString());
            return false;
        }
        return true;
    }
}
