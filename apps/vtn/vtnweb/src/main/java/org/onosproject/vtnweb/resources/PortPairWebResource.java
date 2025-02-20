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

package org.onosproject.vtnweb.resources;

import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.OK;
import static org.onlab.util.Tools.nullIsNotFound;

import java.io.IOException;
import java.io.InputStream;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.onosproject.rest.AbstractWebResource;
import org.onosproject.vtnrsc.PortPair;
import org.onosproject.vtnrsc.PortPairId;
import org.onosproject.vtnrsc.portpair.PortPairService;
import org.onosproject.vtnweb.web.PortPairCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Query and program port pair.
 */
@Path("port_pairs")
public class PortPairWebResource extends AbstractWebResource {

    private final Logger log = LoggerFactory.getLogger(PortPairWebResource.class);
    private final PortPairService service = get(PortPairService.class);
    public static final String PORT_PAIR_NOT_FOUND = "Port pair not found";
    public static final String PORT_PAIR_ID_EXIST = "Port pair exists";
    public static final String PORT_PAIR_ID_NOT_EXIST = "Port pair does not exist with identifier";

    /**
     * Get details of all port pairs created.
     *
     * @return 200 OK
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPortPairs() {
        Iterable<PortPair> portPairs = service.getPortPairs();
        ObjectNode result = new ObjectMapper().createObjectNode();
        ArrayNode portPairEntry = result.putArray("port_pairs");
        if (portPairs != null) {
            for (final PortPair portPair : portPairs) {
                portPairEntry.add(new PortPairCodec().encode(portPair, this));
            }
        }
        return ok(result.toString()).build();
    }

    /**
     * Get details of a specified port pair id.
     *
     * @param id port pair id
     * @return 200 OK, 404 if given identifier does not exist
     */
    @GET
    @Path("{pair_id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPortPair(@PathParam("pair_id") String id) {

        if (!service.exists(PortPairId.of(id))) {
            return Response.status(NOT_FOUND).entity(PORT_PAIR_NOT_FOUND).build();
        }
        PortPair portPair = nullIsNotFound(service.getPortPair(PortPairId.of(id)), PORT_PAIR_NOT_FOUND);
        ObjectNode result = new ObjectMapper().createObjectNode();
        result.set("port_pair", new PortPairCodec().encode(portPair, this));
        return ok(result.toString()).build();
    }

    /**
     * Creates a new port pair.
     *
     * @param stream port pair from JSON
     * @return status of the request - CREATED if the JSON is correct,
     * BAD_REQUEST if the JSON is invalid
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createPortPair(InputStream stream) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode jsonTree = (ObjectNode) mapper.readTree(stream);
            JsonNode port = jsonTree.get("port_pair");
            PortPair portPair = new PortPairCodec().decode((ObjectNode) port, this);
            Boolean isSuccess = nullIsNotFound(service.createPortPair(portPair), PORT_PAIR_NOT_FOUND);
            return Response.status(OK).entity(isSuccess.toString()).build();
        } catch (IOException e) {
            log.error("Exception while creating port pair {}.", e.toString());
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Update details of a specified port pair id.
     *
     * @param id port pair id
     * @param stream port pair from json
     * @return 200 OK, 404 if the given identifier does not exist
     */
    @PUT
    @Path("{pair_id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updatePortPair(@PathParam("pair_id") String id,
                                   final InputStream stream) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode jsonTree = (ObjectNode) mapper.readTree(stream);
            JsonNode port = jsonTree.get("port_pair");
            PortPair portPair = new PortPairCodec().decode((ObjectNode) port, this);
            Boolean isSuccess = nullIsNotFound(service.updatePortPair(portPair), PORT_PAIR_NOT_FOUND);
            return Response.status(OK).entity(isSuccess.toString()).build();
        } catch (IOException e) {
            log.error("Update port pair failed because of exception {}.", e.toString());
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Delete details of a specified port pair id.
     *
     * @param id port pair id
     */
    @Path("{pair_id}")
    @DELETE
    public void deletePortPair(@PathParam("pair_id") String id) {

        PortPairId portPairId = PortPairId.of(id);
        Boolean isSuccess = nullIsNotFound(service.removePortPair(portPairId), PORT_PAIR_NOT_FOUND);
        if (!isSuccess) {
            log.debug("Port pair identifier {} does not exist", id);
        }
    }
}
