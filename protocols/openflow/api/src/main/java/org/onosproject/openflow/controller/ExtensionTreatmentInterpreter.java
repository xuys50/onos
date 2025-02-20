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

package org.onosproject.openflow.controller;

import com.google.common.annotations.Beta;
import org.onosproject.net.driver.HandlerBehaviour;
import org.onosproject.net.flow.instructions.ExtensionTreatment;
import org.onosproject.net.flow.instructions.ExtensionTreatmentType;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.action.OFAction;

/**
 * Interprets extension instructions and converts them to/from OpenFlow objects.
 */
@Beta
public interface ExtensionTreatmentInterpreter extends HandlerBehaviour {

    /**
     * Returns true if the given extension instruction is supported by this
     * driver.
     *
     * @param extensionTreatmentType extension instruction type
     * @return true if the instruction is supported, otherwise false
     */
    boolean supported(ExtensionTreatmentType extensionTreatmentType);

    /**
     * Maps an extension instruction to an OpenFlow action.
     *
     * @param factory OpenFlow factory
     * @param extensionTreatment extension instruction
     * @return OpenFlow action
     */
    OFAction mapInstruction(OFFactory factory, ExtensionTreatment extensionTreatment);

    /**
     * Maps an OpenFlow action to an extension instruction.
     *
     * @param action OpenFlow action
     * @return extension instruction
     */
    ExtensionTreatment mapAction(OFAction action);

}
