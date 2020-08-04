package uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd.definition;

public class CommonCaseFields {

    private CommonCaseFields() {
        // hiding the constructor - this class is for constants only
    }

    // reference to the exception record
    public static final String BULK_SCAN_CASE_REFERENCE = "bulkScanCaseReference";

    // a collection of references to envelopes that affected the case
    public static final String BULK_SCAN_ENVELOPES = "bulkScanEnvelopes";
}
