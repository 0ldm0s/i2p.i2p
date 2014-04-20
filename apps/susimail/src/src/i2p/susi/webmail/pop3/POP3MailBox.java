/*
 * Created on Nov 4, 2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 * $Revision: 1.1 $
 */
package i2p.susi.webmail.pop3;

import i2p.susi.debug.Debug;
import i2p.susi.webmail.Messages;
import i2p.susi.util.ReadBuffer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import net.i2p.data.DataHelper;

/**
 * @author susi23
 */
public class POP3MailBox {

	private final String host, user, pass;

	private String lastLine, lastError;

	private final int port;
	private int mails;

	private boolean connected;
	private boolean supportsPipelining;
	private boolean supportsTOP;
	private boolean supportsUIDL;

	/** ID to size */
	private final HashMap<Integer, Integer> sizes;
	/** UIDL to ID */
	private final HashMap<String, Integer> uidlToID;

	private Socket socket;

	private final Object synchronizer;

	/**
	 * @param host
	 * @param port
	 * @param user
	 * @param pass
	 */
	public POP3MailBox(String host, int port, String user, String pass) {
		Debug.debug(
			Debug.DEBUG,
			"Mailbox(" + host + "," + port + "," + user + ",password)");
		this.host = host;
		this.port = port;
		this.user = user;
		this.pass = pass;
		uidlToID = new HashMap<String, Integer>();
		sizes = new HashMap<Integer, Integer>();
		synchronizer = new Object();
		// this appears in the UI so translate
		lastLine = _("No response from server");
		connect();
	}

	/**
	 * Fetch the header. Does not cache.
	 * 
	 * @param uidl
	 * @return Byte buffer containing header data or null
	 */
	public ReadBuffer getHeader( String uidl ) {
		synchronized( synchronizer ) {
			int id = getIDfromUIDL(uidl);
			if (id < 0)
				return null;
			return getHeader(id);
		}
	}

	/**
	 * retrieves header from pop3 server (with TOP command and RETR as fallback)
	 * Caller must sync.
	 * 
	 * @param id message id
	 * @return Byte buffer containing header data or null
	 */
	private ReadBuffer getHeader( int id ) {
			Debug.debug(Debug.DEBUG, "getHeader(" + id + ")");
			Integer idObj = Integer.valueOf(id);
			ReadBuffer header = null;
			if (id >= 1 && id <= mails) {
				/*
				 * try 'TOP n 0' command
				 */
				header = sendCmdN("TOP " + id + " 0" );
				if( header == null) {
					/*
					 * try 'RETR n' command
					 */
					header = sendCmdN("RETR " + id );
					if (header == null)
						Debug.debug( Debug.DEBUG, "RETR returned null" );
				}
			} else {
				lastError = "Message id out of range.";
			}
			return header;
	}

	/**
	 * Fetch the body. Does not cache.
	 * 
	 * @param uidl
	 * @return Byte buffer containing body data or null
	 */
	public ReadBuffer getBody( String uidl ) {
		synchronized( synchronizer ) {
			int id = getIDfromUIDL(uidl);
			if (id < 0)
				return null;
			return getBody(id);
		}
	}
	
	/**
	 * retrieve message body from pop3 server (via RETR command)
	 * Caller must sync.
	 * 
	 * @param id message id
	 * @return Byte buffer containing body data or null
	 */
	private ReadBuffer getBody(int id) {
			Debug.debug(Debug.DEBUG, "getBody(" + id + ")");
			Integer idObj = Integer.valueOf(id);
			ReadBuffer body = null;
			if (id >= 1 && id <= mails) {
				body = sendCmdN( "RETR " + id );
				if (body == null)
					Debug.debug( Debug.DEBUG, "RETR returned null" );
			}
			else {
				lastError = "Message id out of range.";
			}
			return body;
	}

