// SPDX-License-Identifier: Apache-2.0
module com.swirlds.demo.hello {
    requires com.hedera.node.hapi;
    requires com.hedera.pbj.runtime;
    requires com.swirlds.common;
    requires com.swirlds.platform.core.test.fixtures;
    requires com.swirlds.platform.core;
    requires com.swirlds.state.api;
    requires com.swirlds.state.impl;
    requires com.swirlds.virtualmap;
    requires org.hiero.base.utility;
    requires org.hiero.consensus.model;
    requires org.hiero.consensus.utility;
    requires java.desktop;
    requires org.apache.logging.log4j;
    requires static com.github.spotbugs.annotations;
}
