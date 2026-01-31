package com.example.lms.service.guard;


import java.util.regex.Pattern;
import java.util.regex.*;
public class PIISanitizer {
    public String mask(String text){
        if (text==null) return null;
        String t = text;
        t = t.replaceAll("[0-9]{3}-[0-9]{4}-[0-9]{4}", "***-****-****");
        t = t.replaceAll("[0-9]{2,3}-[0-9]{3,4}-[0-9]{4}", "***-****-****");
        t = t.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "***@***");
        return t;
    }
}