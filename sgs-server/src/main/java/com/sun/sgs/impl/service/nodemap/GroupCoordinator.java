/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.impl.service.nodemap;

import com.sun.sgs.app.ExceptionRetryStatus;
import com.sun.sgs.auth.Identity;
import com.sun.sgs.impl.service.nodemap.affinity.AffinityGroupFinder;
import com.sun.sgs.impl.service.nodemap.affinity.AffinityGroupFinderFailedException;
import com.sun.sgs.impl.service.nodemap.affinity.BasicState;
import com.sun.sgs.impl.service.nodemap.affinity.RelocatingAffinityGroup;
import com.sun.sgs.impl.service.nodemap.affinity.graph.AffinityGraphBuilder;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import com.sun.sgs.impl.sharedutil.PropertiesWrapper;
import com.sun.sgs.impl.util.AbstractKernelRunnable;
import com.sun.sgs.kernel.ComponentRegistry;
import com.sun.sgs.kernel.RecurringTaskHandle;
import com.sun.sgs.kernel.TaskScheduler;
import com.sun.sgs.service.DataService;
import com.sun.sgs.service.Node;
import com.sun.sgs.service.TransactionProxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A group coordinator manages groups of identities. Since grouping activities
 * may generate load on the system, the coordinator may be disabled if
 * conditions merit.<p>
 *
 * A newly constructed coordinator will be in the disabled state.<p>
 *
 * The following property is supported:<p>
 * 
 * <dl style="margin-left: 1em">
 * 
 * <dt>	<i>Property:</i> <code><b>
 *   {@value #UPDATE_FREQ_PROPERTY}
 *	</b></code><br>
 *	<i>Default:</i> {@value DEFAULT_UPDATE_FREQ} (one minute)}
 * <br>
 *
 * <dd style="padding-top: .5em">The frequency that we find affinity groups,
 *  in seconds.  The value must be between {@code 5} and {@code 65535}.<p>
 * </dl><p>
 *
 * TODO - This class is public only because tests need to get at
 * UPDATE_FREQ_PROPERTY.  Grrr.
 */
public class GroupCoordinator extends BasicState {

    /** Package name for this class. */
    private static final String PKG_NAME =
                    "com.sun.sgs.impl.service.nodemap.foo";

    /** The property name for the update frequency. */
    public static final String UPDATE_FREQ_PROPERTY =
        PKG_NAME + ".update.freq";

    /** The default value of the update frequency, in seconds. */
    static final int DEFAULT_UPDATE_FREQ = 60;

    private final LoggerWrapper logger =
                new LoggerWrapper(Logger.getLogger(PKG_NAME + ".coordinator"));

    // Node mapping server
    private final NodeMappingServerImpl server;

    // Data service
    private final DataService dataService;

    // Graph builder
    private final AffinityGraphBuilder builder;

    // Group finder or null if the group subsystem is not configured.
    private final AffinityGroupFinder finder;

    private final TaskScheduler taskScheduler;
    private final Identity taskOwner;
    private final long updatePeriod;

    private RecurringTaskHandle updateTask = null;
    private RecurringTaskHandle collocateTask = null;

    // Map of per-node groups. This map is filled-in only when necessary due
    // to a request to offload a node.
    // The maping is targetNodeID -> groupSet where groupSet is groupId -> group
    private final Map<Long, NavigableSet<RelocatingAffinityGroup>> nodeSets;

    // Sorted set of all the groups
    private NavigableSet<RelocatingAffinityGroup> groups = null;

    /**
     * Public constructor.
     *
     * @param properties server properties
     * @param server node mapping server
     * @param systemRegistry system registry
     * @param txnProxy transaction proxy
     */
    GroupCoordinator(Properties properties,
                     ComponentRegistry systemRegistry,
                     TransactionProxy txnProxy,
                     NodeMappingServerImpl server,
                     AffinityGraphBuilder builder)
        throws Exception
    {
        dataService = txnProxy.getService(DataService.class);
        this.server = server;
        this.builder = builder;

        finder = builder != null ? builder.getAffinityGroupFinder() : null;

        if (finder != null) {
            PropertiesWrapper wrappedProps = new PropertiesWrapper(properties);
            int updateSeconds =wrappedProps.getIntProperty(UPDATE_FREQ_PROPERTY,
                                                           DEFAULT_UPDATE_FREQ,
                                                           5, 65535);
            updatePeriod = updateSeconds * 1000L;
            taskScheduler = systemRegistry.getComponent(TaskScheduler.class);
            taskOwner = txnProxy.getCurrentOwner();
            nodeSets =
                    new HashMap<Long, NavigableSet<RelocatingAffinityGroup>>();
            logger.log(Level.CONFIG,
                       "Created GroupCoordinator with finder: "
                       + finder.getClass().getName() +
                       " with properties:" +
                       "\n  " + UPDATE_FREQ_PROPERTY + "=" +
                       updateSeconds + " (seconds)");
        } else {
            assert builder == null;
            updatePeriod = 0;
            taskScheduler = null;
            taskOwner = null;
            nodeSets = null;
            logger.log(Level.CONFIG,
                       "Created GroupCoordinator with null finder");
        }
        setDisabledState();
    }

