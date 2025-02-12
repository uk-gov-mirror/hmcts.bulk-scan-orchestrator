package uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.reform.bulkscan.orchestrator.config.ServiceConfigItem;
import uk.gov.hmcts.reform.bulkscan.orchestrator.model.in.CcdCallbackRequest;
import uk.gov.hmcts.reform.bulkscan.orchestrator.model.internal.ExceptionRecord;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd.callback.CallbackException;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd.callback.CreateCaseResult;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd.callback.ExceptionRecordValidator;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd.callback.ProcessResult;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd.definition.ExceptionRecordFields;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd.definition.YesNoFieldValues;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.config.ServiceConfigProvider;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.config.ServiceNotConfiguredException;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.servicebus.domains.payments.PaymentsPublishingException;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

import java.util.HashMap;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd.CcdCallbackType.CASE_CREATION;
import static uk.gov.hmcts.reform.bulkscan.orchestrator.services.servicebus.domains.envelopes.model.Classification.EXCEPTION;
import static uk.gov.hmcts.reform.bulkscan.orchestrator.services.servicebus.domains.envelopes.model.Classification.NEW_APPLICATION;
import static uk.gov.hmcts.reform.bulkscan.orchestrator.services.servicebus.domains.envelopes.model.Classification.SUPPLEMENTARY_EVIDENCE;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("checkstyle:LineLength")
class CreateCaseCallbackServiceTest {

    private static final String IDAM_TOKEN = "idam-token";
    private static final String USER_ID = "user-id";
    private static final String SERVICE = "service";
    private static final String CASE_ID = "123";
    private static final String CASE_TYPE_ID = SERVICE + "_ExceptionRecord";

    // TODO: mock this!
    private static final ExceptionRecordValidator VALIDATOR = new ExceptionRecordValidator();

    @Mock ServiceConfigProvider serviceConfigProvider;
    @Mock CaseFinder caseFinder;
    @Mock CcdNewCaseCreator ccdNewCaseCreator;
    @Mock ExceptionRecordFinalizer exceptionRecordFinalizer;
    @Mock PaymentsProcessor paymentsProcessor;

    private CreateCaseCallbackService service;

    @BeforeEach
    void setUp() {
        service = new CreateCaseCallbackService(
            VALIDATOR,
            serviceConfigProvider,
            caseFinder,
            ccdNewCaseCreator,
            exceptionRecordFinalizer,
            paymentsProcessor
        );
    }

    @Test
    void should_not_allow_to_process_callback_in_case_wrong_event_id_is_received() {
        CallbackException callbackException = catchThrowableOfType(() ->
            service.process(new CcdCallbackRequest(
                "some event",
                null,
                true
            ), IDAM_TOKEN, USER_ID),
            CallbackException.class
        );

        assertThat(callbackException.getCause()).isNull();
        assertThat(callbackException).hasMessage("The some event event is not supported. Please contact service team");

        verify(serviceConfigProvider, never()).getConfig(anyString());
        verify(exceptionRecordFinalizer, never()).finalizeExceptionRecord(anyMap(), anyString(), any());
    }

    @Test
    void should_not_allow_to_process_callback_when_case_type_id_is_missing() {
        // given
        CaseDetails caseDetails = TestCaseBuilder.createCaseWith(builder -> builder.id(1L));

        // when
        CallbackException callbackException = catchThrowableOfType(() ->
            service.process(new CcdCallbackRequest(
                EventIds.CREATE_NEW_CASE,
                caseDetails,
                true
            ), IDAM_TOKEN, USER_ID),
            CallbackException.class
        );

        assertThat(callbackException.getCause()).isNull();
        assertThat(callbackException).hasMessage("No case type ID supplied");

        verify(serviceConfigProvider, never()).getConfig(anyString());
        verify(exceptionRecordFinalizer, never()).finalizeExceptionRecord(anyMap(), anyString(), any());
    }

    @Test
    void should_not_allow_to_process_callback_when_case_type_id_is_empty() {
        // given
        CaseDetails caseDetails = TestCaseBuilder.createCaseWith(builder -> builder.caseTypeId(""));

        // when
        CallbackException callbackException = catchThrowableOfType(() ->
            service.process(new CcdCallbackRequest(
                EventIds.CREATE_NEW_CASE,
                caseDetails,
                true
            ), IDAM_TOKEN, USER_ID),
            CallbackException.class
        );

        // then
        assertThat(callbackException.getCause()).isNull();
        assertThat(callbackException).hasMessage("Case type ID () has invalid format");

        verify(serviceConfigProvider, never()).getConfig(anyString());
        verify(exceptionRecordFinalizer, never()).finalizeExceptionRecord(anyMap(), anyString(), any());
    }

