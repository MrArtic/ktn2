/*
 * Created on Oct 27, 2004
 */
package no.ntnu.fp.net.co;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;



import no.ntnu.fp.net.admin.Log;
import no.ntnu.fp.net.cl.ClException;
import no.ntnu.fp.net.cl.ClSocket;
import no.ntnu.fp.net.cl.KtnDatagram;
import no.ntnu.fp.net.cl.KtnDatagram.Flag;

/**
 * Implementation of the Connection-interface. <br>
 * <br>
 * This class implements the behaviour in the methods specified in the interface
 * {@link Connection} over the unreliable, connectionless network realised in
 * {@link ClSocket}. The base class, {@link AbstractConnection} implements some
 * of the functionality, leaving message passing and error handling to this
 * implementation.
 * 
 * @author Sebj�rn Birkeland and Stein Jakob Nordb�
 * @see no.ntnu.fp.net.co.Connection
 * @see no.ntnu.fp.net.cl.ClSocket
 */
public class ConnectionImpl extends AbstractConnection {
	//Keeps track of the used ports for each server port
	private static Map<Integer, Boolean> usedPorts = Collections.synchronizedMap(new HashMap<Integer, Boolean>());

	/**
	 * Initializes initial sequence number and setup state machine.
	 * 
	 * @param myPort
	 *            - the local port to associate with this connection
	 */
	public ConnectionImpl(int myPort) {
		super(); //Initializes sequence number and sets state to disabled
		myAddress = getIPv4Address();
		this.myPort = myPort;
		usedPorts.put(myPort, true);
	}
	
