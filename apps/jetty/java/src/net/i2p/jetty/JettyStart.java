package net.i2p.jetty;

// Contains code from org.mortbay.xml.XmlConfiguation:

// ========================================================================
// Copyright 2004-2005 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.app.*;
import static net.i2p.app.ClientAppState.*;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 *  Start Jetty where the args are one or more XML files.
 *  Save a reference to the Server so it can be cleanly stopped later.
 *
 *  This is like XmlConfiguration.main(), which is essentially what
 *  org.mortbay.start.Main does.
 *
 *  @since 0.9.4
 */
public class JettyStart implements ClientApp {

    private final I2PAppContext _context;
    private final ClientAppManager _mgr;
    private final String[] _args;
    private final List<LifeCycle> _jettys;
    private volatile ClientAppState _state;

    /**
     *  All args must be XML file names.
     *  Does not support any of the other argument types from org.mortbay.start.Main.
     */
    public JettyStart(I2PAppContext context, ClientAppManager mgr, String[] args) throws Exception {
        _state = UNINITIALIZED;
        _context = context;
        _mgr = mgr;
        _args = args;
        _jettys = new ArrayList(args.length);
        parseArgs(args);
        _state = INITIALIZED;
    }

    /**
     *  Modified from XmlConfiguration.main()
     */
    public void parseArgs(String[] args) throws Exception {
        Properties properties=new Properties();
        XmlConfiguration last=null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].toLowerCase().endsWith(".properties")) {
                properties.load(Resource.newResource(args[i]).getInputStream());
            } else {
                XmlConfiguration configuration = new XmlConfiguration(Resource.newResource(args[i]).getURL());
                if (last!=null)
                    configuration.getIdMap().putAll(last.getIdMap());
                if (properties.size()>0) {
                    // to avoid compiler errror
                    Map foo = configuration.getProperties();
                    foo.putAll(properties);
                }
                Object o = configuration.configure();
                if (o instanceof LifeCycle)
                    _jettys.add((LifeCycle)o);
                last=configuration;
            }
        }
    }

    public void startup() {
        if (_state != INITIALIZED)
            return;
        if (_jettys.isEmpty()) {
            changeState(START_FAILED);
        } else {
            (new Starter()).start();
        }
    }

    private class Starter extends Thread {
        public Starter() {
            super("JettyStarter");
        }

        /**
         *  Modified from XmlConfiguration.main()
         */
        public void run() {
            changeState(STARTING);
            for (LifeCycle lc : _jettys) {
                if (!lc.isRunning()) {
                    try {
                        lc.start();
                    } catch (Exception e) {
                        changeState(START_FAILED, e);
                        return;
                    }
                }
            }
            changeState(RUNNING);
            _mgr.register(JettyStart.this);
        }
    }

    public void shutdown(String[] args) {
        if (_state != RUNNING)
            return;
        if (_jettys.isEmpty()) {
            changeState(STOPPED);
        } else {
            (new Stopper()).start();
        }
    }

    private class Stopper extends Thread {
        public Stopper() {
            super("JettyStopper");
        }

        public void run() {
            changeState(STOPPING);
            for (LifeCycle lc : _jettys) {
                if (lc.isRunning()) {
                    try {
                        lc.stop();
                    } catch (Exception e) {
                        changeState(STOPPING, e);
                    }
                }
            }
            changeState(STOPPED);
        }
    }

    public ClientAppState getState() {
        return _state;
    }

    public String getName() {
        return "Jetty";
    }

    public String getDisplayName() {
        return "Jetty " + Arrays.toString(_args);
    }

    private void changeState(ClientAppState state) {
        changeState(state, null);
    }

    private synchronized void changeState(ClientAppState state, Exception e) {
        _state = state;
        _mgr.notify(this, state, null, e);
    }
}
