package net.i2p.router.update;

import java.io.File;
import java.net.URI;
import java.util.List;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.util.RFC822Date;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.update.*;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

    
/**
 *  Eepget the .zip file to the temp dir, then notify.r
 *  Moved from UnsignedUpdateHandler and turned into an UpdateTask.
 *
 *  @since 0.9.4
 */
class UnsignedUpdateRunner extends UpdateRunner {

    public UnsignedUpdateRunner(RouterContext ctx, List<URI> uris) { 
        super(ctx, uris);
        if (!uris.isEmpty())
            _currentURI = uris.get(0);
    }


    @Override
    public UpdateType getType() { return UpdateType.ROUTER_UNSIGNED; }


        /** Get the file */
        @Override
        protected void update() {
            String zipURL = _currentURI.toString();
            updateStatus("<b>" + _("Updating") + "</b>");
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Starting unsigned update URL: " + zipURL);
            // always proxy for now
            //boolean shouldProxy = Boolean.valueOf(_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY)).booleanValue();
            String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
            int proxyPort = ConfigUpdateHandler.proxyPort(_context);
            try {
                // 40 retries!!
                _get = new EepGet(_context, proxyHost, proxyPort, 40, _updateFile, zipURL, false);
                _get.addStatusListener(UnsignedUpdateRunner.this);
                _get.fetch(CONNECT_TIMEOUT, -1, INACTIVITY_TIMEOUT);
            } catch (Throwable t) {
                _log.error("Error updating", t);
            }
        }
        
        /** eepget listener callback Overrides */
        @Override
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            String lastmod = _get.getLastModified();
            File tmp = new File(_updateFile);
/////// FIXME RFC822 or long?
            if (_mgr.notifyComplete(this, lastmod, tmp))
                this.done = true;
            else
                tmp.delete();  // corrupt
        }
}
