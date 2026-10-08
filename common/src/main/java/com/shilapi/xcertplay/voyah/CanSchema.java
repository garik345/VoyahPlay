package com.shilapi.xcertplay.voyah;

/** Wire layouts from the exact user-supplied CAN APK; no OEM executable code bundled. */
final class CanSchema {
    static final class Spec {
        final int code; final String method; final boolean nullable; final String[] fields; final String types;
        Spec(int c,String m,boolean n,String f,String t){code=c;method=m;nullable=n;fields=f.split(",");types=t;}
    }
    static final Spec[] READS={
        new Spec(71,"getBatteryRemainingCapacity",false,"value","f"),
        new Spec(9,"getFuelLevel",true,"mCapacity,mRemain,mPercentage,isFuelShortage,mInstantaneousFuelConsumption,mAvgFuelConsumption,mHistoryAvgFuelConsumption","iififff"),
        new Spec(30,"getAirCondition",true,"airSWStatus,airACStatus,airHighWindStatus,airLowWindStatus,airDUALStatus,airDUALAllStatus,airMaxFrontStatus,airRearLightStatus,airSupplyStatus,airDisplaySW,airWindSpeed,airLeftTemperature,airRightTemperature,airRearTemperature,airCirculationMode,airLeftSeatHeatingLevel,airRearCtlLockSW,airACMaxSW,airRightSeatHeatingLevel,airRearWindowHeatingStatus,airECO,airFrontWindowDefogger,airMode,airCirculationStatus,airRearWindSpeed,airRearDownSupplyStatus,airRearParallelSupplyStatus,airRearUpSupplyStatus,airRearWindowDefogger,airLeftMirrorDefogger,airRightMirrorDefogger,airSyncStatus,airDisplay_mode,IGN_ON,airTempInCar,airTempOutCar,ions_state,ions_switch,ac_power_switch,airACHeatStatus,airTempLevel,airCompressorErrorStatus,airCompressorLimitStatus,airCompressorWorkStatus,airPTCErrorStatus,airPTCLimitStatus,airPTCWorKStatus,airEngineStatus,airPm2_5,acRunningState,airAQSStatus,airPMStatus,airPreSupplyStatus,acRapidCooling,acOneButtonWarmth,acHazeMode,acBabyCareMode,acAirCleanerMode,acRainSnowMode,acSmokingMode,acStopCarMode,displaypop,airPm25_level,airLeftTemperature_f,airRightTemperature_f,airPm2_5_outcar,airPm2_5_sts,airworking_mode,acAirVentCoolSts,airFrontSWStatus,airRearSWStatus,airFrontMode,airRearMode,airFrontSupplyStatus,airRearSupplyStatus,airFrontWindSpeed,isNeedRefreshAirWorkingMode,isAirPM2_5NeedRefresh,isAirPM2_5OutCarNeedRefresh,airDORSwitch,airDORMode","iiiiiiiiiiifffiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiffiiiiiiiiiiiiiiii"),
        new Spec(2,"getDoorStatus",true,"bonnetDoor,fLDoor,fRDoor,loadSpace,rLDoor,rRDoor,fLDoorLockStatus,fRDoorLockStatus,rLDoorLockStatus,rRDoorLockStatus","iiiiiiiiii"),
        new Spec(44,"getWindowStatus",true,"fLWindow,fRWindow,rLWindow,rRWindow,sunroof,roll,sunroofPosition,rollPosition,fLWindowPosition,fRWindowPosition,rLWindowPosition,rRWindowPosition,sunroofTilt,fLWindowHotProtect,fRWindowHotProtect,rLWindowHotProtect,rRWindowHotProtect,fLWindowLearn,fRWindowLearn,rLWindowLearn,rRWindowLearn","iiiiiiiiiiiiiiiiiiiii"),
    };
    static Spec byCode(int code){for(Spec s:READS)if(s.code==code)return s;throw new IllegalArgumentException("Not a read-only allowed transaction");}
}
