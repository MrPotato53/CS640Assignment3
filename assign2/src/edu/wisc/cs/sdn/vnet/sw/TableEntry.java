package edu.wisc.cs.sdn.vnet.sw;

import edu.wisc.cs.sdn.vnet.Iface;

public class TableEntry {
	private Iface iface;
	private long refreshed;
    private int TIMEOUT;

	public TableEntry(Iface iface) {
		this.iface = iface;
		this.refreshed = System.currentTimeMillis();
        this.TIMEOUT = 15;
	}

    public void update(Iface iface) {
        // Update interface and reset TTL
        this.iface = iface;
        this.refreshed = System.currentTimeMillis();
    }

    public boolean out_of_date() {
        // Check if last saved time is more than 15 seconds from current time
        return (System.currentTimeMillis() - this.refreshed) > TIMEOUT * 1000;
    }

    public Iface getIface() {
        return iface;
    }
}