    /**
     * Enable coordination. If the coordinator is enabled, calling this method
     * will have no effect.
     */
    public void enable() {
        if (setEnabledState() && (builder != null)) {
            builder.enable();
            if ((finder != null) && (updateTask == null)) {   // TODO .. sync?
                updateTask = taskScheduler.scheduleRecurringTask(
                                    new AbstractKernelRunnable("UpdateTask") {
                                            public void run() {
                                                findGroups();
                                            } },
                                    taskOwner,
                                    System.currentTimeMillis() + updatePeriod,
                                    updatePeriod);
                updateTask.start();
            }
        }
    }

    /**
     * Disable coordination. If the coordinator is disabled, calling this method
     * will have no effect.
     *
     * TODO - should the groups be cleared out? they will be useful for some
     * time but will eventually become stale
     */
    public void disable() {
        if (setDisabledState() && (builder != null)) {
            if (updateTask != null) {   // TODO... sync?
                updateTask.cancel();
                updateTask = null;
            }
            builder.disable();
        }
    }

    /**
     * Shutdown the coordinator. The coordinator is disabled and
     * all resources released. Any further method calls made on the coordinator
     * will result in a {@code IllegalStateException} being thrown.
     */
    public void shutdown() {
        if (setShutdownState()) {
            if (updateTask != null) {   // TODO... sync?
                updateTask.cancel();
                updateTask = null;
            }
            // Note that the builder is shutdown by the service
        }
    }

