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
package org.onosproject.rest.resources;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.intent.HostToHostIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentEvent;
import org.onosproject.net.intent.IntentListener;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.IntentState;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.rest.AbstractWebResource;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.onlab.util.Tools.nullIsNotFound;
import static org.onosproject.net.intent.IntentState.FAILED;
import static org.onosproject.net.intent.IntentState.WITHDRAWN;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Query, submit and withdraw network intents.
 */
@Path("intents")
public class IntentsWebResource extends AbstractWebResource {
    @Context
    UriInfo uriInfo;

    private static final Logger log = getLogger(IntentsWebResource.class);
    private static final int WITHDRAW_EVENT_TIMEOUT_SECONDS = 5;

    public static final String INTENT_NOT_FOUND = "Intent is not found";

    /**
     * Get all intents.
     * Returns array containing all the intents in the system.
     * @rsModel Intents
     * @return array of all the intents in the system
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getIntents() {
        final Iterable<Intent> intents = get(IntentService.class).getIntents();
        final ObjectNode root = encodeArray(Intent.class, "intents", intents);
        return ok(root).build();
    }

    /**
     * Get intent by application and key.
     * Returns details of the specified intent.
     * @rsModel Intents
     * @param appId application identifier
     * @param key   intent key
     * @return intent data
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{appId}/{key}")
    public Response getIntentById(@PathParam("appId") String appId,
                                  @PathParam("key") String key) {
        final ApplicationId app = get(CoreService.class).getAppId(appId);

        Intent intent = get(IntentService.class).getIntent(Key.of(key, app));
        if (intent == null) {
            long numericalKey = Long.decode(key);
            intent = get(IntentService.class).getIntent(Key.of(numericalKey, app));
        }
        nullIsNotFound(intent, INTENT_NOT_FOUND);

        final ObjectNode root;
        if (intent instanceof HostToHostIntent) {
            root = codec(HostToHostIntent.class).encode((HostToHostIntent) intent, this);
        } else if (intent instanceof PointToPointIntent) {
            root = codec(PointToPointIntent.class).encode((PointToPointIntent) intent, this);
        } else {
            root = codec(Intent.class).encode(intent, this);
        }
        return ok(root).build();
    }

    class DeleteListener implements IntentListener {
        final Key key;
        final CountDownLatch latch;

        DeleteListener(Key key, CountDownLatch latch) {
            this.key = key;
            this.latch = latch;
        }

        @Override
        public void event(IntentEvent event) {
            if (Objects.equals(event.subject().key(), key) &&
                    (event.type() == IntentEvent.Type.WITHDRAWN ||
                            event.type() == IntentEvent.Type.FAILED)) {
                latch.countDown();
            }
        }
    }

    /**
     * Submit a new intent.
     * Creates and submits intent from the JSON request.
     * @rsModel IntentHost
     * @param stream input JSON
     * @return status of the request - CREATED if the JSON is correct,
     * BAD_REQUEST if the JSON is invalid
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createIntent(InputStream stream) {
        try {
            IntentService service = get(IntentService.class);
            ObjectNode root = (ObjectNode) mapper().readTree(stream);
            Intent intent = codec(Intent.class).decode(root, this);
            service.submit(intent);
            UriBuilder locationBuilder = uriInfo.getBaseUriBuilder()
                    .path("intents")
                    .path(intent.appId().name())
                    .path(Long.toString(intent.id().fingerprint()));
            return Response
                    .created(locationBuilder.build())
                    .build();
        } catch (IOException ioe) {
            throw new IllegalArgumentException(ioe);
        }
    }

    /**
     * Withdraw intent.
     * Withdraws the specified intent from the system.
     *
     * @param appId application identifier
     * @param key   intent key
     */
    @DELETE
    @Path("{appId}/{key}")
    public void deleteIntentById(@PathParam("appId") String appId,
                                 @PathParam("key") String key) {
        final ApplicationId app = get(CoreService.class).getAppId(appId);

        Intent intent = get(IntentService.class).getIntent(Key.of(key, app));
        IntentService service = get(IntentService.class);

        if (intent == null) {
            intent = service
                    .getIntent(Key.of(Long.decode(key), app));
        }
        if (intent == null) {
            // No such intent.  REST standards recommend a positive status code
            // in this case.
            return;
        }


        Key k = intent.key();

        // set up latch and listener to track uninstall progress
        CountDownLatch latch = new CountDownLatch(1);

        IntentListener listener = new DeleteListener(k, latch);
        service.addListener(listener);

        try {
            // request the withdraw
            service.withdraw(intent);

            try {
                latch.await(WITHDRAW_EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.info("REST Delete operation timed out waiting for intent {}", k);
            }
            // double check the state
            IntentState state = service.getIntentState(k);
            if (state == WITHDRAWN || state == FAILED) {
                service.purge(intent);
            }

        } finally {
            // clean up the listener
            service.removeListener(listener);
        }
    }

}
