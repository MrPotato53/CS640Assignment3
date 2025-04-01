package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.HashMap;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{	

	private HashMap<String, TableEntry> macTable;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		macTable = new HashMap<String, TableEntry>();
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
		
		String srcMac = etherPacket.getSourceMAC().toString();
		String dstMac = etherPacket.getDestinationMAC().toString();

		// Check if source MAC address is in MAC table and not out of date
		// If nonexistent or out of date, add/override new TableEntry with the current interface
		if (macTable.containsKey(srcMac) && !macTable.get(srcMac).out_of_date()) {
			macTable.get(srcMac).update(inIface);
		} else {
			TableEntry entry = new TableEntry(inIface);
			macTable.put(srcMac, entry);
		}

		// Check if destination MAC address is in MAC table
		if (macTable.containsKey(dstMac) && !macTable.get(dstMac).out_of_date()) {
			// If destination MAC address in MAC table, forward packet to corresponding interface
			Iface outIface = macTable.get(dstMac).getIface();
			sendPacket(etherPacket, outIface);
		} else {
			// If destination MAC address not in MAC table, flood all interfaces except input interface
			for (Iface iface : interfaces.values()) {
				if (iface == inIface) {
					continue;
				}
				sendPacket(etherPacket, iface);
			}
		}
		/********************************************************************/
	}
}
