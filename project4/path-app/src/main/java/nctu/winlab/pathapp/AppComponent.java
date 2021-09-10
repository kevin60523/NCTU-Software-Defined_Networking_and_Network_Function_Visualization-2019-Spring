/*
 * Copyright 2019-present Open Networking Foundation
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
package nctu.winlab.pathapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.collect.ImmutableSet;
import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.onlab.packet.MacAddress;
import org.onlab.packet.Ethernet;
import org.onlab.util.KryoNamespace;
import org.onlab.packet.IPv4;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.WallClockTimestamp;
import org.onosproject.store.service.MultiValuedTimestamp;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.cfg.ComponentConfigService;
import java.util.Dictionary;
import java.util.Properties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import static org.onlab.util.Tools.get;
import org.onlab.graph.Vertex;
import org.onosproject.net.Description;
import org.onosproject.net.topology.Topology;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.topology.TopologyGraph;
import org.onosproject.net.topology.TopologyEdge;
import org.onosproject.net.topology.TopologyVertex;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.link.LinkDescription;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.Link;
import org.onosproject.net.Port;
import org.onosproject.net.host.HostService;
import java.util.HashSet;
import java.util.Iterator;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import org.onlab.packet.IpAddress;
import java.util.ArrayDeque;
import java.util.*;
/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {
	private static final int DEFAULT_PRIORITY = 10;

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PacketService packetService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowRuleService flowRuleService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected CoreService coreService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
    	protected ComponentConfigService cfgService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowObjectiveService flowObjectiveService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected TopologyService topologyService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected HostService hostService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected LinkService linkService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected DeviceService deviceService;



	private ApplicationId appId;
	
	private MyPacketProcessor processor = new MyPacketProcessor();
	private static ArrayList<TopologyVertex> shortestPath = new ArrayList<TopologyVertex>();
	public TopologyVertex source_switch;
	public TopologyVertex destination_switch;

	@Activate
	protected void activate() {
		packetService.addProcessor(processor, PacketProcessor.director(2));
		appId = coreService.registerApplication("nctu.winlab.pathapp");
		requestIntercepts();
		log.info("Started");
	}

	@Deactivate
	protected void deactivate() {
		packetService.removeProcessor(processor);
		flowRuleService.removeFlowRulesById(appId);
		log.info("Stopped");
	}

	private void requestIntercepts() {
        	TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        	selector.matchEthType(Ethernet.TYPE_IPV4);
        	packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
	}

	

	private class MyPacketProcessor implements PacketProcessor {
		@Override
		public void process(PacketContext context) {
			if (context.isHandled()) {
                		return;
			}
			Topology topo = topologyService.currentTopology();
			TopologyGraph graph = topologyService.getGraph(topo);

			InboundPacket pkt = context.inPacket();
			DeviceId device = pkt.receivedFrom().deviceId();
			Ethernet ethPkt = pkt.parsed();
			MacAddress sourceMac = ethPkt.getSourceMAC();
			MacAddress destinationMac = ethPkt.getDestinationMAC();
			log.info("Packet-in from device of:{}", device);

			for(TopologyVertex v: graph.getVertexes()){
				for(Host h: hostService.getConnectedHosts(v.deviceId())){
					if(h.mac().equals(sourceMac)){
						source_switch = v;
					}
					if(h.mac().equals(destinationMac)){
						destination_switch = v;
					}
				}
			}
			shortestPath.clear();
			
			ArrayList<TopologyVertex> path = bfs(graph, source_switch, destination_switch);
			log.info("Start to install path from {} to {}", destinationMac, sourceMac);
			Vector<IpAddress> srcip = new Vector<IpAddress>();
            		Vector<IpAddress> desip = new Vector<IpAddress>();
			for(Host h: hostService.getHostsByMac(destinationMac)){
				for(IpAddress ip: h.ipAddresses()){
					desip.addElement(ip);
				}
			}
			for(Host h: hostService.getHostsByMac(sourceMac)){
				for(IpAddress ip: h.ipAddresses()){
					srcip.addElement(ip);
				}
			}
			for(int i = path.size() - 1; i >= 0; i--){
				if(i == path.size() - 1){
					for(Host h: hostService.getHostsByMac(destinationMac)){
						PortNumber portNumber = h.location().port();
						TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
						selectorBuilder.matchEthType(ethPkt.getEtherType());
						selectorBuilder.matchIPDst(desip.get(0).toIpPrefix());
            					selectorBuilder.matchIPSrc(srcip.get(0).toIpPrefix());

						TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(portNumber).build();

						ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
			                						 .withSelector(selectorBuilder.build())
			                						 .withTreatment(treatment)
			                						 .withPriority(10)
											 .makeTemporary(30)
			                						 .withFlag(ForwardingObjective.Flag.VERSATILE)
			                   						 .fromApp(appId)
											 .add();

						flowObjectiveService.forward(path.get(i).deviceId(), forwardingObjective);

						log.info("Install flow rule on {}", path.get(i).deviceId().toString());
						break;
					}
				}
				for(Link l: linkService.getDeviceEgressLinks(path.get(i).deviceId())){
					
					if(i != path.size() - 1 && l.dst().deviceId().equals(path.get(i + 1).deviceId())){
						PortNumber portNumber = l.src().port();
						TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
						selectorBuilder.matchEthType(ethPkt.getEtherType());
						selectorBuilder.matchIPDst(desip.get(0).toIpPrefix());
            					selectorBuilder.matchIPSrc(srcip.get(0).toIpPrefix());

						TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(portNumber).build();

						ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
											 .withSelector(selectorBuilder.build())
											 .withTreatment(treatment)
											 .withPriority(10)
											 .makeTemporary(30)
											 .withFlag(ForwardingObjective.Flag.VERSATILE)
											 .fromApp(appId)
											 .add();

						flowObjectiveService.forward(l.src().deviceId(), forwardingObjective);

						log.info("Install flow rule on {}", l.src().deviceId().toString());
					}
					
				}
			}

		}
	}
	public static ArrayList<TopologyVertex> bfs(TopologyGraph graph, TopologyVertex source, TopologyVertex destination){
		ArrayList<TopologyVertex> path = new ArrayList<TopologyVertex>();
		if(source.equals(destination)){
			path.add(source);
			return path;
		}
		ArrayDeque<TopologyVertex> queue = new ArrayDeque<TopologyVertex>();
		ArrayDeque<TopologyVertex> visited = new ArrayDeque<TopologyVertex>();
		queue.offer(source);
		while(!queue.isEmpty()){
			TopologyVertex vertex = queue.poll();
			visited.offer(vertex);

			ArrayList<TopologyVertex> neighborList = new ArrayList<TopologyVertex>();
			for(TopologyEdge i: graph.getEdgesFrom(vertex)){
				neighborList.add(i.dst());
			}
			int index = 0;
			int neighborSize = neighborList.size();
			while(index != neighborSize){
				TopologyVertex neighbor = neighborList.get(index);
				path.add(neighbor);
				path.add(vertex);

				if(neighbor.equals(destination)){
					return processPath(source, destination, path);
				}else{
					if(!visited.contains(neighbor)){
						queue.offer(neighbor);
					}
				}
				index ++;
			}
		}
		return null;
	}
	public static ArrayList<TopologyVertex> processPath(TopologyVertex source, TopologyVertex destination, ArrayList<TopologyVertex> path){
		int index = path.indexOf(destination);
		TopologyVertex src = path.get(index + 1);
		shortestPath.add(0, destination);
		if(src.equals(source)){
			shortestPath.add(0, source);
			return shortestPath;
		}else{
			return processPath(source, src, path);
		}
	}
}
