/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.worker;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import lombok.extern.slf4j.Slf4j;
import org.apache.distributedlog.api.namespace.Namespace;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.common.policies.data.ErrorData;
import org.apache.pulsar.functions.proto.Function.Assignment;
import org.apache.pulsar.functions.instance.AuthenticationConfig;
import org.apache.pulsar.functions.proto.InstanceCommunication;
import org.apache.pulsar.functions.proto.Request.AssignmentsUpdate;
import org.apache.pulsar.functions.runtime.RuntimeFactory;
import org.apache.pulsar.functions.runtime.ProcessRuntimeFactory;
import org.apache.pulsar.functions.runtime.Runtime;
import org.apache.pulsar.functions.runtime.ThreadRuntimeFactory;
import org.apache.pulsar.functions.runtime.RuntimeSpawner;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * This class managers all aspects of functions assignments and running of function assignments for this worker
 */
@Slf4j
public class FunctionRuntimeManager implements AutoCloseable{

    // all assignments
    // WorkerId -> Function Fully Qualified InstanceId -> List<Assignments>
    @VisibleForTesting
    Map<String, Map<String, Assignment>> workerIdToAssignments = new ConcurrentHashMap<>();

    // All the runtime info related to functions executed by this worker
    // Fully Qualified InstanceId - > FunctionRuntimeInfo
    @VisibleForTesting
    Map<String, FunctionRuntimeInfo> functionRuntimeInfoMap = new ConcurrentHashMap<>();

    @VisibleForTesting
    final WorkerConfig workerConfig;

    @VisibleForTesting
    LinkedBlockingQueue<FunctionAction> actionQueue;

    private long currentAssignmentVersion = 0;

    private final FunctionAssignmentTailer functionAssignmentTailer;

    private FunctionActioner functionActioner;

    private RuntimeFactory runtimeFactory;

    private MembershipManager membershipManager;
    private final ConnectorsManager connectorsManager;

    public FunctionRuntimeManager(WorkerConfig workerConfig,
                                  PulsarClient pulsarClient,
                                  Namespace dlogNamespace,
                                  MembershipManager membershipManager,
                                  ConnectorsManager connectorsManager) throws Exception {
        this.workerConfig = workerConfig;
        this.connectorsManager = connectorsManager;

        Reader<byte[]> reader = pulsarClient.newReader()
                .topic(this.workerConfig.getFunctionAssignmentTopic())
                .startMessageId(MessageId.earliest)
                .create();

        this.functionAssignmentTailer = new FunctionAssignmentTailer(this, reader);

        AuthenticationConfig authConfig = AuthenticationConfig.builder()
                .clientAuthenticationPlugin(workerConfig.getClientAuthenticationPlugin())
                .clientAuthenticationParameters(workerConfig.getClientAuthenticationParameters())
                .tlsTrustCertsFilePath(workerConfig.getTlsTrustCertsFilePath())
                .useTls(workerConfig.isUseTls()).tlsAllowInsecureConnection(workerConfig.isTlsAllowInsecureConnection())
                .tlsHostnameVerificationEnable(workerConfig.isTlsHostnameVerificationEnable()).build();

        if (workerConfig.getThreadContainerFactory() != null) {
            this.runtimeFactory = new ThreadRuntimeFactory(
                    workerConfig.getThreadContainerFactory().getThreadGroupName(),
                    workerConfig.getPulsarServiceUrl(),
                    workerConfig.getStateStorageServiceUrl(),
                    authConfig);
        } else if (workerConfig.getProcessContainerFactory() != null) {
            this.runtimeFactory = new ProcessRuntimeFactory(
                    workerConfig.getPulsarServiceUrl(),
                    workerConfig.getStateStorageServiceUrl(),
                    authConfig,
                    workerConfig.getProcessContainerFactory().getJavaInstanceJarLocation(),
                    workerConfig.getProcessContainerFactory().getPythonInstanceLocation(),
                    workerConfig.getProcessContainerFactory().getLogDirectory());
        } else {
            throw new RuntimeException("Either Thread or Process Container Factory need to be set");
        }

        this.actionQueue = new LinkedBlockingQueue<>();

        this.functionActioner = new FunctionActioner(this.workerConfig, runtimeFactory,
                dlogNamespace, actionQueue, connectorsManager);

        this.membershipManager = membershipManager;
    }