    @Test
    void should_not_allow_to_process_callback_in_case_service_not_configured() {
        // given
        doThrow(new ServiceNotConfiguredException("oh no")).when(serviceConfigProvider).getConfig(SERVICE);
        CaseDetails caseDetails = TestCaseBuilder.createCaseWith(builder -> builder.caseTypeId(CASE_TYPE_ID));

        // when
        CallbackException callbackException = catchThrowableOfType(() ->
            service.process(new CcdCallbackRequest(
                EventIds.CREATE_NEW_CASE,
                caseDetails,
                true
            ), IDAM_TOKEN, USER_ID),
            CallbackException.class
        );

        // then
        assertThat(callbackException.getCause()).isNull();
        assertThat(callbackException).hasMessage("oh no");

        verify(exceptionRecordFinalizer, never()).finalizeExceptionRecord(anyMap(), anyString(), any());
    }

    @Test
    void should_not_allow_to_process_callback_in_case_transformation_url_not_configured() {
        // given
        when(serviceConfigProvider.getConfig(SERVICE)).thenReturn(new ServiceConfigItem());
        CaseDetails caseDetails = TestCaseBuilder.createCaseWith(builder -> builder.caseTypeId(CASE_TYPE_ID));

        // when
        CallbackException callbackException = catchThrowableOfType(() ->
            service.process(new CcdCallbackRequest(
                EventIds.CREATE_NEW_CASE,
                caseDetails,
                true
            ), IDAM_TOKEN, USER_ID),
            CallbackException.class
        );

        // then
        assertThat(callbackException.getCause()).isNull();
        assertThat(callbackException).hasMessage("Transformation URL is not configured");

        verify(exceptionRecordFinalizer, never()).finalizeExceptionRecord(anyMap(), anyString(), any());
    }

    @Test
    void should_not_allow_to_process_callback_when_idam_token_is_missing() {
        // given
        setUpServiceConfig();

        CaseDetails caseDetails = TestCaseBuilder.createCaseWith(builder -> builder
            .id(Long.valueOf(CASE_ID))
            .caseTypeId(CASE_TYPE_ID)
            .jurisdiction("some jurisdiction")
        );

        // when
        CallbackException callbackException = catchThrowableOfType(() ->
            service.process(new CcdCallbackRequest(
                EventIds.CREATE_NEW_CASE,
                caseDetails,
                true
            ), null, USER_ID),
            CallbackException.class
        );

        // then
        assertThat(callbackException.getCause()).isNull();
        assertThat(callbackException).hasMessage("Callback has no Idam token received in the header");
    }

    @Test
    void should_not_allow_to_process_callback_when_user_id_is_missing() {
        // given
        setUpServiceConfig();

        CaseDetails caseDetails = TestCaseBuilder.createCaseWith(builder -> builder
            .id(Long.valueOf(CASE_ID))
            .caseTypeId(CASE_TYPE_ID)
            .jurisdiction("some jurisdiction")
        );

        // when
        CallbackException callbackException = catchThrowableOfType(() ->
            service.process(new CcdCallbackRequest(
                EventIds.CREATE_NEW_CASE,
                caseDetails,
                true
            ), IDAM_TOKEN, null),
            CallbackException.class
        );

        // then
        assertThat(callbackException.getCause()).isNull();
        assertThat(callbackException).hasMessage("Callback has no user id received in the header");
    }

    @Test
    void should_report_error_if_classification_new_application_with_documents_and_without_ocr_data() {
        // given
        setUpServiceConfig();

        Map<String, Object> data = new HashMap<>();
        // putting 6 via `ImmutableMap` is available from Java 9
        data.put("poBox", "12345");
        data.put("journeyClassification", NEW_APPLICATION.name());
        data.put("formType", "Form1");
        data.put("deliveryDate", "2019-09-06T15:30:03.000Z");
        data.put("openingDate", "2019-09-06T15:30:04.000Z");
        data.put("scannedDocuments", TestCaseBuilder.document("https://url", "some doc"));

        // when
        ProcessResult result = service.process(new CcdCallbackRequest(
            EventIds.CREATE_NEW_CASE,
            caseDetails(data),
            true
        ), IDAM_TOKEN, USER_ID);

        // then
        assertThat(result.getWarnings()).isEmpty();
        assertThat(result.getErrors()).containsOnly(
            String.format(
                "Event %s not allowed for the current journey classification %s without OCR",
                EventIds.CREATE_NEW_CASE,
                NEW_APPLICATION.name()
            )
        );

        verify(exceptionRecordFinalizer, never()).finalizeExceptionRecord(anyMap(), anyString(), any());
    }

