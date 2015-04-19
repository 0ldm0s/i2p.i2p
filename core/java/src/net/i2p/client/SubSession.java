package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.SessionId;

/**
 *  An additional session using another session's connection.
 *
 *  A subsession uses the same connection to the router as the primary session,
 *  but has a different Destination. It uses the same tunnels as the primary
 *  but has its own leaseset. It must use the same encryption keys as the primary
 *  so that garlic encryption/decryption works.
 *
 *  The message handler map and message producer are reused from primary.
 *
 *  Does NOT reuse the session listener ????
 *
 *  While the I2CP protocol, in theory, allows for fully independent sessions
 *  over the same I2CP connection, this is not currently supported by the router.
 *
 *  @since 0.9.19
 */
class SubSession extends I2PSessionMuxedImpl {
    private final I2PSessionMuxedImpl _primary;

    /**
     *  @param primary must be a I2PSessionMuxedImpl
     */
    public SubSession(I2PSession primary, InputStream destKeyStream, Properties options) throws I2PSessionException {
        super((I2PSessionMuxedImpl)primary, destKeyStream, options);
        _primary = (I2PSessionMuxedImpl) primary;
        if (!getDecryptionKey().equals(_primary.getDecryptionKey()))
            throw new I2PSessionException("encryption key mismatch");
        if (getPrivateKey().equals(_primary.getPrivateKey()))
            throw new I2PSessionException("signing key must differ");
        // state management
    }

    /**
     *  Unsupported in a subsession.
     *  @throws UnsupportedOperationException always
     *  @since 0.9.19
     */
    @Override
    public I2PSession addSubsession(InputStream destKeyStream, Properties opts) throws I2PSessionException {
        throw new UnsupportedOperationException();
    }
    
    /**
     *  Unsupported in a subsession.
     *  Does nothing.
     *  @since 0.9.19
     */
    @Override
    public void removeSubsession(I2PSession session) {}
    
    /**
     *  Unsupported in a subsession.
     *  @return empty list always
     *  @since 0.9.19
     */
    @Override
    public List<I2PSession> getSubsessions() {
        return Collections.emptyList();
    }

    /**
     *  Does nothing for now
     */
    @Override
    public void updateOptions(Properties options) {}

    /**
     * Connect to the router and establish a session.  This call blocks until 
     * a session is granted.
     *
     * Should be threadsafe, other threads will block until complete.
     * Disconnect / destroy from another thread may be called simultaneously and
     * will (should?) interrupt the connect.
     *
     * @throws I2PSessionException if there is a configuration error or the router is
     *                             not reachable
     */
    @Override
    public void connect() throws I2PSessionException {
        _primary.connect();
    }

    /**
     *  Has the session been closed (or not yet connected)?
     *  False when open and during transitions.
     */
    @Override
    public boolean isClosed() {
        // FIXME
        return /* getSessionId() == null || */  _primary.isClosed();
    }

    /**
     * Deliver an I2CP message to the router
     * May block for several seconds if the write queue to the router is full
     *
     * @throws I2PSessionException if the message is malformed or there is an error writing it out
     */
    @Override
    void sendMessage(I2CPMessage message) throws I2PSessionException {
        if (isClosed())
            throw new I2PSessionException("Already closed");
        _primary.sendMessage(message);
    }

    /**
     * Pass off the error to the listener
     * Misspelled, oh well.
     * @param error non-null
     */
    @Override
    void propogateError(String msg, Throwable error) {
        _primary.propogateError(msg, error);
        if (_sessionListener != null) _sessionListener.errorOccurred(this, msg, error);
    }

    /**
     * Tear down the session, and do NOT reconnect.
     *
     * Blocks if session has not been fully started.
     */
    @Override
    public void destroySession() {
        _primary.destroySession();
        if (_availabilityNotifier != null)
            _availabilityNotifier.stopNotifying();
        if (_sessionListener != null) _sessionListener.disconnected(this);
    }

    /**
     * Will interrupt a connect in progress.
     */
    @Override
    protected void disconnect() {
        _primary.disconnect();
    }

    @Override
    protected boolean reconnect() {
        return _primary.reconnect();
    }

    /**
     *  Called by the message handler
     *  on reception of DestReplyMessage
     *
     *  This will never happen, as the dest reply message does not contain a session ID.
     */
    @Override
    void destReceived(Destination d) {
        _primary.destReceived(d);
    }

    /**
     *  Called by the message handler
     *  on reception of DestReplyMessage
     *
     *  This will never happen, as the dest reply message does not contain a session ID.
     *
     *  @param h non-null
     */
    @Override
    void destLookupFailed(Hash h) {
        _primary.destLookupFailed(h);
    }

    /**
     *  Called by the message handler
     *  on reception of HostReplyMessage
     *  @param d non-null
     */
    void destReceived(long nonce, Destination d) {
        _primary.destReceived(nonce, d);
    }

    /**
     *  Called by the message handler
     *  on reception of HostReplyMessage
     */
    @Override
    void destLookupFailed(long nonce) {
        _primary.destLookupFailed(nonce);
    }

    /**
     * Called by the message handler.
     * This will never happen, as the bw limits message does not contain a session ID.
     */
    @Override
    void bwReceived(int[] i) {
        _primary.bwReceived(i);
    }

    /**
     *  Blocking. Waits a max of 10 seconds by default.
     *  See lookupDest with maxWait parameter to change.
     *  Implemented in 0.8.3 in I2PSessionImpl;
     *  previously was available only in I2PSimpleSession.
     *  Multiple outstanding lookups are now allowed.
     *  @return null on failure
     */
    @Override
    public Destination lookupDest(Hash h) throws I2PSessionException {
        return _primary.lookupDest(h);
    }

    /**
     *  Blocking.
     *  @param maxWait ms
     *  @return null on failure
     */
    @Override
    public Destination lookupDest(Hash h, long maxWait) throws I2PSessionException {
        return _primary.lookupDest(h, maxWait);
    }

    /**
     *  Ask the router to lookup a Destination by host name.
     *  Blocking. Waits a max of 10 seconds by default.
     *
     *  This only makes sense for a b32 hostname, OR outside router context.
     *  Inside router context, just query the naming service.
     *  Outside router context, this does NOT query the context naming service.
     *  Do that first if you expect a local addressbook.
     *
     *  This will log a warning for non-b32 in router context.
     *
     *  See interface for suggested implementation.
     *
     *  Requires router side to be 0.9.11 or higher. If the router is older,
     *  this will return null immediately.
     */
    @Override
    public Destination lookupDest(String name) throws I2PSessionException {
        return _primary.lookupDest(name);
    }

    /**
     *  Ask the router to lookup a Destination by host name.
     *  Blocking. See above for details.
     *  @param maxWait ms
     *  @return null on failure
     */
    @Override
    public Destination lookupDest(String name, long maxWait) throws I2PSessionException {
        return _primary.lookupDest(name, maxWait);
    }

    /**
     *  This may not work???????????, as the reply does not contain a session ID, so
     *  it won't be routed back to us?
     */
    @Override
    public int[] bandwidthLimits() throws I2PSessionException {
        return _primary.bandwidthLimits();
    }

    @Override
    protected void updateActivity() {
        _primary.updateActivity();
    }

    @Override
    public long lastActivity() {
        return _primary.lastActivity();
    }

    @Override
    public void setReduced() {
        _primary.setReduced();
    }
}
