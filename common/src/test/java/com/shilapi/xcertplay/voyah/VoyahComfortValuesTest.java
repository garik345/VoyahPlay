package com.shilapi.xcertplay.voyah;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoyahComfortValuesTest {
    @Test public void retainedLevelDoesNotMeanSwitchedOn() {
        for(int level=1;level<=3;level++) assertEquals("выкл",VoyahComfortValues.level(1,level));
        assertEquals("нет данных о включении",VoyahComfortValues.level(-1,3));
        assertEquals("●●● 3/3",VoyahComfortValues.level(2,3));
        assertEquals("вкл · уровень неизвестен",VoyahComfortValues.level(2,-1));
    }
    @Test public void snapshotIsCopiedAndFeedbackIsNotReplacedByRequest() {
        Map<String,Integer> fields=new HashMap<>();
        fields.put("IVI_FRAG_TASTE",2);fields.put("IVI_FRAG_TASTE_FB",3);
        fields.put("STEER_WHEEL_HEAT_SWITCH",2);
        VoyahComfortValues values=new VoyahComfortValues(fields,true);
        fields.put("IVI_FRAG_TASTE",1);
        assertTrue(values.display().contains("Выбранный аромат: 2"));
        assertTrue(values.display().contains("аромат=3"));
        assertTrue(values.display().contains("Обратная связь руля: нет данных"));
        assertTrue(values.display().contains("Ближний: нет данных"));
    }
    @Test public void comfortAlsoExpiresWithVehicleSnapshot() {
        VoyahValues v=new VoyahValues(50,30,22,22,1,1000,null,null,
            new VoyahComfortValues(new HashMap<>(),true));
        assertTrue(v.display(2000).contains("Водитель"));
        assertEquals("Данные не обновляются",v.display(7001));
    }
}