	/**
	 * 
	 * @param uidl
	 * @return Success of delete operation: true if successful.
	 */
	public boolean delete( String uidl )
	{
		Debug.debug(Debug.DEBUG, "delete(" + uidl + ")");
		synchronized( synchronizer ) {
			int id = getIDfromUIDL(uidl);
			if (id < 0)
				return false;
			return delete(id);
		}
	}
	
	/**
	 * delete message on pop3 server
	 * 
	 * @param id message id
	 * @return Success of delete operation: true if successful.
	 */
	private boolean delete(int id)
	{
		Debug.debug(Debug.DEBUG, "delete(" + id + ")");
		
		boolean result = false;
		
		synchronized( synchronizer ) {
			
			try {
				result = sendCmd1a( "DELE " + id );
			}
			catch (IOException e) {
			}
		}
		return result;
	}

	/**
	 * Get cached size of a message (via previous LIST command).
	 * 
	 * @param uidl
	 * @return Message size in bytes or 0 if not found
	 */
	public int getSize( String uidl ) {
		synchronized( synchronizer ) {
			int id = getIDfromUIDL(uidl);
			if (id < 0)
				return 0;
			return getSize(id);
		}
	}
	
	/**
	 * Get cached size of a message (via previous LIST command).
	 * Caller must sync.
	 * 
	 * @param id message id
	 * @return Message size in bytes or 0 if not found
	 */
	private int getSize(int id) {
			int result = 0;
			/*
			 * find value in hashtable
			 */
			Integer resultObj = sizes.get(Integer.valueOf(id));
			if (resultObj != null)
				result = resultObj.intValue();
			Debug.debug(Debug.DEBUG, "getSize(" + id + ") = " + result);
			return result;
	}

	/**
	 * check whether connection is still alive
	 * 
	 * @return true or false
	 */
	public boolean isConnected() {
		Debug.debug(Debug.DEBUG, "isConnected()");

		if (socket == null
			|| !socket.isConnected()
			|| socket.isInputShutdown()
			|| socket.isOutputShutdown()
			|| socket.isClosed()) {
			connected = false;
		}
		return connected;
	}

	/**
	 * 
	 * @param response line starting with +OK
	 */
	private void updateMailCount(String response) {
		if (response == null || response.length() < 4) {
			mails = 0;
			return;
		}
		response = response.trim();
		try {
			int i = response.indexOf(" ", 5);
			mails =
				Integer.parseInt(
					i != -1
						? response.substring(4, i)
						: response.substring(4));
		} catch (NumberFormatException nfe) {
			mails = 0;
		}
	}

	/**
	 * Caller must sync.
	 * 
	 * @throws IOException
	 */
	private void updateUIDLs(List<String> lines) {
			uidlToID.clear();
			if (lines != null) {
				for (String line : lines) {
					int j = line.indexOf( " " );
					if( j != -1 ) {
						try {
							int n = Integer.parseInt( line.substring( 0, j ) );
							String uidl = line.substring(j + 1).trim();
							uidlToID.put( uidl, Integer.valueOf( n ) );
						} catch (NumberFormatException nfe) {
							Debug.debug(Debug.DEBUG, "UIDL error " + nfe);
						} catch (IndexOutOfBoundsException ioobe) {
							Debug.debug(Debug.DEBUG, "UIDL error " + ioobe);
						}
					}
				}
			} else {
				Debug.debug(Debug.DEBUG, "Error getting UIDL list from server.");
			}
	}

	/**
	 * Caller must sync.
	 * 
	 * @throws IOException
	 */
	private void updateSizes(List<String> lines) {
		/*
		 * try LIST
		 */
		sizes.clear();
		if (lines != null) {
			for (String line : lines) {
				int j = line.indexOf(" ");
				if (j != -1) {
					try {
						int key = Integer.parseInt(line.substring(0, j));
						int value = Integer.parseInt(line.substring(j + 1).trim());
						sizes.put(Integer.valueOf(key), Integer.valueOf(value));
					} catch (NumberFormatException nfe) {
						Debug.debug(Debug.DEBUG, "LIST error " + nfe);
					}
				}
			}
		} else {
			Debug.debug(Debug.DEBUG, "Error getting LIST from server.");
		}
	}

