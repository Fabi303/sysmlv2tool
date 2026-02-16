package SystemModel {

    public import ProjectRequirements::*; // Import all requirement packages for reference
    public import ProjectRequirements::FunctionalSafetyConcept::*;
    doc /**
     * SystemModel Package
     * This package defines the system architecture and components that reference the generated requirements.
     * It serves as a minimal demonstration of how the generated requirements can be integrated into a system model.
     */
    doc /**
     * Minimal dummy system model referencing generated requirements for verification.
     */
    part def BatteryControllerDefinition {
        doc /** Top-level system definition. */
    }
        
    package Components {
        doc /** Simple components that reference requirements by fully-qualified names. */

        part myBatteryController : BatteryControllerDefinition {
            doc /** Satisfies FSC001 (system-level) and TSC001 (technical-level). */
            satisfy ProjectRequirements::FunctionalSafetyConcept::FSC001;
            satisfy ProjectRequirements::TechnicalSafetyConcept::TSC001;
        }

        requirement TemperatureMonitor {
            doc /** Satisfies the TSC001 technical requirement and SWS001 software requirement. */
            attribute satisfies_tsc = "Requirements::TechnicalSafetyConcept::TSC001";
            attribute satisfies_sws = "Requirements::SoftwareRequirements::SWS001";
        }

        requirement ADCInput {
            doc /** Satisfies HWS001 hardware requirement for ADC input conditioning. */
            attribute satisfies_hws = "Requirements::HardwareRequirements::HWS001";
        }
    }
}
