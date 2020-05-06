package uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import javax.validation.constraints.NotNull;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.toSet;

class DocumentsTest {
    private static final String SCANNED_DOCUMENTS = "scannedDocuments";
    private static final String DOCUMENT_NUMBER = "id";

    private static Object[][] documentDuplicateTestParam() {
        return new Object[][]{
            {createCaseDetailsWith(), createDcnList(), ImmutableSet.<String>of()},
            {createCaseDetailsWith(1, 2, 3, 4, 5), createDcnList(6, 7, 8, 9), ImmutableSet.of()},
            {createCaseDetailsWith(1, 2, 3, 4, 5), createDcnList(6, 3, 4, 8, 9), ImmutableSet.of(3, 4)}
        };
    }

    @NotNull
    private Set<String> asStringSet(Set<Integer> duplicates) {
        return duplicates.stream().map(String::valueOf).collect(toSet());
    }

    @NotNull
    private static CaseDetails createCaseDetailsWith(Integer... dcns) {
        return CaseDetails.builder().data(ImmutableMap.of(SCANNED_DOCUMENTS, createDcnList(dcns))).build();
    }

    private static List<Map<String, Object>> createDcnList(Integer... dcns) {
        return Stream.of(dcns)
            .map(String::valueOf)
            .map(dcn -> ImmutableMap.<String, Object>of(
                DOCUMENT_NUMBER, UUID.randomUUID().toString(),
                "value", ImmutableMap.of("controlNumber", dcn)
                )
            )
            .collect(toImmutableList());
    }
}
