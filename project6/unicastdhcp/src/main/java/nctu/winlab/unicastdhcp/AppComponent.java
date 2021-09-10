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
package nctu.winlab.unicastdhcp;


import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;
import static org.onosproject.net.topology.HopCountLinkWeigher.DEFAULT_HOP_COUNT_WEIGHER;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
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
import org.onosproject.net.Path;
import org.onosproject.net.DisjointPath;
import org.onosproject.net.ElementId;
/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {
	private static final int DEFAULT_PRIORITY = 10;

	private final Logger log = LoggerFactory.getLogger(getClass());
	
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
  	protected NetworkConfigRegistry cfgServiceN;

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
	
	private static String DHCP_server_ID = "DHCP_server_ID";
	private static String DHCP_server_PORT = "DHCP_server_PORT";
	private final DhcpConfigListener cfgListener = new DhcpConfigListener();
  	private final ConfigFactory factory =
      		new ConfigFactory<ApplicationId, DhcpConfig>(
          		APP_SUBJECT_FACTORY, DhcpConfig.class, "UnicastDhcpConfig") {
        	@Override
        		public DhcpConfig createConfig() {
          		return new DhcpConfig();
        		}
      		};
		
	private ApplicationId appId;
	
	private MyPacketProcessor processor = new MyPacketProcessor();
	private static ArrayList<TopologyVertex> shortestPath = new ArrayList<TopologyVertex>();
	public TopologyVertex source_switch;
	public TopologyVertex destination_switch;

	@Activate
	protected void activate() {
		packetService.addProcessor(processor, PacketProcessor.director(2));
		appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
		cfgServiceN.addListener(cfgListener);
    		cfgServiceN.registerConfigFactory(factory);
		requestIntercepts();
		log.info("Started");
	}

	@Deactivate
	protected void deactivate() {
		cfgServiceN.removeListener(cfgListener);
    		cfgServiceN.unregisterConfigFactory(factory);
		packetService.removeProcessor(processor);
		flowRuleService.removeFlowRulesById(appId);
		log.info("Stopped");
	}

	private void requestIntercepts() {
        	TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        	selector.matchEthType(Ethernet.TYPE_IPV4);
        	packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
	}

	private class DhcpConfigListener implements NetworkConfigListener {
	    @Override
	    public void event(NetworkConfigEvent event) {
	      if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
		  && event.configClass().equals(DhcpConfig.class)) {
		DhcpConfig config = cfgServiceN.getConfig(appId, DhcpConfig.class);
		if (config != null) {
		  DHCP_server_ID = config.name().split("/")[0];
		  DHCP_server_PORT = config.name().split("/")[1];
		  log.info("DHCP server is at {}", config.name());
		}
	      }
	    }
	  }

	private class MyPacketProcessor implements PacketProcessor {
		@Override
		public void process(PacketContext context) {
			if (context.isHandled()) {
                		return;
			}
			for(int i=0;i<1000000000;i++){
				
			}
			InboundPacket pkt = context.inPacket();
			Ethernet ethPkt = pkt.parsed();
			IPv4 ipv4Packet = (IPv4) ethPkt.getPayload();
			IpAddress DhcpIp = IpAddress.valueOf(IPv4.toIPv4Address("56.56.56.56"));
			IpAddress sourceIp = IpAddress.valueOf(ipv4Packet.getSourceAddress());			
			MacAddress sourceMac = ethPkt.getSourceMAC();
			MacAddress destinationMac = ethPkt.getDestinationMAC();
			MacAddress macFlood = MacAddress.valueOf("FF:FF:FF:FF:FF:FF");
			Topology topo = topologyService.currentTopology();
			TopologyGraph graph = topologyService.getGraph(topo);
			DeviceId device = pkt.receivedFrom().deviceId();
			for(TopologyVertex v: graph.getVertexes()){
				if(v.deviceId().equals(DeviceId.deviceId(DHCP_server_ID))){
					for(Host h: hostService.getConnectedHosts(v.deviceId())){
						if(h.location().port().equals(PortNumber.portNumber(DHCP_server_PORT))){
							for(IpAddress ip: h.ipAddresses()){
								DhcpIp = ip;
							}						
						}
					}
				}
			}
			log.info("{} {}", device,DhcpIp);
			Set<Path> host_to_server = topologyService.getPaths(topo, device, DeviceId.deviceId(DHCP_server_ID));
			if (destinationMac.equals(macFlood)){
				if(host_to_server.isEmpty()){
					if(DhcpIp.equals(sourceIp)){
						log.info("flood");
						context.treatmentBuilder().setOutput(PortNumber.FLOOD);
						context.send();
						return;
					}
					PortNumber portNumber = PortNumber.portNumber(DHCP_server_PORT);
					TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
					selectorBuilder.matchEthType((short)2048);						
					selectorBuilder.matchEthSrc(sourceMac);
					TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(portNumber).build();
					ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
										.withSelector(selectorBuilder.build())
										.withTreatment(treatment)
										.withPriority(10)
										.withFlag(ForwardingObjective.Flag.VERSATILE)
										.fromApp(appId)
										.makeTemporary(10)
										.add();
					flowObjectiveService.forward(device, forwardingObjective);
					for(Host h: hostService.getHostsByMac(sourceMac)){
							portNumber = h.location().port();
							selectorBuilder = DefaultTrafficSelector.builder();
							selectorBuilder.matchEthType((short)2048);						
							selectorBuilder.matchEthDst(sourceMac);
							treatment = DefaultTrafficTreatment.builder().setOutput(portNumber).build();
							forwardingObjective = DefaultForwardingObjective.builder()
									.withSelector(selectorBuilder.build())
									.withTreatment(treatment)
									.withPriority(10)
									.withFlag(ForwardingObjective.Flag.VERSATILE)
									.fromApp(appId)
									.makeTemporary(10)
									.add();
							flowObjectiveService.forward(device, forwardingObjective);
							break;
					}
					log.info("no path install {}", device);
					
				}
				else{
					Path[] host_to_server_path_list = host_to_server.toArray(new Path[0]);
					for(int i = host_to_server_path_list[0].links().size()-1 ; i >= 0 ; i--){
						if(i == host_to_server_path_list[0].links().size()-1){
							PortNumber portNumber = PortNumber.portNumber(DHCP_server_PORT);
							TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
							selectorBuilder.matchEthType((short)2048);						
							selectorBuilder.matchEthSrc(sourceMac);
							TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(portNumber).build();
							ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
												.withSelector(selectorBuilder.build())
												.withTreatment(treatment)
												.withPriority(10)
												.withFlag(ForwardingObjective.Flag.VERSATILE)
												.fromApp(appId)
												.makeTemporary(10)
												.add();
							flowObjectiveService.forward(host_to_server_path_list[0].links().get(i).dst().deviceId(), forwardingObjective);
						}					
						TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
						selectorBuilder.matchEthType((short)2048);						
						selectorBuilder.matchEthSrc(sourceMac);
						TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(host_to_server_path_list[0].links().get(i).src().port()).build();
						ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
											.withSelector(selectorBuilder.build())
											.withTreatment(treatment)
											.withPriority(10)
											.withFlag(ForwardingObjective.Flag.VERSATILE)
											.fromApp(appId)
											.makeTemporary(10)
											.add();
						flowObjectiveService.forward(host_to_server_path_list[0].links().get(i).src().deviceId(), forwardingObjective);					
					}
					for(int i = host_to_server_path_list[0].links().size()-1 ; i >= 0 ; i--){
						if(i == 0){
							for(Host h: hostService.getHostsByMac(sourceMac)){
								PortNumber portNumber = h.location().port();
								TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
								selectorBuilder.matchEthType((short)2048);						
								selectorBuilder.matchEthDst(sourceMac);
								TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(portNumber).build();
								ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
													.withSelector(selectorBuilder.build())
													.withTreatment(treatment)
													.withPriority(10)
													.withFlag(ForwardingObjective.Flag.VERSATILE)
													.fromApp(appId)
													.makeTemporary(10)
													.add();
								flowObjectiveService.forward(host_to_server_path_list[0].links().get(i).src().deviceId(), forwardingObjective);
								break;
							}
						
						}
						TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
						selectorBuilder.matchEthType((short)2048);						
						selectorBuilder.matchEthDst(sourceMac);
						TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(host_to_server_path_list[0].links().get(i).dst().port()).build();
						ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
											.withSelector(selectorBuilder.build())
											.withTreatment(treatment)
											.withPriority(10)
											.withFlag(ForwardingObjective.Flag.VERSATILE)
											.fromApp(appId)
											.makeTemporary(10)
											.add();
						flowObjectiveService.forward(host_to_server_path_list[0].links().get(i).dst().deviceId(), forwardingObjective);
					}
					log.info("install {}", device);
				}
				
			}

		}
	}	
}
