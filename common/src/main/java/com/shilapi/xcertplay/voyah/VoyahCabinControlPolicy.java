package com.shilapi.xcertplay.voyah;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Absolute operations, separate from the native bar's cycle commands. Null means no writes. */
public final class VoyahCabinControlPolicy {
    private VoyahCabinControlPolicy() {}
    public static final class Step {
        public final String field;
        public final int value;
        Step(String field,int value){this.field=field;this.value=value;}
    }
    private static boolean valid(Map<String,Integer> states,String key,int min,int max){
        Integer v=states.get(key);return v!=null && v>=min && v<=max;
    }
    public static List<Step> plan(String command,int value,Map<String,Integer> states){
        List<Step> result=new ArrayList<>();
        switch(command){
            case "ambient-switch":
                if(value<0||value>1||!valid(states,"AMBIENT_SWITCH",1,2))return null;
                result.add(new Step("AMBIENT_SWITCH",value+1));break;
            case "ambient-brightness":case "ambient-color":{
                String field=command.equals("ambient-color")?"AMBIENT_COLOR":"AMBIENT_BRIGHTNESS";
                int max=command.equals("ambient-color")?64:10;
                // The OEM voice implementation rejects manual brightness in music rhythm mode.
                // Preserve automatic effects; changing a slider never silently disables them.
                if(value<1||value>max||!valid(states,field,1,max)||!Integer.valueOf(1).equals(states.get("AMBIENT_MUSIC")))return null;
                result.add(new Step(field,value));break;
            }
            case "aroma-level":
                if(value<0||value>3||!valid(states,"AROMA_SWITCH",1,2))return null;
                if(value==0)result.add(new Step("AROMA_SWITCH",1));
                else {
                    if(!valid(states,"AROMA_INTENSITY",1,3))return null;
                    result.add(new Step("AROMA_INTENSITY",value));
                    result.add(new Step("AROMA_SWITCH",2));
                }
                break;
            case "aroma-select":
                if(value<1||value>3||!valid(states,"AROMA_TASTE",1,3))return null;
                result.add(new Step("AROMA_TASTE",value));break;
            case "aroma-duration":
                if(value<0||value>2||!valid(states,"AROMA_TIME",0,2))return null;
                result.add(new Step("AROMA_TIME",value));break;
            default:return null;
        }
        return result;
    }
}