	/**
	 * 
	 *
	 */
	public void refresh() {
		synchronized( synchronizer ) {
			close();
			connect();
		}
	}

	/**
	 * Caller must sync.
	 */
	private void clear()
	{
		uidlToID.clear();
		sizes.clear();
		mails = 0;
	}

	/**
	 * connect to pop3 server, login with USER and PASS and try STAT then
	 *
	 * Caller must sync.
	 */
	private void connect() {
		Debug.debug(Debug.DEBUG, "connect()");

		clear();
		
		if (socket != null && socket.isConnected())
			close();
		
		try {
			socket = new Socket(host, port);
		} catch (UnknownHostException e) {
			lastError = e.toString();
			return;
		} catch (IOException e) {
			lastError = e.toString();
			return;
		}
		if (socket != null) {
			try {
				// pipeline 2 commands
				lastError = "";
				boolean ok = doHandshake();
				if (ok) {
					// TODO APOP (unsupported by postman)
					List<SendRecv> cmds = new ArrayList<SendRecv>(4);
					cmds.add(new SendRecv("USER " + user, Mode.A1));
					cmds.add(new SendRecv("PASS " + pass, Mode.A1));
					ok =  sendCmds(cmds);
				}
				if (ok) {
					connected = true;
					List<SendRecv> cmds = new ArrayList<SendRecv>(4);
					SendRecv stat = new SendRecv("STAT", Mode.A1);
					cmds.add(stat);
					SendRecv uidl = new SendRecv("UIDL", Mode.LS);
					cmds.add(uidl);
					SendRecv list = new SendRecv("LIST", Mode.LS);
					cmds.add(list);
					// check individual responses
					sendCmds(cmds);
					if (stat.result)
						updateMailCount(stat.response);
					else
						Debug.debug(Debug.DEBUG, "STAT failed");
					if (uidl.result)
						updateUIDLs(uidl.ls);
					else
						Debug.debug(Debug.DEBUG, "UIDL failed");
					if (list.result)
						updateSizes(list.ls);
					else
						Debug.debug(Debug.DEBUG, "LIST failed");
				} else {
					if (lastError.equals(""))
						lastError = _("Error connecting to server");
					close();
				}
			}
			catch (NumberFormatException e1) {
				lastError = _("Error opening mailbox") + ": " + e1;
			}
			catch (IOException e1) {
				lastError = _("Error opening mailbox") + ": " + e1.getLocalizedMessage();
			}
		}
	}

	/**
	 * Check the initial response, send CAPA, check the CAPA result
	 * Caller must sync.
	 * 
	 * @return true if successful
	 * @throws IOException
	 * @since 0.9.13
	 */
	private boolean doHandshake() throws IOException {
		// can we always pipeline this ?
		supportsPipelining = false;
		supportsUIDL = false;
		supportsTOP = false;
		List<SendRecv> cmds = new ArrayList<SendRecv>(2);
		cmds.add(new SendRecv(null, Mode.A1));
		SendRecv capa = new SendRecv("CAPA", Mode.LS);
		cmds.add(capa);
		boolean rv = sendCmds(cmds);
		if (rv) {
			if (capa.ls != null) {
				for (String cap : capa.ls) {
					String t = cap.trim();
					if (t.equals("PIPELINING"))
						supportsPipelining = true;
					else if (t.equals("UIDL"))
						supportsUIDL = true;
					else if (t.equals("TOP"))
						supportsTOP = true;
				}
			}
		}
		Debug.debug(Debug.DEBUG, "POP3 server caps: pipelining? " + supportsPipelining +
		                                           " UIDL? " + supportsUIDL +
		                                           " TOP? " + supportsTOP);
		return rv;
	}
	
