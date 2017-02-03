package org.exoplatform.chat.server;

import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Subscription;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.exoplatform.chat.listener.GuiceManager;
import org.exoplatform.chat.model.MessageBean;
import org.exoplatform.chat.services.ChatService;
import org.exoplatform.chat.services.UserService;
import org.exoplatform.container.PortalContainer;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.mortbay.cometd.continuation.EXoContinuationBayeux;

import javax.inject.Inject;
import java.util.List;

/**
 * This service is used to receive all Cometd messages and then publish the messages to the right clients.
 * A Cometd service channel is used so the messages sent by clients on this channel are only sent to the server (so
 * by this service) and not to all the subscribed clients. It is up to the server to forward the messages to
 * the right clients.
 */
@Service
public class CometdService {

  @Inject
  private BayeuxServer bayeuxServer;

  @Listener("/service/chat")
  public void onMessageReceived(final ServerSession remoteSession, final ServerMessage message) {
    System.out.println(">>>>>>>>> message received on /service/chat : " + message.getJSON());

    try {
      EXoContinuationBayeux bayeux = PortalContainer.getInstance().getComponentInstanceOfType(EXoContinuationBayeux.class);

      JSONParser jsonParser = new JSONParser();
      JSONObject jsonMessage = (JSONObject) jsonParser.parse((String) message.getData());

      String event = (String) jsonMessage.get("event");

      //TODO read each message data and send it each room member. It requires to use 'deliver' instead of 'publish'
      //to avoid broadcasting the message to all connected clients (even the ones not members of the target room)

      if(event.equals("message-sent")) {
        // TODO store message in db
        ChatService chatService = GuiceManager.getInstance().getInstance(ChatService.class);
        UserService userService = GuiceManager.getInstance().getInstance(UserService.class);


        String room = (String) jsonMessage.get("room");
        String isSystem = (String) jsonMessage.get("isSystem");
        if (isSystem == null) isSystem = "false";
        String dbName = (String) jsonMessage.get("dbName");
        String options = (String) jsonMessage.get("options");
        String sender = (String) jsonMessage.get("sender");
        String msg = (String) ((JSONObject)jsonMessage.get("data")).get("msg");
        String targetUser = (String) jsonMessage.get("targetUser");

        String id = chatService.save(msg, sender, room, isSystem, options, dbName);
        MessageBean sentMsg = chatService.getMessage(room, id, dbName);

        List<String> receivers = null;
        if (targetUser.startsWith(ChatService.SPACE_PREFIX))
        {
          receivers = userService.getUsersFilterBy(sender, targetUser.substring(ChatService.SPACE_PREFIX
              .length()), ChatService.TYPE_ROOM_SPACE, dbName);
        }
        else if (targetUser.startsWith(ChatService.TEAM_PREFIX))
        {
          receivers = userService.getUsersFilterBy(sender, targetUser.substring(ChatService.TEAM_PREFIX
              .length()), ChatService.TYPE_ROOM_TEAM, dbName);
        }
        else
        {
          receivers.add(targetUser);
        }

        for (String receiver: receivers) {
          if(bayeux.isPresent(receiver)) {
            bayeux.sendMessage(receiver, "/eXo/Application/chat", jsonMessage.toJSONString(), null);
          }
        }
      }
    } catch (ParseException e) {
      e.printStackTrace();
    }
  }

}