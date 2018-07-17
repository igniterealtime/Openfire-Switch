<?xml version="1.0" encoding="UTF-8"?>

<!--
  ~ Copyright (c) 2017 Ignite Realtime Foundation. All rights reserved.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~      http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  -->

<%@ page import="org.jivesoftware.openfire.plugin.ofswitch.*" %>
<%@ page import="org.jivesoftware.openfire.*" %>
<%@ page import="org.jivesoftware.util.*" %>
<%@ page import="java.io.File" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.net.InetAddress" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%

    boolean action = request.getParameter("action") != null;

    String errorMessage = null;
    String ourIpAddress = OfSwitchPlugin.self.getIpAddress();  
    String ourHostname = XMPPServer.getInstance().getServerInfo().getHostname();
            
    if (action)
    {
        String useExtensions = request.getParameter("useExtensions");
        String startExten = request.getParameter("startExten");
        String fsConfigPath = request.getParameter("fsConfigPath");          
        
        JiveGlobals.setProperty("freeswitch.helper.use.extensions", (useExtensions != null && useExtensions.equals("on")) ? "true": "false");        
        JiveGlobals.setProperty("freeswitch.helper.start.extension", startExten);        
        JiveGlobals.setProperty("freeswitch.server.config.path", fsConfigPath);   
        
        errorMessage = OfSwitchPlugin.self.doHelper(useExtensions != null && useExtensions.equals("on"), startExten, fsConfigPath);         
    }

%>
<html>
<head>
   <title><fmt:message key="config.page.helper.title" /></title>
   <meta name="pageID" content="freeswitch-helper"/>
</head>
<body>
<% if ("ok".equals(errorMessage)) {%>
    <div class="jive-success">
    <table cellpadding="0" cellspacing="0" border="0">
    <tbody>
        <tr><td class="jive-icon"><img src="images/success-16x16.gif" width="16" height="16" border="0" alt=""></td>
        <td class="jive-icon-label">
        FreeSWITCH Updated
        </td></tr>
    </tbody>
    </table>
    </div><br>

<% } else if (errorMessage != null) { %>
<div class="error">
    <%= errorMessage%>
</div>    

<%  } %>
<br/>

<form action="freeswitch-helper.jsp" method="post">

<div class="jive-contentBoxHeader">   
    <fmt:message key="config.page.helper.title"/>
</div>
<div class="jive-contentBox">      
    <p>
        <table cellpadding="3" cellspacing="0" border="0" width="100%">
            <tbody> 
        <tr>
        <td nowrap  colspan="2">
            <input type="checkbox" name="useExtensions"<%= (JiveGlobals.getProperty("freeswitch.helper.use.extensions", "false").equals("true")) ? " checked" : "" %>>
            <fmt:message key="settings.freeswitch.helper.use.extensions" />        
        </td>
        </tr>                       
        <tr>
        <td align="left" width="150">
            <fmt:message key="settings.freeswitch.helper.start.extension"/>
        </td>
        <td><input type="text" size="50" maxlength="100" name="startExten"
               value="<%= JiveGlobals.getProperty("freeswitch.helper.start.extension", "1001") %>">
        </td>
        </tr>   
        <tr>
        <td align="left" width="150">
            <fmt:message key="settings.freeswitch.server.config.path"/>
        </td>
        <td><input type="text" size="50" maxlength="100" name="fsConfigPath"
               value="<%= JiveGlobals.getProperty("freeswitch.server.config.path", "/etc/freeswitch") %>">
        </td>
        </tr>       
        </tbody>
        </table>
   </p>  
</div>
<div class="jive-contentBoxHeader">   
    <fmt:message key="config.page.helper.run"/>
</div>
<div class="jive-contentBox">     
    <p>
        <fmt:message key="config.page.configuration.helper.desc" />               
    </p> 
    <p>
        <input type="submit" name="action" value="<fmt:message key="config.page.configuration.helper.action" />">
    </p>
</div>
</form>
</body>
</html>