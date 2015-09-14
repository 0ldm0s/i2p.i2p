package net.i2p.router.web;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.router.news.NewsEntry;
import net.i2p.router.news.NewsManager;


/**
 *  HTML-formatted full news entries
 *
 *  @since 0.9.23
 */
public class NewsFeedHelper extends HelperBase {
    
    private int _start = 0;
    private int _limit = 3;

    /**
     *  @param limit less than or equal to zero means all
     */
    public void setLimit(int limit) {
        _limit = limit;
    }

    public void setStart(int start) {
        _start = start;
    }

    public String getEntries() {
        return getEntries(_context, _start, _limit);
    }

    /**
     *  @param max less than or equal to zero means all
     *  @return non-null, "" if none
     */
    static String getEntries(I2PAppContext ctx, int start, int max) {
        if (max <= 0)
            max = Integer.MAX_VALUE;
        StringBuilder buf = new StringBuilder(512);
        List<NewsEntry> entries = Collections.emptyList();
        ClientAppManager cmgr = ctx.clientAppManager();
        if (cmgr != null) {
            NewsManager nmgr = (NewsManager) cmgr.getRegisteredApp(NewsManager.APP_NAME);
            if (nmgr != null)
                entries = nmgr.getEntries();
        }
        if (!entries.isEmpty()) {
            DateFormat fmt = DateFormat.getDateInstance(DateFormat.SHORT);
            // the router sets the JVM time zone to UTC but saves the original here so we can get it
            String systemTimeZone = ctx.getProperty("i2p.systemTimeZone");
            if (systemTimeZone != null)
                fmt.setTimeZone(TimeZone.getTimeZone(systemTimeZone));
            int i = 0;
            for (NewsEntry entry : entries) {
                if (i++ < start)
                    continue;
                buf.append("<h3>");
                if (entry.updated > 0) {
                    Date date = new Date(entry.updated);
                    buf.append(fmt.format(date))
                       .append(": ");
                }
                buf.append(entry.title)
                   .append("</h3>\n")
                   .append(entry.content)
                   .append("\n");
                if (i >= start + max)
                    break;
            }
        }
        return buf.toString();
    }
}
