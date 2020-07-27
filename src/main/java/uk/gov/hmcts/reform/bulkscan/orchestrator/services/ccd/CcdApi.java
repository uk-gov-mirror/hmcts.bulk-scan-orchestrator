package uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.bulkscan.orchestrator.config.ServiceConfigItem;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd.events.UnableToAttachDocumentsException;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.config.ServiceConfigProvider;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.Event;
import uk.gov.hmcts.reform.ccd.client.model.SearchResult;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nonnull;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.reform.bulkscan.orchestrator.logging.FeignExceptionLogger.debugCcdException;

/**
 * This class is intended to be a wrapper/adaptor/facade for the orchestrator -> CcdApi.
 * In theory this should make the calls to ccd both easier to manage and quicker to refactor.
 */

@Component
public class CcdApi {

    public static final Logger log = LoggerFactory.getLogger(CcdApi.class);

    public static final String SEARCH_BY_LEGACY_ID_QUERY_FORMAT =
        "{\"query\": { \"match_phrase\" : { \"alias.previousServiceCaseReference\" : \"%s\" }}}";

    public static final String SEARCH_BY_ENVELOPE_ID_QUERY_FORMAT =
        "{\"query\": { \"match_phrase\" : { \"data.envelopeId\" : \"%s\" }}}";

    public static final String SEARCH_BY_BULK_SCAN_CASE_REFERENCE_QUERY_FORMAT =
        "{\"query\": { \"match_phrase\" : { \"data.bulkScanCaseReference\" : \"%s\" }}}";

    private final CoreCaseDataApi feignCcdApi;
    private final CcdAuthenticatorFactory authenticatorFactory;
    private final ServiceConfigProvider serviceConfigProvider;

    public CcdApi(
        CoreCaseDataApi feignCcdApi,
        CcdAuthenticatorFactory authenticator,
        ServiceConfigProvider serviceConfigProvider
    ) {
        this.feignCcdApi = feignCcdApi;
        this.authenticatorFactory = authenticator;
        this.serviceConfigProvider = serviceConfigProvider;
    }

    private SearchResult searchCases(
        String jurisdiction,
        String caseType,
        String searchString
    ) {
        CcdAuthenticator authenticator = authenticatorFactory.createForJurisdiction(jurisdiction);
        try {
            return feignCcdApi.searchCases(
                authenticator.getUserToken(),
                authenticator.getServiceToken(),
                caseType,
                searchString
            );
        } catch (FeignException ex) {
            debugCcdException(log, ex, "Failed to call 'searchCases'");
            removeFromIdamCacheIfAuthProblem(ex.status(), jurisdiction);
            throw ex;
        }
    }

    private StartEventResponse startAttachScannedDocs(
        String caseRef,
        String serviceToken,
        String idamToken,
        String userId,
        String jurisdiction,
        String caseTypeId
    ) {
        return feignCcdApi.startEventForCaseWorker(
            idamToken,
            serviceToken,
            userId,
            jurisdiction,
            caseTypeId,
            caseRef,
            EventIds.ATTACH_SCANNED_DOCS
        );
    }

    @Nonnull
    StartEventResponse startAttachScannedDocs(CaseDetails theCase, String idamToken, String userId) {
        String caseRef = String.valueOf(theCase.getId());
        try {
            //TODO We don't need to login here as we just need the service token
            CcdAuthenticator authenticator = authenticatorFactory.createForJurisdiction(theCase.getJurisdiction());
            StartEventResponse response = startAttachScannedDocs(
                caseRef,
                authenticator.getServiceToken(),
                idamToken,
                userId,
                theCase.getJurisdiction(),
                theCase.getCaseTypeId()
            );

            log.info(
                "Started event to attach docs to case. "
                    + "Event ID: {}. Case ID: {}. Case type: {}. Jurisdiction: {}. Case state: {}",
                EventIds.ATTACH_SCANNED_DOCS,
                caseRef,
                theCase.getCaseTypeId(),
                theCase.getJurisdiction(),
                theCase.getState()
            );

            return response;
        } catch (FeignException e) {
            debugCcdException(log, e, "Failed to call 'startAttachScannedDocs'");
            throw new CcdCallException(
                format("Internal Error: start event call failed case: %s Error: %s", caseRef, e.status()), e
            );
        }
    }

