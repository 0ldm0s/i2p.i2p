/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.util.ArrayList;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.EventDispatcher;
import net.i2p.util.InternalSocket;
import net.i2p.util.Log;
import net.i2p.util.PasswordManager;

/**
 * Common things for HTTPClient and ConnectClient
 * Retrofit over them in 0.8.2
 *
 * @since 0.8.2
 */
public abstract class I2PTunnelHTTPClientBase extends I2PTunnelClientBase implements Runnable {

    private static final int PROXYNONCE_BYTES = 8;
    private static final int MD5_BYTES = 16;
    /** 24 */
    private static final int NONCE_BYTES = DataHelper.DATE_LENGTH + MD5_BYTES;
    private static final long MAX_NONCE_AGE = 30*24*60*60*1000L;

    private static final String ERR_AUTH1 =
            "HTTP/1.1 407 Proxy Authentication Required\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "Cache-control: no-cache\r\n" +
            "Accept-Charset: ISO-8859-1,utf-8;q=0.7,*;q=0.5\r\n" + // try to get a UTF-8-encoded response back for the password
            "Proxy-Authenticate: ";
    // put the auth type and realm in between
    private static final String ERR_AUTH2 =
            "\r\n" +
            "\r\n" +
            "<html><body><H1>I2P ERROR: PROXY AUTHENTICATION REQUIRED</H1>" +
            "This proxy is configured to require authentication.";

    protected final List<String> _proxyList;

    protected final static byte[] ERR_NO_OUTPROXY =
        ("HTTP/1.1 503 Service Unavailable\r\n"+
         "Content-Type: text/html; charset=iso-8859-1\r\n"+
         "Cache-control: no-cache\r\n"+
         "\r\n"+
         "<html><body><H1>I2P ERROR: No outproxy found</H1>"+
         "Your request was for a site outside of I2P, but you have no "+
         "HTTP outproxy configured.  Please configure an outproxy in I2PTunnel")
         .getBytes();
    
    /** used to assign unique IDs to the threads / clients.  no logic or functionality */
    protected static volatile long __clientId = 0;

    private final byte[] _proxyNonce;

    protected String getPrefix(long requestId) { return "Client[" + _clientId + "/" + requestId + "]: "; }
    
    protected String selectProxy() {
        synchronized (_proxyList) {
            int size = _proxyList.size();
            if (size <= 0)
                return null;
            int index = _context.random().nextInt(size);
            return _proxyList.get(index);
        }
    }

    protected static final int DEFAULT_READ_TIMEOUT = 5*60*1000;
    
    protected static long __requestId = 0;

