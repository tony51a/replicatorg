/*
  Sanguino3GDriver.java

  This is a driver to control a machine that uses the Sanguino with 3rd Generation Electronics.

  Part of the ReplicatorG project - http://www.replicat.org
  Copyright (c) 2008 Zach Smith

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package replicatorg.app.drivers;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Vector;

import javax.vecmath.Point3d;

import org.w3c.dom.Node;

import replicatorg.app.Base;
import replicatorg.app.Preferences;
import replicatorg.app.Serial;
import replicatorg.app.exceptions.SerialException;
import replicatorg.app.models.ToolModel;
import replicatorg.app.tools.XML;

public class Sanguino3GDriver extends DriverBaseImplementation
{
    /**
     * An enumeration of the available command codes for the three-axis
     * CNC stage.
     */
    class CommandCodesMaster {
		public final static int GET_VERSION     =   0;
		public final static int INIT            =   1;
		public final static int GET_AVAIL_BUF   =   2;
		public final static int CLEAR_BUF       =   3;
		public final static int GET_POS         =   4;
		public final static int GET_RANGE       =   5;
		public final static int SET_RANGE       =   6;
		public final static int ABORT           =   7;
		public final static int PAUSE           =   8;
		public final static int PROBE           =   9;
		public final static int TOOL_QUERY      =  10;
		
		public final static int QUEUE_POINT_INC = 128;
		public final static int QUEUE_POINT_ABS = 129;
		public final static int SET_POS         = 130;
		public final static int FIND_MINS       = 131;
		public final static int FIND_MAXS       = 132;
		public final static int DELAY           = 133;
		public final static int CHANGE_TOOL     = 135;
		public final static int WAIT_FOR_TOOL   = 136;
		public final static int TOOL_COMMAND    = 137;
    };

    /**
     * An enumeration of the available command codes for a tool.
     */
    class CommandCodesSlave {
		public final static int GET_VERSION     =   0;
		public final static int INIT            =   1;
		public final static int GET_TEMPERATURE =   2;
		public final static int SET_TEMPERATURE =   3;
		public final static int SET_MOTOR_1_PWM =   4;
		public final static int SET_MOTOR_2_PWM =   5;
		public final static int SET_MOTOR_1_RPM =   6;
		public final static int SET_MOTOR_2_RPM =   7;
		public final static int SET_MOTOR_1_DIR =   8;
		public final static int SET_MOTOR_2_DIR =   9;
		public final static int TOGGLE_MOTOR_1  =  10;
		public final static int TOGGLE_MOTOR_2  =  11;
		public final static int TOGGLE_FAN      =  12;
		public final static int TOGGLE_VALVE    =  13;
		public final static int SET_SERVO_1_POS =  14;
		public final static int SET_SERVO_2_POS =  15;
		public final static int FILAMENT_STATUS =  16;
		public final static int GET_MOTOR_1_RPM =  17;
		public final static int GET_MOTOR_2_RPM =  18;
		public final static int GET_MOTOR_1_PWM =  19;
		public final static int GET_MOTOR_2_PWM =  20;
		public final static int ABORT           =  21;
		public final static int PAUSE           =  22;
    };


    /** The start byte that opens every packet. */
    private final byte START_BYTE = (byte)0xD5;

    /** The response codes at the start of every response packet. */
    class ResponseCode {
		final static int GENERIC_ERROR   =0;
		final static int OK              =1;
		final static int BUFFER_OVERFLOW =2;
		final static int CRC_MISMATCH    =3;
		final static int QUERY_OVERFLOW  =4;
    };

    /**
     * An object representing the serial connection.
     */
    private Serial serial;
    
    /**
     * Serial connection parameters
     **/
    String name;
    int    rate;
    char   parity;
    int    databits;
    float  stopbits;
    
    /**
     * This is a Java implementation of the IButton/Maxim 8-bit CRC.
     * Code ported from the AVR-libc implementation, which is used
     * on the RR3G end.
     */
    protected class IButtonCrc {

		private int crc = 0;

		/**
		 * Construct a new, initialized object for keeping track of a CRC.
		 */
		public IButtonCrc() {
		    crc = 0;
		}

		/**
		 * Update the  CRC with a new byte of sequential data.
		 * See include/util/crc16.h in the avr-libc project for a 
		 * full explanation of the algorithm.
		 * @param data a byte of new data to be added to the crc.
		 */
		public void update(byte data) {
		    crc = (crc ^ data)&0xff; // i loathe java's promotion rules
		    for (int i=0; i<8; i++) {
			if ((crc & 0x01) != 0) {
			    crc = ((crc >>> 1) ^ 0x8c)&0xff;
			} else {
			    crc = (crc >>> 1)&0xff;
			}
		    }
		}

		/**
		 * Get the 8-bit crc value.
		 */
		public byte getCrc() {
		    return (byte)crc;
		}

		/**
		 * Reset the crc.
		 */
		public void reset() {
		    crc = 0;
		}
    }

    /**
     * A class for building a new packet to send down the wire to the
     * Sanguino3G.
     */
    class PacketBuilder {
		// yay magic numbers.
		byte[] data = new byte[256];
		// current end of packet.  Bytes 0 and 1 are reserved for start byte
		// and packet payload length.
		int idx = 2; 
		IButtonCrc crc = new IButtonCrc();

		/**
		 * Start building a new command packet.
		 * @param target the target identifier for this packet.
		 * @param command the command identifier for this packet.
		 */
		PacketBuilder(int command)
		{
		    data[0] = START_BYTE;
		    add8((byte)command);
		}

		/**
		 * Add an 8-bit value to the end of the packet payload.
		 * @param v the value to append.
		 */
		void add8( int v ) {
		    data[idx++] =  (byte)v;
		    crc.update((byte)v);
		}

		/**
		 * Add a 16-bit value to the end of the packet payload.
		 * @param v the value to append.
		 */
		void add16( int v ) {
		    add8((byte)(v & 0xff));
		    add8((byte)((v >> 8) & 0xff));
		}

		/**
		 * Add a 32-bit value to the end of the packet payload.
		 * @param v the value to append. Must be long to support unsigned ints.
		 */
		void add32( long v ) {
		    add16((int)(v & 0xffff));
		    add16((int)((v >> 16) & 0xffff));
		}

		/**
		 * Complete the packet.
		 * @return a byte array representing the completed packet.
		 */
		byte[] getPacket() {
		    data[idx] = crc.getCrc();
		    data[1] = (byte)(idx-2); // len does not count packet header
		    byte[] rv = new byte[idx+1];
		    System.arraycopy(data,0,rv,0,idx+1);
		    return rv;
		}
    };

    /**
     * A class for keeping track of the state of an incoming packet and
     * storing its payload.
     */
    class PacketProcessor {
		final static byte PS_START = 0;
		final static byte PS_LEN = 1;
		final static byte PS_PAYLOAD = 2;
		final static byte PS_CRC = 3;
		final static byte PS_LAST = 4;

		byte packetState = PS_START;
		int payloadLength = -1;
		int payloadIdx = 0;
		byte[] payload;
		byte targetCrc = 0;
		IButtonCrc crc;

		/**
		 * Reset the packet's state.  (The crc is (re-)generated on the length byte
		 * and thus doesn't need to be reset.(
		 */
		public void reset() {
		    packetState = PS_START;
		}

		/**
		 * Create a PacketResponse object that contains this packet's payload.
		 * @return A valid PacketResponse object
		 */
		public PacketResponse getResponse() { return new PacketResponse(payload); }

		/**
		 * Process the next byte in an incoming packet.
		 * @return true if the packet is complete and valid; false otherwise.
		 */
		public boolean processByte(byte b) {
		    System.out.println("IN: Processing byte " + Integer.toHexString((int)b&0xff));
		    switch (packetState) {
		    case PS_START:
				if (b == START_BYTE) {
				    packetState = PS_LEN;
				} else {
				    // throw exception?
				}
				break;
		
		    case PS_LEN:
				payloadLength = ((int)b) & 0xFF;
				payload = new byte[payloadLength];
				crc = new IButtonCrc();
				packetState = PS_PAYLOAD;
				break;
		
		    case PS_PAYLOAD:
				// sanity check
				if (payloadIdx < payloadLength) {
				    payload[payloadIdx++] = b;
				    crc.update(b);
				}
				if (payloadIdx >= payloadLength) {
				    packetState = PS_CRC;
				}
				break;
		
		    case PS_CRC:
				targetCrc = b;
				System.out.println("Target CRC: " + Integer.toHexString( (int)targetCrc&0xff ) +
						   " - expected CRC: " + Integer.toHexString( (int)crc.getCrc()&0xff ));
				if (crc.getCrc() != targetCrc) {
				    throw new java.lang.RuntimeException("CRC mismatch on reply");
				}
				return true;
		    }
		    return false;
		}
    }

    /**
     * Packet response wrapper, with convenience functions for 
     * reading off values in sequence and retrieving the response
     * code.
     */
    class PacketResponse {
		
		byte[] payload;
		int readPoint = 1;
		
		public PacketResponse(byte[] p) {
		    payload = p;
		}
		
		/**
		 * Prints a debug message with the packet response code decoded, along wiith the
		 * packet's contents in hex.
		 */
		public void printDebug() {
		    String msg = "Unknown";
		    switch(payload[0]) {
			    case ResponseCode.GENERIC_ERROR:
					msg = "Generic Error";
					break;
				
			    case ResponseCode.OK:
					msg = "OK";
					break;
			    
				case ResponseCode.BUFFER_OVERFLOW:
					msg = "Buffer overflow";
					break;
			    
				case ResponseCode.CRC_MISMATCH:
					msg = "CRC mismatch";
					break;
			    
				case ResponseCode.QUERY_OVERFLOW:
					msg = "Query overflow";
					break;
		    }
		    System.out.println("Packet response code: "+msg);
		    System.out.print("Packet payload: ");
		    for (int i = 1; i < payload.length; i++) {
				System.out.print(Integer.toHexString(payload[i]&0xff) + " ");
		    }
		    System.out.print("\n");
		}

		/**
		 * Retrieve the packet payload.
		 * @return an array of bytes representing the payload.
		 */
		public byte[] getPayload() {
		    return payload;
		}

		/**
		 * Get the next 8-bit value from the packet payload.
		 */
		int get8() {
		    return ((int)payload[readPoint++])&0xff;
		}
		/**
		 * Get the next 16-bit value from the packet payload.
		 */
		int get16() {
		    return get8() + (get8()<<8);
		}
		/**
		 * Get the next 32-bit value from the packet payload.
		 */
		int get32() {
		    return get16() + (get16()<<16);
		}

		/**
		 * Does the response code indicate that the command was successful?
		 */
		public boolean isOK() { 
		    return payload[0] == ResponseCode.OK;
		}
    };

    public Sanguino3GDriver()
    {
		super();
	
		//init our variables.
		setInitialized(false);
		
		//some decent default prefs.
		String[] serialPortNames = Serial.list();
		if (serialPortNames.length != 0)
		    name = serialPortNames[0];
		else
		    name = null;
		
		rate = Preferences.getInteger("serial.debug_rate");
		parity = Preferences.get("serial.parity").charAt(0);
		databits = Preferences.getInteger("serial.databits");
		stopbits = new Float(Preferences.get("serial.stopbits")).floatValue();

		System.out.println("hello");
    }
	
    public void loadXML(Node xml)
    {
		super.loadXML(xml);
		
		//load from our XML config, if we have it.
		if (XML.hasChildNode(xml, "portname"))
		    name = XML.getChildNodeValue(xml, "portname");
		if (XML.hasChildNode(xml, "rate"))
		    rate = Integer.parseInt(XML.getChildNodeValue(xml, "rate"));
		if (XML.hasChildNode(xml, "parity"))
		    parity = XML.getChildNodeValue(xml, "parity").charAt(0);
		if (XML.hasChildNode(xml, "databits"))
		    databits = Integer.parseInt(XML.getChildNodeValue(xml, "databits"));
		if (databits != 8) {
		    throw new java.lang.RuntimeException("Sanguino3G driver requires 8 serial data bits.");
		}
		if (XML.hasChildNode(xml, "stopbits"))
		    stopbits = Integer.parseInt(XML.getChildNodeValue(xml, "stopbits"));
    }
	
    public void initialize()
    {
		// Create our serial object
		if (serial == null) {
		    if (name != null) {
			try {
			    System.out.println("Connecting to " + name + " at " + rate);
			    serial = new Serial(name, rate, parity, databits, stopbits);
			} catch (SerialException e) {
			    System.out.println("Unable to open port " + name + "\n");
			    return;
			}
		    } else {
				System.out.println("No Serial Port found.\n");
				return;
		    }
		}

		//wait till we're initialized
		if (!isInitialized()) {
		    // attempt to send version command and retrieve reply.
		    try {
				System.out.println("Running getversion.");
				getVersion(Base.VERSION);
				sendInit();
				super.initialize();
		    } catch (Exception e) {
			    //todo: handle init exceptions here
				System.out.println("yarg!" + e.getMessage());
		    }
		    System.out.println("Ready to rock.");
		}
	
		//default us to absolute positioning
		// todo agm sendCommand("G90");
    }
		
    /**
     * Sends the command over the serial connection and retrieves a result.
     */
    protected PacketResponse runCommand(byte[] packet)
    {
		assert (serial != null);

		if (packet == null || packet.length < 4)
			return null; // skip empty commands or broken commands

		boolean checkQueue = false;
		if (packet[2] == 0x0 && (packet[3]&0x80) != 0x0) {
		    checkQueue = true;
		}

		synchronized(serial) {
		    //do the actual send.
		    serial.write(packet);
		}
		{ // debug
		    System.out.print("OUT: ");
		    for (int i =0; i<packet.length;i++) {
			System.out.print( Integer.toHexString( (int)packet[i] & 0xff ) );
			System.out.print( " " );
		    }
		    System.out.print( "\n" );
		}
		PacketProcessor pp = new PacketProcessor();
		try {
		    boolean c = false;
		    while(!c){
			int b = serial.input.read();
			c = pp.processByte((byte)b);
		    }
		} catch (java.io.IOException ioe) {
		    System.out.println(ioe.toString());
		} 
		return pp.getResponse();
    }
	
	
    public boolean isFinished()
    {
		// todo agm
		return true;
    }
	
    public void dispose()
    {
		super.dispose();
		
		if (serial != null) serial.dispose();
		serial = null;
    }

    /****************************************************
     *  commands used internally to driver
     ****************************************************/
    public int getVersion(int ourVersion)
	{
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.GET_VERSION);
		pb.add16(ourVersion);

		PacketResponse pr = runCommand(pb.getPacket());
		int version = pr.get16();
		pr.printDebug();

		System.out.println("Reported version: " + Integer.toHexString(version));

		return version;
    }

    public void sendInit()
	{
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.INIT);
		PacketResponse pr = runCommand(pb.getPacket());
		pr.printDebug();
    }
    
    /****************************************************
     *  commands for interfacing with the driver directly
     ****************************************************/

    public void queuePoint(Point3d p)
    {
		//sendCommand(cmd);
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.QUEUE_POINT_INC);

		//figure out our feedrate variables.
		long ticks = convertFeedrateToTicks(getCurrentPosition(), p, getCurrentFeedrate());

		//figure out the axis with the most steps.
		Point3d steps = getDeltaSteps(getCurrentPosition(), p);
		int max = Math.max((int)steps.x, (int)steps.y);
		max = Math.max(max, (int)steps.z);
		
		//get the ratio of steps to take each segment
		double xRatio = steps.x / max;
		double yRatio = steps.y / max;
		double zRatio = steps.z / max;
		
		//how many segments will there be?
		int segmentCount = (int)Math.ceil(max / 65535.0);
		
		//within our range?  just do it.
		if (segmentCount == 1)
			queueIncrementalPoint(pb, steps, ticks);
		else
		{
			for (int i=0; i<segmentCount; i++)
			{
				Point3d segmentSteps = new Point3d();

				//TODO: is this accurate?
				//calculate our line segments
				segmentSteps.x = Math.round(65535 * xRatio);
				segmentSteps.y = Math.round(65535 * yRatio);
				segmentSteps.z = Math.round(65535 * zRatio);

				//keep track of them.
				steps.x -= segmentSteps.x;
				steps.y -= segmentSteps.y;
				steps.z -= segmentSteps.z;

				//send this segment
				queueIncrementalPoint(pb, segmentSteps, ticks);
			}
		}
		
		super.queuePoint(p);
    }

	private void queueIncrementalPoint(PacketBuilder pb, Point3d steps, long ticks)
	{
		//figure out our timer values.
		byte prescaler = convertTicksToPrescaler(ticks);
		int counter = convertTicksToCounter(ticks);

		//just add them in now.
		pb.add16((int)steps.x);
		pb.add16((int)steps.y);
		pb.add16((int)steps.z);
		pb.add8(prescaler);
		pb.add16(counter);

		PacketResponse pr = runCommand(pb.getPacket());
	}
	
    public void setCurrentPosition(Point3d p)
    {
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.SET_POS);

		Point3d steps = machine.mmToSteps(p);
		pb.add32((long)steps.x);
		pb.add32((long)steps.y);
		pb.add32((long)steps.z);

		PacketResponse pr = runCommand(pb.getPacket());

		super.setCurrentPosition(p);
    }
	
    public void homeXYZ()
    {
		homeAxes(true, true, true);
		super.homeXYZ();
    }

    public void homeXY()
    {
		homeAxes(true, true, false);
		super.homeXY();
    }

    public void homeX()
    {
		homeAxes(true, false, false);
		super.homeX();
    }

    public void homeY()
    {
		homeAxes(false, true, false);
		super.homeY();
    }

    public void homeZ()
    {
		homeAxes(false, false, false);
		super.homeZ();
    }

	private void homeAxes(boolean x, boolean y, boolean z)
	{
		byte flags = 0x00;
		
		//figure out our fastest feedrate.
		Point3d maxFeedrates = machine.getMaximumFeedrates();
		double feedrate = Math.max(maxFeedrates.x, maxFeedrates.y);
		feedrate = Math.max(maxFeedrates.z, feedrate);
		
		Point3d target = new Point3d();
		
		if (x)
		{
			flags += 1;
			feedrate = Math.min(feedrate, maxFeedrates.x);
			target.x = 1; //just to give us feedrate info.
		}
		if (y)
		{
			flags += 2;
			feedrate = Math.min(feedrate, maxFeedrates.y);
			target.y = 1; //just to give us feedrate info.
		}
		if (z)
		{
			flags += 4;
			feedrate = Math.min(feedrate, maxFeedrates.z);
			target.z = 1; //just to give us feedrate info.
		}

		//calculate ticks
		long ticks = convertFeedrateToTicks(new Point3d(), target, feedrate);

		//calculate prescaler/counter values
		byte prescaler = convertTicksToPrescaler(ticks);
		int counter = convertTicksToCounter(ticks);
		
		//send it!
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.FIND_MINS);
		pb.add8(prescaler);
		pb.add16(counter);
		pb.add16(300); //default to 5 minutes
		pb.add8(flags);

		PacketResponse pr = runCommand(pb.getPacket());
	}
	
    public void delay(long millis)
    {
		//send it!
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.DELAY);
		pb.add32(millis);
		PacketResponse pr = runCommand(pb.getPacket());
    }
	
    public void openClamp(int clampIndex)
    {
		//TODO: throw some sort of unsupported exception.
		super.openClamp(clampIndex);
    }
	
    public void closeClamp(int clampIndex)
    {
		//TODO: throw some sort of unsupported exception.
		super.closeClamp(clampIndex);
    }
	
    public void enableDrives()
    {
		//TODO: throw some sort of unsupported exception.
		super.enableDrives();
    }
	
    public void disableDrives()
    {
		//TODO: throw some sort of unsupported exception.
		super.disableDrives();
    }
	
    public void changeGearRatio(int ratioIndex)
    {
		//TODO: throw some sort of unsupported exception.
		super.changeGearRatio(ratioIndex);
    }
	
	public void selectTool(int toolIndex)
	{
		//send it!
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.CHANGE_TOOL);
		pb.add8((byte)toolIndex);
		PacketResponse pr = runCommand(pb.getPacket());
		
		super.selectTool(toolIndex);
	}

    /*************************************
     *  Motor interface functions
     *************************************/
    public void setMotorSpeed(double rpm)
    {
		//convert RPM into microseconds and then send.
		long microseconds = (int)Math.round(60 * 1000000 / rpm); //no unsigned ints?!?
		microseconds = Math.min(microseconds, 2^32-1); // limit to uint32.
		
		//send it!
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_COMMAND);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.SET_MOTOR_1_RPM);
		pb.add32(microseconds);
		PacketResponse pr = runCommand(pb.getPacket());

		super.setMotorSpeed(rpm);
    }
	
    public void enableMotor()
    {
		//our flag variable starts with motors enabled.
		byte flags = 1;
		
		//bit 1 determines direction...
		if (machine.currentTool().getMotorDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		//send it!
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_COMMAND);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.TOGGLE_MOTOR_1);
		pb.add8(flags);
		PacketResponse pr = runCommand(pb.getPacket());
		
		super.enableMotor();
    }
	
    public void disableMotor()
    {
		byte flags = 0;
		
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_COMMAND);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.TOGGLE_MOTOR_1);
		pb.add8(flags);
		PacketResponse pr = runCommand(pb.getPacket());

		super.disableMotor();
    }

    /*************************************
     *  Spindle interface functions
     *************************************/
    public void setSpindleSpeed(double rpm)
    {
		//convert RPM into microseconds and then send.
		long microseconds = (int)Math.round(60 * 1000000 / rpm); //no unsigned ints?!?
		microseconds = Math.min(microseconds, 2^32-1); // limit to uint32.
		
		//send it!
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_COMMAND);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.SET_MOTOR_2_RPM);
		pb.add32(microseconds);
		PacketResponse pr = runCommand(pb.getPacket());

		super.setSpindleSpeed(rpm);
    }
	
    public void enableSpindle()
    {
		//our flag variable starts with spindles enabled.
		byte flags = 1;
		
		//bit 1 determines direction...
		if (machine.currentTool().getSpindleSpeed() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		//send it!
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_COMMAND);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.TOGGLE_MOTOR_2);
		pb.add8(flags);
		PacketResponse pr = runCommand(pb.getPacket());
		
		super.enableSpindle();
    }
	
    public void disableSpindle()
    {
		byte flags = 0;
		
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_COMMAND);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.TOGGLE_MOTOR_1);
		pb.add8(flags);
		PacketResponse pr = runCommand(pb.getPacket());

		super.disableSpindle();
    }
	
    public void readSpindleSpeed()
    {
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_QUERY);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.GET_MOTOR_2_RPM);
		PacketResponse pr = runCommand(pb.getPacket());
		
		//TODO: get microsecond value and convert to RPM.
		
		super.readSpindleSpeed();
    }
	
    /*************************************
     *  Temperature interface functions
     *************************************/
    public void setTemperature(double temperature)
    {
		//constrain our temperature.
		int temp = (int)Math.round(temperature);
		temp = Math.min(temp, 65535);
		
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_COMMAND);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.SET_TEMPERATURE);
		pb.add16(temp);
		PacketResponse pr = runCommand(pb.getPacket());		
		
		super.setTemperature(temperature);
    }

    public void readTemperature()
    {
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_QUERY);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.GET_TEMPERATURE);
		PacketResponse pr = runCommand(pb.getPacket());		

		//TODO: get value and update our model with it, etc.
		
		super.readTemperature();
    }

    /*************************************
     *  Flood Coolant interface functions
     *************************************/
    public void enableFloodCoolant()
    {
		//TODO: throw unsupported exception
		
		super.enableFloodCoolant();
    }
	
    public void disableFloodCoolant()
    {
		//TODO: throw unsupported exception
		
		super.disableFloodCoolant();
    }

    /*************************************
     *  Mist Coolant interface functions
     *************************************/
    public void enableMistCoolant()
    {
		//TODO: throw unsupported exception
		
		super.enableMistCoolant();
    }
	
    public void disableMistCoolant()
    {
		//TODO: throw unsupported exception

		super.disableMistCoolant();
    }

    /*************************************
     *  Fan interface functions
     *************************************/
    public void enableFan()
    {
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_COMMAND);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.TOGGLE_FAN);
		pb.add16(1);
		PacketResponse pr = runCommand(pb.getPacket());		
		
		super.enableFan();
    }
	
    public void disableFan()
    {
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_COMMAND);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.TOGGLE_FAN);
		pb.add16(0);
		PacketResponse pr = runCommand(pb.getPacket());		
		
		super.disableFan();
    }
	
    /*************************************
     *  Valve interface functions
     *************************************/
    public void openValve()
    {
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_COMMAND);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.TOGGLE_VALVE);
		pb.add16(1);
		PacketResponse pr = runCommand(pb.getPacket());		

		super.openValve();
    }
	
    public void closeValve()
    {
		PacketBuilder pb = new PacketBuilder(CommandCodesMaster.TOOL_COMMAND);
		pb.add8((byte)machine.currentTool().getIndex());
		pb.add8(CommandCodesSlave.TOGGLE_VALVE);
		pb.add16(0);
		PacketResponse pr = runCommand(pb.getPacket());		
		
		super.closeValve();
    }
	
    /*************************************
     *  Collet interface functions
     *************************************/
    public void openCollet()
    {
		//TODO: throw unsupported exception.
		
		super.openCollet();
    }
	
    public void closeCollet()
    {
		//TODO: throw unsupported exception.
		
		super.closeCollet();
    }

	/*************************************
     *  Various timer and math functions.
     *************************************/

	private Point3d getDeltaSteps(Point3d current, Point3d target)
	{
		//calculate our deltas.
		Point3d delta = new Point3d();
		delta.x = Math.abs(target.x - current.x);
		delta.y = Math.abs(target.y - current.y);
		delta.z = Math.abs(target.z - current.z);
		
		return machine.mmToSteps(delta);
	}
	
	private long convertFeedrateToTicks(Point3d current, Point3d target, double feedrate)
	{
		Point3d deltaSteps = getDeltaSteps(current, target);
		
		//how long is our line length?
		double distance = Math.sqrt(
			deltaSteps.x * deltaSteps.x + 
			deltaSteps.y * deltaSteps.y + 
			deltaSteps.z * deltaSteps.z
		);
		
		long master_steps = 0;

		//find the dominant axis.
		if (deltaSteps.x > deltaSteps.y)
		{
			if (deltaSteps.z > deltaSteps.x)
				master_steps = (long)deltaSteps.z;
			else
				master_steps = (long)deltaSteps.x;
		}
		else
		{
			if (deltaSteps.z > deltaSteps.y)
				master_steps = (long)deltaSteps.z;
			else
				master_steps = (long)deltaSteps.y;
		}

		//calculate delay between steps in ticks.  this is sort of tricky, but not too bad.
		// 960,000,000 = number of ticks in a second at 16Mhz, the speed of an Arduino/Sanguino.
		//the formula has been condensed to save space.  here it is in english:
	        // (feedrate is in mm/minute)
		// distance / feedrate * 960,000,000.0 = move duration in ticks
		// move duration / master_steps = time between steps for master axis.

		return (long)Math.floor(((distance * 960000000.0) / feedrate) / master_steps);
	}
	
	private byte convertTicksToPrescaler(long ticks)
	{
		// these also represent frequency: 1000000 / ticks / 2 = frequency in hz.

		// our slowest speed at our highest resolution ( (2^16-1) * 0.0625 usecs = 4095 usecs (4 millisecond max))
		// range: 8Mhz max - 122hz min
		if (ticks <= 65535L)
			return 1;
		// our slowest speed at our next highest resolution ( (2^16-1) * 0.5 usecs = 32767 usecs (32 millisecond max))
		// range:1Mhz max - 15.26hz min
		else if (ticks <= 524280L)
			return 2;
		// our slowest speed at our medium resolution ( (2^16-1) * 4 usecs = 262140 usecs (0.26 seconds max))
		// range: 125Khz max - 1.9hz min
		else if (ticks <= 4194240L)
			return 3;
		// our slowest speed at our medium-low resolution ( (2^16-1) * 16 usecs = 1048560 usecs (1.04 seconds max))
		// range: 31.25Khz max - 0.475hz min
		else if (ticks <= 16776960L)
			return 4;
		// our slowest speed at our lowest resolution ((2^16-1) * 64 usecs = 4194240 usecs (4.19 seconds max))
		// range: 7.812Khz max - 0.119hz min
		else if (ticks <= 67107840L)
			return 5;
		//its really slow... hopefully we can just get by with super slow.
		else
			return 5;		
	}
	
	private int convertTicksToCounter(long ticks)
	{
		// our slowest speed at our highest resolution ( (2^16-1) * 0.0625 usecs = 4095 usecs)
		if (ticks <= 65535)
			return ((int)(ticks & 0xffff));
		// our slowest speed at our next highest resolution ( (2^16-1) * 0.5 usecs = 32767 usecs)
		else if (ticks <= 524280)
			return ((int)((ticks / 8) & 0xffff));
		// our slowest speed at our medium resolution ( (2^16-1) * 4 usecs = 262140 usecs)
		else if (ticks <= 4194240)
			return ((int)((ticks / 64) & 0xffff));
		// our slowest speed at our medium-low resolution ( (2^16-1) * 16 usecs = 1048560 usecs)
		else if (ticks <= 16776960)
			return ((int)(ticks / 256));
		// our slowest speed at our lowest resolution ((2^16-1) * 64 usecs = 4194240 usecs)
		else if (ticks <= 67107840)
			return ((int)(ticks / 1024));
		//its really slow... hopefully we can just get by with super slow.
		else
			return 65535;
	}
}
