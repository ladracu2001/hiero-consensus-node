// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.sync;

import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;

import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;
import org.hiero.consensus.gossip.FallenBehindManager;
import org.hiero.consensus.model.node.NodeId;

/**
 * A class that manages information about who we need to sync with, and whether we need to reconnect
 */
public class SyncManagerImpl implements FallenBehindManager {

    /** This object holds data on how nodes are connected to each other. */
    private final FallenBehindManager fallenBehindManager;

    /**
     * Creates a new SyncManager
     *
     * @param metrics             the metrics to use
     * @param fallenBehindManager the fallen behind manager
     */
    public SyncManagerImpl(@NonNull final Metrics metrics, @NonNull final FallenBehindManager fallenBehindManager) {

        this.fallenBehindManager = Objects.requireNonNull(fallenBehindManager);

        metrics.getOrCreate(
                new FunctionGauge.Config<>(INTERNAL_CATEGORY, "hasFallenBehind", Object.class, this::hasFallenBehind)
                        .withDescription("has this node fallen behind?"));
        metrics.getOrCreate(new FunctionGauge.Config<>(
                        INTERNAL_CATEGORY, "numReportFallenBehind", Integer.class, this::numReportedFallenBehind)
                .withDescription("the number of nodes that have fallen behind")
                .withUnit("count"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportFallenBehind(@NonNull final NodeId id) {
        fallenBehindManager.reportFallenBehind(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearFallenBehind(@NonNull final NodeId id) {
        fallenBehindManager.clearFallenBehind(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resetFallenBehind() {
        fallenBehindManager.resetFallenBehind();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasFallenBehind() {
        return fallenBehindManager.hasFallenBehind();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldReconnectFrom(@NonNull final NodeId peerId) {
        return fallenBehindManager.shouldReconnectFrom(peerId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int numReportedFallenBehind() {
        return fallenBehindManager.numReportedFallenBehind();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addRemovePeers(@NonNull final Set<NodeId> added, @NonNull final Set<NodeId> removed) {
        fallenBehindManager.addRemovePeers(added, removed);
    }
}
