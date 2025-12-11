
package com.example.lms.planning.artplate;

import java.util.Map;



public class ArtPlate {
    public final String id;
    public final String name;
    public final Map<String,Object> params;

    public ArtPlate(String id, String name, Map<String,Object> params) {
        this.id = id;
        this.name = name;
        this.params = params;
    }
}