    @Test
    void should_report_error_if_classification_is_supplementary_evidence() {
        // given
        setUpServiceConfig();

        Map<String, Object> data = basicCaseData();
        data.put("journeyClassification", SUPPLEMENTARY_EVIDENCE.name());

        // when
        ProcessResult result = service.process(new CcdCallbackRequest(
            EventIds.CREATE_NEW_CASE,
            caseDetails(data),
            true
        ), IDAM_TOKEN, USER_ID);

        // then
        assertThat(result.getWarnings()).isEmpty();
        assertThat(result.getErrors()).containsOnly(
            String.format(
                "Event %s not allowed for the current journey classification %s",
                EventIds.CREATE_NEW_CASE,
                SUPPLEMENTARY_EVIDENCE.name()
            )
        );

        verify(exceptionRecordFinalizer, never()).finalizeExceptionRecord(anyMap(), anyString(), any());
    }

    @Test
    void should_return_existing_case_if_it_exists_in_ccd_for_a_given_exception_record() throws Exception {
        // given
        setUpServiceConfig();

        when(caseFinder.findCases(any(), any()))
            .thenReturn(asList(345L));
        Map<String, Object> caseData = basicCaseData();
        Map<String, Object> finalizedCaseData = new HashMap<>();
        when(exceptionRecordFinalizer.finalizeExceptionRecord(caseData, "345", CASE_CREATION))
            .thenReturn(finalizedCaseData);

        // when
        ProcessResult result = service.process(new CcdCallbackRequest(
            EventIds.CREATE_NEW_CASE,
            caseDetails(caseData),
            true
        ), IDAM_TOKEN, USER_ID);

        // then
        assertThat(result.getExceptionRecordData()).isEqualTo(finalizedCaseData);
        assertThat(result.getWarnings()).isEmpty();
        assertThat(result.getErrors()).isEmpty();

        verify(exceptionRecordFinalizer).finalizeExceptionRecord(caseData, "345", CASE_CREATION);
    }

    @Test
    void should_return_error_if_multiple_cases_exist_in_ccd_for_a_given_exception_record() throws Exception {
        setUpServiceConfig();

        when(caseFinder.findCases(any(), any()))
            .thenReturn(asList(345L, 456L));

        assertThatThrownBy(
            () -> service.process(new CcdCallbackRequest(
                EventIds.CREATE_NEW_CASE,
                caseDetails(basicCaseData()),
                true
            ), IDAM_TOKEN, USER_ID)
        )
            .isInstanceOf(MultipleCasesFoundException.class)
            .hasMessage("Multiple cases (345, 456) found for the given bulk scan case reference: 123");

        verify(exceptionRecordFinalizer, never()).finalizeExceptionRecord(anyMap(), anyString(), any());
    }

