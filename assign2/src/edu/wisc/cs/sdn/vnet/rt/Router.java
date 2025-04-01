package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.MACAddress;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import net.floodlightcontroller.packet.UDP;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;

	/** Timer for sending RIP updates */
	private Timer ripTimer;

	/** RIP port number */
	public static final short RIP_PORT = 520;

	/** RIP multicast IP */
	public static final int RIP_IP_MULTICAST = IPv4.toIPv4Address("224.0.0.9");

	/** MAC broadcast address */
	private static final byte[] BROADCAST_MAC = MACAddress.valueOf("FF:FF:FF:FF:FF:FF").toBytes();
	
	/** RIP update interval in milliseconds (1 second) */
	private static final long RIP_UPDATE_INTERVAL = 1000;
	
	/** RIP route timeout in milliseconds (30 seconds) */
	private static final long RIP_ROUTE_TIMEOUT = 30000;
	
	/** RIP route information class */
	private class RIPRouteInfo {
		public int destinationAddress;
		public int subnetMask;
		public int nextHopAddress;
		public int distance;
		public Iface iface;
		public long timestamp;
		
		public RIPRouteInfo(long timestamp, int distance, int destinationAddress, int subnetMask, int nextHopAddress, Iface iface) {
			this.timestamp = timestamp;
			this.distance = distance;
			this.destinationAddress = destinationAddress;
			this.nextHopAddress = nextHopAddress;
			this.subnetMask = subnetMask;
			this.iface = iface;
		}
	}

	/** Map of RIP routes and their information */
	private Map<Integer, RIPRouteInfo> ripRoutes;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
		this.ripRoutes = new HashMap<>();
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}

	/**
	 * Start RIP routing protocol.
	 */
	public void startRIP() {

		// Add directly connected routes to the RIP routing table
		for (Iface iface : interfaces.values()) {
			int network = iface.getIpAddress() & iface.getSubnetMask();
			ripRoutes.put(network, new RIPRouteInfo(System.currentTimeMillis(),1,network,iface.getSubnetMask(),0, iface));
		}

		// Start timer for periodic RIP updates
		AtomicInteger counter = new AtomicInteger(0);

		this.ripTimer = new Timer();
		this.ripTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				// Check for expired routes
				checkRouteTimeouts();

				if(counter.incrementAndGet() == 10) {
					// Send RIP requests to all interfaces
					sendRIPResponses();
					counter.set(0);
				}
			}
		}, 0, RIP_UPDATE_INTERVAL);
		
		// Send initial RIP requests
		sendRIPRequests();
	}

	/**
	 * Send RIP requests to all interfaces.
	 */
	public void sendRIPRequests() {
		// Create RIP request packet
		RIPv2 ripRequest = new RIPv2();
		ripRequest.setCommand(RIPv2.COMMAND_REQUEST);

		for(Iface iface : interfaces.values()) {
			// Send RIP request to the interface
			sendRIPPacket(ripRequest, iface, RIP_IP_MULTICAST, BROADCAST_MAC);
		}
	}

	/**
	 * Send RIP responses to all interfaces.
	 */
	public void sendRIPResponses() {
		for(Iface iface : interfaces.values()) {
			// Send RIP response to the interface
			sendRIPResponseForInterface(iface, RIP_IP_MULTICAST, BROADCAST_MAC);
		}
	}

	/** 
	 * Send Response to interface 
	 */
	private void sendRIPResponseForInterface(Iface outIface, int destIP, byte[] destMac) {
		// Create RIP response
		RIPv2 ripResponse = new RIPv2();
		ripResponse.setCommand(RIPv2.COMMAND_RESPONSE);
		
		// Add entries from routing table
		for (RIPRouteInfo entry : ripRoutes.values()) {
			// Create RIP entry
			RIPv2Entry ripEntry = new RIPv2Entry();
			ripEntry.setAddress(entry.destinationAddress);
			ripEntry.setSubnetMask(entry.subnetMask);
			
			int metric = entry.distance; // Set to 1 by default
			
			// Apply split horizon with poisoned reverse
			if (entry.iface == outIface && entry.nextHopAddress != 0) {
				// Route learned through this interface, set metric to infinity (16)
				ripEntry.setMetric(16);
			} else {
				// Use actual metric
				ripEntry.setMetric(metric);
			}
			
			// Add entry to RIP packet
			ripResponse.addEntry(ripEntry);
		}
		
		// Send packet
		sendRIPPacket(ripResponse, outIface, destIP, destMac);
	}

	/**
	 * Send a RIP packet on the specified interface
	 */
	private void sendRIPPacket(RIPv2 ripPacket, Iface outIface, int destIP, byte[] destMac) {
		// Create UDP packet
		UDP udpPacket = new UDP();
		udpPacket.setSourcePort(RIP_PORT);
		udpPacket.setDestinationPort(RIP_PORT);
		udpPacket.setPayload(ripPacket);
		
		// Create IP packet
		IPv4 ipPacket = new IPv4();
		ipPacket.setSourceAddress(outIface.getIpAddress());
		ipPacket.setDestinationAddress(destIP);
		ipPacket.setProtocol(IPv4.PROTOCOL_UDP);
		ipPacket.setTtl((byte)64);
		ipPacket.setPayload(udpPacket);
		
		// Create Ethernet packet
		Ethernet etherPacket = new Ethernet();
		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());
		etherPacket.setDestinationMACAddress(destMac);
		etherPacket.setEtherType(Ethernet.TYPE_IPv4);
		etherPacket.setPayload(ipPacket);
		
		// Send packet
		sendPacket(etherPacket, outIface);
	}

	/**
	 * Check for route timeouts
	 */
	private void checkRouteTimeouts() {
		long currentTime = System.currentTimeMillis();
		List<RIPRouteInfo> expiredRoutes = new LinkedList<>();
		
		// Find expired routes
		for (Map.Entry<Integer, RIPRouteInfo> entry : ripRoutes.entrySet()) {
			if (currentTime - entry.getValue().timestamp > RIP_ROUTE_TIMEOUT) {
				expiredRoutes.add(entry.getValue());
			}
		}
		
		// Remove expired routes
		for (RIPRouteInfo entry : expiredRoutes) {
			if(entry.nextHopAddress != 0) {
				// Remove from routing table
				routeTable.remove(entry.destinationAddress, entry.subnetMask);
				ripRoutes.remove(entry.destinationAddress);
			}
		}
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle RIP packets.
	 * @param ripPacket the RIP packet that was received
	 * @param inIface the interface on which the packet was received
	 * @param sourceIP the source IP address of the packet
	 * @param sourceMAC the source MAC address of the packet
	 */
	public void handleRIPPacket(RIPv2 ripPacket, Iface inIface, int sourceIP, byte[] sourceMAC)
	{
		// Check if the packet is a request or response
		if (ripPacket.getCommand() == RIPv2.COMMAND_REQUEST) {
			// Handle RIP request
			sendRIPResponseForInterface(inIface, sourceIP, sourceMAC);
		} else if (ripPacket.getCommand() == RIPv2.COMMAND_RESPONSE) {
			// Handle RIP response
			for (RIPv2Entry entry : ripPacket.getEntries()) {
				int destinationAddress = entry.getAddress();
				int subnetMask = entry.getSubnetMask();
				int nextHopAddress = sourceIP;
				int distance = entry.getMetric() + 1; // Increment metric by 1

				if(distance == 16) continue;

				// Check if the route already exists
				RIPRouteInfo existingRoute = ripRoutes.get(destinationAddress);
				if (existingRoute != null && distance >= existingRoute.distance) {
					// If distance is greater than current, just update timestamp
					existingRoute.timestamp = System.currentTimeMillis();
					continue;
				}
					
				// Update the routing table or add entry if DNE
				ripRoutes.put(destinationAddress, new RIPRouteInfo(System.currentTimeMillis(), distance, destinationAddress, subnetMask, nextHopAddress, inIface));

				// Update the route table
				if(routeTable.update(destinationAddress, nextHopAddress, subnetMask, inIface) == false) {
					// Add to route table if it doesn't exist
					routeTable.insert(destinationAddress, nextHopAddress, subnetMask, inIface);
				}
			}
		}
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		/********************************************************************/
		/* TODO: Handle packets                                             */
		
		// Check if the packet is ipv4
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) {
			return;
		}
		// System.out.println("Confirmed IPv4 packet");

		// Verify the checksum
		IPv4 ipPacket = (IPv4) etherPacket.getPayload();
		short savedChecksum = ipPacket.getChecksum();
		ipPacket.resetChecksum();
		byte[] serialized = ipPacket.serialize();
		ipPacket.deserialize(serialized, 0, serialized.length);
		if (savedChecksum != ipPacket.getChecksum()) {
			// System.out.println("Checksum failed");
			return;
		}
		// System.out.println("Checksum verified");

		// Handle RIP packet if it's a UDP packet on RIP port
		if (ipPacket.getProtocol() == IPv4.PROTOCOL_UDP) {
			UDP udpPacket = (UDP) ipPacket.getPayload();
			if (udpPacket.getDestinationPort() == RIP_PORT) {
				// This is a RIP packet
				RIPv2 ripPacket = (RIPv2) udpPacket.getPayload();
				handleRIPPacket(ripPacket, inIface, ipPacket.getSourceAddress(), 
						etherPacket.getSourceMACAddress());
				return;
			}
		}

		// Check / decrement TTL
		if (ipPacket.getTtl() <= 1) {
			return;
		}
		ipPacket.setTtl((byte) (ipPacket.getTtl() - 1));
		// Recalculate checksum
		ipPacket.resetChecksum();
		serialized = ipPacket.serialize();
		ipPacket.deserialize(serialized, 0, serialized.length);
		// System.out.println("TTL decremented");

		// Check if the packet is destined for one of the router's interfaces
		for (Iface iface : interfaces.values()) {
			if (ipPacket.getDestinationAddress() == iface.getIpAddress()) {
				return;
			}
		}
		// System.out.println("Not destined for router confirmed");

		// Forwarding logic
		RouteEntry bestMatch = routeTable.lookup(ipPacket.getDestinationAddress());
		if (bestMatch == null) {
			return;
		}
		// System.out.println("Best match found: " + bestMatch.toString());

		// Update the IP header with new mac addresses
		// System.out.println("Looking up " + IPv4.fromIPv4Address(bestMatch.getGatewayAddress()));
		int gatewayAddress = bestMatch.getGatewayAddress();
		if(gatewayAddress == 0) {
			gatewayAddress = ipPacket.getDestinationAddress();
		}
		ArpEntry arpEntry = arpCache.lookup(gatewayAddress);
		// System.out.println("Found arp entry" + arpEntry.toString());

		MACAddress newDstMac = arpEntry.getMac();
		MACAddress newSrcMac = bestMatch.getInterface().getMacAddress();
		etherPacket.setDestinationMACAddress(newDstMac.toBytes());
		etherPacket.setSourceMACAddress(newSrcMac.toBytes());
		// System.out.println("Updated MAC addresses in etherPacket header: dst " + newDstMac.toString() + " src " + newSrcMac.toString());

		// Send the packet
		sendPacket(etherPacket, bestMatch.getInterface());
		// System.out.println("Packet sent on interface " + bestMatch.getInterface().getName());
		/********************************************************************/
	}
}
