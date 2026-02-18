package IntegratedSystemExample {
    private import ScalarValues::*;
    
    abstract requirement def SafetyRequirement {
        attribute level : String;
        attribute active : Boolean;
    }

    requirement fsc001 : SafetyRequirement {
        doc /** FSC001: Unterbrechung Leistungsfluss bei >65°C */
        attribute level = "1.0";
    }

    requirement tsc001 : SafetyRequirement {
        doc /** TSC001: Überwachung via NTC und SAFE_STATE Ausgang */
        dependency derivation from tsc001 to fsc001;
    }

    requirement sws001 : SafetyRequirement {
        doc /** SWS001: Logik check_temp_limit() */
        dependency derivation from sws001 to tsc001;
    }

    requirement hws001 : SafetyRequirement {
        doc /** HWS001: RC-Tiefpassfilter 10Hz  */
        dependency derivation from hws001 to tsc001;
    }

    // --- TEIL 2: Systemstruktur (Parts) ---

    part def BatteryUnit;
    part def Controller;
    part def Sensor;

    part batterySystem : BatteryUnit {
        part bmsController : Controller;
        part tempSensor : Sensor;
    }

    // --- TEIL 3: Viewpoints & Views ---

    viewpoint SoftwareViewpoint;
    viewpoint HardwareViewpoint;

    // View für Software-Entwickler
    view softwareView {
        viewpoint SoftwareViewpoint;
        
        // Zeigt nur die Software-Anforderung und den Controller
        expose sws001;
        expose tsc001;
        expose batterySystem;
    }

    // View für Hardware-Entwickler
    view hardwareView {
        viewpoint HardwareViewpoint;
        
        // Zeigt nur die Hardware-Anforderung und den Sensor
        expose hws001;
        expose tsc001;
        expose batterySystem;
    }
}