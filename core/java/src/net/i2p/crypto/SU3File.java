package net.i2p.crypto;

import java.io.EOFException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.HexDump;
import net.i2p.util.SecureFileOutputStream;

/**
 *  Succesor to the ".sud" format used in TrustedUpdate.
 *  Format specified in http://www.i2p2.de/updates
 * 
 *  @since 0.9.8
 */
public class SU3File {

    private final I2PAppContext _context;
    private final Map<SigningPublicKey, String> _trustedKeys;

    private final File _file;
    private String _version;
    private int _versionLength;
    private String _signer;
    private int _signerLength;
    private int _contentType;
    private long _contentLength;
    private SigningPublicKey _signerPubkey;
    private boolean _headerVerified;
    private SigType _sigType;

    private static final byte[] MAGIC = DataHelper.getUTF8("I2Psu3");
    private static final int FILE_VERSION = 0;
    private static final int MIN_VERSION_BYTES = 16;
    private static final int VERSION_OFFSET = Signature.SIGNATURE_BYTES;

    private static final int TYPE_ZIP = 0;

    private static final int CONTENT_ROUTER = 0;
    private static final int CONTENT_ROUTER_P200 = 1;
    private static final int CONTENT_PLUGIN = 2;
    private static final int CONTENT_RESEED = 3;

    private static final SigType DEFAULT_TYPE = SigType.DSA_SHA1;

    /**
     *  Uses TrustedUpdate's default keys for verification.
     */
    public SU3File(String file) {
        this(new File(file));
    }

    /**
     *  Uses TrustedUpdate's default keys for verification.
     */
    public SU3File(File file) {
        //this(file, (new TrustedUpdate()).getKeys());
        this(file, null);
    }

    /**
     *  @param trustedKeys map of pubkey to signer name, null ok if not verifying
     */
    public SU3File(File file, Map<SigningPublicKey, String> trustedKeys) {
        this(I2PAppContext.getGlobalContext(), file, trustedKeys);
    }

    /**
     *  @param trustedKeys map of pubkey to signer name, null ok if not verifying
     */
    public SU3File(I2PAppContext context, File file, Map<SigningPublicKey, String> trustedKeys) {
        _context = context;
        _file = file;
        _trustedKeys = trustedKeys;
    }

    public String getVersionString() throws IOException {
        verifyHeader();
        return _version;
    }

    public String getSignerString() throws IOException {
        verifyHeader();
        return _signer;
    }

