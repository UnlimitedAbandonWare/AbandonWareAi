package com.abandonwareai.resilience.cfvm;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;

@RestController
@RequestMapping("/internal")
public class CfvmController {
    @GetMapping("/cfvm/ping") public ResponseEntity<String> ping(){ return ResponseEntity.ok("cfvm-ok"); }

}