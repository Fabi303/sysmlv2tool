package ProjectRequirements {
    doc /**
     * Requirements package for 26262 compliance demonstrator.
     * Structure reflects: FSC -> TSC -> {SWS, HWS}
     * Generated from Doorstop requirements.
     */

    // Import standard library types (explicit visibility)
    private import ScalarValues::String;
    private import ScalarValues::Boolean;

    /**
     * Base SafetyRequirement definition for all domain-specific requirements.
     */
    abstract requirement SafetyRequirement {
        doc /**
         * Base requirement type for safety-critical requirements
         * per ISO 26262 compliance.
         */
        attribute text : String;
        attribute level : String;
        attribute active : Boolean;
    }
    
    // Import subpackages for cross-package reference resolution    public import FunctionalSafetyConcept::*;
    public import TechnicalSafetyConcept::*;
    public import SoftwareRequirements::*;
    public import HardwareRequirements::*;
    package FunctionalSafetyConcept {
        doc /**
         * FunctionalSafetyConcept Package
         */

    public requirement <'FSC001'> FSC001 {
            doc /**
             * FSC001: Das System muss den Leistungsfluss der Batterie innerhalb von 100ms unterbrechen, wenn die Zelltemperatur 65°C überschreitet.
             */
            :> SafetyRequirement;
            attribute active = "True";
            attribute meta_derived = "False";
            attribute header = "";
            attribute level = "1.0";
            attribute normative = "True";
            attribute meta_ref = "";
            attribute reviewed = "";
        }

    }

    package TechnicalSafetyConcept {
        doc /**
         * TechnicalSafetyConcept Package
         */
    private import FunctionalSafetyConcept::*;

    public requirement <'TSC001'> TSC001 {
            doc /**
             * TSC001: Die Überwachung erfolgt durch einen NTC-Sensor am ADC-Eingang. Bei Schwellenwertüberschreitung muss der digitale Ausgang 'SAFE_STATE' auf LOW gesetzt werden.
             */
            :> SafetyRequirement;
            dependency derivation from TSC001 to FunctionalSafetyConcept::FSC001;
            attribute active = "True";
            attribute meta_derived = "False";
            attribute header = "";
            attribute level = "1.0";
            attribute normative = "True";
            attribute meta_ref = "";
            attribute reviewed = "";
        }

    }

    package SoftwareRequirements {
        doc /**
         * SoftwareRequirements Package
         */
    private import TechnicalSafetyConcept::*;

    public requirement <'SWS001'> SWS001 {
            doc /**
             * SWS001: Die Funktion check_temp_limit() liest den Rohwert vom ADC-Kanal. Der Vergleich erfolgt gegen MAX_TEMP_RAW.
             */
            :> SafetyRequirement;
            dependency derivation from SWS001 to TechnicalSafetyConcept::TSC001;
            attribute active = "True";
            attribute asil_level = "";
            attribute meta_derived = "False";
            attribute header = "";
            attribute header_param = "";
            attribute level = "1.0";
            attribute normative = "True";
            attribute meta_ref = "";
            attribute reviewed = "";
            attribute sysml_port = "";
        }

    }

    package HardwareRequirements {
        doc /**
         * HardwareRequirements Package
         */
    private import TechnicalSafetyConcept::*;

    public requirement <'HWS001'> HWS001 {
            doc /**
             * HWS001: Der ADC-Eingang muss mit einem RC-Tiefpassfilter (Grenzfrequenz 10Hz) beschaltet sein.
             */
            :> SafetyRequirement;
            dependency derivation from HWS001 to TechnicalSafetyConcept::TSC001;
            attribute active = "True";
            attribute component = "";
            attribute meta_derived = "False";
            attribute header = "";
            attribute level = "1.0";
            attribute normative = "True";
            attribute meta_ref = "";
            attribute reviewed = "";
            attribute voltage_rating = "";
        }

    }

}