    /**
     *  Throws IOE if verify vails.
     */
    public void verifyHeader() throws IOException {
        if (_headerVerified)
            return;
        InputStream in = null;
        try {
            in = new FileInputStream(_file);
            verifyHeader(in);
        } catch (DataFormatException dfe) {
            IOException ioe = new IOException("foo");
            ioe.initCause(dfe);
            throw ioe;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Throws if verify vails.
     */
    private void verifyHeader(InputStream in) throws IOException, DataFormatException {
        byte[] magic = new byte[MAGIC.length];
        DataHelper.read(in, magic);
        if (!DataHelper.eq(magic, MAGIC))
            throw new IOException("Not an su3 file");
        skip(in, 1);
        int foo = in.read();
        if (foo != FILE_VERSION)
            throw new IOException("bad file version");
        skip(in, 1);
        int sigTypeCode = in.read();
        _sigType = SigType.getByCode(sigTypeCode);
        // TODO, for other known algos we must start over with a new MessageDigest
        // (rewind 10 bytes)
        if (_sigType == null)
            throw new IOException("unknown sig type: " + sigTypeCode);
        _signerLength = (int) DataHelper.readLong(in, 2);
        if (_signerLength != _sigType.getSigLen())
            throw new IOException("bad sig length");
        skip(in, 1);
        int _versionLength = in.read();
        if (_versionLength < MIN_VERSION_BYTES)
            throw new IOException("bad version length");
        skip(in, 1);
        int signerLen = in.read();
        if (signerLen <= 0)
            throw new IOException("bad signer length");
        _contentLength = DataHelper.readLong(in, 8);
        if (_contentLength <= 0)
            throw new IOException("bad content length");
        skip(in, 1);
        foo = in.read();
        if (foo != TYPE_ZIP)
            throw new IOException("bad type");
        skip(in, 1);
        _contentType = in.read();
        if (_contentType < CONTENT_ROUTER || _contentType > CONTENT_RESEED)
            throw new IOException("bad content type");
        skip(in, 12);

        byte[] data = new byte[_versionLength];
        int bytesRead = DataHelper.read(in, data);
        if (bytesRead != _versionLength)
            throw new EOFException();
        int zbyte;
        for (zbyte = 0; zbyte < _versionLength; zbyte++) {
            if (data[zbyte] == 0x00)
                break;
        }
        _version = new String(data, 0, zbyte, "UTF-8");

        data = new byte[signerLen];
        bytesRead = DataHelper.read(in, data);
        if (bytesRead != signerLen)
            throw new EOFException();
        _signer = DataHelper.getUTF8(data);
        if (_trustedKeys != null) {
            for (Map.Entry<SigningPublicKey, String> e : _trustedKeys.entrySet()) {
                if (e.getValue().equals(_signer)) {
                    _signerPubkey = e.getKey();
                    break;
                }
            }
        } else {
            // testing
            KeyRing ring = new DirKeyRing(new File("su3keyring"));
            try {
                _signerPubkey = ring.getKey(_signer, "default", _sigType);
            } catch (GeneralSecurityException gse) {
                IOException ioe = new IOException("keystore error");
                ioe.initCause(gse);
                throw ioe;
            }
        }
        if (_signerPubkey == null)
            throw new IOException("unknown signer: " + _signer);
        _headerVerified = true;
    }

    /** skip but update digest */
    private static void skip(InputStream in, int cnt) throws IOException {
        for (int i = 0; i < cnt; i++) {
            if (in.read() < 0)
                throw new EOFException();
        }
    }

    private int getContentOffset() throws IOException {
        verifyHeader();
        return VERSION_OFFSET + _versionLength + _signerLength;
    }

    /**
     *  One-pass verify and extract the content.
     *  Recommend extracting to a temp location as the sig is not checked until
     *  after extraction. This will delete the file if the sig does not verify.
     *  Throws IOE on all format errors.
     *
     *  @param migrateTo the output file, probably in zip format
     *  @return true if signature is good
     */
    public boolean verifyAndMigrate(File migrateTo) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        boolean rv = false;
        try {
            in = new BufferedInputStream(new FileInputStream(_file));
            // read 10 bytes to get the sig type
            in.mark(10);
            // following is a dup of that in verifyHeader()
            byte[] magic = new byte[MAGIC.length];
            DataHelper.read(in, magic);
            if (!DataHelper.eq(magic, MAGIC))
                throw new IOException("Not an su3 file");
            skip(in, 1);
            int foo = in.read();
            if (foo != FILE_VERSION)
                throw new IOException("bad file version");
            skip(in, 1);
            int sigTypeCode = in.read();
            _sigType = SigType.getByCode(sigTypeCode);
            if (_sigType == null)
                throw new IOException("unknown sig type: " + sigTypeCode);
            // end duplicate code
            // rewind
            in.reset();
            MessageDigest md = _sigType.getDigestInstance();
            DigestInputStream din = new DigestInputStream(in, md);
            in = din;
            if (!_headerVerified)
                verifyHeader(in);
            else
                skip(in, getContentOffset());
            if (_signerPubkey == null)
                throw new IOException("unknown signer: " + _signer);
            out = new FileOutputStream(migrateTo);
            byte[] buf = new byte[16*1024];
            long tot = 0;
            while (tot < _contentLength) {
                int read = in.read(buf, 0, (int) Math.min(buf.length, _contentLength - tot));
                if (read < 0)
                    throw new EOFException();
                out.write(buf, 0, read);
                tot += read;
            }
            byte[] sha = md.digest();
            din.on(false);
            Signature signature = new Signature(_sigType);
            signature.readBytes(in);
            SimpleDataStructure hash = _sigType.getHashInstance();
            hash.setData(sha);
            //System.out.println("hash\n" + HexDump.dump(sha));
            //System.out.println("sig\n" + HexDump.dump(signature.getData()));
            rv = _context.dsa().verifySignature(signature, hash, _signerPubkey);
        } catch (DataFormatException dfe) {
            IOException ioe = new IOException("foo");
            ioe.initCause(dfe);
            throw ioe;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
            if (!rv)
                migrateTo.delete();
        }
        return rv;
    }

    /**
     *  One-pass wrap and sign the content.
     *  Writes to the file specified in the constructor.
     *  Throws on all errors.
     *
     *  @param content the input file, probably in zip format
     *  @param contentType 0-255, 0 for zip
     *  @param version 1-255 bytes when converted to UTF-8
     *  @param signer ID of the public key, 1-255 bytes when converted to UTF-8
     */
    public void write(File content, int contentType, String version,
                      String signer, SigningPrivateKey privkey) throws IOException {
        InputStream in = null;
        DigestOutputStream out = null;
        boolean ok = false;
        try {
            in = new BufferedInputStream(new FileInputStream(content));
            SigType sigType = privkey.getType();
            MessageDigest md = sigType.getDigestInstance();
            out = new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(_file)), md);
            out.write(MAGIC);
            out.write((byte) 0);
            out.write((byte) FILE_VERSION);
            out.write((byte) 0);
            out.write((byte) sigType.getCode());
            DataHelper.writeLong(out, 2, sigType.getSigLen());
            out.write((byte) 0);
            byte[] verBytes = DataHelper.getUTF8(version);
            if (verBytes.length == 0 || verBytes.length > 255)
                throw new IllegalArgumentException("bad version length");
            int verLen = Math.max(verBytes.length, MIN_VERSION_BYTES);
            out.write((byte) verLen);
            out.write((byte) 0);
            byte[] signerBytes = DataHelper.getUTF8(signer);
            if (signerBytes.length == 0 || signerBytes.length > 255)
                throw new IllegalArgumentException("bad signer length");
            out.write((byte) signerBytes.length);
            long contentLength = content.length();
            if (contentLength <= 0)
                throw new IllegalArgumentException("No content");
            DataHelper.writeLong(out, 8, contentLength);
            out.write((byte) 0);
            out.write((byte) TYPE_ZIP);
            out.write((byte) 0);
            if (contentType < 0 || contentType > 255)
                throw new IllegalArgumentException("bad content type");
            out.write((byte) contentType);
            out.write(new byte[12]);
            out.write(verBytes);
            if (verBytes.length < MIN_VERSION_BYTES)
                out.write(new byte[MIN_VERSION_BYTES - verBytes.length]);
            out.write(signerBytes);

            byte[] buf = new byte[16*1024];
            long tot = 0;
            while (tot < contentLength) {
                int read = in.read(buf, 0, (int) Math.min(buf.length, contentLength - tot));
                if (read < 0)
                    throw new EOFException();
                out.write(buf, 0, read);
                tot += read;
            }

            byte[] sha = md.digest();
            out.on(false);
            SimpleDataStructure hash = sigType.getHashInstance();
            hash.setData(sha);
            Signature signature = _context.dsa().sign(hash, privkey);
            //System.out.println("hash\n" + HexDump.dump(sha));
            //System.out.println("sig\n" + HexDump.dump(signature.getData()));
            signature.writeBytes(out);
            ok = true;
        } catch (DataFormatException dfe) {
            IOException ioe = new IOException("foo");
            ioe.initCause(dfe);
            throw ioe;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
            if (!ok)
                _file.delete();
        }
    }

    /**
     * Parses command line arguments when this class is used from the command
     * line.
     * Exits 1 on failure so this can be used in scripts.
     * 
     * @param args Command line parameters.
     */
    public static void main(String[] args) {
        boolean ok = false;
        try {
            if ("showversion".equals(args[0])) {
                ok = showVersionCLI(args[1]);
            } else if ("sign".equals(args[0])) {
                if (args[1].equals("-t"))
                    ok = signCLI(args[2], args[3], args[4], args[5], args[6], args[7]);
                else
                    ok = signCLI(args[1], args[2], args[3], args[4], args[5]);
            } else if ("verifysig".equals(args[0])) {
                ok = verifySigCLI(args[1]);
            } else if ("keygen".equals(args[0])) {
                if (args[1].equals("-t"))
                    ok = genKeysCLI(args[2], args[3], args[4]);
                else
                    ok = genKeysCLI(args[1], args[2]);
            } else {
                showUsageCLI();
            }
        } catch (ArrayIndexOutOfBoundsException aioobe) {
            showUsageCLI();
        }
        if (!ok)
            System.exit(1);
    }

    private static final void showUsageCLI() {
        System.err.println("Usage: SU3File keygen       [-t type|code] publicKeyFile privateKeyFile");
        System.err.println("       SU3File showversion  signedFile.su3");
        System.err.println("       SU3File sign         [-t type|code] inputFile.zip signedFile.su3 privateKeyFile version signerName@mail.i2p");
        System.err.println("       SU3File verifysig    signedFile.su3");
        System.err.println(dumpSigTypes());
    }

    /** @since 0.9.9 */
    private static String dumpSigTypes() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("Available signature types:\n");
        for (SigType t : EnumSet.allOf(SigType.class)) {
            buf.append("      ").append(t).append("\t(code: ").append(t.getCode()).append(')');
            if (t == SigType.DSA_SHA1)
                buf.append(" DEFAULT");
            buf.append('\n');
        }
        return buf.toString();
    }

    /**
     *  @param stype number or name
     *  @return null if not found
     *  @since 0.9.9
     */
    private static SigType parseSigType(String stype) {
        try {
            return SigType.valueOf(stype.toUpperCase(Locale.US));
        } catch (IllegalArgumentException iae) {
            try {
                int code = Integer.parseInt(stype);
                return SigType.getByCode(code);
            } catch (NumberFormatException nfe) {
                return null;
             }
        }
    }

    /** @return success */
    private static final boolean showVersionCLI(String signedFile) {
        try {
            SU3File file = new SU3File(new File(signedFile), null);
            String versionString = file.getVersionString();
            if (versionString.equals(""))
                System.out.println("No version string found in file '" + signedFile + "'");
            else
                System.out.println("Version:  " + versionString);
            String signerString = file.getSignerString();
            if (signerString.equals(""))
                System.out.println("No signer string found in file '" + signedFile + "'");
            else
                System.out.println("Signer:   " + signerString);
            if (file._sigType != null)
                System.out.println("SigType:  " + file._sigType);
            return !versionString.equals("");
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }

    /** @return success */
    private static final boolean signCLI(String inputFile, String signedFile,
                                         String privateKeyFile, String version, String signerName) {
        return signCLI(DEFAULT_TYPE, inputFile, signedFile, privateKeyFile, version, signerName);
    }

    /**
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean signCLI(String stype, String inputFile, String signedFile,
                                         String privateKeyFile, String version, String signerName) {
        SigType type = parseSigType(stype);
        if (type == null) {
            System.out.println("Signature type " + stype + " is not supported");
            return false;
        }
        return signCLI(type, inputFile, signedFile, privateKeyFile, version, signerName);
    }

    /**
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean signCLI(SigType type, String inputFile, String signedFile,
                                         String privateKeyFile, String version, String signerName) {
        try {
            File pkfile = new File(privateKeyFile);
            PrivateKey pk = SigUtil.importJavaPrivateKey(pkfile, type);
            SigningPrivateKey spk = SigUtil.fromJavaKey(pk, type);
            SU3File file = new SU3File(signedFile);
            file.write(new File(inputFile), CONTENT_ROUTER, version, signerName, spk);
            System.out.println("Input file '" + inputFile + "' signed and written to '" + signedFile + "'");
            return true;
        } catch (GeneralSecurityException gse) {
            System.out.println("Error signing input file '" + inputFile + "'");
            gse.printStackTrace();
            return false;
        } catch (IOException ioe) {
            System.out.println("Error signing input file '" + inputFile + "'");
            ioe.printStackTrace();
            return false;
        }
    }

    /** @return valid */
    private static final boolean verifySigCLI(String signedFile) {
        InputStream in = null;
        try {
            SU3File file = new SU3File(signedFile);
            //// fixme
            boolean isValidSignature = file.verifyAndMigrate(new File("/dev/null"));
            if (isValidSignature)
                System.out.println("Signature VALID (signed by " + file.getSignerString() + ' ' + file._sigType + ')');
            else
                System.out.println("Signature INVALID (signed by " + file.getSignerString() + ' ' + file._sigType +')');
            return isValidSignature;
        } catch (IOException ioe) {
            System.out.println("Error verifying input file '" + signedFile + "'");
            ioe.printStackTrace();
            return false;
        }
    }

    /**
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean genKeysCLI(String publicKeyFile, String privateKeyFile) {
        return genKeysCLI(DEFAULT_TYPE, publicKeyFile, privateKeyFile);
    }

    /**
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean genKeysCLI(String stype, String publicKeyFile, String privateKeyFile) {
        SigType type = parseSigType(stype);
        if (type == null) {
            System.out.println("Signature type " + stype + " is not supported");
            return false;
        }
        return genKeysCLI(type, publicKeyFile, privateKeyFile);
    }

    /**
     *  Writes Java-encoded keys (X.509 for public and PKCS#8 for private)
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean genKeysCLI(SigType type, String publicKeyFile, String privateKeyFile) {
        File pubFile = new File(publicKeyFile);
        File privFile = new File(privateKeyFile);
        if (pubFile.exists()) {
            System.out.println("Error: Not overwriting file " + publicKeyFile);
            return false;
        }
        if (privFile.exists()) {
            System.out.println("Error: Not overwriting file " + privateKeyFile);
            return false;
        }
        FileOutputStream fileOutputStream = null;
        I2PAppContext context = I2PAppContext.getGlobalContext();
        try {
            // inefficiently go from Java to I2P to Java formats
            SimpleDataStructure signingKeypair[] = context.keyGenerator().generateSigningKeys(type);
            SigningPublicKey signingPublicKey = (SigningPublicKey) signingKeypair[0];
            SigningPrivateKey signingPrivateKey = (SigningPrivateKey) signingKeypair[1];
            PublicKey pubkey = SigUtil.toJavaKey(signingPublicKey);
            PrivateKey privkey = SigUtil.toJavaKey(signingPrivateKey);

            fileOutputStream = new SecureFileOutputStream(pubFile);
            fileOutputStream.write(pubkey.getEncoded());
            fileOutputStream.close();
            fileOutputStream = null;

            fileOutputStream = new SecureFileOutputStream(privFile);
            fileOutputStream.write(privkey.getEncoded());

            System.out.println("\r\n" + type + " Private key written to: " + privateKeyFile);
            System.out.println(type + " Public key written to: " + publicKeyFile);
            System.out.println("\r\nPublic key: " + signingPublicKey.toBase64() + "\r\n");
        } catch (Exception e) {
            System.err.println("Error writing keys:");
            e.printStackTrace();
            return false;
        } finally {
            if (fileOutputStream != null)
                try {
                    fileOutputStream.close();
                } catch (IOException ioe) {}
        }
        return true;
    }
}