    @ParameterizedTest(name = "Allowed to proceed: {0}. User ignores warnings: {1}")
    @CsvSource({
        "false, false",
        "false, true",
        "true, false",
        // {true, true} case tested separately - no warnings or errors are returned
    })
    void should_return_either_a_warning_or_error_when_payment_are_not_processed_based_on_service_config(
        boolean isAllowedToProceed,
        boolean ignoresWarnings
    ) {
        // given
        setUpServiceConfig("https://localhost", isAllowedToProceed);

        Map<String, Object> data = basicCaseData();
        data.put(ExceptionRecordFields.AWAITING_PAYMENT_DCN_PROCESSING, YesNoFieldValues.YES);

        // when
        ProcessResult result =
            service
                .process(
                    new CcdCallbackRequest(EventIds.CREATE_NEW_CASE, caseDetails(data), ignoresWarnings),
                    IDAM_TOKEN,
                    USER_ID
                );

        // then
        if (isAllowedToProceed) {
            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getWarnings()).containsExactly(CreateCaseCallbackService.AWAITING_PAYMENTS_MESSAGE);
        } else {
            assertThat(result.getErrors()).containsExactly(CreateCaseCallbackService.AWAITING_PAYMENTS_MESSAGE);
            assertThat(result.getWarnings()).isEmpty();
        }
    }

    @Test
    void should_allow_creating_case_when_payments_are_not_present_but_user_is_allowed_to_proceed_and_ignores_warnings() throws Exception {
        // given
        setUpServiceConfig("https://localhost", true); // allowed to create case despite pending payments

        Map<String, Object> data = basicCaseData();
        data.put(ExceptionRecordFields.AWAITING_PAYMENT_DCN_PROCESSING, YesNoFieldValues.YES);

        long newCaseId = 1;
        given(ccdNewCaseCreator.createNewCase(
            any(ExceptionRecord.class),
            any(ServiceConfigItem.class),
            anyBoolean(),
            anyString(),
            anyString()
        )).willReturn(new CreateCaseResult(newCaseId));

        // when
        ProcessResult result =
            service
                .process(
                    new CcdCallbackRequest(EventIds.CREATE_NEW_CASE, caseDetails(data), true), // ignore warnings
                    IDAM_TOKEN,
                    USER_ID
                );

        // then
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).isEmpty();
        verify(paymentsProcessor).updatePayments(any(), anyString(), anyString(), eq(Long.toString(newCaseId)));
    }

    @Test
    void should_create_case_but_respond_failure_when_payments_processor_throws_an_error() {
        // given
        setUpServiceConfig("https://localhost", true); // allowed to create case despite pending payments

        Map<String, Object> data = basicCaseData();
        data.put(ExceptionRecordFields.AWAITING_PAYMENT_DCN_PROCESSING, YesNoFieldValues.YES);

        long newCaseId = 1;
        given(ccdNewCaseCreator.createNewCase(
            any(ExceptionRecord.class),
            any(ServiceConfigItem.class),
            anyBoolean(),
            anyString(),
            anyString()
        )).willReturn(new CreateCaseResult(newCaseId));

        willThrow(PaymentsPublishingException.class).given(paymentsProcessor)
            .updatePayments(any(), anyString(), anyString(), eq(Long.toString(newCaseId)));

        // when
        ProcessResult result =
            service
                .process(
                    new CcdCallbackRequest(EventIds.CREATE_NEW_CASE, caseDetails(data), true), // ignore warnings
                    IDAM_TOKEN,
                    USER_ID
                );

        // then
        assertThat(result.getErrors()).containsOnly("Payment references cannot be processed. Please try again later");
        assertThat(result.getWarnings()).isEmpty();
    }

    private void setUpServiceConfig() {
        setUpServiceConfig("some-url", false);
    }

    private void setUpServiceConfig(String transformationUrl, boolean allowCreatingCaseBeforePaymentsAreProcessed) {
        var configItem = new ServiceConfigItem();
        configItem.setService(SERVICE);
        configItem.setTransformationUrl(transformationUrl);
        configItem.setAllowCreatingCaseBeforePaymentsAreProcessed(allowCreatingCaseBeforePaymentsAreProcessed);

        when(serviceConfigProvider.getConfig(SERVICE)).thenReturn(configItem);
    }

    private Map<String, Object> basicCaseData() {
        Map<String, Object> data = new HashMap<>();
        data.put("envelopeLegacyCaseReference", null);
        data.put("poBox", "12345");
        data.put("journeyClassification", EXCEPTION.name());
        data.put("formType", "A1");
        data.put("deliveryDate", "2019-09-06T15:30:03.000Z");
        data.put("openingDate", "2019-09-06T15:30:04.000Z");
        data.put("scannedDocuments", TestCaseBuilder.document("https://url", "name"));
        data.put("scanOCRData", TestCaseBuilder.ocrDataEntry("key", "value"));
        data.put(ExceptionRecordFields.CONTAINS_PAYMENTS, YesNoFieldValues.YES);
        data.put(ExceptionRecordFields.ENVELOPE_ID, "987");
        data.put(ExceptionRecordFields.PO_BOX_JURISDICTION, "sample jurisdiction");
        return data;
    }

    private CaseDetails caseDetails(Map<String, Object> data) {
        return TestCaseBuilder.createCaseWith(builder -> builder
            .id(Long.valueOf(CASE_ID))
            .caseTypeId(CASE_TYPE_ID)
            .jurisdiction("some jurisdiction")
            .data(data)
        );
    }
}
