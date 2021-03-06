/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.gsc.net.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.gsc.config.Args;
import org.gsc.net.discover.NodeHandler;
import org.gsc.net.discover.NodeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class SyncPool {

  public static final Logger logger = LoggerFactory.getLogger("SyncPool");

  private static final long WORKER_TIMEOUT = 15;

  private final List<Channel> activePeers = Collections.synchronizedList(new ArrayList<Channel>());

  @Autowired
  private NodeManager nodeManager;

  @Autowired
  private ApplicationContext ctx;

  private ChannelManager channelManager;

  @Autowired
  private Args args ;

  private int maxActiveNodes = args.getNodeMaxActiveNodes() > 0 ? args.getNodeMaxActiveNodes() : 30;

  private ScheduledExecutorService poolLoopExecutor = Executors.newSingleThreadScheduledExecutor();

  private ScheduledExecutorService logExecutor = Executors.newSingleThreadScheduledExecutor();



  @Autowired
  public SyncPool() {

  }

  public void init() {
    channelManager = ctx.getBean(ChannelManager.class);

    poolLoopExecutor.scheduleWithFixedDelay(() -> {
      try {
        fillUp();
        prepareActive();
      } catch (Throwable t) {
        logger.error("Exception in sync worker", t);
      }
    }, WORKER_TIMEOUT, WORKER_TIMEOUT, TimeUnit.SECONDS);

    logExecutor.scheduleWithFixedDelay(() -> {
      try {
        logActivePeers();
      } catch (Throwable t) {}
    }, 10, 10, TimeUnit.SECONDS);
  }

  private void fillUp() {
    int lackSize = maxActiveNodes - channelManager.getActivePeers().size();
    if(lackSize <= 0) return;

    final Set<String> nodesInUse = channelManager.nodesInUse();
    nodesInUse.add(nodeManager.getPublicHomeNode().getHexId());

    List<NodeHandler> newNodes = nodeManager.getNodes(new NodeSelector(nodesInUse), lackSize);
//    newNodes.forEach(n ->  peerClient.connectAsync(n.getNode().getHost(), n.getNode().getPort(),
//                n.getNode().getHexId(), false));
  }

  private synchronized void prepareActive() {
    for (Channel channel : channelManager.getActivePeers()) {
      if (!activePeers.contains(channel)) {
//        activePeers.add((PeerConnection)channel);
//        peerDel.onConnectPeer((PeerConnection)channel);
      }
    }
    activePeers.sort(Comparator.comparingDouble(c -> c.getPeerStats().getAvgLatency()));
  }

  synchronized void logActivePeers() {

  }

  public synchronized List<Channel> getActivePeers() {
    return new ArrayList<>(activePeers);
  }

  public synchronized void onDisconnect(Channel peer) {
    if (activePeers.contains(peer)) {
      activePeers.remove(peer);
     }
  }

  public void close() {
    try {
      poolLoopExecutor.shutdownNow();
      logExecutor.shutdownNow();
    } catch (Exception e) {
      logger.warn("Problems shutting down executor", e);
    }
  }

  class NodeSelector implements Predicate<NodeHandler> {

    Set<String> nodesInUse;

    public NodeSelector(Set<String> nodesInUse) {
      this.nodesInUse = nodesInUse;
    }

    @Override
    public boolean test(NodeHandler handler) {

//      if (!nodeManager.isNodeAlive(handler)){
//        return false;
//      }

      if (handler.getNode().getHost().equals(nodeManager.getPublicHomeNode().getHost()) &&
              handler.getNode().getPort() == nodeManager.getPublicHomeNode().getPort()) {
        return false;
      }

      if (channelManager.isRecentlyDisconnected(handler.getInetSocketAddress().getAddress())){
          return false;
      }

      if (nodesInUse != null && nodesInUse.contains(handler.getNode().getHexId())) {
        return false;
      }

      if (handler.getNodeStatistics().getReputation() < 100) {
        return false;
      }

      return  true;
    }
  }

}
