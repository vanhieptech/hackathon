package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DesignComparator {
    private static final Logger logger = LoggerFactory.getLogger(DesignComparator.class);
    private static final Pattern ELEMENT_PATTERN = Pattern.compile("(class|interface|enum)\\s+(\\w+)");

    public ComparisonResult compare(String generatedDiagram, String existingDiagram, String projectName) {
        logger.info("Starting diagram comparison for project: {}", projectName);
        ComparisonResult result = new ComparisonResult();

        List<String> generatedLines = Arrays.asList(generatedDiagram.split("\n"));
        List<String> existingLines = Arrays.asList(existingDiagram.split("\n"));

        List<DiffResult> diffs = calculateDiff(generatedLines, existingLines);
        Map<String, List<String>> elementRelations = extractElementRelations(generatedLines, existingLines);

        result.setDiffs(diffs);
        result.setMatchingScore(calculateMatchingScore(diffs, generatedLines.size(), existingLines.size()));
        result.setStructuralSimilarity(calculateStructuralSimilarity(generatedLines, existingLines));
        result.setElementRelations(elementRelations);
        result.setProjectName(projectName);

        logger.info("Diagram comparison completed. Matching score: {}, Structural similarity: {}",
                result.getMatchingScore(), result.getStructuralSimilarity());
        return result;
    }

    private List<DiffResult> calculateDiff(List<String> generated, List<String> existing) {
        List<DiffResult> diffs = new ArrayList<>();
        int[][] lcs = computeLCSMatrix(generated, existing);
        int i = generated.size(), j = existing.size();

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && generated.get(i - 1).equals(existing.get(j - 1))) {
                diffs.add(0, new DiffResult(DiffType.MATCH, generated.get(i - 1)));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
                diffs.add(0, new DiffResult(DiffType.MISSING, existing.get(j - 1)));
                j--;
            } else if (i > 0 && (j == 0 || lcs[i][j - 1] < lcs[i - 1][j])) {
                diffs.add(0, new DiffResult(DiffType.EXTRA, generated.get(i - 1)));
                i--;
            }
        }

        return diffs;
    }

    private int[][] computeLCSMatrix(List<String> generated, List<String> existing) {
        int[][] lcs = new int[generated.size() + 1][existing.size() + 1];

        for (int i = 1; i <= generated.size(); i++) {
            for (int j = 1; j <= existing.size(); j++) {
                if (generated.get(i - 1).equals(existing.get(j - 1))) {
                    lcs[i][j] = lcs[i - 1][j - 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i - 1][j], lcs[i][j - 1]);
                }
            }
        }

        return lcs;
    }

    private double calculateMatchingScore(List<DiffResult> diffs, int generatedSize, int existingSize) {
        long matchCount = diffs.stream().filter(d -> d.getType() == DiffType.MATCH).count();
        return (double) matchCount / Math.max(generatedSize, existingSize);
    }

    private double calculateStructuralSimilarity(List<String> generated, List<String> existing) {
        Set<String> generatedElements = extractElements(generated);
        Set<String> existingElements = extractElements(existing);

        Set<String> commonElements = new HashSet<>(generatedElements);
        commonElements.retainAll(existingElements);

        return (double) commonElements.size() / Math.max(generatedElements.size(), existingElements.size());
    }

    private Set<String> extractElements(List<String> lines) {
        Set<String> elements = new HashSet<>();
        for (String line : lines) {
            Matcher matcher = ELEMENT_PATTERN.matcher(line);
            if (matcher.find()) {
                elements.add(matcher.group(2));
            }
        }
        return elements;
    }

    private Map<String, List<String>> extractElementRelations(List<String> generated, List<String> existing) {
        Map<String, List<String>> relations = new HashMap<>();
        Pattern relationPattern = Pattern.compile("(\\w+)\\s*(?:--|<|>)\\s*(\\w+)");

        for (String line : generated) {
            Matcher matcher = relationPattern.matcher(line);
            if (matcher.find()) {
                String from = matcher.group(1);
                String to = matcher.group(2);
                relations.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            }
        }

        return relations;
    }

    public String generateHtmlDiff(ComparisonResult result) {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>\n");
        html.append(".match { background-color: #e6ffe6; }\n");
        html.append(".missing { background-color: #ffe6e6; }\n");
        html.append(".extra { background-color: #e6e6ff; }\n");
        html.append("</style></head><body>\n");
        html.append("<h1>Diagram Comparison for ").append(result.getProjectName()).append("</h1>\n");
        html.append("<p>Matching Score: ").append(String.format("%.2f%%", result.getMatchingScore() * 100)).append("</p>\n");
        html.append("<p>Structural Similarity: ").append(String.format("%.2f%%", result.getStructuralSimilarity() * 100)).append("</p>\n");
        html.append("<h2>Differences</h2>\n");
        html.append("<pre>\n");

        for (DiffResult diff : result.getDiffs()) {
            switch (diff.getType()) {
                case MATCH:
                    html.append("<span class=\"match\">").append(diff.getContent()).append("</span>\n");
                    break;
                case MISSING:
                    html.append("<span class=\"missing\">").append(diff.getContent()).append("</span>\n");
                    break;
                case EXTRA:
                    html.append("<span class=\"extra\">").append(diff.getContent()).append("</span>\n");
                    break;
            }
        }

        html.append("</pre>\n");
        html.append("<h2>Element Relations</h2>\n");
        html.append("<ul>\n");
        for (Map.Entry<String, List<String>> entry : result.getElementRelations().entrySet()) {
            html.append("<li>").append(entry.getKey()).append(" -> ").append(String.join(", ", entry.getValue())).append("</li>\n");
        }
        html.append("</ul>\n");
        html.append("</body></html>");

        return html.toString();
    }
}