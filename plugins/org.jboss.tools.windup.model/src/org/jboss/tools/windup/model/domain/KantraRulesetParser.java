package org.jboss.tools.windup.model.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.jboss.tools.windup.windup.WindupFactory;
import org.jboss.tools.windup.windup.WindupResult;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import static org.jboss.tools.windup.runtime.WindupRuntimePlugin.logInfo;
import static org.jboss.tools.windup.model.domain.KantraConfiguration.*;

public class KantraRulesetParser {

    public static List<Ruleset> parseRuleset(String resultFilePath) {
        logInfo("parseRuleset " + resultFilePath);
        Thread currentThread = Thread.currentThread();
        ClassLoader originalClassLoader = currentThread.getContextClassLoader();
        ClassLoader jacksonClassLoader = KantraRulesetParser.class.getClassLoader();

        try {
            currentThread.setContextClassLoader(jacksonClassLoader);
            ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false); // <-- Key line
            File yamlFile = new File(resultFilePath);

            List<Ruleset> ruleSets = objectMapper.readValue(yamlFile, new TypeReference<List<Ruleset>>() {});
            if (ruleSets == null) {
                logInfo("Parsed ruleSets is null.");
                return Collections.emptyList();
            }

            ruleSets.removeIf(ruleset -> {
                logInfo("Checking ruleset for empty violations");
                return ruleset.getViolations() == null || ruleset.getViolations().isEmpty();
            });

            return ruleSets;
        } catch (Exception e) {
            logInfo("Error parsing YAML: " + resultFilePath + " - " + e.getMessage());
            e.printStackTrace();
        } finally {
            currentThread.setContextClassLoader(originalClassLoader);
        }

        return Collections.emptyList();
    }

    public static void parseRulesetForKantraConfig(KantraConfiguration configuration) {
        String outputLocation = configuration.getRulesetResultLocation();
        logInfo("Output Location: " + outputLocation);
        File outFile = new File(outputLocation);

        if (outFile.exists()) {
            List<Ruleset> rulesets = parseRuleset(outputLocation);
            if (!rulesets.isEmpty()) {
                configuration.setSummary(new AnalysisResultsSummary(rulesets));
                processIncidents(rulesets, configuration);
            } else {
                logInfo("No valid rulesets found.");
            }
        } else {
            logInfo("Output file does not exist: " + outputLocation);
        }
    }

    public static void processIncidents(List<Ruleset> rulesets, KantraConfiguration configuration) {
        if (rulesets == null || rulesets.isEmpty()) return;

        logInfo("Processing incidents");
        WindupResult result = WindupFactory.eINSTANCE.createWindupResult();
        configuration.getWindupConfiguration().setWindupResult(result);
        configuration.getWindupConfiguration().setTimestamp(ModelService.createTimestamp());

        for (Ruleset ruleset : rulesets) {
            Map<String, Violation> violations = ruleset.getViolations();
            if (violations != null) {
                for (Map.Entry<String, Violation> entry : violations.entrySet()) {
                    toHints(configuration, entry.getKey(), entry.getValue(), result);
                }
            }
        }
    }

    private static void toHints(KantraConfiguration configuration, String ruleId, Violation violation, WindupResult result) {
        if (configuration.getWindupConfiguration().getInputs().isEmpty()) return;

        String input = configuration.getWindupConfiguration().getInputs().get(0).getLocation();
        String title = violation.getDescription().split("\n", 2)[0];

        for (Incident incident : violation.getIncidents()) {
            org.jboss.tools.windup.windup.Hint hint = WindupFactory.eINSTANCE.createHint();
            result.getIssues().add(hint);

            try {
                URI uri = new URI(incident.getUri());
                String absolutePath = new File(uri).getAbsolutePath();
                hint.setFileAbsolutePath(absolutePath);
            } catch (URISyntaxException e) {
                logInfo("Invalid URI: " + incident.getUri());
                continue;
            }

            hint.setRuleId(ruleId);
            hint.setTitle(title);
            hint.setLineNumber(incident.getLineNumber());
            hint.setOriginalLineSource(incident.getCodeSnip());
            hint.setEffort(violation.getEffort());
            hint.setMessageOrDescription(incident.getMessage());

            for (Link link : violation.getLinks()) {
                org.jboss.tools.windup.windup.Link windupLink = WindupFactory.eINSTANCE.createLink();
                windupLink.setDescription(link.getTitle());
                windupLink.setUrl(link.getUrl());
                hint.getLinks().add(windupLink);
            }
        }
    }
}
