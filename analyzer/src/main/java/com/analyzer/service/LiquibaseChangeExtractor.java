package com.analyzer.service;

import com.analyzer.model.DatabaseChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LiquibaseChangeExtractor {

    public List<DatabaseChange> extractChanges(Map<String, byte[]> projectFiles) {
        List<DatabaseChange> changes = new ArrayList<>();

        try {
            Path tempDir = Files.createTempDirectory("liquibase_temp");

            for (Map.Entry<String, byte[]> entry : projectFiles.entrySet()) {
                if (entry.getKey().endsWith("changelog.xml")) {
                    File tempFile = tempDir.resolve(entry.getKey()).toFile();
                    tempFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        fos.write(entry.getValue());
                    }

                    changes.addAll(parseChangeLog(tempFile));
                }
            }

            // Clean up temporary directory
            deleteDirectory(tempDir.toFile());

        } catch (Exception e) {
            System.err.println("Error processing changelog files: " + e.getMessage());
            e.printStackTrace();
        }

        return changes;
    }

    private List<DatabaseChange> parseChangeLog(File changeLogFile) throws Exception {
        List<DatabaseChange> changes = new ArrayList<>();

        ResourceAccessor resourceAccessor = new DirectoryResourceAccessor(changeLogFile.getParentFile());
        ChangeLogParser parser = ChangeLogParserFactory.getInstance().getParser(changeLogFile.getName(), resourceAccessor);

        DatabaseChangeLog changeLog = parser.parse(changeLogFile.getName(), new ChangeLogParameters(), resourceAccessor);

        for (ChangeSet changeSet : changeLog.getChangeSets()) {
            for (liquibase.change.Change change : changeSet.getChanges()) {
                String changeType = change.getClass().getSimpleName();
                String description = change.getDescription();
                if (description == null || description.isEmpty()) {
                    description = "No description provided";
                }
                String author = changeSet.getAuthor();
                String id = changeSet.getId();
                changes.add(new DatabaseChange(changeType, description, author, id));
            }
        }

        return changes;
    }

    private void deleteDirectory(File directory) {
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        directory.delete();
    }
}