    public I2PTunnelHTTPClientBase(int localPort, boolean ownDest, Logging l, 
                               EventDispatcher notifyThis, String handlerName, 
                               I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, handlerName, tunnel);
        _proxyList = new ArrayList(4);
        _proxyNonce = new byte[PROXYNONCE_BYTES];
        _context.random().nextBytes(_proxyNonce);
    }

    /**
     *  This constructor always starts the tunnel (ignoring the i2cp.delayOpen option).
     *  It is used to add a client to an existing socket manager.
     *
     *  @param sktMgr the existing socket manager
     */
    public I2PTunnelHTTPClientBase(int localPort, Logging l, I2PSocketManager sktMgr,
            I2PTunnel tunnel, EventDispatcher notifyThis, long clientId )
            throws IllegalArgumentException {
        super(localPort, l, sktMgr, tunnel, notifyThis, clientId);
        _proxyList = new ArrayList(4);
        _proxyNonce = new byte[PROXYNONCE_BYTES];
        _context.random().nextBytes(_proxyNonce);
    }

    //////// Authorization stuff

    /** all auth @since 0.8.2 */
    public static final String PROP_AUTH = "proxyAuth";
    public static final String PROP_USER = "proxyUsername";
    public static final String PROP_PW = "proxyPassword";
    /** additional users may be added with proxyPassword.user=pw */
    public static final String PROP_PW_PREFIX = PROP_PW + '.';
    public static final String PROP_OUTPROXY_AUTH = "outproxyAuth";
    public static final String PROP_OUTPROXY_USER = "outproxyUsername";
    public static final String PROP_OUTPROXY_PW = "outproxyPassword";
    /** passwords for specific outproxies may be added with outproxyUsername.fooproxy.i2p=user and outproxyPassword.fooproxy.i2p=pw */
    public static final String PROP_OUTPROXY_USER_PREFIX = PROP_OUTPROXY_USER + '.';
    public static final String PROP_OUTPROXY_PW_PREFIX = PROP_OUTPROXY_PW + '.';

    protected abstract String getRealm();

    /**
     *  @since 0.9.4
     */
    protected boolean isDigestAuthRequired() {
        String authRequired = getTunnel().getClientOptions().getProperty(PROP_AUTH);
        if (authRequired == null)
            return false;
        return authRequired.toLowerCase(Locale.US).equals("digest");
    }

    /**
     *  Authorization
     *  Ref: RFC 2617
     *  If the socket is an InternalSocket, no auth required.
     *
     *  @param method GET, POST, etc.
     *  @param authorization may be null, the full auth line e.g. "Basic lskjlksjf"
     *  @return success
     */
    protected boolean authorize(Socket s, long requestId, String method, String authorization) {
        String authRequired = getTunnel().getClientOptions().getProperty(PROP_AUTH);
        if (authRequired == null)
            return true;
        authRequired = authRequired.toLowerCase(Locale.US);
        if (authRequired.equals("false"))
            return true;
        if (s instanceof InternalSocket) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix(requestId) + "Internal access, no auth required");
            return true;
        }
        if (authorization == null)
            return false;
        if (_log.shouldLog(Log.INFO))
            _log.info(getPrefix(requestId) + "Auth: " + authorization);
        String authLC = authorization.toLowerCase(Locale.US);
        if (authRequired.equals("true") || authRequired.equals("basic")) {
            if (!authLC.startsWith("basic "))
                return false;
            authorization = authorization.substring(6);

                // hmm safeDecode(foo, true) to use standard alphabet is private in Base64
                byte[] decoded = Base64.decode(authorization.replace("/", "~").replace("+", "="));
                if (decoded != null) {
                    // We send Accept-Charset: UTF-8 in the 407 so hopefully it comes back that way inside the B64 ?
                    try {
                        String dec = new String(decoded, "UTF-8");
                        String[] parts = dec.split(":");
                        String user = parts[0];
                        String pw = parts[1];
                        // first try pw for that user
                        String configPW = getTunnel().getClientOptions().getProperty(PROP_PW_PREFIX + user);
                        if (configPW == null) {
                            // if not, look at default user and pw
                            String configUser = getTunnel().getClientOptions().getProperty(PROP_USER);
                            if (user.equals(configUser))
                                configPW = getTunnel().getClientOptions().getProperty(PROP_PW);
                        }
                        if (configPW != null) {
                            if (pw.equals(configPW)) {
                                if (_log.shouldLog(Log.INFO))
                                    _log.info(getPrefix(requestId) + "Good auth - user: " + user + " pw: " + pw);
                                return true;
                            } else {
                                if (_log.shouldLog(Log.WARN))
                                    _log.warn(getPrefix(requestId) + "Bad auth, pw mismatch - user: " + user + " pw: " + pw + " expected: " + configPW);
                            }
                        } else {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn(getPrefix(requestId) + "Bad auth, no stored pw for user: " + user + " pw: " + pw);
                        }
                    } catch (UnsupportedEncodingException uee) {
                        _log.error(getPrefix(requestId) + "No UTF-8 support? B64: " + authorization, uee);
                    } catch (ArrayIndexOutOfBoundsException aioobe) {
                        // no ':' in response
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(getPrefix(requestId) + "Bad auth B64: " + authorization, aioobe);
                    }
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getPrefix(requestId) + "Bad auth B64: " + authorization);
                }

            return false;
        } else if (authRequired.equals("digest")) {
            if (!authLC.startsWith("digest "))
                return false;
            authorization = authorization.substring(7);
            Map<String, String> args = parseArgs(authorization);
            AuthResult rv = validateDigest(method, args);
            return rv == AuthResult.AUTH_GOOD;
        } else {
            _log.error("Unknown proxy authorization type configured: " + authRequired);
            return true;
        }
    }

    /**
     *  Verify all of it.
     *  Ref: RFC 2617
     *  @since 0.9.4
     */
    private AuthResult validateDigest(String method, Map<String, String> args) {
        String user = args.get("username");
        String realm = args.get("realm");
        String nonce = args.get("nonce");
        String qop = args.get("qop");
        String uri = args.get("uri");
        String cnonce = args.get("cnonce");
        String nc = args.get("nc");
        String response = args.get("response");
        if (user == null || realm == null || nonce == null || qop == null ||
            uri == null || cnonce == null || nc == null || response == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Bad digest request: " + DataHelper.toString(args));
            return AuthResult.AUTH_BAD_REQ;
        }
        // nonce check
        AuthResult check = verifyNonce(nonce);
        if (check != AuthResult.AUTH_GOOD) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Bad digest nonce: " + check + ' ' + DataHelper.toString(args));
            return check;
        }
        // get H(A1) == stored password
        String ha1 = getTunnel().getClientOptions().getProperty(PROP_PW_PREFIX + user);
        if (ha1 == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Bad digest auth - no stored pw for user: " + user);
            return AuthResult.AUTH_BAD;
        }
        // get H(A2)
        String a2 = method + ':' + uri;
        String ha2 = PasswordManager.md5Hex(a2);
        // response check
        String kd = ha1 + ':' + nonce + ':' + nc + ':' + cnonce + ':' + qop + ':' + ha2;
        String hkd = PasswordManager.md5Hex(kd);
        if (!response.equals(hkd)) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Bad digest auth - user: " + user);
            return AuthResult.AUTH_BAD;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Good digest auth - user: " + user);
        return AuthResult.AUTH_GOOD;
    }

    /**
     *  The Base 64 of 24 bytes: (now, md5 of (now, proxy nonce))
     *  @since 0.9.4
     */
    private String getNonce() {
        byte[] b = new byte[DataHelper.DATE_LENGTH + PROXYNONCE_BYTES];
        byte[] n = new byte[NONCE_BYTES];
        long now = _context.clock().now();
        DataHelper.toLong(b, 0, DataHelper.DATE_LENGTH, now);
        System.arraycopy(_proxyNonce, 0, b, DataHelper.DATE_LENGTH, PROXYNONCE_BYTES);
        System.arraycopy(b, 0, n, 0, DataHelper.DATE_LENGTH);
        byte[] md5 = PasswordManager.md5Sum(b);
        System.arraycopy(md5, 0, n, DataHelper.DATE_LENGTH, MD5_BYTES);
        return Base64.encode(n);
    }

    protected enum AuthResult {AUTH_BAD_REQ, AUTH_BAD, AUTH_STALE, AUTH_GOOD}

    /**
     *  Verify the Base 64 of 24 bytes: (now, md5 of (now, proxy nonce))
     *  @since 0.9.4
     */
    private AuthResult verifyNonce(String b64) {
        byte[] n = Base64.decode(b64);
        if (n == null || n.length != NONCE_BYTES)
            return AuthResult.AUTH_BAD;
        long now = _context.clock().now();
        long stamp = DataHelper.fromLong(n, 0, DataHelper.DATE_LENGTH);
        if (now - stamp > MAX_NONCE_AGE)
            return AuthResult.AUTH_STALE;
        byte[] b = new byte[DataHelper.DATE_LENGTH + PROXYNONCE_BYTES];
        System.arraycopy(n, 0, b, 0, DataHelper.DATE_LENGTH);
        System.arraycopy(_proxyNonce, 0, b, DataHelper.DATE_LENGTH, PROXYNONCE_BYTES);
        byte[] md5 = PasswordManager.md5Sum(b);
        if (!DataHelper.eq(md5, 0, n, DataHelper.DATE_LENGTH, MD5_BYTES))
            return AuthResult.AUTH_BAD;
        return AuthResult.AUTH_GOOD;
    }

    /**
     *  What to send if digest auth fails
     *  @since 0.9.4
     */
    protected String getAuthError(boolean isStale) {
        boolean isDigest = isDigestAuthRequired();
        return
            ERR_AUTH1 +
            (isDigest ? "Digest" : "Basic") +
            " realm=\"" + getRealm() + '"' +
            (isDigest ? ", nonce=\"" + getNonce() + "\"," +
                        " algorithm=MD5," +
                        " qop=\"auth\"" +
                        (isStale ? ", stale=true" : "")
                      : "") +
            ERR_AUTH2;
    }

    /**
     *  Modified from LoadClientAppsJob.
     *  All keys are mapped to lower case.
     *  Ref: RFC 2617
     *
     *  @param args non-null
     *  @since 0.9.4
     */
    private static Map<String, String> parseArgs(String args) {
        Map<String, String> rv = new HashMap(8);
        char data[] = args.toCharArray();
        StringBuilder buf = new StringBuilder(32);
        boolean isQuoted = false;
        String key = null;
        for (int i = 0; i < data.length; i++) {
            switch (data[i]) {
                case '\"':
                    if (isQuoted) {
                        // keys never quoted
                        if (key != null) {
                            rv.put(key, buf.toString().trim());
                            key = null;
                        }
                        buf.setLength(0);
                    }
                    isQuoted = !isQuoted;
                    break;

                case ' ':
                case '\r':
                case '\n':
                case '\t':
                case ',':
                    // whitespace - if we're in a quoted section, keep this as part of the quote,
                    // otherwise use it as a delim
                    if (isQuoted) {
                        buf.append(data[i]);
                    } else {
                        if (key != null) {
                            rv.put(key, buf.toString().trim());
                            key = null;
                        }
                        buf.setLength(0);
                    }
                    break;

                case '=':
                    if (isQuoted) {
                        buf.append(data[i]);
                    } else {
                        key = buf.toString().trim().toLowerCase(Locale.US);
                        buf.setLength(0);
                    }
                    break;

                default:
                    buf.append(data[i]);
                    break;
            }
        }
        if (key != null)
            rv.put(key, buf.toString().trim());
        return rv;
    }

    //////// Error page stuff

    /**
     *  foo => errordir/foo-header_xx.ht for lang xx, or errordir/foo-header.ht,
     *  or the backup byte array on fail.
     *
     *  .ht files must be UTF-8 encoded and use \r\n terminators so the
     *  HTTP headers are conformant.
     *  We can't use FileUtil.readFile() because it strips \r
     *
     *  @return non-null
     *  @since 0.9.4 moved from I2PTunnelHTTPClient
     */
    protected byte[] getErrorPage(String base, byte[] backup) {
        return getErrorPage(_context, base, backup);
    }

    /**
     *  foo => errordir/foo-header_xx.ht for lang xx, or errordir/foo-header.ht,
     *  or the backup byte array on fail.
     *
     *  .ht files must be UTF-8 encoded and use \r\n terminators so the
     *  HTTP headers are conformant.
     *  We can't use FileUtil.readFile() because it strips \r
     *
     *  @return non-null
     *  @since 0.9.4 moved from I2PTunnelHTTPClient
     */
    protected static byte[] getErrorPage(I2PAppContext ctx, String base, byte[] backup) {
        File errorDir = new File(ctx.getBaseDir(), "docs");
        String lang = ctx.getProperty("routerconsole.lang", Locale.getDefault().getLanguage());
        if(lang != null && lang.length() > 0 && !lang.equals("en")) {
            File file = new File(errorDir, base + "-header_" + lang + ".ht");
            try {
                return readFile(file);
            } catch(IOException ioe) {
                // try the english version now
            }
        }
        File file = new File(errorDir, base + "-header.ht");
        try {
            return readFile(file);
        } catch(IOException ioe) {
            return backup;
        }
    }

    /**
     *  @since 0.9.4 moved from I2PTunnelHTTPClient
     */
    private static byte[] readFile(File file) throws IOException {
        FileInputStream fis = null;
        byte[] buf = new byte[2048];
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
        try {
            int len = 0;
            fis = new FileInputStream(file);
            while((len = fis.read(buf)) > 0) {
                baos.write(buf, 0, len);
            }
            return baos.toByteArray();
        } finally {
            try {
                if(fis != null) {
                    fis.close();
                }
            } catch(IOException foo) {
            }
        }
        // we won't ever get here
    }
}