	/**
	 * send command to pop3 server (and expect single line answer)
	 * Response will be in lastLine. Does not read past the first line of the response.
	 * Caller must sync.
	 * 
	 * @param cmd command to send
	 * @return true if command was successful (+OK)
	 * @throws IOException
	 */
	private boolean sendCmd1a(String cmd) throws IOException {
		boolean result = false;
		sendCmd1aNoWait(cmd);
		socket.getOutputStream().flush();
		String foo = DataHelper.readLine(socket.getInputStream());
		// Debug.debug(Debug.DEBUG, "sendCmd1a: read " + read + " bytes");
		if (foo != null) {
			lastLine = foo;
			if (lastLine.startsWith("+OK")) {
				if (cmd.startsWith("PASS"))
					cmd = "PASS provided";
				Debug.debug(Debug.DEBUG, "sendCmd1a: (" + cmd + ") success: \"" + lastLine.trim() + '"');
				result = true;
			} else {
				if (cmd.startsWith("PASS"))
					cmd = "PASS provided";
				Debug.debug(Debug.DEBUG, "sendCmd1a: (" + cmd + ") FAIL: \"" + lastLine.trim() + '"');
				lastError = lastLine;
			}
		} else {
			Debug.debug(Debug.DEBUG, "sendCmd1a: (" + cmd + ") NO RESPONSE");
			lastError = _("No response from server");
			throw new IOException(lastError);
		}
		return result;
	}

	/**
	 * Send commands to pop3 server all at once (and expect answers).
	 * Sets lastError to the FIRST error.
	 * Caller must sync.
	 * 
	 * @param cmd command to send
	 * @param rcvLines lines to receive
	 * @return true if ALL received lines were successful (+OK)
	 * @throws IOException
	 * @since 0.9.13
	 */
	private boolean sendCmds(List<SendRecv> cmds) throws IOException {
		boolean result = true;
		boolean pipe = supportsPipelining;
		if (pipe) {
			Debug.debug(Debug.DEBUG, "POP3 pipelining " + cmds.size() + " commands");
			for (SendRecv sr : cmds) {
				String cmd = sr.send;
				if (cmd != null)
					sendCmd1aNoWait(cmd);
			}
		} // else we will do it below
		socket.getOutputStream().flush();
		InputStream in = socket.getInputStream();
		int i = 0;
		for (SendRecv sr : cmds) {
			if (!pipe) {
				String cmd = sr.send;
				if (cmd != null) {
					sendCmd1aNoWait(cmd);
					socket.getOutputStream().flush();
				}
			}
			String foo = DataHelper.readLine(in);
			if (foo == null) {
				lastError = _("No response from server");
				throw new IOException(lastError);
			}
			sr.response = foo.trim();
			i++;
			if (!foo.startsWith("+OK")) {
				Debug.debug(Debug.DEBUG, "Fail after " + i + " of " + cmds.size() + " responses: \"" + foo.trim() + '"');
				if (result)
				    lastError = foo;   // actually the first error, for better info to the user
				result = false;
				sr.result = false;
			} else {
				Debug.debug(Debug.DEBUG, "OK after " + i + " of " + cmds.size() + " responses: \"" + foo.trim() + '"');
				switch (sr.mode) {
				    case A1:
					sr.result = true;
					break;

				    case RB:
					try {
						sr.rb = getResultNa();
						sr.result = true;
					} catch (IOException ioe) {
						result = false;
						sr.result = false;
					}
					break;

				    case LS:
					try {
						sr.ls = getResultNl();
						sr.result = true;
					} catch (IOException ioe) {
						result = false;
						sr.result = false;
					}
					break;
				}
			}
			lastLine = foo;
		}
		return result;
	}
	
	/**
	 * send command to pop3 server. Does NOT flush or read or wait.
	 * Caller must sync.
	 * 
	 * @param cmd command to send non-null
	 * @throws IOException
         * @since 0.9.13
	 */
	private void sendCmd1aNoWait(String cmd) throws IOException {
		/*
		 * dont log password
		 */
		String msg = cmd;
		if (msg.startsWith("PASS"))
			msg = "PASS provided";
		Debug.debug(Debug.DEBUG, "sendCmd1a(" + msg + ")");
		cmd += "\r\n";
		socket.getOutputStream().write(cmd.getBytes());
	}