    @Nonnull
    public CaseDetails getCase(String caseRef, String jurisdiction) {
        CcdAuthenticator authenticator = authenticatorFactory.createForJurisdiction(jurisdiction);

        try {
            return feignCcdApi.getCase(authenticator.getUserToken(), authenticator.getServiceToken(), caseRef);
        } catch (FeignException e) {
            debugCcdException(log, e, "Failed to call 'getCase'");
            removeFromIdamCacheIfAuthProblem(e.status(), jurisdiction);

            switch (e.status()) {
                case 404:
                    throw new CaseNotFoundException("Could not find case: " + caseRef, e);
                case 400:
                    throw new InvalidCaseIdException("Invalid case ID: " + caseRef, e);
                default:
                    throw new CcdCallException(
                        format("Internal Error: Could not retrieve case: %s Error: %s", caseRef, e.status()),
                        e
                    );
            }
        }
    }

    public List<Long> getCaseRefsByLegacyId(String legacyId, String service) {
        ServiceConfigItem serviceConfig = serviceConfigProvider.getConfig(service);

        if (serviceConfig.getCaseTypeIds().isEmpty()) {
            log.info(
                "Skipping case search by legacy ID ({}) for service {} because it has no case type ID configured",
                legacyId,
                service
            );

            return emptyList();
        } else {
            String jurisdiction = serviceConfig.getJurisdiction();
            String caseTypeIdsStr = String.join(",", serviceConfig.getCaseTypeIds());

            SearchResult searchResult = searchCases(
                jurisdiction,
                caseTypeIdsStr,
                format(SEARCH_BY_LEGACY_ID_QUERY_FORMAT, legacyId)
            );

            return searchResult
                .getCases()
                .stream()
                .map(CaseDetails::getId)
                .collect(toList());
        }
    }

    public List<Long> getExceptionRecordRefsByEnvelopeId(String envelopeId, String service) {
        ServiceConfigItem serviceConfig = serviceConfigProvider.getConfig(service);
        final String caseTypeIdsStr = format("%s_ExceptionRecord", service.toUpperCase());
        final String searchQuery = format(SEARCH_BY_ENVELOPE_ID_QUERY_FORMAT, envelopeId);

        return getCaseRefs(serviceConfig, caseTypeIdsStr, searchQuery);
    }

    public List<Long> getCaseRefsByBulkScanCaseReference(String bulkScanCaseReference, String service) {
        // 'bulkScanCaseReference' is the reference which SSCS is using to map
        // from which exception record service case was created
        ServiceConfigItem serviceConfig = serviceConfigProvider.getConfig(service);
        final String caseTypeIdsStr = String.join(",", serviceConfig.getCaseTypeIds());
        final String searchQuery = format(SEARCH_BY_BULK_SCAN_CASE_REFERENCE_QUERY_FORMAT, bulkScanCaseReference);

        return getCaseRefs(serviceConfig, caseTypeIdsStr, searchQuery);
    }

    public void attachExceptionRecord(
        CaseDetails theCase,
        String idamToken,
        String userId,
        Map<String, Object> data,
        String eventSummary,
        StartEventResponse event
    ) {
        String caseRef = String.valueOf(theCase.getId());
        String jurisdiction = theCase.getJurisdiction();
        String caseTypeId = theCase.getCaseTypeId();
        try {
            //TODO We don't need to login here as we just need the service token
            CcdAuthenticator authenticator = authenticatorFactory.createForJurisdiction(jurisdiction);
            attachCall(
                caseRef,
                authenticator.getServiceToken(),
                idamToken,
                userId,
                data,
                event.getToken(),
                jurisdiction,
                caseTypeId,
                Event.builder().summary(eventSummary).id(event.getEventId()).build()
            );
        } catch (FeignException e) {
            debugCcdException(log, e, "Failed to call 'attachCall' - `submitEventForCaseWorker`");
            throw new CcdCallException(
                format("Internal Error: submitting attach file event failed case: %s Error: %s", caseRef, e.status()),
                e
            );
        }
    }

    private List<Long> getCaseRefs(
        ServiceConfigItem serviceConfig,
        String caseTypeIdsStr,
        String searchQuery
    ) {
        String jurisdiction = serviceConfig.getJurisdiction();
        SearchResult searchResult = searchCases(
            jurisdiction,
            caseTypeIdsStr,
            searchQuery
        );

        return searchResult
            .getCases()
            .stream()
            .map(CaseDetails::getId)
            .collect(toList());
    }

    private void attachCall(
        String caseRef,
        String serviceToken,
        String idamToken,
        String userId,
        Map<String, Object> data,
        String eventToken,
        String jurisdiction,
        String caseTypeId,
        Event eventInfo
    ) {
        feignCcdApi.submitEventForCaseWorker(
            idamToken,
            serviceToken,
            userId,
            jurisdiction,
            caseTypeId,
            caseRef,
            true,
            CaseDataContent.builder()
                .data(data)
                .event(eventInfo)
                .eventToken(eventToken)
                .build()
        );
    }

