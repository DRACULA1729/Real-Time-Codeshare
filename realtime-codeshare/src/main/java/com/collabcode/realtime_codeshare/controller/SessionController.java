package com.collabcode.realtime_codeshare.controller;

import com.collabcode.realtime_codeshare.dto.JoinRequestDTO;
import com.collabcode.realtime_codeshare.model.User;
import com.collabcode.realtime_codeshare.service.SessionService;

import com.collabcode.realtime_codeshare.security.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Random;


@Controller

public class SessionController {

    @Autowired
    private SessionService sessionService;

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/create")
    @ResponseBody
    public ResponseEntity<?> createSession(@RequestBody JoinRequestDTO dto){
        User creator= new User(dto.getName(), dto.getIpAddress(), Role.CREATOR, randomColor());
        SessionService sessionService= new SessionService();
        sessionService.createSession(dto.getSessionId(), creator);
        return ResponseEntity.ok("Session created successfully!");
    }
    @PostMapping("/join-request")
    @ResponseBody
    public ResponseEntity<?> joinRequest( @RequestBody JoinRequestDTO dto){
        User joiner= new User(dto.getName(), dto.getIpAddress(), Role.JOINER, randomColor());
        boolean success= sessionService.submitJoinRequest(dto.getSessionId(), joiner);
        if(success){
            return ResponseEntity.ok("Request Submitted!");
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Request Denied!");
    }
   private String randomColor() {
            String[] colors = {"#e6194b", "#3cb44b", "#ffe119", "#4363d8", "#f58231", "#911eb4", "#46f0f0"};
            return colors[new Random().nextInt(colors.length)];
        }
}
