/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo;

import net.java.sip.communicator.service.shutdown.*;
import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.jicofo.log.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.log.*;
import org.jivesoftware.smack.provider.*;

import java.util.*;


/**
 * Manages {@link JitsiMeetConference} on some server. Takes care of creating
 * and expiring conference focus instances.
 *
 * @author Pawel Domas
 */
public class FocusManager
    implements JitsiMeetConference.ConferenceListener
{
    /**
     * The logger used by this instance.
     */
    private final static Logger logger = Logger.getLogger(FocusManager.class);

    /**
     * Name of configuration property for focus idle timeout.
     */
    public static final String IDLE_TIMEOUT_PROP_NAME
        = "org.jitsi.focus.IDLE_TIMEOUT";

    /**
     * Default amount of time for which the focus is being kept alive in idle
     * mode(no peers in the room).
     */
    public static final long DEFAULT_IDLE_TIMEOUT = 15000;

    /**
     * The address of XMPP server to which the focus user will connect to.
     */
    private String serverAddress;

    /**
     * The XMPP domain used by the focus user to register to.
     */
    private String xmppDomain;

    /**
     * Optional focus user password(if null then will login anonymously).
     */
    private String xmppLoginPassword;

    /**
     * The thread that expires {@link JitsiMeetConference}s.
     */
    private FocusExpireThread expireThread = new FocusExpireThread();

    /**
     * Jitsi Meet conferences mapped by MUC room names.
     */
    private Map<String, JitsiMeetConference> conferences
        = new HashMap<String, JitsiMeetConference>();

    /**
     * <tt>JitsiMeetServices</tt> instance that recognizes currently available
     * conferencing services like Jitsi videobridge or SIP gateway.
     */
    private JitsiMeetServices jitsiMeetServices;

    /**
     * Indicates if graceful shutdown mode has been enabled and
     * no new conference request will be accepted.
     */
    private boolean shutdownInProgress;

    /**
     * Starts this manager for given <tt>serverAddress</tt>.
     * @param serverAddress the address of XMPP server to which the focus will
     *                      connect to.
     * @param xmppDomain optional name of XMPP domain used by focus to login to,
     *        if <tt>null</tt> then <tt>serverAddress</tt> will be used.
     * @param password optional password used to login, if <tt>null</tt> is
     *                 passed then anonymous login wil be used.
     */
    public void start(String serverAddress, String xmppDomain, String password)
    {
        this.serverAddress = serverAddress;

        this.xmppDomain = xmppDomain;

        this.xmppLoginPassword = password;

        expireThread.start();

        jitsiMeetServices = new JitsiMeetServices();

        jitsiMeetServices.start(serverAddress, xmppDomain, xmppLoginPassword);

        ProviderManager
            .getInstance()
                .addExtensionProvider(LogPacketExtension.LOG_ELEM_NAME,
                                      LogPacketExtension.NAMESPACE,
                                      new LogExtensionProvider());
        FocusBundleActivator
            .bundleContext.registerService(
                    JitsiMeetServices.class, jitsiMeetServices, null);
    }

    /**
     * Stops this instance.
     */
    public void stop()
    {
        expireThread.stop();

        jitsiMeetServices.stop();
    }

    /**
     * Allocates new focus for given MUC room.
     * @param room the name of MUC room for which new conference has to be
     *             allocated.
     * @return <tt>true</tt> if conference focus is in the room and ready to
     *         handle session participants.
     */
    public synchronized boolean conferenceRequest(String room)
    {
        if (StringUtils.isNullOrEmpty(room))
            return false;

        if (shutdownInProgress && !conferences.containsKey(room))
            return false;

        ensureConferenceAllocated(room);

        JitsiMeetConference conference = conferences.get(room);

        return conference.isInTheRoom();
    }

    /**
     * Makes sure that conference is allocated for given <tt>room</tt>.
     * @param room name of the MUC room of Jitsi Meet conference.
     */
    private void ensureConferenceAllocated(String room)
    {
        if (conferences.containsKey(room))
        {
            return;
        }

        JitsiMeetConference conference
            = new JitsiMeetConference(
                    room, serverAddress, xmppDomain, xmppLoginPassword, this);
        try
        {
            conferences.put(room, conference);

            logger.info("Created new focus for " + room + "@" + xmppDomain
                            + " conferences count: " + conferences.size());

            LoggingService loggingService
                    = FocusBundleActivator.getLoggingService();
            if (loggingService != null)
            {
                loggingService.logEvent(
                    LogEventFactory.focusCreated(room + "@" + xmppDomain));
            }

            conference.start();
        }
        catch (Exception e)
        {
            logger.error("Failed to start conference for room: " + room, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void conferenceEnded(JitsiMeetConference conference)
    {
        String roomName = conference.getRoomName();

        conferences.remove(roomName);

        logger.info(
            "Disposed conference for room: " + roomName
            + " conference count: " + conferences.size());

        maybeDoShutdown();
    }

    /**
     * Returns {@link JitsiMeetConference} for given MUC <tt>roomName</tt>
     * or <tt>null</tt> if no conference has been allocated yet.
     *
     * @param roomName the name of MUC room for which we want get the
     *        {@link JitsiMeetConference} instance.
     */
    public JitsiMeetConference getConference(String roomName)
    {
        return conferences.get(roomName);
    }

    /**
     * Enables shutdown mode which means that no new focus instances will
     * be allocated. After conference count drops to zero the process will exit.
     */
    public void enableGracefulShutdownMode()
    {
        if (!this.shutdownInProgress)
        {
            logger.info("Focus entered graceful shutdown mode");
        }
        this.shutdownInProgress = true;
        maybeDoShutdown();
    }

    private void maybeDoShutdown()
    {
        if (shutdownInProgress && conferences.size() == 0)
        {
            logger.info("Focus is shutting down NOW");

            ShutdownService shutdownService
                = ServiceUtils.getService(
                        FocusBundleActivator.bundleContext,
                        ShutdownService.class);

            shutdownService.beginShutdown();
        }
    }

    /**
     * Returns the number of currently allocated focus instances.
     */
    public int getConferenceCount()
    {
        return conferences.size();
    }

    /**
     * Returns <tt>true</tt> if graceful shutdown mode has been enabled and
     * the process is going to be finished once conference count drops to zero.
     */
    public boolean isShutdownInProgress()
    {
        return shutdownInProgress;
    }

    /**
     * Method should be called by authentication component when user identified
     * by given <tt>realJid</tt> has been authenticated for given
     * <tt>identity</tt>.
     *
     * @param roomName the name of the conference room for which authentication
     *                 has occurred.
     * @param realJid the real JID of authenticated user
     *                (not MUC jid which can be faked).
     * @param identity confirmed identity of the user.
     */
    public void userAuthenticated(String roomName,
                                  String realJid, String identity)
    {
        JitsiMeetConference conference = conferences.get(roomName);
        if (conference == null)
        {
            logger.error(
                "Auth request - no active conference for room: " + roomName);
            return;
        }
        conference.userAuthenticated(realJid, identity);
    }

    /**
     * Class takes care of stopping {@link JitsiMeetConference} if there is no
     * active session for too long.
     */
    class FocusExpireThread
    {
        private static final long POLL_INTERVAL = 5000;

        private final long timeout;

        private Thread timeoutThread;

        private final Object sleepLock = new Object();

        private boolean enabled;

        public FocusExpireThread()
        {
            timeout = FocusBundleActivator.getConfigService()
                        .getLong(IDLE_TIMEOUT_PROP_NAME, DEFAULT_IDLE_TIMEOUT);
        }

        void start()
        {
            if (timeoutThread != null)
            {
                throw new IllegalStateException();
            }

            timeoutThread = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    expireLoop();
                }
            }, "FocusExpireThread");

            enabled = true;

            timeoutThread.start();
        }

        void stop()
        {
            if (timeoutThread == null)
            {
                return;
            }

            enabled = false;

            synchronized (sleepLock)
            {
                sleepLock.notifyAll();
            }

            try
            {
                if (Thread.currentThread() != timeoutThread)
                {
                    timeoutThread.join();
                }
                timeoutThread = null;
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }

        private void expireLoop()
        {
            while (enabled)
            {
                // Sleep
                try
                {
                    synchronized (sleepLock)
                    {
                        sleepLock.wait(POLL_INTERVAL);
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }

                if (!enabled)
                    break;

                // Loop over conferences
                for (JitsiMeetConference conference
                    : new ArrayList<JitsiMeetConference>(conferences.values()))
                {
                    long idleStamp = conference.getIdleTimestamp();
                    // Is active ?
                    if (idleStamp == -1)
                    {
                        continue;
                    }
                    if (System.currentTimeMillis() - idleStamp > timeout)
                    {
                        logger.info(
                            "Focus idle timeout for "
                                + conference.getRoomName());

                        conference.stop();
                    }
                }
            }
        }
    }
}