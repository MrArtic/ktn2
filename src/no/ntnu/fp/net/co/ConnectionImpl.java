/*
 * Created on Oct 27, 2004
 */
package no.ntnu.fp.net.co;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

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

    /** Keeps track of the used ports for each server port. */
    private static Map<Integer, Boolean> usedPorts = Collections.synchronizedMap(new HashMap<Integer, Boolean>());
    private ClSocket socket = new ClSocket();
    private final int maxresends = 5;
    private final int maxrereceives = 5;
    private int resends = 0;
    private int rereceives = 0;
    private KtnDatagram lastDatagramReceived = null;
    /**
     * Initialise initial sequence number and setup state machine.
     * 
     * @param myPort
     *            - the local port to associate with this connection
     */
    public ConnectionImpl(int myPort) {
//        throw new NotImplementedException();
    	this.myPort = myPort;
    	this.myAddress = getIPv4Address();
    	state = state.CLOSED; //there's no connection from the get-go
    }
    
    public ConnectionImpl(String myAddress, int myPort, String remoteAddress, int remotePort){
    	this.myAddress = myAddress;
    	this.myPort = myPort;
    	this.remoteAddress = remoteAddress;
    	this.remotePort = remotePort;
    }

    private String getIPv4Address() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        }
        catch (UnknownHostException e) {
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
    public void connect(InetAddress remoteAddress, int remotePort) throws IOException,
            SocketTimeoutException {
//        throw new NotImplementedException();
    	
    	this.remoteAddress = remoteAddress.getHostAddress();
    	this.remotePort = remotePort;
    	
    	//We need a syn!
    	KtnDatagram syn = constructInternalPacket(Flag.SYN);
    	
    	try{
    		simplySendPacket(syn);
    	}catch (ClException e){
    		e.printStackTrace();
    	}
    	
    	//Need to wait for synAck
    	KtnDatagram synAck = receiveAck();
    	
    	if(synAck != null){
    		this.remotePort = synAck.getSrc_port();
    	} else {
    		throw new SocketTimeoutException();
    	}
    	if(synAck.getFlag() == Flag.SYN_ACK){
    		state = State.ESTABLISHED;
    	}
    	sendAck(synAck, false);
    }

    /**
     * Listen for, and accept, incoming connections.
     * 
     * @return A new ConnectionImpl-object representing the new connection.
     * @see Connection#accept()
     */
    public Connection accept() throws IOException, SocketTimeoutException {
//        throw new NotImplementedException();
        
        state = State.LISTEN;
        
        KtnDatagram syn;
        do{
        	syn = receivePacket(true);
        	System.out.println(syn);
        } while (syn == null || syn.getFlag() != Flag.SYN);
        
        this.remoteAddress = syn.getSrc_addr();
        this.remotePort = syn.getSrc_port();
        state = State.SYN_RCVD;
        
        ConnectionImpl newConnection = new ConnectionImpl(this.myAddress, getNewPort(), this.remoteAddress, this.remotePort);
        
        return (Connection) newConnection;
    }
    
    private int getNewPort(){
    	return (int) (Math.random()*40000+10000); //assuming the port we get is not in use!
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
    public void send(String msg) throws ConnectException, IOException {
        throw new NotImplementedException();
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
        throw new NotImplementedException();
    }

    
    
    
    /**
     * Close the connection.
     * 
     * @see Connection#close()
     */
    public void close() throws IOException {
    	KtnDatagram acknowledge, datagram, finalAknowledge = null;
    	if(state == State.CLOSE_WAIT){
    		sendAck(lastDatagramReceived, false);
    		try{
    			Thread.currentThread().sleep(1000);
    		} catch (InterruptedException e){
    			e.printStackTrace();
    		}
    		acknowledge = receiveAck();
    		state = State.CLOSED;
    	}
    	else if(state == State.ESTABLISHED){
    		try {
    			datagram = constructInternalPacket(Flag.FIN);
    			simplySendPacket(datagram);
    		} catch (ClException e){
    			e.printStackTrace();
    		}
    		state = State.FIN_WAIT_1;
    		acknowledge = receiveAck();
    		if(acknowledge == null){
    			if(resends < maxresends){
    		    	state = State.ESTABLISHED;
    				try {
    					close();
    				} catch (IOException e) {
    					e.printStackTrace();
    				}
    				return;
    			}
    			else {
    				state = State.CLOSED;
    			}
    			state = State.FIN_WAIT_2;
    			finalAknowledge = receiveAck();
    			if(finalAknowledge == null){
    				finalAknowledge = receiveAck();
    			}
    			if(finalAknowledge != null){
    				sendAck(finalAknowledge, false);
    			}
    		}
    		state = State.CLOSED;
    	}
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
        throw new NotImplementedException();
    }
}
