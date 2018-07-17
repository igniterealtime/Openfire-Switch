/**
 * $Revision $
 * $Date $
 *
 * Copyright (C) 2005-2010 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.plugin.ofswitch;

import java.sql.*;
import java.io.File;
import java.util.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import javax.servlet.DispatcherType;

import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.SimpleInstanceManager;

import org.jivesoftware.util.*;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.http.HttpBindManager;
import org.jivesoftware.openfire.cluster.ClusterEventListener;
import org.jivesoftware.openfire.cluster.ClusterManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.muc.MUCEventListener;
import org.jivesoftware.openfire.muc.MUCEventDispatcher;
import org.jivesoftware.openfire.user.*;
import org.jivesoftware.openfire.vcard.*;
import org.jivesoftware.openfire.group.*;
import org.jivesoftware.openfire.sip.sipaccount.*;

import org.xmpp.component.ComponentManager;
import org.xmpp.component.ComponentManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.jetty.apache.jsp.JettyJasperInitializer;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;

import org.jitsi.util.OSUtils;

import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.inbound.InboundConnectionFailure;
import org.freeswitch.esl.client.manager.*;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.freeswitch.esl.client.IEslEventListener;
import org.freeswitch.esl.client.transport.event.EslEvent;

import org.jboss.netty.channel.ExceptionEvent;

import org.ifsoft.websockets.*;

import org.xmpp.packet.*;
import net.sf.json.*;
import org.dom4j.*;



public class OfSwitchPlugin implements Plugin, ClusterEventListener, IEslEventListener, PropertyEventListener, MUCEventListener  {

    private static final Logger Log = LoggerFactory.getLogger(OfSwitchPlugin.class);
    private FreeSwitchThread freeSwitchThread;
    private ExecutorService executor;
    private String freeSwitchExePath = null;
    private String freeSwitchHomePath = null;
    private final String serviceName = "freeswitch";
    private static final ScheduledExecutorService connExec = Executors.newSingleThreadScheduledExecutor();
    private ManagerConnection managerConnection;
    private Client client;
    private ScheduledFuture<ConnectThread> connectTask;
    private volatile boolean subscribed = false;
    private XMPPServer server;
    private ComponentManager componentManager;
    private ServletContextHandler sipContextHandler;
    private CallControlComponent ccComponent;
    private Plugin ofchat = null;
    private Plugin ofmeet = null;
    private Timer timer = null;
    private File pluginDirectory;

    private final HashMap<String, String> makeCalls = new HashMap<String, String>();
    private final HashMap<String, String> memberCallIdMaping = new HashMap<String, String>();
    private final HashMap<String, String> sipCallIdMaping = new HashMap<String, String>();
    private final HashMap<String, String> confRoomMaping  = new HashMap<String, String>();

    public static OfSwitchPlugin self;


    public String getName() {
        return "ofswitch";
    }

    public String getDescription() {
        return "OfSwitch Plugin";
    }

    public void initializePlugin(final PluginManager manager, final File pluginDirectory)
    {
        componentManager = ComponentManagerFactory.getComponentManager();
        self = this;
        this.pluginDirectory = pluginDirectory;
        server = XMPPServer.getInstance();

        try {
            ClusterManager.addListener(this);
            PropertyEventDispatcher.addListener(this);

            Log.info("Initialize websockets proxy for SIP ");

            sipContextHandler = new ServletContextHandler(null, "/sip", ServletContextHandler.SESSIONS);
            sipContextHandler.addServlet(new ServletHolder(new XMPPServlet()),"/proxy");
            HttpBindManager.getInstance().addJettyHandler(sipContextHandler);

            Log.info("Initialize FreeSwitch");

            checkNatives();

            boolean freeswitchEnabled = JiveGlobals.getBooleanProperty("freeswitch.enabled", false);

            if (freeswitchEnabled)
            {
                String freeswitchServer = JiveGlobals.getProperty("freeswitch.server.hostname", "127.0.0.1");
                String freeswitchPassword = JiveGlobals.getProperty("freeswitch.server.password", "ClueCon");
                boolean freeswitchInstalled = JiveGlobals.getBooleanProperty("freeswitch.installed", true);

                freeSwitchHomePath = JiveGlobals.getProperty("freeswitch.server.homepath", freeSwitchHomePath);
                freeSwitchExePath = JiveGlobals.getProperty("freeswitch.server.exepath", freeSwitchExePath);

                if (freeswitchInstalled == false)
                {
                    if (freeSwitchExePath != null && !"".equals(freeSwitchExePath) && freeSwitchHomePath != null && !"".equals(freeSwitchHomePath))
                    {
                        executor = Executors.newCachedThreadPool();

                        executor.submit(new Callable<Boolean>()
                        {
                            public Boolean call() throws Exception {
                                try {
                                    Log.info("FreeSwitch executable path " + freeSwitchExePath);

                                    freeSwitchThread = new FreeSwitchThread();
                                    freeSwitchThread.start(freeSwitchExePath + " ",  new File(freeSwitchHomePath));
                                }

                                catch (Exception e) {
                                    Log.error("FreeSwitch initializePluginn", e);
                                }

                                return true;
                            }
                        });

                    } else {
                        Log.error("FreeSwitch path error server " + freeswitchServer + " " + freeSwitchHomePath);
                    }
                }

                managerConnection = new DefaultManagerConnection(freeswitchServer, freeswitchPassword);
                Client client = managerConnection.getESLClient();
                ConnectThread connector = new ConnectThread();
                connectTask = (ScheduledFuture<ConnectThread>) connExec.scheduleAtFixedRate(connector, 30,  freeswitchInstalled ? 5 : 0, TimeUnit.SECONDS);

                Log.info( "Starting Call control component");

                ccComponent = new CallControlComponent();

                try {
                    componentManager.addComponent("callcontrol", ccComponent);
                    ccComponent.componentEnable();

                } catch (Exception e) {
                    Log.error(e.getMessage(), e);
                }
            }

            timer = new Timer();
            MUCEventDispatcher.addListener(this);

        } catch (Exception e) {
            Log.error("Could NOT start openfire switch", e);
        }
    }

    public void destroyPlugin() {

        PropertyEventDispatcher.removeListener(this);

        try {
            if (freeSwitchThread != null)
            {
                freeSwitchThread.stop();
            }

            if (executor != null)
            {
                executor.shutdown();
            }

            if (connectTask != null)
            {
                connectTask.cancel(true);
            }

            ClusterManager.removeListener(this);

            HttpBindManager.getInstance().removeJettyHandler(sipContextHandler);

            try {
                ccComponent.componentDestroyed();
                componentManager.removeComponent("callcontrol");
            } catch (Exception e) { }


            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            MUCEventDispatcher.removeListener(this);

        } catch (Exception e) {

        }
    }

    public String getDomain()
    {
        return server.getServerInfo().getXMPPDomain();
    }

    public String getHostname()
    {
        return server.getServerInfo().getHostname();
    }

    public String getIpAddress()
    {
        String ourHostname = server.getServerInfo().getHostname();
        String ourIpAddress = ourHostname;

        try {
            ourIpAddress = InetAddress.getByName(ourHostname).getHostAddress();
        } catch (Exception e) {

        }

        return ourIpAddress;
    }

//-------------------------------------------------------
//
//
//
//-------------------------------------------------------

    private class ConnectThread implements Runnable
    {
        public void run()
        {
            try {
                client = managerConnection.getESLClient();
                if (! client.canSend()) {
                    Log.info("Attempting to connect to FreeSWITCH ESL");
                    subscribed = false;
                    managerConnection.connect();
                } else {
                    if (!subscribed) {
                        Log.info("Subscribing for ESL events.");
                        client.cancelEventSubscriptions();
                        client.addEventListener(self);
                        client.setEventSubscriptions( "plain", "all" );
                        client.addEventFilter("Event-Name", "heartbeat");
                        client.addEventFilter("Event-Name", "custom");
                        client.addEventFilter("Event-Name", "channel_callstate");
                        client.addEventFilter("Event-Name", "presence_in");
                        client.addEventFilter("Event-Name", "background_job");
                        client.addEventFilter("Event-Name", "recv_info");
                        client.addEventFilter("Event-Name", "dtmf");
                        client.addEventFilter("Event-Name", "conference::maintenance");
                        client.addEventFilter("Event-Name", "sofia::register");
                        client.addEventFilter("Event-Name", "sofia::expire");
                        client.addEventFilter("Event-Name", "message");
                        client.addEventFilter("Event-Name", "dtmf");
                        subscribed = true;
                    }
                }
            } catch (InboundConnectionFailure e) {
                Log.error("Failed to connect to ESL", e);
            }
        }
    }

    private void checkNatives()
    {
        try
        {
            String suffix = null;

            if(OSUtils.IS_LINUX32)
            {
                suffix = "linux-32";
            }
            else if(OSUtils.IS_LINUX64)
            {
                suffix = "linux-64";
            }
            else if(OSUtils.IS_WINDOWS32)
            {
                suffix = "win-32";
            }
            else if(OSUtils.IS_WINDOWS64)
            {
                suffix = "win-64";
            }
            else if(OSUtils.IS_MAC)
            {
                suffix = "osx-64";
            }

            if (suffix != null)
            {
                freeSwitchHomePath = pluginDirectory.getAbsolutePath() + File.separator + "native" + File.separator + suffix;

                try {
                    freeSwitchExePath = freeSwitchHomePath + File.separator + "FreeSwitchConsole";
                    File file = new File(freeSwitchExePath);
                    file.setReadable(true, true);
                    file.setWritable(true, true);
                    file.setExecutable(true, true);

                    Log.info("checkNatives freeSwitch executable path " + freeSwitchExePath);

                } catch (Exception e) {
                    freeSwitchExePath = null;
                }

            } else {

                Log.error("checkNatives unknown OS " + pluginDirectory.getAbsolutePath());
            }
        }
        catch (Exception e)
        {
            Log.error(e.getMessage(), e);
        }
    }


//-------------------------------------------------------
//
//
//
//-------------------------------------------------------

    @Override
    public void joinedCluster()
    {
        Log.info("OfSwitch Plugin - joinedCluster");
    }

    @Override
    public void joinedCluster(byte[] arg0)
    {


    }

    @Override
    public void leftCluster()
    {
        Log.info("OfSwitch Plugin - leftCluster");
    }

    @Override
    public void leftCluster(byte[] arg0)
    {


    }

    @Override
    public void markedAsSeniorClusterMember()
    {
        Log.info("OfSwitch Plugin - markedAsSeniorClusterMember");
    }

//-------------------------------------------------------
//
//
//
//-------------------------------------------------------

    public void propertySet(String property, Map params)
    {

    }

    public void propertyDeleted(String property, Map<String, Object> params)
    {

    }

    public void xmlPropertySet(String property, Map<String, Object> params) {

    }

    public void xmlPropertyDeleted(String property, Map<String, Object> params) {

    }

//-------------------------------------------------------
//
//
//
//-------------------------------------------------------


    @Override public void eventReceived( EslEvent event )
    {
        String eventName = event.getEventName();
        Map<String, String> headers = event.getEventHeaders();

        if (ofchat == null) ofchat = (Plugin) server.getPluginManager().getPlugin("ofchat");

        try{
            Method method = ofchat.getClass().getDeclaredMethod ("eventReceived", new Class[] {String.class, Map.class});
            method.invoke(ofchat, new Object[] {eventName, headers});
        } catch (Exception e) {
            Log.error("reflect error " + e);
        }

        String eventType = headers.get("Event-Subclass");
        String origCallState = headers.get("Original-Channel-Call-State");

        Log.debug("eventReceived " + eventName + " " + eventType);

        if (eventName.equals("CHANNEL_CALLSTATE"))
        {
            final String callState = headers.get("Channel-Call-State");
            final String callId = headers.get("Caller-Unique-ID");
            final String callDirection = headers.get("Call-Direction");

            if ("HANGUP".equals(callState))
            {
                makeCalls.remove(callId);
            }
            else

            if ("DOWN".equals(origCallState) && "ACTIVE".equals(callState) && "outbound".equals(callDirection))
            {
                makeCalls.remove(callId);
            }

        }

        if (eventName.equals("CUSTOM") && eventType != null && (eventType.equals("sofia::register") || eventType.equals("sofia::unregister")))
        {
            final String extension = headers.get("from-user");
            final boolean registered = eventType.equals("sofia::register");
        }
    }

    @Override public void conferenceEventJoin(String uniqueId, String confName, int confSize, EslEvent event)
    {
        Map<String, String> headers = event.getEventHeaders();

        if (ofchat == null) ofchat = (Plugin) server.getPluginManager().getPlugin("ofchat");

        try {
            Method method = ofchat.getClass().getMethod("conferenceEventJoin", new Class[] {String.class, String.class, int.class, Map.class});
            method.invoke(ofchat, new Object[] {uniqueId, confName, confSize, headers});
        } catch (Exception e) {
            Log.error("reflect error " + e);
        }


        String callId = headers.get("Caller-Unique-ID");
        String memberId = headers.get("Member-ID");
        String source = headers.get("Caller-Caller-ID-Number");

        sendSipEvent(confName, source, callId, "etherlynk.event.sip.join");

        // the following parameters ares et by Jitsi-Meet endpoint. Not SIP

        String mcuUserId = getSessionVar(callId, "sip_h_X-ofmeet-userid");
        String roomName = getSessionVar(callId, "sip_h_X-ofmeet-room");

        if (mcuUserId != null && !"_undef_".equals(mcuUserId) && roomName != null && !"_undef_".equals(roomName) && !"_undef_".equals(confName))
        {
            Log.info("conferenceEventJoin mcu " + mcuUserId + " " + roomName);

            for (String sfuUserId : memberCallIdMaping.keySet())
            {
                Log.info("conferenceEventJoin jitsi user " + sfuUserId + " " + roomName);

                if (sfuUserId.equals(mcuUserId) == false)
                {
                    String target = memberCallIdMaping.get(sfuUserId);

                    sendAsyncFWCommand("conference " + confName + " relate " + memberId + " " + target + " " + "nospeak");
                    sendAsyncFWCommand("conference " + confName + " relate " + target + " " + memberId + " " + "nospeak");
                }

                notifySipParticipants(roomName);    // announce all existing SIP endpoints
            }

            memberCallIdMaping.put(mcuUserId, memberId);
            confRoomMaping.put(confName, roomName);

        }  else {   // SIP endpoint

            if (confRoomMaping.containsKey(confName))
            {
                roomName = confRoomMaping.get(confName);

                Log.info("conferenceEventJoin SIP user " + source + " " + roomName);
                sendSipEvent(roomName, source, callId, "ofmeet.event.sip.join");
                sipCallIdMaping.put(callId, source);
            }
        }
    }

    @Override public void conferenceEventLeave(String uniqueId, String confName, int confSize, EslEvent event)
    {
        Map<String, String> headers = event.getEventHeaders();

        if (ofchat == null) ofchat = (Plugin) server.getPluginManager().getPlugin("ofchat");

        try {
            Method method = ofchat.getClass().getMethod("conferenceEventLeave", new Class[] {String.class, String.class, int.class, Map.class});
            method.invoke(ofchat, new Object[] {uniqueId, confName, confSize, headers});
        } catch (Exception e) {
            Log.error("reflect error " + e);
        }
        String callId = headers.get("Caller-Unique-ID");
        String memberId = headers.get("Member-ID");
        String source = headers.get("Caller-Caller-ID-Number");

        sendSipEvent(confName, source, callId, "etherlynk.event.sip.leave");

        if (confRoomMaping.containsKey(confName))
        {
            String roomName = confRoomMaping.get(confName);

            Log.info("conferenceEventLeave SIP user " + source + " " + roomName);

            sendSipEvent(roomName, source, callId, "ofmeet.event.sip.leave");
            sipCallIdMaping.remove(callId);
        }
    }

    public void sendSipEvent(String roomName, String source, String callId, String eventName)
    {
        String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        JSONObject event = new JSONObject();
        event.put("event", eventName);
        event.put("conference", roomName);
        event.put("source", source);
        event.put("id", callId);

        Message message = new Message();
        message.setFrom(domain);
        message.addChildElement("ofmeet", "jabber:x:ofmeet").setText(event.toString());

        XMPPServer.getInstance().getSessionManager().broadcast(message);
    }

    public void notifySipParticipants(String roomName)
    {
        for (String key : sipCallIdMaping.keySet())
        {
            sendSipEvent(roomName, sipCallIdMaping.get(key), key, "ofmeet.event.sip.join");
        }
    }

    @Override public void conferenceEventMute(String uniqueId, String confName, int confSize, EslEvent event)
    {

    }

    @Override public void conferenceEventUnMute(String uniqueId, String confName, int confSize, EslEvent event)
    {

    }

    @Override public void conferenceEventAction(String uniqueId, String confName, int confSize, String action, EslEvent event)
    {

    }

    @Override public void conferenceEventTransfer(String uniqueId, String confName, int confSize, EslEvent event)
    {

    }

    @Override public void conferenceEventThreadRun(String uniqueId, String confName, int confSize, EslEvent event)
    {

    }

    @Override public void conferenceEventRecord(String uniqueId, String confName, int confSize, EslEvent event)
    {

    }

    @Override public void conferenceEventPlayFile(String uniqueId, String confName, int confSize, EslEvent event)
    {

    }

    @Override public void backgroundJobResultReceived( EslEvent event )
    {

    }

    @Override public void exceptionCaught(ExceptionEvent e)
    {
        Log.error("exceptionCaught", e);
    }

    public String getSessionVar(String uuid, String var)
    {
        String value = null;

        if (client.canSend())
        {
            EslMessage response = client.sendSyncApiCommand("uuid_getvar", uuid + " " + var);

            if (response != null)
            {
                value = response.getBodyLines().get(0);
            }
        }

        return value;
    }

    public String sendAsyncFWCommand(String command)
    {
        Log.debug("sendAsyncFWCommand " + command);

        String response = null;

        if (client != null)
        {
            response = client.sendAsyncApiCommand(command, "");
        }

        return response;
    }

    public EslMessage sendFWCommand(String command)
    {
        Log.debug("sendFWCommand " + command);

        EslMessage response = null;

        if (client != null)
        {
            response = client.sendSyncApiCommand(command, "");
        }

        return response;
    }

    public String getDeviceIP(String userId)
    {
        List<String> regLines = sendFWCommand("sofia status profile internal reg").getBodyLines();
        boolean foundUser = false;
        String ip = null;

        for (String line : regLines)
        {
            if (foundUser && line.startsWith("IP:"))
            {
                ip = line.substring(4).trim();
                break;
            }

            if (line.startsWith("User:") && line.indexOf(userId + "@") > -1) foundUser = true;
        }

        return ip;
    }

    public String makeCall(String conference, String destination)
    {
        Log.debug("makeCall " + conference + " " + destination);

        String sipGateway = JiveGlobals.getProperty("freeswitch.sip.gateway", getIpAddress());
        String callId = "makecall-" + System.currentTimeMillis() + "-" + conference + "-" + destination;
        String command = "conference " + conference + " bgdial {origination_uuid=" + callId + "}" + "sofia/gateway/" + sipGateway + "/" + conference + " \"" + conference + "\"";

        makeCalls.put(callId, conference);

        if (sendAsyncFWCommand(command) != null)
        {
            int counter = 0;

            while (makeCalls.containsKey(callId) && counter < 30) // timeout after 30 secs
            {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) { }

                counter++;
            }

            if (counter < 30) {
                return callId;

            } else {
                Log.warn("makeCall timeout \n" + command);
            }
        }
        makeCalls.remove(callId);
        return null;
    }

    public String doHelper(boolean useExtensions, String startExten, String fsConfigPath)
    {
        Log.debug("doHelper " + useExtensions + " " + startExten + " " + fsConfigPath);

        String response = "ok";

        try {
            String extension = startExten;
            SipRegisterStatus status = SipRegisterStatus.valueOf("Unregistered");
            String sipServer = JiveGlobals.getProperty("freeswitch.sip.hostname", getIpAddress());
            String password = JiveGlobals.getProperty("freeswitch.sip.password", "Welcome123");

            for (User user : UserManager.getInstance().getUsers())
            {
                String userId = user.getUsername();
                String name = user.getName();

                if (!useExtensions) extension = userId;

                Map<String, String> userProperties = user.getProperties();

                SipAccount sipAccount = SipAccountDAO.getAccountByUser(userId);

                if (sipAccount == null)
                {
                    // create one

                    if (useExtensions)
                    {
                        // find first unused extension
                        while (SipAccountDAO.getAccountByExtn(extension) != null)
                        {
                            extension = getNextExten(extension);
                        }
                    }

                    List<String> lines = Arrays.asList("<include>", "<user id=\"" + extension + "\">", "<params>", "<param name=\"password\" value=\"" + password + "\"/>", "<param name=\"vm-password\" value=\"" + password + "\"/>", "</params>", "<variables>", "<variable name=\"toll_allow\" value=\"domestic,international,local\"/>", "<variable name=\"accountcode\" value=\"" + extension + "\"/>", "<variable name=\"user_context\" value=\"default\"/>", "<variable name=\"effective_caller_id_name\" value=\"" + name + "\"/>", "<variable name=\"effective_caller_id_number\" value=\"" + extension + "\"/>", "<variable name=\"outbound_caller_id_name\" value=\"$${outbound_caller_name}\"/>", "<variable name=\"outbound_caller_id_number\" value=\"$${outbound_caller_id}\"/>", "<variable name=\"callgroup\" value=\"default\"/>", "</variables>", "</user>", "</include>");
                    java.nio.file.Path file = java.nio.file.Paths.get(fsConfigPath + File.separator + "directory" + File.separator + "default" + File.separator + extension + ".xml");
                    Files.write(file, lines, Charset.forName("UTF-8"));

                    sipAccount = new SipAccount(userId);
                    sipAccount.setSipUsername(extension);
                    sipAccount.setAuthUsername(extension);
                    sipAccount.setDisplayName(name);
                    sipAccount.setPassword(password);
                    sipAccount.setServer(sipServer);
                    sipAccount.setEnabled(true);
                    sipAccount.setStatus(status);
                    sipAccount.setUseStun(false);
                    sipAccount.setVoiceMailNumber(extension);
                    sipAccount.setOutboundproxy(sipServer);
                    sipAccount.setPromptCredentials(false);

                    SipAccountDAO.insert(sipAccount);

                    if (useExtensions)
                    {
                        extension = getNextExten(extension);
                    }
                }
            }

            Collection<Group> groups = GroupManager.getInstance().getGroups();
            List<String> groupLines = new ArrayList<String>();

            for (Group group : groups)
            {
                groupLines.add("<group name=\"" + group.getName() + "\">");
                groupLines.add("<users>");

                for (JID memberJID : group.getMembers())
                {
                    setMember(memberJID.getNode(), useExtensions, groupLines);
                }

                for (JID memberJID : group.getAdmins())
                {
                    setMember(memberJID.getNode(), useExtensions, groupLines);
                }
                groupLines.add("</users>");
                groupLines.add("</group>");
            }

            java.nio.file.Path file = java.nio.file.Paths.get(fsConfigPath + File.separator + "directory" + File.separator + "openfire-groups.xml");
            Files.write(file, groupLines, Charset.forName("UTF-8"));

        } catch (Exception e) {
            response = e.toString();
        }

        if ("ok".equals(response)) sendFWCommand("reloadxml");

        return response;
    }

    private String getNextExten(String extension)
    {
        int ext = Integer.parseInt(extension);
        ext++;
        return String.valueOf(ext);
    }

    private void setMember(String userId, boolean useExtensions, List<String> groupLines)
    {
        String member = userId;

        if (useExtensions)
        {
            member = null;

            try {
                SipAccount myAccount = SipAccountDAO.getAccountByUser(userId);

                if (myAccount != null)
                {
                    member = myAccount.getSipUsername();
                }
            } catch (Exception e) {

            }
        }

        if (member != null)
        {
            groupLines.add("<user id=\"" + member + "\" type=\"pointer\"/>");
        }
    }

    //-------------------------------------------------------
    //
    //      MUC room events
    //
    //-------------------------------------------------------

    @Override
    public void roomCreated(JID roomJID)
    {

    }

    @Override
    public void roomDestroyed(JID roomJID)
    {

    }

    @Override
    public void occupantJoined(final JID roomJID, JID user, String nickname)
    {
        final String roomName = roomJID.getNode();

        timer.schedule(new TimerTask()
        {
            @Override public void run()
            {
                if (JiveGlobals.getProperty("ofmeet.autorecord.enabled", "false").equals("true"))
                {
                    if (ofmeet == null) ofmeet = (Plugin) server.getPluginManager().getPlugin("ofmeet");

                    try{
                        Method method = ofmeet.getClass().getDeclaredMethod ("setRecording", new Class[] {String.class, String.class});
                        method.invoke(ofmeet, new Object[] {roomName, null});
                    } catch (Exception e) {
                        Log.error("reflect error " + e);
                    }
                }
            }

        }, 20000);
    }

    @Override
    public void occupantLeft(final JID roomJID, JID user)
    {

    }

    @Override
    public void nicknameChanged(JID roomJID, JID user, String oldNickname, String newNickname)
    {

    }

    @Override
    public void messageReceived(JID roomJID, JID user, String nickname, Message message)
    {

    }

    @Override
    public void roomSubjectChanged(JID roomJID, JID user, String newSubject)
    {

    }

    @Override
    public void privateMessageRecieved(JID a, JID b, Message message)
    {

    }
}
