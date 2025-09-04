
// src/main/java/com/example/lms/web/AttendanceController.java
package com.example.lms.web;

import com.example.lms.domain.Status;
import com.example.lms.service.AttendanceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Controller
@RequestMapping("/attendances")
public class AttendanceController {
    private final AttendanceService service;
    public AttendanceController(AttendanceService service) {
        this.service = service;
    }

    @PostMapping("/record")
    public String record(@RequestParam Long studentId,
                         @RequestParam Long courseId,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @RequestParam Status status) {
        service.record(studentId, courseId, date, status);
        return "redirect:/attendances/course/" + courseId;
    }

    @PostMapping("/calculate/{courseId}")
    public String calculate(@PathVariable Long courseId) {
        service.calculateGrades(courseId);
        return "redirect:/courses/" + courseId + "?msg=gradesCalculated";
    }
}