	private static String getIPv4Address() {
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
			return "127.0.0.1";
		}
	}

	/**
	 * Establish a connection to a remote location.
	 * 
	 * @param remoteAddress
	 *            - the remote IP-address to connect to
	 * @param remotePort
	 *            - the remote portnumber to connect to
	 * @throws IOException
	 *             If there's an I/O error.
	 * @throws java.net.SocketTimeoutException
	 *             If timeout expires before connection is completed.
	 * @see Connection#connect(InetAddress, int)
	 */
	public void connect(InetAddress remoteAddress, int remotePort)
			throws IOException, SocketTimeoutException {
		this.remoteAddress = remoteAddress.getHostAddress();
		this.remotePort = remotePort;
		
        if (state != State.CLOSED) {
            throw new ConnectException("socket allready connected/in use");
        }
        Log.writeToLog("Trying to connect to: "
							+ remoteAddress.getHostAddress() 
							+ " : " 
							+ remotePort, 
							"ConnectionImpl");
		//Handshake
		try {
			//Send SYN to server
			int tries = 3;
			boolean sent = false;
			KtnDatagram synPacket = constructInternalPacket(Flag.SYN);

			// Send the SYN, trying at most `tries' times.
			Log.writeToLog(synPacket, "Sending SYN", "ConnectionImpl");

			do {
				try {
					simplySendPacket(synPacket);
					//new ClSocket().send(synPacket);
					sent = true;
				} catch (ConnectException e) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException ex) {/*Ignore*/}
				}
			} while (!sent && (tries-- > 0));

			if (!sent) {
				nextSequenceNo--;
				throw new ConnectException("Unable to send SYN.");
			}
			state = State.SYN_SENT;
			
			//Receive SYNACK from server
			KtnDatagram response = receiveAck();

			//Connect to the new connection
			this.remoteAddress = response.getSrc_addr();
			this.remotePort = response.getSrc_port();
			
			//ACK the SYNACK
			sendAck(response, false);
		}
		catch (ConnectException e) {
			throw e;
		}
		catch (Exception e) {
			throw new ConnectException("Could not establish connection!");
		}
		state = State.ESTABLISHED;
		Log.writeToLog("Connection established", "ConnectionImpl");
	}
	
	/**
	 * Listen for, and accept, incoming connections.
	 * 
	 * @return A new ConnectionImpl-object representing the new connection.
	 * @see Connection#accept()
	 */
	public Connection accept() throws IOException, SocketTimeoutException {
		state = State.LISTEN;
		KtnDatagram response = null;
		Log.writeToLog("Listening for new connections on port " + myPort, 
						"ConnectionImpl");
		
		while (!isValid(response) || response.getFlag() != Flag.SYN) {
			//Wait for SYN package
			response = receivePacket(true);
		}
		
		//Set up the new connection
		ConnectionImpl newConnection = new ConnectionImpl(getFreePort());
		newConnection.lastValidPacketReceived = response;
		newConnection.state = State.SYN_RCVD;
		newConnection.remoteAddress = response.getSrc_addr();
		newConnection.remotePort = response.getSrc_port();

		//Send SYNACK
		newConnection.sendAck(response, true);

		newConnection.receiveAck();
		
		//Return the established connection to client
		newConnection.state = State.ESTABLISHED;
		state = State.CLOSED;
		return newConnection;
    }
	
	private static int getFreePort() {
		int port;
		
		while (usedPorts.containsKey(
					port = (int)(Math.random() * 30000) + 10000))
			;
		return port;
	}

	/**
	 * Send a message from the application.
	 * 
	 * @param msg
	 *            - the String to be sent.
	 * @throws ConnectException
	 *             If no connection exists.
	 * @throws IOException
	 *             If no ACK was received.
	 * @see AbstractConnection#sendDataPacketWithRetransmit(KtnDatagram)
	 * @see no.ntnu.fp.net.co.Connection#send(String)
	 */
	public synchronized void send(String msg) throws ConnectException, IOException {
		if (state != State.ESTABLISHED) {
			throw new ConnectException("Cannot send without an established connection");
		}
		KtnDatagram ack = null;
		
		while (ack == null) {
			try {
				ack = sendDataPacketWithRetransmit(constructDataPacket(msg));
				
				if (!isValid(ack)) {
					//Bad checksum? Send again
					ack = null;
				}
			}
			catch (SocketException e) {/*Try again*/}
		}
	}

	/**
	 * Wait for incoming data.
	 * 
	 * @return The received data's payload as a String.
	 * @see Connection#receive()
	 * @see AbstractConnection#receivePacket(boolean)
	 * @see AbstractConnection#sendAck(KtnDatagram, boolean)
	 */
	public String receive() throws ConnectException, IOException {
		if (state != State.ESTABLISHED) {
			throw new ConnectException("No connection");
		}
		KtnDatagram received = null;

		while (received == null) {
			try{
				received = receivePacket(false);
			}catch(EOFException e){
				try {
					Thread.sleep(150);
				} catch (InterruptedException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				sendAck(disconnectRequest, false);
				this.state = State.CLOSE_WAIT;
				close();
				throw e;
			}
			if (lastValidPacketReceived != null && 
					received.getSeq_nr() <= lastValidPacketReceived.getSeq_nr()) {
				//Ignore the already received packet
				received = null;
			}
			else if (!isValid(received)) {
				//Probably indicates a checksum mismatch
				received = null;
			}

		}
		//ACK the received packet
		sendAck(received, false);
		lastValidPacketReceived = received;
		return (String)received.getPayload();
	}


	/**
	 * Close the connection.
	 * 
	 * @see Connection#close()
	 */
	public void close() throws IOException {
			
		/*
		 * start of adabra edits
		 */
		
		if(state == State.CLOSE_WAIT){
			KtnDatagram finPacket = constructInternalPacket(Flag.FIN);
			
			try {
				Thread.sleep(1000);
				System.out.println("Jeg ville sende FIN!!!");
				simplySendPacket(finPacket);
			} catch (ClException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				
			}
			
			KtnDatagram ack = receiveAck();
			this.state = State.CLOSED;
		}
		
		
		else if(this.state == State.ESTABLISHED){
			KtnDatagram finPacket = constructInternalPacket(Flag.FIN);
			try {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				simplySendPacket(finPacket);
			} catch (ClException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			this.state = State.FIN_WAIT_1;
			KtnDatagram ackToFin = receiveAck();
			this.state = State.FIN_WAIT_2;
			
			KtnDatagram FIN = receivePacket(true);
			if(FIN == null){
				System.out.println("Fin blei null");
			}
			
			sendAck(FIN, false);
			this.state = State.TIME_WAIT;
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {}
			this.state = State.CLOSED;	
		}
		/*
		 * end  adabrakode
		 */
	}		


	/**
	 * Test a packet for transmission errors. This function should only called
	 * with data or ACK packets in the ESTABLISHED state.
	 * 
	 * @param packet
	 *            Packet to test.
	 * @return true if packet is free of errors, false otherwise.
	 */
	protected boolean isValid(KtnDatagram packet) {
		if (packet == null) {
			return false;
		}

		if (packet.calculateChecksum() != packet.getChecksum()) {
			//Checksum mismatch
			return false;
		}
		
		if (packet.getFlag() == Flag.NONE && packet.getPayload() == null) {
			//External packet with null-load is invalid
			return false;
		}
		
		if (packet.getFlag() != Flag.NONE && packet.getPayload() != null) {
			//An internal packet with a non-null payload is invalid
			return false;
		}
		//All tests passed :D
		return true;
	}
}