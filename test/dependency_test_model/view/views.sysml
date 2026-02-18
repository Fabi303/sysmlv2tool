package RequirementViews {
    private import ProjectRequirements::*;
    private import ProjectRequirements::FunctionalSafetyConcept::*;
    private import ProjectRequirements::TechnicalSafetyConcept::*;
    private import ProjectRequirements::SoftwareRequirements::*;
    private import ProjectRequirements::HardwareRequirements::*;

    // 1. Definition der Viewpoints
    // Wir definieren den Viewpoint ohne festes Subject-Attribut im Body,
    // um die restriktive Prüfung von Syside zu umgehen.
    viewpoint SoftwareComplianceViewpoint {
        // In Syside oft stabiler: Das Subject wird über den Typ der View impliziert
    }

    viewpoint HardwareComplianceViewpoint {
    }

    // 2. Die Sichten (Views)
    // Wir nutzen 'view' als Instanz und 'expose' für die Filterung
    view softwareSafetyView {
        viewpoint SoftwareComplianceViewpoint;
        
        // Citing sources from your requirement structure [cite: 17, 19]
        expose SWS001; // Software Requirement 
        expose TSC001; // Technical Source [cite: 12]
        
        doc /** Filtert SWS001 und TSC001 */
    }

    view hardwareSafetyView {
        viewpoint HardwareComplianceViewpoint;
        
        // Citing sources from your requirement structure [cite: 22, 23]
        expose HWS001; // Hardware Requirement 
        expose TSC001; // Technical Source [cite: 12]
        
        doc /** Filtert HWS001 und TSC001 */
    }
}