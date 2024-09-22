package com.analyzer.service;

import com.analyzer.model.ApiInfo;
import com.analyzer.model.ComparisonResult;
import com.analyzer.model.UmlDiagram;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ComparisonService {

    public ComparisonResult compare(List<UmlDiagram> extractedDiagrams, List<UmlDiagram> dabDiagrams,
                                    List<ApiInfo> extractedApiInfo, List<ApiInfo> dabApiInfo) {
        List<String> discrepancies = new ArrayList<>();
        Map<String, Double> scores = new HashMap<>();

        scores.put("classDiagram", compareDiagrams(extractedDiagrams, dabDiagrams, "Class Diagram", discrepancies));
        scores.put("sequenceDiagram", compareDiagrams(extractedDiagrams, dabDiagrams, "Sequence Diagram", discrepancies));
        scores.put("apiInfo", compareApiInfo(extractedApiInfo, dabApiInfo, discrepancies));

        double overallScore = scores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        return new ComparisonResult(overallScore, discrepancies, scores);
    }

    private double compareDiagrams(List<UmlDiagram> extractedDiagrams, List<UmlDiagram> dabDiagrams,
                                   String diagramType, List<String> discrepancies) {
        Optional<UmlDiagram> extractedDiagramOpt = extractedDiagrams.stream()
                .filter(d -> d.getType().equals(diagramType))
                .findFirst();
        Optional<UmlDiagram> dabDiagramOpt = dabDiagrams.stream()
                .filter(d -> d.getType().equals(diagramType))
                .findFirst();

        if (extractedDiagramOpt.isPresent() && dabDiagramOpt.isPresent()) {
            return calculateDiagramSimilarity(extractedDiagramOpt.get(), dabDiagramOpt.get(), discrepancies);
        } else {
            discrepancies.add("Missing " + diagramType);
            return 0.0;
        }
    }

    private double calculateDiagramSimilarity(UmlDiagram extractedDiagram, UmlDiagram dabDiagram, List<String> discrepancies) {
        Set<String> extractedElements = parseUmlElements(extractedDiagram.getContent());
        Set<String> dabElements = parseUmlElements(dabDiagram.getContent());

        Set<String> commonElements = new HashSet<>(extractedElements);
        commonElements.retainAll(dabElements);

        Set<String> missingElements = new HashSet<>(dabElements);
        missingElements.removeAll(extractedElements);

        Set<String> extraElements = new HashSet<>(extractedElements);
        extraElements.removeAll(dabElements);

        for (String missing : missingElements) {
            discrepancies.add("Missing element in " + extractedDiagram.getType() + ": " + missing);
        }

        for (String extra : extraElements) {
            discrepancies.add("Extra element in " + extractedDiagram.getType() + ": " + extra);
        }

        return (double) commonElements.size() / dabElements.size();
    }

    private Set<String> parseUmlElements(String content) {
        // This is a simplified parser. A real implementation would be more robust.
        return Arrays.stream(content.split("\n"))
                .map(String::trim)
                .filter(line -> !line.startsWith("@") && !line.isEmpty())
                .collect(Collectors.toSet());
    }

    private double compareApiInfo(List<ApiInfo> extractedApiInfo, List<ApiInfo> dabApiInfo, List<String> discrepancies) {
        Set<ApiInfo> extractedSet = new HashSet<>(extractedApiInfo);
        Set<ApiInfo> dabSet = new HashSet<>(dabApiInfo);

        Set<ApiInfo> commonApis = new HashSet<>(extractedSet);
        commonApis.retainAll(dabSet);

        Set<ApiInfo> missingApis = new HashSet<>(dabSet);
        missingApis.removeAll(extractedSet);

        Set<ApiInfo> extraApis = new HashSet<>(extractedSet);
        extraApis.removeAll(dabSet);

        for (ApiInfo missing : missingApis) {
            discrepancies.add("Missing API: " + missing.getClassName() + "." + missing.getMethodName());
        }

        for (ApiInfo extra : extraApis) {
            discrepancies.add("Extra API: " + extra.getClassName() + "." + extra.getMethodName());
        }

        return dabSet.isEmpty() ? 1.0 : (double) commonApis.size() / dabSet.size();
    }
}