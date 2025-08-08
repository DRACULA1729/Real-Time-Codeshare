package com.collabcode.realtime_codeshare.controller;

import com.collabcode.realtime_codeshare.dto.EditorMessageDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class EditorWebSocketController {
    @Autowired
    private final SimpMessagingTemplate messagingTemplate;

    public EditorWebSocketController(SimpMessagingTemplate messagingTemplate){
        this.messagingTemplate=messagingTemplate;
    }

    @MessageMapping("/editor/update")
    public void handleCodeUpdate(EditorMessageDTO message){
        String topic="/topic/session/"+ message.getSessionId()+"/code";
        messagingTemplate.convertAndSend(topic, message);
    }
    @MessageMapping("/editor/cursor/{sessionId}")
    @SendTo("/topic/session/{sessionId}/cursor")
    public Map<String, Object> broadcastCursor(@DestinationVariable String sessionId,
                                               @Payload Map<String, Object> payload) {
        return payload;
    }

}
