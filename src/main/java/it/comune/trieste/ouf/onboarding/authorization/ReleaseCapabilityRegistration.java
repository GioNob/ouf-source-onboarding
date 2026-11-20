package it.comune.trieste.ouf.onboarding.authorization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.CapabilityDescriptor;
import it.comune.trieste.ouf.authorization.PrincipalContext.ActorType;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Release input is bundled with the application. It has no publication authority. */
@Component
public class ReleaseCapabilityRegistration implements ApplicationRunner {
    private static final List<String> CATALOGUES = List.of(
            "catalogue/chatbot-source-review-capabilities.json",
            "catalogue/r4a-semantic-human-capabilities.json");
    private final JdbcClient db;
    private final ObjectMapper json;

    public ReleaseCapabilityRegistration(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        Set<String> seen = new HashSet<>();
        for (String catalogue : CATALOGUES) {
            try (var input = new ClassPathResource(catalogue).getInputStream()) {
                JsonNode entries = json.reader().withFeatures(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
                if (entries == null || !entries.isArray() || entries.isEmpty() || entries.size() > 10000)
                    throw new IllegalArgumentException("expected 1..10000 entries");
                for (JsonNode entry : entries) {
                    if (!entry.isObject() || entry.size() != 2) throw new IllegalArgumentException("invalid entry");
                    String owner = requiredText(entry, "ownerRef");
                    var descriptor = descriptor(entry.path("descriptor"));
                    if (!seen.add(descriptor.capabilityId())) throw new IllegalArgumentException("duplicate capabilityId");
                    register(catalogue, owner, descriptor);
                }
            } catch (IOException | IllegalArgumentException ex) {
                throw new IllegalStateException("AUTH_RELEASE_CATALOGUE_INVALID " + catalogue, ex);
            }
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).textValue();
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field);
        return value;
    }

    private static CapabilityDescriptor descriptor(JsonNode node) {
        JsonNode actors = node.path("allowedActors");
        if (!node.isObject() || node.size() != 4 || !actors.isArray() || actors.isEmpty())
            throw new IllegalArgumentException("invalid descriptor");
        Set<ActorType> allowed = EnumSet.noneOf(ActorType.class);
        for (var actor : actors) {
            if (!actor.isTextual() || !allowed.add(ActorType.valueOf(actor.textValue())))
                throw new IllegalArgumentException("invalid allowedActors");
        }
        return new CapabilityDescriptor(requiredText(node, "capabilityId"), requiredText(node, "operation"),
                requiredText(node, "requiredScope"), allowed);
    }

    private record Stored(String owner, String descriptor) {}

    private boolean alreadyRegistered(String owner, CapabilityDescriptor expected) {
        var row = db.sql("""
                select owner_ref, descriptor::text as descriptor from ouf_authorization.capability_registration
                where capability_id = :id
                """).param("id", expected.capabilityId())
                .query((rs, n) -> new Stored(rs.getString("owner_ref"), rs.getString("descriptor"))).optional();
        if (row.isEmpty()) return false;
        String collision = "AUTH_RELEASE_CAPABILITY_COLLISION " + expected.capabilityId();
        try {
            if (owner.equals(row.get().owner()) &&
                    expected.equals(descriptor(json.readTree(row.get().descriptor())))) return true;
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException(collision, ex);
        }
        throw new IllegalStateException(collision);
    }

    private void register(String catalogue, String owner, CapabilityDescriptor descriptor) throws IOException {
        if (alreadyRegistered(owner, descriptor)) return;
        // The immutable row also records release provenance and the database transaction timestamp.
        int inserted = db.sql("""
                insert into ouf_authorization.capability_registration(capability_id,owner_ref,descriptor,registered_by)
                values (:id,:owner,cast(:descriptor as jsonb),:source) on conflict (capability_id) do nothing
                """).param("id", descriptor.capabilityId()).param("owner", owner)
                .param("descriptor", json.writeValueAsString(descriptor)).param("source", "release:" + catalogue).update();
        if (inserted == 0 && !alreadyRegistered(owner, descriptor))
            throw new IllegalStateException("AUTH_RELEASE_REGISTRATION_MISSING " + descriptor.capabilityId());
    }
}
