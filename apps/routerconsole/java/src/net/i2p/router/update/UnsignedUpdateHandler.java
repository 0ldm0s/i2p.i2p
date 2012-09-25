package net.i2p.router.update;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.util.RFC822Date;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.NewsHelper;
import net.i2p.update.*;
import net.i2p.util.EepGet;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * <p>Handles the request to update the router by firing off an
 * {@link net.i2p.util.EepGet} call to download the latest unsigned zip file
 * and displaying the status to anyone who asks.
 * </p>
 * <p>After the download completes the signed update file is copied to the
 * router directory, and if configured the router is restarted to complete
 * the update process.
 * </p>
 */
class UnsignedUpdateHandler implements Updater {
    private final RouterContext _context;

    public UnsignedUpdateHandler(RouterContext ctx) {
        _context = ctx;
    }

    /**
     *  @param currentVersion ignored, we use time stored in a property
     */
    @Override
    public UpdateTask check(UpdateType type, UpdateMethod method,
                            String id, String currentVersion, long maxTime) {
        if (type != UpdateType.ROUTER_UNSIGNED || method != UpdateMethod.HTTP)
            return null;

        String url = _context.getProperty(ConfigUpdateHandler.PROP_ZIP_URL);
        if (url == null)
            return null;

        List<URI> updateSources;
        try {
            updateSources = Collections.singletonList(new URI(url));
        } catch (URISyntaxException use) {
            return null;
        }

        String lastUpdate = _context.getProperty(NewsHelper.PROP_LAST_UPDATE_TIME);
        if (lastUpdate == null) {
            // we don't know what version you have, so stamp it with the current time,
            // and we'll look for something newer next time around.
            _context.router().saveConfig(NewsHelper.PROP_LAST_UPDATE_TIME,
                                               Long.toString(_context.clock().now()));
            return null;
        }
        long ms = 0;
        try {
            ms = Long.parseLong(lastUpdate);
        } catch (NumberFormatException nfe) {}
        if (ms <= 0) {
            // we don't know what version you have, so stamp it with the current time,
            // and we'll look for something newer next time around.
            _context.router().saveConfig(NewsHelper.PROP_LAST_UPDATE_TIME,
                                               Long.toString(_context.clock().now()));
            return null;
        }

        UpdateRunner update = new UnsignedUpdateChecker(_context, updateSources, ms);
        update.start();
        return update;
    }

    /**
     *  Start a download and return a handle to the download task.
     *  Should not block.
     *
     *  @param id plugin name or ignored
     *  @param maxTime how long you have
     *  @return active task or null if unable to download
     */
    @Override
    public UpdateTask update(UpdateType type, UpdateMethod method, List<URI> updateSources,
                             String id, String newVersion, long maxTime) {
        if (type != UpdateType.ROUTER_UNSIGNED || method != UpdateMethod.HTTP || updateSources.isEmpty())
            return null;
        UpdateRunner update = new UnsignedUpdateRunner(_context, updateSources);
        update.start();
        return update;
    }
}