	/**
	 * Tries twice
	 * Caller must sync.
	 * 
	 * @return buffer or null
	 */
	private ReadBuffer sendCmdN(String cmd )
	{
		synchronized (synchronizer) {
			if (!isConnected())
				connect();
			try {
				return sendCmdNa(cmd);
			} catch (IOException e) {
				lastError = e.toString();
				Debug.debug( Debug.DEBUG, "sendCmdNa throws: " + e);
			}
			connect();
			if (connected) {
				try {
					return sendCmdNa(cmd);
				} catch (IOException e2) {
					lastError = e2.toString();
					Debug.debug( Debug.DEBUG, "2nd sendCmdNa throws: " + e2);
				}
			} else {
				Debug.debug( Debug.DEBUG, "not connected after reconnect" );					
			}
		}
		return null;
	}

	/**
	 * No total timeout (result could be large)
	 * Caller must sync.
	 *
	 * @return buffer or null
	 * @throws IOException
	 */
	private ReadBuffer sendCmdNa(String cmd) throws IOException
	{
		if (sendCmd1a(cmd)) {
			return getResultNa();
		} else {
			Debug.debug( Debug.DEBUG, "sendCmd1a returned false" );
			return null;
		}
	}

	/**
	 * Like sendCmdNa but returns a list of strings, one per line.
	 * Strings will have trailing \r but not \n.
	 * Total timeout 2 minutes.
	 * Caller must sync.
	 *
	 * @return the lines or null on error
	 * @throws IOException on timeout
         * @since 0.9.13
	 */
	private List<String> sendCmdNl(String cmd) throws IOException
	{
		if (sendCmd1a(cmd)) {
			return getResultNl();
		} else {
			Debug.debug( Debug.DEBUG, "sendCmd1a returned false" );
			return null;
		}
	}

	/**
	 * No total timeout (result could be large)
	 * Caller must sync.
	 *
	 * @return buffer non-null
	 * @throws IOException
	 */
	private ReadBuffer getResultNa() throws IOException
	{
		InputStream input = socket.getInputStream();
		StringBuilder buf = new StringBuilder(512);
		ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
		while (DataHelper.readLine(input, buf)) {
			int len = buf.length();
			if (len == 0)
				break; // huh? no \r?
			if (len == 2 && buf.charAt(0) == '.' && buf.charAt(1) == '\r')
				break;
			String line;
			// RFC 1939 sec. 3 de-byte-stuffing
			if (buf.charAt(0) == '.')
				line = buf.substring(1);
			else
				line = buf.toString();
			baos.write(DataHelper.getASCII(line));
			if (buf.charAt(len - 1) != '\r')
				baos.write((byte) '\n');
			baos.write((byte) '\n');
			buf.setLength(0);
		}
		ReadBuffer readBuffer = new ReadBuffer();
		readBuffer.content = baos.toByteArray();
		readBuffer.offset = 0;
		readBuffer.length = baos.size();
		return readBuffer;
	}

	/**
	 * Like getResultNa but returns a list of strings, one per line.
	 * Strings will have trailing \r but not \n.
	 * Total timeout 2 minutes.
	 * Caller must sync.
	 *
	 * @return the lines non-null
	 * @throws IOException on timeout
         * @since 0.9.13
	 */
	private List<String> getResultNl() throws IOException
	{
		List<String> rv = new ArrayList<String>(16);
		long timeOut = 120*1000;
		InputStream input = socket.getInputStream();
		long startTime = System.currentTimeMillis();
		StringBuilder buf = new StringBuilder(512);
		while (DataHelper.readLine(input, buf)) {
			int len = buf.length();
			if (len == 0)
				break; // huh? no \r?
			if (len == 2 && buf.charAt(0) == '.' && buf.charAt(1) == '\r')
				break;
			if( System.currentTimeMillis() - startTime > timeOut )
				throw new IOException( "Timeout while waiting on server response." );
			String line;
			// RFC 1939 sec. 3 de-byte-stuffing
			if (buf.charAt(0) == '.')
				line = buf.substring(1);
			else
				line = buf.toString();
			rv.add(line);
			buf.setLength(0);
		}
		return rv;
	}

