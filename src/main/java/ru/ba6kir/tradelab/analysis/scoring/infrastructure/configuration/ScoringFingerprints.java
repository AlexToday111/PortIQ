package ru.ba6kir.tradelab.analysis.scoring.infrastructure.configuration;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

public class ScoringFingerprints implements ru.ba6kir.tradelab.analysis.scoring.application.service.ScoringFingerprint {
    private final ObjectMapper json;
    private static final Set<String> SETS=Set.of("qualityStatus","mandatoryPillars","requiredContext","optionalContext","warnings");
    public ScoringFingerprints(ObjectMapper json) { this.json=json; }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(json.valueToTree(value),"").toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private JsonNode canonical(JsonNode node,String field) {
        if (node.isObject()) {
            var result=json.createObjectNode(); var names=new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name,canonical(node.get(name),name))); return result;
        }
        if (node.isArray()) {
            var elements=new ArrayList<JsonNode>(); node.forEach(n -> elements.add(canonical(n,"")));
            if (SETS.contains(field)) elements.sort(Comparator.comparing(JsonNode::toString));
            var result=json.createArrayNode(); elements.forEach(result::add); return result;
        }
        return node;
    }
}
