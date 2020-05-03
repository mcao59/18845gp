import java.io.Serializable;

public class Lease implements Serializable {
	public long term;	// term time im millisecond
	public String type;	// Read or Write lease
	public String filename;
	// private boolean isExpired;
	// avoid false sharing, set to true when client is done using. lease will be returned even not expired
	// private int client;	// used later to enable lease revoke
	// release()
	// check()

	private final long RTTIME = 100;	// estimated round trip time in the system

	// constructor
	public Lease(long term, String type, String filename) {
		this.term = term;
		this.type = type;
		this.filename = filename;
	}

	// /** @param timePassed time passed after client got the lease
	//  * 
	//  */
	// public boolean checkIfExpired(long timePassed) {
	// 	if (timePassed >= term) {
	// 		return true;
	// 	} else {
	// 		return false;
	// 	}
	// }

	/** @param timePassed time passed after client got the lease
	 * 
	 */
	public boolean checkIfRenew(long timePassed) {
		long actualLeasePeriod = term - RTTIME;
		if (timePassed >= actualLeasePeriod) {
			return true;
		} else {
			return false;
		}
	}

}