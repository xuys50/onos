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
package org.onosproject.sfc.manager;

/**
 * SFC application that applies flows to the device.
 */
public interface SfcService {

    /**
     * When port-pair is created, check whether Forwarding Rule needs to be
     * updated in OVS.
     */
    public void onPortPairCreated();

    /**
     * When port-pair is deleted, check whether Forwarding Rule needs to be
     * updated in OVS.
     */
    public void onPortPairDeleted();

    /**
     * When port-pair-group is created, check whether Forwarding Rule needs to
     * be updated in OVS.
     */
    public void onPortPairGroupCreated();

    /**
     * When port-pair-group is deleted, check whether Forwarding Rule needs to
     * be updated in OVS.
     */
    public void onPortPairGroupDeleted();

    /**
     * When flow-classifier is created, check whether Forwarding Rule needs to
     * be updated in OVS.
     */
    public void onFlowClassifierCreated();

    /**
     * When flow-classifier is deleted, check whether Forwarding Rule needs to
     * be updated in OVS.
     */
    public void onFlowClassifierDeleted();

    /**
     * When port-chain is created, check whether Forwarding Rule needs to be
     * updated in OVS.
     */
    public void onPortChainCreated();

    /**
     * When port-chain is deleted, check whether Forwarding Rule needs to be
     * updated in OVS.
     */
    public void onPortChainDeleted();
}
