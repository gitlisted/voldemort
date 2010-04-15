/*
 * Copyright 2010 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.routed.action;

import java.util.List;

import org.apache.log4j.Level;

import voldemort.VoldemortApplicationException;
import voldemort.cluster.Node;
import voldemort.store.InsufficientOperationalNodesException;
import voldemort.store.Store;
import voldemort.store.UnreachableStoreException;
import voldemort.store.routed.ListStateData;
import voldemort.store.routed.Pipeline;
import voldemort.store.routed.RequestCompletedCallback;
import voldemort.store.routed.Pipeline.Event;
import voldemort.utils.ByteArray;
import voldemort.utils.Time;

public class PerformSerialRequests extends AbstractKeyBasedAction<ListStateData> {

    private BlockingStoreRequest storeRequest;

    private Event insufficientSuccessesEvent;

    public BlockingStoreRequest getStoreRequest() {
        return storeRequest;
    }

    public void setStoreRequest(BlockingStoreRequest storeRequest) {
        this.storeRequest = storeRequest;
    }

    public Event getInsufficientSuccessesEvent() {
        return insufficientSuccessesEvent;
    }

    public void setInsufficientSuccessesEvent(Event insufficientSuccessesEvent) {
        this.insufficientSuccessesEvent = insufficientSuccessesEvent;
    }

    public void execute(Pipeline pipeline, Object eventData) {
        List<Node> nodes = pipelineData.getNodes();

        // Now if we had any failures we will be short a few reads. Do serial
        // reads to make up for these.
        while(pipelineData.getSuccesses() < preferred && pipelineData.getNodeIndex() < nodes.size()) {
            Node node = nodes.get(pipelineData.getNodeIndex());
            long start = System.nanoTime();

            try {
                Store<ByteArray, byte[]> store = stores.get(node.getId());
                Object result = storeRequest.request(node, store);

                RequestCompletedCallback rcc = new RequestCompletedCallback(node,
                                                                            key,
                                                                            ((System.nanoTime() - start) / Time.NS_PER_MS),
                                                                            result);

                pipelineData.incrementSuccesses();
                pipelineData.getInterimResults().add(rcc);
                failureDetector.recordSuccess(rcc.getNode(), rcc.getRequestTime());
            } catch(UnreachableStoreException e) {
                pipelineData.recordFailure(e);
                failureDetector.recordException(node,
                                                ((System.nanoTime() - start) / Time.NS_PER_MS),
                                                e);
            } catch(VoldemortApplicationException e) {
                pipelineData.setFatalError(e);
                pipeline.addEvent(Event.ERROR);
                return;
            } catch(Exception e) {
                pipelineData.recordFailure(e);

                if(logger.isEnabledFor(Level.WARN))
                    logger.warn("Error in " + pipeline.getOperation() + " on node " + node.getId()
                                + "(" + node.getHost() + ")", e);
            }

            pipelineData.incrementNodeIndex();
        }

        if(pipelineData.getSuccesses() < required) {
            if(insufficientSuccessesEvent != null) {
                pipeline.addEvent(insufficientSuccessesEvent);
            } else {
                pipelineData.setFatalError(new InsufficientOperationalNodesException(required
                                                                                          + " "
                                                                                          + pipeline.getOperation()
                                                                                                    .getSimpleName()
                                                                                          + "s required, but "
                                                                                          + pipelineData.getSuccesses()
                                                                                          + " succeeded",
                                                                                  pipelineData.getFailures()));

                pipeline.addEvent(Event.ERROR);
            }
        } else {
            pipeline.addEvent(completeEvent);
        }
    }

    public interface BlockingStoreRequest {

        public Object request(Node node, Store<ByteArray, byte[]> store);

    }

}