    /**
     * Starts the function runtime manager
     */
    public void start() {
        log.info("/** Starting Function Runtime Manager **/");
        log.info("Initialize metrics sink...");
        log.info("Starting function actioner...");
        this.functionActioner.start();
        log.info("Starting function assignment tailer...");
        this.functionAssignmentTailer.start();
    }

    /**
     * Public methods
     */

    /**
     * Get current assignments
     * @return a map of current assignments in the follwing format
     * {workerId : {FullyQualifiedInstanceId : Assignment}}
     */
    public synchronized Map<String, Map<String, Assignment>> getCurrentAssignments() {
        Map<String, Map<String, Assignment>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, Assignment>> entry : this.workerIdToAssignments.entrySet()) {
            Map<String, Assignment> tmp = new HashMap<>();
            tmp.putAll(entry.getValue());
            copy.put(entry.getKey(), tmp);
        }
        return copy;
    }

    /**
     * Find a assignment of a function
     * @param tenant the tenant the function belongs to
     * @param namespace the namespace the function belongs to
     * @param functionName the function name
     * @return the assignment of the function
     */
    public synchronized Assignment findFunctionAssignment(String tenant, String namespace,
                                                          String functionName, int instanceId) {
        return this.findAssignment(tenant, namespace, functionName, instanceId);
    }

    /**
     * Find all instance assignments of function
     * @param tenant
     * @param namespace
     * @param functionName
     * @return
     */
    public synchronized Collection<Assignment> findFunctionAssignments(String tenant,
                                                                       String namespace, String functionName) {
        return findFunctionAssignments(tenant, namespace, functionName, this.workerIdToAssignments);
    }

    public static Collection<Assignment> findFunctionAssignments(String tenant,
                                                                 String namespace, String functionName,
                                                                 Map<String, Map<String, Assignment>> workerIdToAssignments) {

        Collection<Assignment> assignments = new LinkedList<>();

        for (Map<String, Assignment> entryMap : workerIdToAssignments.values()) {
            assignments.addAll(entryMap.values().stream()
                    .filter(
                            assignment ->
                                    (tenant.equals(assignment.getInstance()
                                            .getFunctionMetaData().getFunctionDetails()
                                            .getTenant())
                                            && namespace.equals((assignment.getInstance()
                                            .getFunctionMetaData().getFunctionDetails()
                                            .getNamespace()))
                                            && functionName.equals(assignment.getInstance()
                                            .getFunctionMetaData().getFunctionDetails()
                                            .getName())))
                    .collect(Collectors.toList()));
        }

        return assignments;
    }

    /**
     * get the current version number of assignments
     * @return assignments version number
     */
    public synchronized long getCurrentAssignmentVersion() {
        return new Long(this.currentAssignmentVersion);
    }

    /**
     * Removes a collection of assignments
     * @param assignments assignments to remove
     */
    public synchronized void removeAssignments(Collection<Assignment> assignments) {
        for (Assignment assignment : assignments) {
            this.deleteAssignment(assignment);
        }
    }

    /**
     * Get status of a function instance.  If this worker is not running the function instance,
     * @param tenant the tenant the function belongs to
     * @param namespace the namespace the function belongs to
     * @param functionName the function name
     * @param instanceId the function instance id
     * @return the function status
     */
    public InstanceCommunication.FunctionStatus getFunctionInstanceStatus(String tenant, String namespace,
                                                                          String functionName, int instanceId) {
        Assignment assignment = this.findAssignment(tenant, namespace, functionName, instanceId);
        final String assignedWorkerId = assignment.getWorkerId();
        final String workerId = this.workerConfig.getWorkerId();
        
        if (assignment == null) {
            InstanceCommunication.FunctionStatus.Builder functionStatusBuilder
                    = InstanceCommunication.FunctionStatus.newBuilder();
            functionStatusBuilder.setRunning(false);
            functionStatusBuilder.setFailureException("Function has not been scheduled");
            return functionStatusBuilder.build();
        }

        InstanceCommunication.FunctionStatus functionStatus = null;
        // If I am running worker
        if (assignedWorkerId.equals(workerId)) {
            FunctionRuntimeInfo functionRuntimeInfo = this.getFunctionRuntimeInfo(
                    Utils.getFullyQualifiedInstanceId(assignment.getInstance()));
            RuntimeSpawner runtimeSpawner = functionRuntimeInfo.getRuntimeSpawner();
            if (runtimeSpawner != null) {
                try {
                    InstanceCommunication.FunctionStatus.Builder functionStatusBuilder = InstanceCommunication.FunctionStatus
                            .newBuilder(functionRuntimeInfo.getRuntimeSpawner().getFunctionStatus().get());
                    functionStatusBuilder.setWorkerId(assignedWorkerId);
                    functionStatus = functionStatusBuilder.build();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            } else {
                InstanceCommunication.FunctionStatus.Builder functionStatusBuilder
                        = InstanceCommunication.FunctionStatus.newBuilder();
                functionStatusBuilder.setRunning(false);
                functionStatusBuilder.setInstanceId(String.valueOf(instanceId));
                if (functionRuntimeInfo.getStartupException() != null) {
                    functionStatusBuilder.setFailureException(functionRuntimeInfo.getStartupException().getMessage());
                }
                functionStatusBuilder.setWorkerId(assignedWorkerId);
                functionStatus = functionStatusBuilder.build();
            }
        } else {
            // query other worker

            List<WorkerInfo> workerInfoList = this.membershipManager.getCurrentMembership();
            WorkerInfo workerInfo = null;
            for (WorkerInfo entry: workerInfoList) {
                if (assignment.getWorkerId().equals(entry.getWorkerId())) {
                    workerInfo = entry;
                }
            }
            if (workerInfo == null) {
                InstanceCommunication.FunctionStatus.Builder functionStatusBuilder
                        = InstanceCommunication.FunctionStatus.newBuilder();
                functionStatusBuilder.setRunning(false);
                functionStatusBuilder.setInstanceId(String.valueOf(instanceId));
                functionStatusBuilder.setFailureException("Function has not been scheduled");
                return functionStatusBuilder.build();
            }

            Client client = ClientBuilder.newClient();

            // TODO: implement authentication/authorization
            String jsonResponse = client.target(String.format("http://%s:%d/admin/functions/%s/%s/%s/%d/status",
                    workerInfo.getWorkerHostname(), workerInfo.getPort(), tenant, namespace, functionName, instanceId))
                    .request(MediaType.TEXT_PLAIN)
                    .get(String.class);

            InstanceCommunication.FunctionStatus.Builder functionStatusBuilder = InstanceCommunication.FunctionStatus.newBuilder();
            try {
                org.apache.pulsar.functions.utils.Utils.mergeJson(jsonResponse, functionStatusBuilder);
            } catch (IOException e) {
                log.warn("Got invalid function status response from {}", workerInfo, e);
                throw new RuntimeException(e);
            }
            functionStatusBuilder.setWorkerId(assignedWorkerId);
            functionStatus = functionStatusBuilder.build();
        }

        return functionStatus;
    }

    public Response restartFunctionInstance(String tenant, String namespace, String functionName, int instanceId) throws Exception {
        Assignment assignment = this.findAssignment(tenant, namespace, functionName, instanceId);
        final String fullFunctionName = String.format("%s/%s/%s/%s", tenant, namespace, functionName, instanceId);
        if (assignment == null) {
            return Response.status(Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorData(fullFunctionName + " doesn't exist")).build();
        }

        final String assignedWorkerId = assignment.getWorkerId();
        final String workerId = this.workerConfig.getWorkerId();
        
        if (assignedWorkerId.equals(workerId)) {
            restartFunction(Utils.getFullyQualifiedInstanceId(assignment.getInstance()));
            return Response.status(Status.OK).build();
        } else {
            // query other worker
            List<WorkerInfo> workerInfoList = this.membershipManager.getCurrentMembership();
            WorkerInfo workerInfo = null;
            for (WorkerInfo entry : workerInfoList) {
                if (assignment.getWorkerId().equals(entry.getWorkerId())) {
                    workerInfo = entry;
                }
            }
            if (workerInfo == null) {
                return Response.status(Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON)
                        .entity(new ErrorData(fullFunctionName + " has not been assigned yet")).build();
            }

            URI redirect = null;
            final String redirectUrl = String.format("http://%s:%d/admin/functions/%s/%s/%s/%d/restart",
                    workerInfo.getWorkerHostname(), workerInfo.getPort(), tenant, namespace, functionName, instanceId);
            try {
                redirect = new URI(redirectUrl);
            } catch (URISyntaxException e) {
                log.error("Error in preparing redirect url for {}/{}/{}/{}: {}", tenant, namespace, functionName,
                        instanceId, e.getMessage(), e);
                return Response.status(Status.INTERNAL_SERVER_ERROR).type(MediaType.APPLICATION_JSON)
                        .entity(new ErrorData(fullFunctionName + " invalid redirection url")).build();
            }
            throw new WebApplicationException(Response.temporaryRedirect(redirect).build());
        }
    }

    public Response restartFunctionInstances(String tenant, String namespace, String functionName) throws Exception {
        final String fullFunctionName = String.format("%s/%s/%s", tenant, namespace, functionName);
        Collection<Assignment> assignments = this.findFunctionAssignments(tenant, namespace, functionName);

        if (assignments.isEmpty()) {
            return Response.status(Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorData(fullFunctionName + " has not been assigned yet")).build();
        }
        for (Assignment assignment : assignments) {
            final String assignedWorkerId = assignment.getWorkerId();
            final String workerId = this.workerConfig.getWorkerId();
            String fullyQualifiedInstanceId = Utils.getFullyQualifiedInstanceId(assignment.getInstance());
            if (assignedWorkerId.equals(workerId)) {
                restartFunction(fullyQualifiedInstanceId);
            } else {
                List<WorkerInfo> workerInfoList = this.membershipManager.getCurrentMembership();
                WorkerInfo workerInfo = null;
                for (WorkerInfo entry : workerInfoList) {
                    if (assignment.getWorkerId().equals(entry.getWorkerId())) {
                        workerInfo = entry;
                    }
                }
                if (workerInfo == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] has not been assigned yet", fullyQualifiedInstanceId);
                    }
                    continue;
                }
                Client client = ClientBuilder.newClient();
                // TODO: create and use pulsar-admin to support authorization and authentication and manage redirect
                final String instanceRestartUrl = String.format("http://%s:%d/admin/functions/%s/%s/%s/%d/restart",
                        workerInfo.getWorkerHostname(), workerInfo.getPort(), tenant, namespace, functionName,
                        assignment.getInstance().getInstanceId());
                client.target(instanceRestartUrl).request(MediaType.APPLICATION_JSON)
                        .post(Entity.entity("", MediaType.APPLICATION_JSON), ErrorData.class);
            }
        }
        return Response.status(Status.OK).build();
    }

    private void restartFunction(String fullyQualifiedInstanceId) throws Exception {
        log.info("[{}] restarting..", fullyQualifiedInstanceId);
        FunctionRuntimeInfo functionRuntimeInfo = this.getFunctionRuntimeInfo(fullyQualifiedInstanceId);
        if (functionRuntimeInfo != null) {
            this.functionActioner.stopFunction(functionRuntimeInfo);
            try {
                this.functionActioner.startFunction(functionRuntimeInfo);
            } catch (Exception ex) {
                log.info("{} Error starting function", fullyQualifiedInstanceId, ex);
                functionRuntimeInfo.setStartupException(ex);
                throw ex;
            }
        }
    }

    /**
     * Get statuses of all function instances.
     * @param tenant the tenant the function belongs to
     * @param namespace the namespace the function belongs to
     * @param functionName the function name
     * @return a list of function statuses
     */
    public InstanceCommunication.FunctionStatusList getAllFunctionStatus(
            String tenant, String namespace, String functionName) {

        Collection<Assignment> assignments = this.findFunctionAssignments(tenant, namespace, functionName);

        InstanceCommunication.FunctionStatusList.Builder functionStatusListBuilder = InstanceCommunication.FunctionStatusList.newBuilder();
        if (assignments.isEmpty()) {
            return functionStatusListBuilder.build();
        }

        for (Assignment assignment : assignments) {

            InstanceCommunication.FunctionStatus functionStatus = this.getFunctionInstanceStatus(
                    assignment.getInstance().getFunctionMetaData().getFunctionDetails().getTenant(),
                    assignment.getInstance().getFunctionMetaData().getFunctionDetails().getNamespace(),
                    assignment.getInstance().getFunctionMetaData().getFunctionDetails().getName(),
                    assignment.getInstance().getInstanceId());

            functionStatusListBuilder.addFunctionStatusList(functionStatus);
        }
        return functionStatusListBuilder.build();
    }

    /**
     * Process an assignment update from the assignment topic
     * @param messageId the message id of the update assignment
     * @param assignmentsUpdate the assignment update
     */
    public synchronized void processAssignmentUpdate(MessageId messageId, AssignmentsUpdate assignmentsUpdate) {

        if (assignmentsUpdate.getVersion() > this.currentAssignmentVersion) {

            Map<String, Assignment> assignmentMap = new HashMap<>();
            for (Assignment assignment : assignmentsUpdate.getAssignmentsList()) {
                assignmentMap.put(
                        Utils.getFullyQualifiedInstanceId(assignment.getInstance()),
                        assignment);
            }
            Map<String, Assignment> existingAssignmentMap = new HashMap<>();
            for (Map<String, Assignment> entry : this.workerIdToAssignments.values()) {
                existingAssignmentMap.putAll(entry);
            }

            Map<String, Assignment> assignmentsToAdd = diff(assignmentMap, existingAssignmentMap);

            Map<String, Assignment> assignmentsToDelete = diff(existingAssignmentMap, assignmentMap);

            Map<String, Assignment> existingAssignments = inCommon(assignmentMap, existingAssignmentMap);

            // functions to add
            for (Map.Entry<String, Assignment> assignmentEntry : assignmentsToAdd.entrySet()) {
                String fullyQualifiedInstanceId = assignmentEntry.getKey();
                Assignment assignment = assignmentEntry.getValue();

                //add new function
                this.setAssignment(assignment);

                //Assigned to me
                if (assignment.getWorkerId().equals(workerConfig.getWorkerId())) {
                    if (!this.functionRuntimeInfoMap.containsKey(fullyQualifiedInstanceId)) {
                        this.setFunctionRuntimeInfo(fullyQualifiedInstanceId, new FunctionRuntimeInfo()
                                .setFunctionInstance(assignment.getInstance()));

                    } else {
                        //Somehow this function is already started
                        log.warn("Function {} already running. Going to restart function.",
                                this.functionRuntimeInfoMap.get(fullyQualifiedInstanceId));
                        this.insertStopAction(this.functionRuntimeInfoMap.get(fullyQualifiedInstanceId));
                    }
                    FunctionRuntimeInfo functionRuntimeInfo = this.functionRuntimeInfoMap.get(fullyQualifiedInstanceId);
                    this.insertStartAction(functionRuntimeInfo);
                }
            }

            // functions to delete
            for (Map.Entry<String, Assignment> assignmentEntry : assignmentsToDelete.entrySet()) {
                String fullyQualifiedInstanceId = assignmentEntry.getKey();
                Assignment assignment = assignmentEntry.getValue();

                FunctionRuntimeInfo functionRuntimeInfo = this.functionRuntimeInfoMap.get(fullyQualifiedInstanceId);
                if (functionRuntimeInfo != null) {
                    this.insertStopAction(functionRuntimeInfo);
                    this.deleteFunctionRuntimeInfo(fullyQualifiedInstanceId);
                }
                this.deleteAssignment(assignment);
            }

            // functions to update
            for (Map.Entry<String, Assignment> assignmentEntry : existingAssignments.entrySet()) {
                String fullyQualifiedInstanceId = assignmentEntry.getKey();
                Assignment assignment = assignmentEntry.getValue();
                Assignment existingAssignment = this.findAssignment(assignment);
                // potential updates need to happen
                if (!existingAssignment.equals(assignment)) {
                    FunctionRuntimeInfo functionRuntimeInfo = this.functionRuntimeInfoMap.get(fullyQualifiedInstanceId);
                    //stop function
                    if (functionRuntimeInfo != null) {
                        this.insertStopAction(functionRuntimeInfo);
                    }
                    // still assigned to me, need to restart
                    if (assignment.getWorkerId().equals(this.workerConfig.getWorkerId())) {
                        //start again
                        FunctionRuntimeInfo newFunctionRuntimeInfo = new FunctionRuntimeInfo();
                        newFunctionRuntimeInfo.setFunctionInstance(assignment.getInstance());
                        this.insertStartAction(newFunctionRuntimeInfo);
                        this.setFunctionRuntimeInfo(fullyQualifiedInstanceId, newFunctionRuntimeInfo);
                    }

                    // find existing assignment
                    Assignment existing_assignment = this.findAssignment(assignment);
                    if (existing_assignment != null) {
                        // delete old assignment that could have old data
                        this.deleteAssignment(existing_assignment);
                    }
                    // set to newest assignment
                    this.setAssignment(assignment);
                }
            }

            // set as current assignment
            this.currentAssignmentVersion = assignmentsUpdate.getVersion();

        } else {
            log.debug("Received out of date assignment update: {}", assignmentsUpdate);
        }
    }

    public Map<String, FunctionRuntimeInfo> getFunctionRuntimeInfos() {
        return this.functionRuntimeInfoMap;
    }
    
    public void updateRates() {
        for (Entry<String, FunctionRuntimeInfo> entry : this.functionRuntimeInfoMap.entrySet()) {
            RuntimeSpawner functionRuntimeSpawner = entry.getValue().getRuntimeSpawner();
            if (functionRuntimeSpawner != null) {
                Runtime functionRuntime = functionRuntimeSpawner.getRuntime();
                if (functionRuntime != null) {
                    try {
                        functionRuntime.resetMetrics().get();
                    } catch (Exception e) {
                        log.error("Failed to update stats for {}-{}", entry.getKey(), e.getMessage());
                    }
                }
            }
        }
    }
    /**
     * Private methods for internal use.  Should not be used outside of this class
     */

    @VisibleForTesting
    void insertStopAction(FunctionRuntimeInfo functionRuntimeInfo) {
        FunctionAction functionAction = new FunctionAction();
        functionAction.setAction(FunctionAction.Action.STOP);
        functionAction.setFunctionRuntimeInfo(functionRuntimeInfo);
        try {
            actionQueue.put(functionAction);
        } catch (InterruptedException ex) {
            throw new RuntimeException("Interrupted while putting action");
        }

    }

    @VisibleForTesting
    void insertStartAction(FunctionRuntimeInfo functionRuntimeInfo) {
        FunctionAction functionAction = new FunctionAction();
        functionAction.setAction(FunctionAction.Action.START);
        functionAction.setFunctionRuntimeInfo(functionRuntimeInfo);
        try {
            actionQueue.put(functionAction);
        } catch (InterruptedException ex) {
            throw new RuntimeException("Interrupted while putting action");
        }
    }

    private Assignment findAssignment(String tenant, String namespace, String functionName, int instanceId) {
        String fullyQualifiedInstanceId
                = Utils.getFullyQualifiedInstanceId(tenant, namespace, functionName, instanceId);
        for (Map.Entry<String, Map<String, Assignment>> entry : this.workerIdToAssignments.entrySet()) {
            Map<String, Assignment> assignmentMap = entry.getValue();
            Assignment existingAssignment = assignmentMap.get(fullyQualifiedInstanceId);
            if (existingAssignment != null) {
                return existingAssignment;
            }
        }
        return null;
    }

    private Assignment findAssignment(Assignment assignment) {
        return findAssignment(
                assignment.getInstance().getFunctionMetaData().getFunctionDetails().getTenant(),
                assignment.getInstance().getFunctionMetaData().getFunctionDetails().getNamespace(),
                assignment.getInstance().getFunctionMetaData().getFunctionDetails().getName(),
                assignment.getInstance().getInstanceId()
        );
    }

    @VisibleForTesting
    void setAssignment(Assignment assignment) {
        if (!this.workerIdToAssignments.containsKey(assignment.getWorkerId())) {
            this.workerIdToAssignments.put(assignment.getWorkerId(), new HashMap<>());
        }
        this.workerIdToAssignments.get(assignment.getWorkerId()).put(
                Utils.getFullyQualifiedInstanceId(assignment.getInstance()),
                assignment);
    }

    @VisibleForTesting
    void deleteAssignment(Assignment assignment) {
        Map<String, Assignment> assignmentMap = this.workerIdToAssignments.get(assignment.getWorkerId());
        if (assignmentMap != null) {
            String fullyQualifiedInstanceId = Utils.getFullyQualifiedInstanceId(assignment.getInstance());
            if (assignmentMap.containsKey(fullyQualifiedInstanceId)) {
                assignmentMap.remove(fullyQualifiedInstanceId);
            }
            if (assignmentMap.isEmpty()) {
                this.workerIdToAssignments.remove(assignment.getWorkerId());
            }
        }
    }

    private void deleteFunctionRuntimeInfo(String fullyQualifiedInstanceId) {
        this.functionRuntimeInfoMap.remove(fullyQualifiedInstanceId);
    }

    private void setFunctionRuntimeInfo(String fullyQualifiedInstanceId, FunctionRuntimeInfo functionRuntimeInfo) {
        this.functionRuntimeInfoMap.put(fullyQualifiedInstanceId, functionRuntimeInfo);
    }

    @Override
    public void close() throws Exception {
        this.functionActioner.close();
        this.functionAssignmentTailer.close();
        if (runtimeFactory != null) {
            runtimeFactory.close();
        }
    }

    private Map<String, Assignment> diff(Map<String, Assignment> assignmentMap1, Map<String, Assignment> assignmentMap2) {
        Map<String, Assignment> result = new HashMap<>();
        for (Map.Entry<String, Assignment> entry : assignmentMap1.entrySet()) {
            if (!assignmentMap2.containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Assignment> inCommon(Map<String, Assignment> assignmentMap1, Map<String, Assignment> assignmentMap2) {

        Map<String, Assignment> result = new HashMap<>();
        for (Map.Entry<String, Assignment> entry : assignmentMap1.entrySet()) {
            if (assignmentMap2.containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private FunctionRuntimeInfo getFunctionRuntimeInfo(String fullyQualifiedInstanceId) {
        return this.functionRuntimeInfoMap.get(fullyQualifiedInstanceId);
    }
}
