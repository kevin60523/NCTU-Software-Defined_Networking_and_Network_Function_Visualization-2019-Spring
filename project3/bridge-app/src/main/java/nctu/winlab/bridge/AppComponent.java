/*
 * Copyright 2020-present Open Networking Foundation
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
package nctu.winlab.bridge;

import com.google.common.collect.ImmutableSet;
import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/*                                        */
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

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent{

    	private static final int DEFAULT_PRIORITY = 10;
	private static final int DEFAULT_TIMEOUT = 10;
	private final Logger log = LoggerFactory.getLogger(getClass());

	//攔截封包，選擇要攔截哪些封包，或是做哪些動作 
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PacketService packetService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected StorageService storageService;

	//用於新增flow rule
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowObjectiveService flowObjectiveService;
	
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
    	protected ComponentConfigService cfgService;
	//在使用onos的各種服務前，要先向CoreService註冊
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected CoreService coreService;
	//table:記錄所有host的資訊
	private  EventuallyConsistentMap<DeviceId, Map<MacAddress, PortNumber>> table;
	//封包進來的時候，進行處理的handler
	private ReactivePacketProcessor processor = new ReactivePacketProcessor();
	//ONOS下，每個app都要有自己的appId
	private ApplicationId appId;

    	@Activate
	protected void activate() {
		KryoNamespace.Builder metricSerializer = KryoNamespace.newBuilder()
		.register(KryoNamespaces.API)
		.register(MultiValuedTimestamp.class);
		table = storageService.<DeviceId, Map<MacAddress, PortNumber>>eventuallyConsistentMapBuilder()
		.withName("table")
		.withSerializer(metricSerializer)
		.withTimestampProvider((key, metricsData) -> new
		MultiValuedTimestamp<>(new WallClockTimestamp(), System.nanoTime()))
		.build();
		//cfgService.registerProperties(getClass());
		//ReactivePacketProcessor(這裡的變數是processor).process裡定義了接收到封包要做什麼動作
		packetService.addProcessor(processor, PacketProcessor.director(2));
		//註冊appId
		appId = coreService.registerApplication("nctu.winlab.bridge");
		//想要攔截哪些種類的封包
		requestIntercepts();
		log.info("Started");
	}

    	@Deactivate
	protected void deactivate() {
		cfgService.unregisterProperties(getClass(), false);
		withdrawIntercepts();
		log.info("Stopped");
	}
	private void requestIntercepts() {
		//Abstraction of a slice of network traffic.
		//Builder of traffic selector entities.
		TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
		selector.matchEthType(Ethernet.TYPE_IPV4);
		//要求讓ipv4的封包都packet in進來    
		packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);

	}
  
	/*
	* Cancel request for packet in via packet service.
	* 反正就是做跟requestIntercepts相反的事情
	*/
	private void withdrawIntercepts() {
		TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
		selector.matchEthType(Ethernet.TYPE_IPV4);
		packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
	}

    	private class ReactivePacketProcessor implements PacketProcessor {
        	//修改父母類別的function
		@Override
		public void process(PacketContext context) {
            
            		if (context.isHandled()) {
                		return;
			}
			InboundPacket pkt = context.inPacket();
			Ethernet ethPkt = pkt.parsed();

			if (ethPkt == null) {
    			        return;
            		}
			MacAddress sourceMac = ethPkt.getSourceMAC();
			MacAddress destinationMac = ethPkt.getDestinationMAC();
			DeviceId device = pkt.receivedFrom().deviceId();
			PortNumber inport = pkt.receivedFrom().port();
			if(table.containsKey(device)) {
				Map <MacAddress, PortNumber> macaddress = new HashMap<>();
				macaddress = table.get(device);
				if(!macaddress.containsKey(sourceMac)) {
					macaddress.put(sourceMac, inport);
				}
			} else{
				Map <MacAddress, PortNumber> macaddress = new HashMap<>();
				macaddress.put(sourceMac, inport);
				table.put(device, macaddress);
				
			}
 			          
            		//DeviceId, MacAddress device, destinationMac
			Map <MacAddress, PortNumber> packetDestination = new HashMap<>();
			packetDestination = table.get(device);
			// Check whether output data is in table
			if(!packetDestination.containsKey(destinationMac)) {
				// output data is not in table
				log.info("flood");
				context.treatmentBuilder().setOutput(PortNumber.FLOOD);
				context.send();
			} else{
				log.info("Contains");
				PortNumber outport = packetDestination.get(destinationMac);
				context.treatmentBuilder().setOutput(outport);
				context.send();

				TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
				selectorBuilder.matchEthSrc(ethPkt.getSourceMAC());
				selectorBuilder.matchEthDst(ethPkt.getDestinationMAC());

				TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(outport).build();

				ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
	                	.withSelector(selectorBuilder.build())
	                	.withTreatment(treatment)
	                	.withPriority(10)
				.makeTemporary(10)
	                	.withFlag(ForwardingObjective.Flag.VERSATILE)
	                	.fromApp(appId)
				.add();
				flowObjectiveService.forward(device, forwardingObjective);

				log.info("Sent");
			}
		}
	} 
}
