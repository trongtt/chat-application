/*
 * Copyright (C) 2012 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.exoplatform.chat.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.util.JSON;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import juzu.impl.request.Request;
import juzu.request.ApplicationContext;
import juzu.request.UserContext;

import org.apache.commons.lang3.StringEscapeUtils;
import org.exoplatform.commons.utils.HTMLSanitizer;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.notification.LinkProviderUtils;
import org.exoplatform.social.notification.Utils;

import juzu.MimeType;
import juzu.Path;
import juzu.Resource;
import juzu.Response;
import juzu.Route;
import juzu.View;
import juzu.impl.common.Tools;
import juzu.template.Template;

import org.apache.commons.lang.StringUtils;
import org.exoplatform.ws.frameworks.cometd.ContinuationService;
import org.json.JSONException;
import org.json.simple.JSONObject;

import org.exoplatform.chat.listener.GuiceManager;
import org.exoplatform.chat.model.MessageBean;
import org.exoplatform.chat.model.NotificationBean;
import org.exoplatform.chat.model.ReportBean;
import org.exoplatform.chat.model.RoomBean;
import org.exoplatform.chat.model.RoomsBean;
import org.exoplatform.chat.model.SpaceBean;
import org.exoplatform.chat.model.UserBean;
import org.exoplatform.chat.model.UsersBean;
import org.exoplatform.chat.services.ChatService;
import org.exoplatform.chat.services.NotificationService;
import org.exoplatform.chat.services.TokenService;
import org.exoplatform.chat.services.UserService;
import org.exoplatform.chat.utils.ChatUtils;
import org.exoplatform.chat.utils.PropertyManager;
import org.mortbay.cometd.continuation.EXoContinuationBayeux;


@ApplicationScoped
public class ChatServer
{
  private static final Logger LOG = Logger.getLogger("ChatServer");

  @Inject
  @Path("index.gtmpl")
  Template index;
  @Inject
  @Path("users.gtmpl")
  Template users;

  ChatService chatService;
  UserService userService;
  TokenService tokenService;
  NotificationService notificationService;
  @Inject
  ChatTools chatTools;

  public ChatServer()
  {
    chatService = GuiceManager.getInstance().getInstance(ChatService.class);
    userService = GuiceManager.getInstance().getInstance(UserService.class);
    tokenService = GuiceManager.getInstance().getInstance(TokenService.class);
    notificationService = GuiceManager.getInstance().getInstance(NotificationService.class);
  }

  @View
  @Route("/")
  public Response.Content index() throws IOException
  {
    return index.ok();
  }

  @Resource
  @Route("/whoIsOnline")
  public Response.Content whoIsOnline(String user, String token, String filter, String isAdmin, String limit, String dbName)
  {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }
    Integer ilimit = 0;
    try {
      if (limit!=null && !"".equals(limit))
        ilimit = Integer.parseInt(limit);
    } catch (NumberFormatException nfe) {
      LOG.info("limit is not a valid Integer number");
    }

    RoomsBean roomsBean = chatService.getRooms(user, filter, true, true, false, true, "true".equals(isAdmin), ilimit,
            notificationService, tokenService, dbName);
    return Response.ok(roomsBean.roomsToJSON()).withMimeType("application/json").withHeader
            ("Cache-Control", "no-cache").withCharset(Tools.UTF_8);
  }

  @Resource
  @Route("/send")
  public Response.Content send(String sender, String token, String targetUser, String message, String room,
                               String isSystem, String options, String dbName) throws IOException {
    if (!tokenService.hasUserWithToken(sender, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }

    if (message!=null)
    {
      // Only members of the room can send messages
      if (!isMemberOfRoom(sender, room, dbName)) {
        return Response.content(403, "Petit malin !");
      }

      try {
        message = URLDecoder.decode(message,"UTF-8");
        options = URLDecoder.decode(options,"UTF-8");
      } catch (UnsupportedEncodingException e) {
        // Chat server cannot do anything in this case
        // Get original value
      }

      chatService.write(message, sender, room, isSystem, options, dbName, targetUser);
    }

    return Response.ok("ok").withMimeType("application/json; charset=UTF-8").withHeader("Cache-Control", "no-cache");
  }

  @Resource
  @Route("/read")
  public Response.Content read(String user, String token, String room, String fromTimestamp,
                               String isTextOnly, String dbName) throws IOException {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }

    // Only members of the room can view the messages
    if (!isMemberOfRoom(user, room, dbName)) {
      return Response.content(403, "Petit malin !");
    }

    Long from = null;
    try {
      if (fromTimestamp != null && !"".equals(fromTimestamp))
        from = Long.parseLong(fromTimestamp);
    } catch (NumberFormatException nfe) {
      LOG.info("fromTimestamp is not a valid Long number");
    }

    String data = chatService.read(room, "true".equals(isTextOnly), from, dbName);

    return Response.ok(data).withMimeType("application/json").withHeader("Cache-Control", "no-cache")
                   .withCharset(Tools.UTF_8);
  }

  @Resource
  @Route("/sendMeetingNotes")
  public Response.Content sendMeetingNotes(String user, String token, String room, String fromTimestamp,
                                           String toTimestamp, String serverBase, String dbName, ApplicationContext applicationContext, UserContext userContext) throws IOException {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }

    Long from = null;
    Long to = null;

    try {
      if (fromTimestamp!=null && !"".equals(fromTimestamp))
        from = Long.parseLong(fromTimestamp);
    } catch (NumberFormatException nfe) {
      LOG.info("fromTimestamp is not a valid Long number");
    }
    try {
      if (toTimestamp!=null && !"".equals(toTimestamp))
        to = Long.parseLong(toTimestamp);
    } catch (NumberFormatException nfe) {
      LOG.info("fromTimestamp is not a valid Long number");
    }
    String data = chatService.read(room, false, from, to, dbName);
    BasicDBObject datao = (BasicDBObject)JSON.parse(data);
    String roomType = chatService.getTypeRoomChat(room, dbName);
    SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
    String date = formatter.format(new GregorianCalendar().getTime());
    String title = "";
    String roomName = "";
    
    List<UserBean> users = new ArrayList<UserBean>();
    Locale locale = userContext.getLocale();
    ResourceBundle res = applicationContext.resolveBundle(locale);

    if (datao.containsField("messages")) {
      if (ChatService.TYPE_ROOM_USER.equalsIgnoreCase(roomType)) {
        users = userService.getUsersInRoomChatOneToOne(room, dbName);
        title = res.getString("exoplatform.chat.meetingnotes") + " ["+date+"]";
      } else {
        users = userService.getUsers(room, dbName);
        List<SpaceBean> spaces = userService.getSpaces(user, dbName);
        for (SpaceBean spaceBean:spaces)
        {
          if (room.equals(spaceBean.getRoom()))
          {
            roomName = spaceBean.getDisplayName();
          }
        }
        List<RoomBean> roomBeans = userService.getTeams(user, dbName);
        for (RoomBean roomBean:roomBeans)
        {
          if (room.equals(roomBean.getRoom()))
          {
            roomName = roomBean.getFullname();
          }
        }
        title = roomName+" : "+ res.getString("exoplatform.chat.meetingnotes") + " ["+date+"]";
      }
      ReportBean reportBean = new ReportBean(res);
      reportBean.fill((BasicDBList) datao.get("messages"), users);

      ArrayList<String> tos = new ArrayList<String>();
      String senderName = user;
      String senderMail = "";
      for (UserBean userBean:users)
      {
        if (!"".equals(userBean.getEmail()))
        {
          tos.add(userBean.getEmail());
        }
        if (user.equals(userBean.getName()))
        {
          senderName = userBean.getFullname();
          senderMail = userBean.getEmail();
        }
      }
      String html = reportBean.getAsHtml(title, serverBase,locale);

      // inline images
      String prevUser = "";
      int index = 0;
      Map<String, String> inlineImages = new HashMap<String, String>();
      for(MessageBean messageBean : reportBean.getMessages()) {
        if(!messageBean.getUser().equals(prevUser)) {
          String keyAvatar = messageBean.getUser() + index;
          Identity identity = Utils.getIdentityManager().getOrCreateIdentity("organization", messageBean.getUser(), true);
          String getAvatarUrl = LinkProviderUtils.getUserAvatarUrl(identity.getProfile());
          inlineImages.put(keyAvatar, getAvatarUrl);
          index ++;
        }
        prevUser = messageBean.getUser();
      }

      try {
        sendMailWithAuth(senderName,senderMail , tos, html.toString(), title, inlineImages);
      } catch (Exception e) {
        LOG.info(e.getMessage());
      }

    }

    return Response.ok("sent").withMimeType("text/event-stream; charset=UTF-8").withHeader("Cache-Control", "no-cache");
  }

  @Resource
  @Route("/getMeetingNotes")
  public Response.Content getMeetingNotes(String user, String token, String room, String fromTimestamp,
                                          String toTimestamp, String serverBase, String dbName, String portalURI, ApplicationContext applicationContext, UserContext userContext) throws IOException {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }

    Long from = null;
    Long to = null;
    String xwiki = "";
    String roomName = "";
    List<UserBean> users = new ArrayList<UserBean>();
    JSONObject jsonObject = new JSONObject();
    try {
      if (fromTimestamp!=null && !"".equals(fromTimestamp))
        from = Long.parseLong(fromTimestamp);
    } catch (NumberFormatException nfe) {
      LOG.info("fromTimestamp is not a valid Long number");
    }
    try {
      if (toTimestamp!=null && !"".equals(toTimestamp))
        to = Long.parseLong(toTimestamp);
    } catch (NumberFormatException nfe) {
      LOG.info("fromTimestamp is not a valid Long number");
    }
    String data = chatService.read(room, false, from, to, dbName);
    String typeRoom = chatService.getTypeRoomChat(room, dbName);
    BasicDBObject datao = (BasicDBObject)JSON.parse(data);
    if (datao.containsField("messages")) {
      if(ChatService.TYPE_ROOM_USER.equalsIgnoreCase(typeRoom)) {
        users = userService.getUsersInRoomChatOneToOne(room, dbName);
      }
      else {
        users = userService.getUsers(room, dbName);
        List<SpaceBean> spaces = userService.getSpaces(user, dbName);
        for (SpaceBean spaceBean:spaces)
        {
          if (room.equals(spaceBean.getRoom()))
          {
            roomName = spaceBean.getDisplayName();
          }
        }
        List<RoomBean> roomBeans = userService.getTeams(user, dbName);
        for (RoomBean roomBean:roomBeans)
        {
          if (room.equals(roomBean.getRoom()))
          {
            roomName = roomBean.getFullname();
          }
        }
      }
      Locale locale = userContext.getLocale();
      ResourceBundle res = applicationContext.resolveBundle(locale);

      ReportBean reportBean = new ReportBean(res);

      reportBean.fill((BasicDBList) datao.get("messages"), users);
      ArrayList<String> usersInGroup = new ArrayList<String>();
      xwiki = reportBean.getAsXWiki(serverBase, portalURI);
      try {
        for (UserBean userBean : users) {
          if (!"".equals(userBean.getName())) {
            usersInGroup.add(userBean.getName());
          }
        }
        jsonObject.put("users", usersInGroup);
        jsonObject.put("xwiki", xwiki);
        jsonObject.put("typeRoom", typeRoom);
      } catch (Exception e) {
        LOG.warning(e.getMessage());
        return Response.notFound("No Room yet");
      }
    }

    return Response.ok(jsonObject.toString()).withMimeType("application/json").withHeader("Cache-Control", "no-cache")
                   .withCharset(Tools.UTF_8);
  }

  @Resource
  @MimeType("text/plain")
  @Route("/delete")
  public Response.Content delete(String user, String token, String room, String messageId, String dbName) throws IOException
  {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }

    // Only author of the message can delete it
    MessageBean message = chatService.getMessage(room, messageId, dbName);
    if (message == null || !message.getUser().equals(user)) {
      return Response.notFound("");
    }

    try
    {
      chatService.delete(room, user, messageId, dbName);
    }
    catch (Exception e)
    {
      return Response.notFound("Oups");
    }
    return Response.ok("Updated!");

  }

  @Resource
  @MimeType("text/plain")
  @Route("/deleteTeamRoom")
  public Response.Content deleteTeamRoom(String user, String token, String room, String dbName) throws IOException {
    if (!tokenService.hasUserWithToken(user, token, dbName)) {
      return Response.notFound("Petit malin !");
    }
    try {
      chatService.deleteTeamRoom(room, user, dbName);
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Impossible to delete Team Room [" + room + "] : " + e.getMessage(), e);
      return Response.content(500, "Oups!");
    }
    return Response.ok("Updated!");
  }

  @Resource
  @MimeType("text/plain")
  @Route("/edit")
  public Response.Content edit(String user, String token, String room, String messageId, String message, String dbName) throws IOException
  {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }

    // Only author of the message can edit it
    MessageBean currentMessage = chatService.getMessage(room, messageId, dbName);
    if (currentMessage == null || !currentMessage.getUser().equals(user)) {
      return Response.notFound("");
    }

    try
    {
      try {
        message = URLDecoder.decode(message,"UTF-8");
      } catch (UnsupportedEncodingException e) {
        // Chat server cannot do anything in this case
        // Get original value
      }
      chatService.edit(room, user, messageId, message, dbName);
    }
    catch (Exception e)
    {
      return Response.notFound("Oups");
    }
    return Response.ok("Updated!");

  }

  @Resource
  @MimeType("text/plain")
  @Route("/toggleFavorite")
  public Response.Content toggleFavorite(String user, String token, String targetUser, String dbName)
  {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }
    try
    {
      userService.toggleFavorite(user, targetUser, dbName);
    }
    catch (Exception e)
    {
      LOG.log(java.util.logging.Level.WARNING, e.getMessage());
      return Response.notFound("Oups");
    }
    return Response.ok("Updated!");
  }
  
  @Resource
  @MimeType("text/plain")
  @Route("/isFavorite")
  public Response.Content isFavorite(String user, String token, String targetUser, String dbName) {
    if (!tokenService.hasUserWithToken(user, token, dbName)) {
      return Response.notFound("Petit malin !");
    }
    boolean isFavorite = false;
    try {
      isFavorite = userService.isFavorite(user, targetUser, dbName);
    } catch (Exception e) {
      LOG.log(java.util.logging.Level.WARNING, e.getMessage());
      return Response.notFound("Oups");
    }
    return Response.ok(String.valueOf(isFavorite));
  }

  @Resource
  @MimeType("application/json")
  @Route("/getUserDesktopNotificationSettings")
  public Response.Content getUserDesktopNotificationSettings(String user, String token, String dbName) throws JSONException {
    if (!tokenService.hasUserWithToken(user, token, dbName)) {
      return Response.notFound("Something is wrong.");
    }

    JSONObject response = new JSONObject();
    JSONObject res =  userService.getUserDesktopNotificationSettings(user, dbName).toJSON();
    if(res==null || res.isEmpty()){
      response.put("done",false);
    } else {
      response.put("done",true);
    }
    response.put("userDesktopNotificationSettings",res);

    return Response.ok(response.toString()).withHeader("Cache-Control", "no-cache")
            .withCharset(Tools.UTF_8);
  }

  @Resource
  @MimeType("text/plain")
  @Route("/setPreferredNotification")
  public Response.Content setPreferredNotification(String user, String token, String notifManner, String dbName) throws JSONException {
    if (!tokenService.hasUserWithToken(user, token, dbName)) {
      return Response.notFound("Something is wrong.");
    }

    JSONObject response = new JSONObject();
    try {
      userService.setPreferredNotification(user, notifManner, dbName);
      response.put("done",true);
    } catch (Exception e) {
      response.put("done",false);
    }

    return Response.ok(response.toString()).withMimeType("application/json").withHeader("Cache-Control", "no-cache")
            .withCharset(Tools.UTF_8);
  }

  @Resource
  @MimeType("text/plain")
  @Route("/setRoomNotificationTrigger")
  public Response.Content setRoomNotificationTrigger(String user, String token, String room, String notifCondition,String notifConditionType, String dbName, Long time) throws JSONException {
    if (!tokenService.hasUserWithToken(user, token, dbName)) {
      return Response.notFound("Something is wrong.");
    }

    JSONObject response = new JSONObject();
    try{
      userService.setRoomNotificationTrigger(user, room, notifCondition, notifConditionType, dbName, time);
      response.put("done", true);
    } catch(Exception e) {
      response.put("done",false);
    }

    return Response.ok(response.toString()).withMimeType("application/json").withHeader("Cache-Control", "no-cache")
            .withCharset(Tools.UTF_8);
  }

  @Resource
  @MimeType("text/plain")
  @Route("/setNotificationTrigger")
  public Response.Content setNotificationTrigger(String user, String token, String notifCondition, String dbName) throws JSONException {
    if (!tokenService.hasUserWithToken(user, token, dbName)) {
      return Response.notFound("Something is wrong.");
    }

    JSONObject response = new JSONObject();
    try {
      userService.setNotificationTrigger(user, notifCondition,dbName);
      response.put("done", true);
    } catch (Exception e) {
      response.put("done",false);
    }

    return Response.ok(response.toString()).withMimeType("application/json").withHeader("Cache-Control", "no-cache")
            .withCharset(Tools.UTF_8);
  }

  @Resource
  @Route("/getRoom")
  public Response.Content getRoom(String user, String token, String targetUser, String isAdmin, String withDetail,
                                  String type, String dbName) {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }
    String room = targetUser;
    RoomBean roomBean = null;
    try
    {
      if (type != null) {
        if ("room-id".equals(type))
        {
          room = targetUser;
        }
        else if ("space-name".equals(type))
        {
          room = chatService.getSpaceRoomByName(targetUser, dbName);
        }
        else if ("space-id".equals(type))
        {
          room = ChatUtils.getRoomId(targetUser);
        }
        else if ("username".equals(type))
        {
          List<String> users = new ArrayList<String>();
          users.add(user);
          users.add(targetUser);
          room = chatService.getRoom(users, dbName);
        }
        else if ("external".equals(type))
        {
          room = chatService.getExternalRoom(targetUser, dbName);
        }
      }
      else if (targetUser.startsWith(ChatService.SPACE_PREFIX))
      {
        room = chatService.getSpaceRoom(targetUser, dbName);

      }
      else
      if (targetUser.startsWith(ChatService.TEAM_PREFIX))
      {
        room = chatService.getTeamRoom(targetUser, user, dbName);

      }
      else
      if (targetUser.startsWith(ChatService.EXTERNAL_PREFIX))
      {
        room = chatService.getExternalRoom(targetUser, dbName);
      }
      else
      {
        String finalUser = ("true".equals(isAdmin) && !user.startsWith(UserService.ANONIM_USER) && targetUser
                .startsWith(UserService.ANONIM_USER)) ? UserService.SUPPORT_USER : user;

        ArrayList<String> users = new ArrayList<String>(2);
        users.add(finalUser);
        users.add(targetUser);
        room = chatService.getRoom(users, dbName);
      }
      if ("true".equals(withDetail))
      {
        roomBean = userService.getRoom(user, room, dbName);
      }
      notificationService.setNotificationsAsRead(user, "chat", "room", room, dbName);
    }
    catch (Exception e)
    {
      LOG.log(java.util.logging.Level.WARNING, e.getMessage());
      return Response.notFound("No Room yet");
    }
    String out = room;
    if (roomBean!=null)
    {
      out = roomBean.toJSON();
    }

    return Response.ok(out).withMimeType("application/json").withCharset(Tools.UTF_8).withHeader("Cache-Control", "no-cache");
  }

  @Resource
  @Route("/saveTeamRoom")
  public Response.Content saveTeamRoom(String user, String token, String teamName, String room, String users, String dbName)
  {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }
    JSONObject jsonObject = new JSONObject();

    try
    {
      try {
        teamName = URLDecoder.decode(teamName,"UTF-8");
      } catch (UnsupportedEncodingException e) {
        LOG.info("Cannot decode message: " + teamName);
      }
      teamName = HTMLSanitizer.sanitize(teamName);
      if("".equals(teamName)) return Response.content(400, "Data is invalid!");
      if (room==null || "".equals(room) || "---".equals(room))
      {
        room = chatService.getTeamRoom(teamName, user, dbName);
        userService.addTeamRoom(user, room, dbName);
      }
      else
      {
        // Only creator can edit members or set team name
        String creator = chatService.getTeamCreator(room, dbName);
        if (!user.equals(creator)) {
          return Response.notFound("Petit malin !");
        }

        if (room.startsWith(ChatService.TEAM_PREFIX) && room.length()>ChatService.TEAM_PREFIX.length()+1)
        {
          room = room.substring(ChatService.TEAM_PREFIX.length());
        }
        chatService.setRoomName(room, teamName, dbName);
      }

      if (users!=null && !"".equals(users)) {
        String[] ausers = users.split(",");
        List<String> usersNew = Arrays.asList(ausers);
        List<String> usersToAdd = new ArrayList<String>(usersNew);
        List<String> usersToRemove = new ArrayList<String>();
        List<String> usersExisting = userService.getUsersFilterBy(null, room, ChatService.TYPE_ROOM_TEAM, dbName);

        for (String userExist:usersExisting)
        {
          if (!usersNew.contains(userExist))
          {
            usersToRemove.add(userExist);
          }
          else if (usersNew.contains(userExist))
          {
            usersToAdd.remove(userExist);
          }
        }
        if (usersToRemove.size()>0)
        {
          userService.removeTeamUsers(room, usersToRemove, dbName);
          StringBuilder sbUsers = new StringBuilder();
          boolean first = true;
          for (String usert:usersToRemove)
          {
            if (!first) sbUsers.append("; ");
            sbUsers.append(userService.getUserFullName(usert, dbName));
            first = false;
            notificationService.setNotificationsAsRead(usert, "chat", "room", room, dbName);
          }

          // Send a websocket message of type 'room-member-left' to all the room members
          JSONObject leaveRoomMessage = new JSONObject();
          leaveRoomMessage.put("event", "room-member-left");
          leaveRoomMessage.put("room", room);
          leaveRoomMessage.put("sender", user);
          leaveRoomMessage.put("ts", System.currentTimeMillis());
          JSONObject data = new JSONObject();
          data.put("members", String.join(",", usersToRemove));
          leaveRoomMessage.put("data", data);

          EXoContinuationBayeux bayeux = PortalContainer.getInstance().getComponentInstanceOfType(EXoContinuationBayeux.class);
          usersExisting.stream()
                  .filter(u -> bayeux.isPresent(u))
                  .forEach(u -> bayeux.sendMessage(u, CometdService.COMETD_CHANNEL_NAME, leaveRoomMessage, null));

          // Send members removal message in the room
          String removeTeamUserOptions
                  = "{\"type\":\"type-remove-team-user\",\"users\":\"" + sbUsers + "\", " +
                  "\"fullname\":\"" + userService.getUserFullName(user, dbName) + "\"}";
          this.send(user, token, ChatService.TEAM_PREFIX+room, StringUtils.EMPTY, room, "true", removeTeamUserOptions, dbName);
        }
        if (usersToAdd.size()>0)
        {
          userService.addTeamUsers(room, usersToAdd, dbName);
          StringBuilder sbUsers = new StringBuilder();
          boolean first = true;
          for (String usert:usersToAdd)
          {
            if (!first) sbUsers.append("; ");
            sbUsers.append(userService.getUserFullName(usert, dbName));
            first = false;
          }
          String addTeamUserOptions
                  = "{\"type\":\"type-add-team-user\",\"users\":\"" + sbUsers + "\", " +
                  "\"fullname\":\"" + userService.getUserFullName(user, dbName) + "\"}";
          this.send(user, token, ChatService.TEAM_PREFIX+room, StringUtils.EMPTY, room, "true", addTeamUserOptions, dbName);
        }

      }

      jsonObject.put("name", teamName);
      jsonObject.put("room", room);

    }
    catch (Exception e)
    {
      LOG.warning(e.getMessage());
      return Response.notFound("No Room yet");
    }
    return Response.ok(jsonObject.toString()).withMimeType("application/json").withHeader("Cache-Control", "no-cache")
                   .withCharset(Tools.UTF_8);
  }

  @Resource
  @MimeType("text/plain")
  @Route("/updateUnreadMessages")
  public Response.Content updateUnreadMessages(String room, String user, String token, String dbName)
  {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }
    try
    {
      notificationService.setNotificationsAsRead(user, "chat", "room", room, dbName);
      if (userService.isAdmin(user, dbName))
      {
        notificationService.setNotificationsAsRead(UserService.SUPPORT_USER, "chat", "room", room, dbName);
      }

    }
    catch (Exception e)
    {
      LOG.warning(e.getMessage());
      return Response.notFound("Server Not Available yet");
    }
    return Response.ok("Updated.");
  }

  @Resource
  @Route("/notification")
  public Response.Content notification(String user, String token, String event, String withDetails, String dbName) throws IOException
  {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }
    boolean detailed = ("true".equals(withDetails));
    int totalUnread = 0;
    List<NotificationBean> notifications = null;
    if (!detailed)
    {
      // GETTING TOTAL NOTIFICATION WITHOUT DETAILS
      totalUnread = notificationService.getUnreadNotificationsTotal(user, dbName);
      if (userService.isAdmin(user, dbName))
      {
        totalUnread += notificationService.getUnreadNotificationsTotal(UserService.SUPPORT_USER, dbName);
      }
    }
    else {
      // GETTING ALL NOTIFICATION DETAILS
      notifications = notificationService.getUnreadNotifications(user, userService, dbName);
      totalUnread = notifications.size();
    }

    String data = "{\"total\": \""+totalUnread+"\"";
    if (detailed && notifications!=null)
    {
      data += ","+NotificationBean.notificationstoJSON(notifications);
    }
    data += "}";
    if (event!=null && event.equals("1"))
    {
      data = "id: "+totalUnread+"\n";
      data += "data: {\"total\": "+totalUnread+"}\n\n";
    }

    return Response.ok(data).withMimeType("application/json").withCharset(Tools.UTF_8).withHeader("Cache-Control", "no-cache");
  }

  @Resource
  @MimeType("text/plain")
  @Route("/getStatus")
  public Response.Content getStatus(String user, String token, String targetUser, String dbName)
  {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }
    String status = UserService.STATUS_INVISIBLE;
    try
    {
      if (targetUser!=null)
      {
        boolean online = tokenService.isUserOnline(targetUser, dbName);
        if (online)
          status = userService.getStatus(targetUser, dbName);
        else
          status = UserService.STATUS_OFFLINE;
      }
      else
      {
        status = userService.getStatus(user, dbName);
        tokenService.updateValidity(user, token, dbName);
      }
    }
    catch (Exception e)
    {
      LOG.warning(e.getMessage());
      return Response.notFound(status);
    }
    return Response.ok(status).withHeader("Cache-Control", "no-cache");
  }

  @Resource
  @MimeType("text/plain")
  @Route("/setStatus")
  public Response.Content setStatus(String user, String token, String status, String dbName)
  {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }
    try
    {
      userService.setStatus(user, status, dbName);
    }
    catch (Exception e)
    {
      LOG.warning(e.getMessage());
      return Response.notFound("No Status for this User");
    }
    return Response.ok(status);
  }

  @Resource
  @MimeType("text/plain")
  @Route("/getCreator")
  public Response.Content getCreator(String user, String token, String room, String dbName)
  {
    String creator = "";
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }
    try
    {
      creator = chatService.getTeamCreator(room, dbName);
    }
    catch (Exception e)
    {
      LOG.warning(e.getMessage());
      return Response.notFound("No Status for this User");
    }
    return Response.ok(creator);
  }

  @Resource
  @Route("/users")
  public Response.Content getUsers(String user, String token, String room, String filter, String dbName)
  {
    if (!tokenService.hasUserWithToken(user, token, dbName))
    {
      return Response.notFound("Petit malin !");
    }

    List<UserBean> users;
    if (room!=null && !"".equals(room))
    {
      users = userService.getUsers(room, dbName);
    }
    else
    {
      users = userService.getUsers(filter, true, dbName);
    }

    for (UserBean userBean:users)
    {
      boolean online = tokenService.isUserOnline(userBean.getName(), dbName);
      if (!online) userBean.setStatus(UserService.STATUS_OFFLINE);
    }


    UsersBean usersBean = new UsersBean();
    usersBean.setUsers(users);
    return Response.ok(usersBean.usersToJSON()).withMimeType("application/json").withHeader
            ("Cache-Control", "no-cache").withCharset(Tools.UTF_8);
  }

  @Resource
  @Route("/statistics")
  public Response.Content getStatistics(String dbName)
  {
    StringBuffer data = new StringBuffer();
    data.append("{");
    data.append(" \"users\": "+userService.getNumberOfUsers(dbName)+", ");
    data.append(" \"rooms\": "+chatService.getNumberOfRooms(dbName)+", ");
    data.append(" \"messages\": "+ chatService.getNumberOfMessages(dbName)+", ");
    data.append(" \"notifications\": "+notificationService.getNumberOfNotifications(dbName)+", ");
    data.append(" \"notificationsUnread\": "+notificationService.getNumberOfUnreadNotifications(dbName));
    data.append("}");

    return Response.ok(data.toString()).withMimeType("application/json").withCharset(Tools.UTF_8).withHeader("Cache-Control", "no-cache");
  }

  private Session getMailSession() {
    String protocal = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_PROTOCAL);
    String host = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_HOST);
    final String user = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_USER);
    final String password = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_PASSWORD);
    String port = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_PORT);
    String auth = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_AUTH);
    String starttlsEnable = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_STARTTLS_ENABLE);
    String enableSSL = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_ENABLE_SSL_ENABLE);
    String smtpAuth = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_AUTH);
    String socketFactoryPort = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_SOCKET_FACTORY_PORT);
    String socketFactoryClass = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_SOCKET_FACTORY_CLASS);
    String socketFactoryFallback = PropertyManager.getProperty(PropertyManager.PROPERTY_MAIL_SOCKET_FACTORY_FALLBACK);
    
    Properties props = new Properties();

    // SMTP protocol properties
    props.put("mail.transport.protocol",protocal);
    props.put("mail.smtp.host", host);
    props.put("mail.smtp.port", port);
    props.put("mail.smtp.auth", auth);

    if (Boolean.parseBoolean(smtpAuth)) {
      props.put("mail.smtp.socketFactory.port",socketFactoryPort);
      props.put("mail.smtp.socketFactory.class",socketFactoryClass);
      props.put("mail.smtp.socketFactory.fallback",socketFactoryFallback);
      props.put("mail.smtp.starttls.enable",starttlsEnable);
      props.put("mail.smtp.ssl.enable",enableSSL);
      return Session.getInstance(props, new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
          return new PasswordAuthentication(user, password);
        }
      });
    } else {
      return Session.getInstance(props);
    }
  }
  
  public void sendMailWithAuth(String senderFullname, String senderMail, List<String> toList, String htmlBody, String subject, Map<String, String> inlineImages) throws Exception {

    Session session = getMailSession();
    
    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress(senderMail, senderFullname, "UTF-8"));

    // To get the array of addresses
    for (String to: toList) {
      message.addRecipients(Message.RecipientType.BCC, InternetAddress.parse(to));
    }
    
    message.setSubject(subject, "UTF-8");
    // Create a message part to represent the body text
    MimeBodyPart messageBodyPart = new MimeBodyPart();
    messageBodyPart.setContent(htmlBody, "text/html; charset=UTF-8");
    // use a MimeMultipart as we need to handle the file attachments
    Multipart multipart = new MimeMultipart();
    // add the message body to the mime message
    multipart.addBodyPart(messageBodyPart);
    
    // Part2: get user's avatar
    
    if (inlineImages != null && inlineImages.size() > 0) {
      Set<String> setImageID = inlineImages.keySet();
      for (String contentId : setImageID) {
        messageBodyPart = new MimeBodyPart();
        String imageFilePath = inlineImages.get(contentId);
        URL url = new URL(imageFilePath);
        URLConnection con = url.openConnection();
        con.setDoOutput(true);
        InputStream is = con.getInputStream();
        ByteArrayDataSource byteArrayDataSource = new ByteArrayDataSource(is, con.getContentType());
        messageBodyPart.setDataHandler(new DataHandler(byteArrayDataSource));
        messageBodyPart.setContentID("<" + contentId + ">");
        messageBodyPart.setDisposition(MimeBodyPart.INLINE);
        multipart.addBodyPart(messageBodyPart);
        }
    }
    // Put all message parts in the message
    message.setContent(multipart);
    
    try {
      Transport.send(message);
    } catch(Exception e){
      LOG.info(e.getMessage());
    }
  }

  /**
   * Check if an user is member of a room
   * @param username Username of the user
   * @param roomId Id of the room
   * @param dbName Databse name
   * @return true if the user is member of the room
   */
  protected boolean isMemberOfRoom(String username, String roomId, String dbName) {
    List<String> roomMembers;
    RoomBean room = userService.getRoom(username, roomId, dbName);
    if(room == null) {
      LOG.warning("Cannot check if user " + username + " is member of room " + roomId + " since the room does not exist.");
      return false;
    }
    if (room.getType().equals("t")) {
      roomMembers = userService.getUsersFilterBy(null, roomId, ChatService.TYPE_ROOM_TEAM, dbName);
    } else if(room.getType().equals("s")) {
      roomMembers = userService.getUsersFilterBy(null, roomId, ChatService.TYPE_ROOM_SPACE, dbName);
    } else {
      roomMembers = new ArrayList<>();
      List<UserBean> userBeans = userService.getUsersInRoomChatOneToOne(roomId, dbName);
      if(userBeans != null) {
        for (UserBean userBean : userBeans) {
          roomMembers.add(userBean.getName());
        }
      }
    }
    if (roomMembers == null || !roomMembers.contains(username)) {
      return false;
    }
    return true;
  }
}
