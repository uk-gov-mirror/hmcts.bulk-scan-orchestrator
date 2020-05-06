package uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;

import static com.google.common.collect.ImmutableList.toImmutableList;

public final class Documents {
    private static final String SCANNED_DOCUMENTS = "scannedDocuments";

    private Documents() {
    }

    public static String getExceptionRecordReference(Map<String, Object> document) {
        return Optional.ofNullable(document)
            .map(doc -> doc.get("value"))
            .map(map -> ((Map)map).get("exceptionRecordReference"))
            .map(item -> (String) item)
            .orElse(null);
    }

    static List<String> getDocumentNumbers(List<Map<String, Object>> documents) {
        return documents
            .stream()
            .map(doc -> Documents.getOptionalDocumentId(doc).orElse(""))
            .collect(toImmutableList());
    }

    public static String getDocumentId(Map<String, Object> document) {
        return Optional.ofNullable(document)
            .map(doc -> doc.get("value"))
            .map(map -> ((Map) map).get("controlNumber"))
            .map(item -> (String) item)
            .orElse(null);
    }

    @Nonnull
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getScannedDocuments(CaseDetails theCase) {
        return (List<Map<String, Object>>)
            Optional.ofNullable(theCase.getData())
                .map(map -> map.get(SCANNED_DOCUMENTS))
                .orElseGet(Lists::newArrayList);
    }


    static List<Object> concatDocuments(
        List<Map<String, Object>> exceptionDocuments,
        List<Map<String, Object>> existingDocuments
    ) {
        return ImmutableList.builder()
            .addAll(existingDocuments)
            .addAll(exceptionDocuments)
            .build();
    }

    private static Optional<String> getOptionalDocumentId(Map<String, Object> document) {
        return Optional.ofNullable(document)
            .map(doc -> doc.get("value"))
            .filter(item -> item instanceof Map)
            .map(map -> ((Map) map).get("controlNumber"))
            .filter(item -> item instanceof String)
            .map(item -> (String) item);
    }
}