    public CcdAuthenticator authenticateJurisdiction(String jurisdiction) {
        return authenticatorFactory.createForJurisdiction(jurisdiction);
    }

    // ideally should be more generic. atm just used in creating new exception record as auto event
    public CaseDetails createExceptionRecord(
        CcdAuthenticator authenticator,
        String jurisdiction,
        String caseTypeId,
        String eventTypeId,
        Function<StartEventResponse, CaseDataContent> caseDataContentBuilder,
        String logContext
    ) {
        try {
            StartEventResponse eventResponse = feignCcdApi.startForCaseworker(
                authenticator.getUserToken(),
                authenticator.getServiceToken(),
                authenticator.getUserDetails().getId(),
                jurisdiction,
                caseTypeId,
                eventTypeId
            );

            log.info("Started event in CCD. Event: {}, case type: {}. {}", eventTypeId, caseTypeId, logContext);

            CaseDataContent caseData = caseDataContentBuilder.apply(eventResponse);

            return feignCcdApi.submitForCaseworker(
                authenticator.getUserToken(),
                authenticator.getServiceToken(),
                authenticator.getUserDetails().getId(),
                jurisdiction,
                caseTypeId,
                true,
                caseData
            );
        } catch (FeignException ex) {
            debugCcdException(log, ex, "Failed to call 'createExceptionRecord'");
            removeFromIdamCacheIfAuthProblem(ex.status(), jurisdiction);
            throw ex;
        }
    }

    // ideally should be more generic. need custom exception handling
    public void attachScannedDocs(
        CcdAuthenticator authenticator,
        String jurisdiction,
        String caseTypeId,
        String caseRef,
        String eventTypeId,
        Function<StartEventResponse, CaseDataContent> caseDataContentBuilder,
        String logContext
    ) {
        try {
            StartEventResponse eventResponse = feignCcdApi.startEventForCaseWorker(
                authenticator.getUserToken(),
                authenticator.getServiceToken(),
                authenticator.getUserDetails().getId(),
                jurisdiction,
                caseTypeId,
                caseRef,
                eventTypeId
            );

            log.info("Started event in CCD. Event: {}, case type: {}. {}", eventTypeId, caseTypeId, logContext);

            CaseDataContent caseData = caseDataContentBuilder.apply(eventResponse);

            feignCcdApi.submitEventForCaseWorker(
                authenticator.getUserToken(),
                authenticator.getServiceToken(),
                authenticator.getUserDetails().getId(),
                jurisdiction,
                caseTypeId,
                caseRef,
                true,
                caseData
            );
        } catch (FeignException.NotFound e) {
            throw new UnableToAttachDocumentsException(
                String.format(
                    "Event failed. Event: %s, case type: %s, case ref: %s", eventTypeId, caseTypeId, caseRef
                ),
                e
            );
        } catch (FeignException e) {
            debugCcdException(log, e, "Failed to call 'attachScannedDocs'");
            removeFromIdamCacheIfAuthProblem(e.status(), jurisdiction);

            throw new CcdCallException(
                String.format("Could not attach documents for case ref: %s Error: %s", caseRef, e.status()), e
            );
        }
    }

    public long createNewCaseFromCallback(
        String idamToken,
        String s2sToken,
        String userId,
        String jurisdiction,
        String caseTypeId,
        String eventId,
        Function<StartEventResponse, CaseDataContent> caseDataContentBuilder,
        String logContext
    ) {
        try {
            StartEventResponse eventResponse = feignCcdApi.startForCaseworker(
                idamToken,
                s2sToken,
                userId,
                jurisdiction,
                caseTypeId,
                eventId
            );

            log.info("Started event in CCD. Event: {}, case type: {}. {}", eventId, caseTypeId, logContext);

            return feignCcdApi.submitForCaseworker(
                idamToken,
                s2sToken,
                userId,
                jurisdiction,
                caseTypeId,
                true,
                caseDataContentBuilder.apply(eventResponse)
            ).getId();
        } catch (FeignException exception) {
            debugCcdException(log, exception, "Failed to call 'createNewCaseFromCallback'");

            throw exception;
        }
    }

    private void removeFromIdamCacheIfAuthProblem(int status, String jurisdiction) {
        if (status == 403 || status == 401) {
            authenticatorFactory.removeFromCache(jurisdiction);
        }
    }
}