	/**
	 * Warning - forces a connection.
	 *
	 * @return The amount of e-mails available.
	 * @deprecated unused
	 */
	public int getNumMails() {
		synchronized( synchronizer ) {
			Debug.debug(Debug.DEBUG, "getNumMails()");

			if (!isConnected())
				connect();

			return connected ? mails : 0;
		}
	}

	/**
	 * @return The most recent error message.
	 */
	public String lastError() {
		//Debug.debug(Debug.DEBUG, "lastError()");
		// Hide the "-ERR" from the user
		String e = lastError;
		if (e.startsWith("-ERR ") && e.length() > 5)
			e = e.substring(5);
		// translate this common error
		if (e.trim().equals("Login failed."))
			e = _("Login failed");
		return e;
	}

	/**
	 *  Close without waiting for response
	 */
	public void close() {
		close(false);
	}

	/**
	 *  Close and optionally waiting for response
	 *  @since 0.9.13
	 */
	private void close(boolean shouldWait) {
		synchronized( synchronizer ) {
			Debug.debug(Debug.DEBUG, "close()");
			if (socket != null && socket.isConnected()) {
				try {
					if (shouldWait)
						sendCmd1a("QUIT");
					else
						sendCmd1aNoWait("QUIT");
					socket.close();
				} catch (IOException e) {
					Debug.debug( Debug.DEBUG, "Error while closing connection: " + e);
				}
			}
			socket = null;
			connected = false;
		}
	}

	/**
	 * returns number of message with given UIDL
	 * Caller must sync.
	 * 
	 * @param uidl
	 * @return Message number or -1
	 */
	private int getIDfromUIDL( String uidl )
	{
		int result = -1;
		Integer intObject = uidlToID.get( uidl );
		if( intObject != null ) {
			result = intObject.intValue();
		}
		return result;
	}

	/**
	 * Unused
	 * @param id
	 * @return UIDL or null
	 */
/****
	public String getUIDLfromID( int id )
	{
		synchronized( synchronizer ) {
			try {
				return uidlList.get( id );
			} catch (IndexOutOfBoundsException ioobe) {
				return null;
			}
		}
	}
****/

	/**
	 * 
	 * @return A new array of the available UIDLs. No particular order.
	 */
	public String[] getUIDLs()
	{
		synchronized( synchronizer ) {
		       return uidlToID.keySet().toArray(new String[uidlToID.size()]);
		}
	}

	/**
	 * 
	 * @param args
	 */
/****
	public static void main( String[] args )
	{
		Debug.setLevel( Debug.DEBUG );
		POP3MailBox mailbox = new POP3MailBox( "localhost", 7660 , "test", "test");
		ReadBuffer readBuffer = mailbox.sendCmdN( "LIST" );
		System.out.println( "list='" + readBuffer + "'" );
	}
****/

	/**
	 *  Close and reconnect. Takes a while.
	 */
	public void performDelete()
	{
		synchronized( synchronizer ) {
			close(true);
			connect();
		}
	}

	private enum Mode {
		/** no extra lines (sendCmd1a) */
		A1,
		/** return extra lines in ReadBuffer (sendCmdNa) */
		RB,
		/** return extra lines in List of Strings (sendCmdNl) */
		LS
	}

	/**
	 *  A command to send and a mode to receive and return the results
	 *  @since 0.9.13
	 */
	private static class SendRecv {
		public final String send;
		public final Mode mode;
		public String response;
		public boolean result;
		public ReadBuffer rb;
		public List<String> ls;

		/** @param s may be null */
		public SendRecv(String s, Mode m) {
			send = s;
			mode = m;
		}
	}

	/** translate */
	private static String _(String s) {
		return Messages.getString(s);
	}
}
