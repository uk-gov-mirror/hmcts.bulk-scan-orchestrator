package uk.gov.hmcts.reform.bulkscan.orchestrator.model.ccd.mappers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.bulkscan.orchestrator.model.ccd.CaseAction;
import uk.gov.hmcts.reform.bulkscan.orchestrator.model.ccd.CcdCollectionElement;
import uk.gov.hmcts.reform.bulkscan.orchestrator.model.ccd.EnvelopeReference;
import uk.gov.hmcts.reform.bulkscan.orchestrator.model.ccd.SupplementaryEvidence;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.ccd.EnvelopeReferenceCollectionHelper;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.servicebus.domains.envelopes.model.Document;
import uk.gov.hmcts.reform.bulkscan.orchestrator.services.servicebus.domains.envelopes.model.Envelope;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.reform.bulkscan.orchestrator.model.ccd.mappers.DocumentMapper.mapDocuments;

@Component
public class SupplementaryEvidenceMapper {

    private final String documentManagementUrl;
    private final String contextPath;
    private final EnvelopeReferenceCollectionHelper envelopeReferenceCollectionHelper;

    public SupplementaryEvidenceMapper(
        @Value("${document_management.url}") final String documentManagementUrl,
        @Value("${document_management.context-path}") final String contextPath,
        final EnvelopeReferenceCollectionHelper envelopeReferenceCollectionHelper
    ) {
        this.documentManagementUrl = documentManagementUrl;
        this.contextPath = contextPath;
        this.envelopeReferenceCollectionHelper = envelopeReferenceCollectionHelper;
    }

    // TODO: update tests
    public SupplementaryEvidence map(
        List<Document> existingDocs,
        List<Map<String, Object>> existingEnvelopeReferences,
        Envelope envelope
    ) {
        List<CcdCollectionElement<EnvelopeReference>> updatedEnvelopeReferences = null;
        if (envelopeReferenceCollectionHelper.serviceSupportsEnvelopeReferences(envelope.container)) {
            updatedEnvelopeReferences =
                envelopeReferenceCollectionHelper.parseEnvelopeReferences(existingEnvelopeReferences);

            updatedEnvelopeReferences.addAll(
                envelopeReferenceCollectionHelper.singleEnvelopeReferenceList(envelope.id, CaseAction.UPDATE)
            );
        }

        var scannedDocuments = mapDocuments(
            Stream.concat(
                existingDocs.stream(),
                getDocsToAdd(existingDocs, envelope.documents).stream()
            ).collect(toList()),
            documentManagementUrl,
            contextPath,
            envelope.deliveryDate
        );

        return new SupplementaryEvidence(scannedDocuments, updatedEnvelopeReferences);
    }

    private List<CcdCollectionElement<EnvelopeReference>> appendEnvelopeReference(
        List<EnvelopeReference> existingEnvelopeReferences,
        String envelopeId
    ) {
        List<EnvelopeReference> updatedEnvelopeReferences =
            newArrayList(existingEnvelopeReferences);
        // TODO: constant
        // TODO: update tests
        updatedEnvelopeReferences.add(new EnvelopeReference(envelopeId, CaseAction.UPDATE));

        return updatedEnvelopeReferences
            .stream()
            .map(envRef -> new CcdCollectionElement<>(envRef))
            .collect(toList());
    }

    public List<Document> getDocsToAdd(List<Document> existingDocs, List<Document> newDocs) {
        return newDocs
            .stream()
            .filter(d -> existingDocs.stream().noneMatch(e -> areDuplicates(d, e)))
            .collect(toList());
    }

    private boolean areDuplicates(Document d1, Document d2) {
        return Objects.equals(d1.uuid, d2.uuid)
            || Objects.equals(d1.controlNumber, d2.controlNumber);
    }
}