    /**
     * Move one or more identities off of a node. If the old node is alive
     * a group of identities will be selected to move. The group is
     * selected is based on how the groups are sorted in the node set. If
     * there are no groups to move, a single identity is moved.
     * If the node is not alive, all identities on that node will be moved.
     * Note that is this method does not guarantee that any identities are
     * be moved.
     *
     * @param node the node to offload identities
     *
     * @throws NullPointerException if {@code node} is {@code null}
     * @throws NoNodesAvailableException if no nodes are available
     */
    void offload(Node node) throws NoNodesAvailableException {
        checkForShutdownState();

        if (node == null) {
            throw new NullPointerException("node can not be null");
        }

        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "Request to offload {0}", node);
        }

        final long nodeId = node.getId();
        Iterator<RelocatingAffinityGroup> iter = getNodeSet(nodeId).iterator();

        while (iter.hasNext()) {
            checkForShutdownState();

            long newNodeId = server.chooseNode();

            // Could happen if things got better, so quit
            if (newNodeId == nodeId) {
                return;
            }
            RelocatingAffinityGroup group = iter.next();
            iter.remove();

            // Re-target the group, note that we do not re-insert the group into
            // the new node's node set (even if there is one).
            group.setTargetNode(newNodeId);
            collocateGroup(group);

            // If the node is alive, then just move off one group
            if (node.isAlive()) {
                return;
            }
        }
        // If we are here either 1) the group sub-system is not running or it is
        // running and no groups were found, in either case offloadSingles()
        // will move an individual identity, or 2) we were moving everyone off,
        // and offloadSingles() will move any remaining identities that were not
        // in a group.
        offloadSingles(node);
    }

    /**
     * Get the groups on the specified node.
     *
     * @param nodeId a node id
     * @return a set of groups, the set may be empty
     */
    private Set<RelocatingAffinityGroup> getNodeSet(long nodeId) {

        // if the group subsystem is not running, just return an empty set
        if (nodeSets == null) {
            return Collections.emptySet();
        }

        synchronized (nodeSets) {
            NavigableSet<RelocatingAffinityGroup> nodeSet =nodeSets.get(nodeId);

            if (nodeSet == null) {
                // The group order is defined by RelocatingAffinityGroup
                nodeSet = new TreeSet<RelocatingAffinityGroup>();

                Set<RelocatingAffinityGroup> currentGroups = groups;
                if (currentGroups != null) {
                    for (RelocatingAffinityGroup group : currentGroups) {
                        if (group.getTargetNode() == nodeId) {
                            nodeSet.add(group);
                        }
                    }
                }
                nodeSets.put(nodeId, nodeSet);
            }
            return nodeSet;
        }
    }

    /**
     * Collocate the identities of a group onto the group's target node. If the
     * target node is unknown, then just pick one.
     *
     * @param group the group to collocate
     * @return true if the group has had any identities moved, otherwise false
     * @throws NoNodesAvailableException if the target node is unavailable, or
     *         if the target node is unknown and a new one is not available
     */
    private boolean collocateGroup(RelocatingAffinityGroup group)
            throws NoNodesAvailableException
    {
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "Request to collocate {0}", group);
        }
        long targetNodeId = group.getTargetNode();

        if (targetNodeId < 0) {
            targetNodeId = server.chooseNode();
            group.setTargetNode(targetNodeId);
        }

        Set<Identity> identities = group.findStragglers();

        if (logger.isLoggable(Level.FINER)) {
            logger.log(Level.FINER,
                       "Collocating {0} members of group {1} to node {2}",
                       identities.size(), group.getId(), targetNodeId);
        }
        for (Identity identity : identities) {
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINEST, "Collocating id {0}", identity);
            }
            server.moveIdentity(identity, null, targetNodeId);
        }
        return !identities.isEmpty();
    }

    /**
     * Try to gather a new set of groups, pushing the results if successful.
     */
    private void findGroups() {
        checkForDisabledOrShutdownState();
        
        if (collocateTask != null) {
            collocateTask.cancel();
            collocateTask = null;
        }
        // TODO - Better to leave old groups around until new are found?
//        groups = null;
        synchronized (nodeSets) {
            nodeSets.clear();
        }

        try {
            groups = finder.findAffinityGroups();

            if (logger.isLoggable(Level.FINER)) {
                logger.log(Level.FINER,
                           "findAffinityGroups returned {0} groups",
                           groups.size());
            }

            if (!groups.isEmpty()) {
                collocateTask = taskScheduler.scheduleRecurringTask(
                                    new CollocateTask(groups),
                                    taskOwner,
                                    System.currentTimeMillis() + updatePeriod,
                                    updatePeriod);
            }
        } catch (AffinityGroupFinderFailedException e) {
            logger.logThrow(Level.INFO, e, "Affinity group finder failed");
        }
    }

    /**
     * Task to move the collocate the identities the current set of groups.
     * Groups selected in ascending order from {@code groups}.
     */
    private class CollocateTask extends AbstractKernelRunnable {

        private final Iterator<RelocatingAffinityGroup> iter;

        CollocateTask(Set<RelocatingAffinityGroup> groups) {
            super("CollocateTask");
            iter = groups.iterator();
        }

        @Override
        public void run() throws Exception {
            // Run through the list until there was a successful move
            while (iter.hasNext() && !collocateGroup(iter.next())) {}
        }
    }

    /**
     * Move one or more identities off of a node. If the old node is alive
     * an identity will be selected to move. If the node is not alive,
     * all identities on that node will be moved. Note that is this method does
     * not guarantee that any identities are be moved.
     *
     * @param node the node to offload identities
     *
     * @throws NoNodesAvailableException if no nodes are available
     */
    private void offloadSingles(Node node) throws NoNodesAvailableException {
        final long nodeId = node.getId();

        // Look up each identity on the old node and move it
        String nodekey = NodeMapUtil.getPartialNodeKey(nodeId);
        GetIdOnNodeTask task =
                new GetIdOnNodeTask(dataService, nodekey, logger);

        do {
            checkForShutdownState();
            try {
                // Find an identity on the node
                server.runTransactionally(task);
            } catch (Exception ex) {
                logger.logThrow(Level.WARNING, ex,
                                "Failed to find identity on node {1}",
                                nodeId);
                break;
            }
            if (task.done()) {
                break;
            }
            server.moveIdentity(task.getId().getIdentity(), node, -1);

        // if the node is alive, only move one id
        } while (!node.isAlive());
    }

    /**
     * Task to find identities on a node.
     */
    private static class GetIdOnNodeTask extends AbstractKernelRunnable {
        /** Set to true when no more identities to be found (or a failure) */
        private boolean done = false;
        /** If !done, the identity we were looking for */
        private IdentityMO idmo = null;

        private final DataService dataService;
        private final String nodekey;
        private final LoggerWrapper logger;

        GetIdOnNodeTask(DataService dataService,
                        String nodekey, LoggerWrapper logger)
        {
	    super("GetIdOnNodeTask for " + nodekey);
            this.dataService = dataService;
            this.nodekey = nodekey;
            this.logger = logger;
        }

        public void run() throws Exception {
            try {
                String key = dataService.nextServiceBoundName(nodekey);
                done = (key == null || !key.contains(nodekey));
                if (!done) {
                    idmo = (IdentityMO)dataService.getServiceBinding(key);
                }
            } catch (Exception e) {
                if ((e instanceof ExceptionRetryStatus) &&
                    (((ExceptionRetryStatus)e).shouldRetry()))
                {
                    throw e;
                }
                done = true;
                logger.logThrow(Level.WARNING, e,
                                "Failed to get key or binding for {0}",
                                nodekey);
            }
        }

        /**
         * Returns true if there are no more identities to be found.
         * @return {@code true} if no more identities could be found for the
         *          node, {@code false} otherwise.
         */
        public boolean done() {
            return done;
        }

        /**
         * The identity MO retrieved from the data store, or null if
         * the task has not yet executed or there was an error while
         * executing.
         * @return the IdentityMO
         */
        public IdentityMO getId() {
            return idmo;
        }
    }
}
