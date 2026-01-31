package com.abandonware.ai.agent.web;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
@RestController
public class RoomController {
    @GetMapping("/health")
    public String health(){ return "ok"; }
}