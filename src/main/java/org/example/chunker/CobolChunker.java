package org.example.chunker;

import org.example.graph.KnowledgeGraphBuilder;
import org.example.model.FileChunk;
import org.example.model.FileType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Chunks COBOL programs (.cbl) and copybooks (.cpy) into semantic units.
 *
 * COBOL programs are chunked by:
 *   - IDENTIFICATION DIVISION
 *   - ENVIRONMENT DIVISION (with sub-sections)
 *   - DATA DIVISION / FILE SECTION
 *   - DATA DIVISION / WORKING-STORAGE SECTION
 *   - PROCEDURE DIVISION: each named SECTION (0000-MAIN, 1000-INIT, etc.)
 *
 * Copybooks are chunked by 01-level record definitions.
 */
public class CobolChunker {

    private static final Pattern DIVISION_PAT = Pattern.compile(
        "^\\s+(IDENTIFICATION|ENVIRONMENT|DATA|PROCEDURE)\\s+DIVISION\\.?\\s*$",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern STANDARD_SECTION_PAT = Pattern.compile(
        "^\\s+(FILE|WORKING-STORAGE|LINKAGE|LOCAL-STORAGE|INPUT-OUTPUT|CONFIGURATION|SCREEN)\\s+SECTION\\.?\\s*$",
        Pattern.CASE_INSENSITIVE);

    // Numbered PROCEDURE DIVISION sections: e.g. "       0000-MAIN SECTION."
    private static final Pattern PROC_SECTION_PAT = Pattern.compile(
        "^\\s+([A-Z0-9][A-Z0-9-]+)\\s+SECTION\\.?\\s*$",
        Pattern.CASE_INSENSITIVE);

    // Entry points within PROCEDURE DIVISION
    private static final Pattern ENTRY_PAT = Pattern.compile(
        "^\\s+ENTRY\\s+'([A-Z][A-Z0-9]+)'",
        Pattern.CASE_INSENSITIVE);

    // Numbered paragraph headers in PROCEDURE DIVISION (no SECTION keyword)
    // e.g. "       1000-ACCTFILE-GET-NEXT." or "       0000-MAIN-PARA."
    // Requires 6+ leading spaces so statements/data names at Area B are not matched
    private static final Pattern PARA_PAT = Pattern.compile(
        "^\\s{6,}([0-9]{1,4}-[A-Z][A-Z0-9-]+)\\.\\s*$",
        Pattern.CASE_INSENSITIVE);

    // 01-level records in copybooks
    private static final Pattern COPY_RECORD_PAT = Pattern.compile(
        "^\\s+01\\s+([A-Z][A-Z0-9-]+)",
        Pattern.CASE_INSENSITIVE);

    private final CobolAnalyzer analyzer = new CobolAnalyzer();

    public CobolChunker() {}

    public List<FileChunk> chunk(Path filePath, FileType fileType) throws IOException {
        return chunk(filePath, fileType, null);
    }

    public List<FileChunk> chunk(Path filePath, FileType fileType,
                                  KnowledgeGraphBuilder graphBuilder) throws IOException {
        List<String> lines = Files.readAllLines(filePath);
        String fullContent = String.join("\n", lines);
        String fileName = filePath.getFileName().toString();

        CobolAnalyzer.ProgramMetadata meta = analyzer.extractProgramMetadata(fullContent);
        if (meta.programId.isBlank()) {
            meta.programId = fileName.replaceAll("\\.[^.]+$", "").toUpperCase();
        }

        List<FileChunk> chunks;
        if (fileType == FileType.COPYBOOK) {
            chunks = chunkCopybook(lines, fileName, meta);
        } else {
            chunks = chunkCobolProgram(lines, fileName, meta);
        }

        if (graphBuilder != null) {
            registerInGraph(graphBuilder, meta, fileName, chunks, fileType);
        }
        return chunks;
    }

    private void registerInGraph(KnowledgeGraphBuilder graphBuilder,
                                  CobolAnalyzer.ProgramMetadata meta,
                                  String fileName, List<FileChunk> chunks,
                                  FileType fileType) {
        if (chunks.isEmpty()) return;

        if (fileType == FileType.COPYBOOK) {
            List<String> records = chunks.stream()
                .map(FileChunk::getSectionName)
                .filter(s -> s != null && !s.equals("PREAMBLE"))
                .collect(Collectors.toList());
            graphBuilder.registerCopybook(meta.programId, fileName, records);
            return;
        }

        Set<String> allRead    = new LinkedHashSet<>();
        Set<String> allWritten = new LinkedHashSet<>();
        Set<String> allUpdated = new LinkedHashSet<>();
        Set<String> allDeleted = new LinkedHashSet<>();
        Set<String> allCalls   = new LinkedHashSet<>();

        for (FileChunk c : chunks) {
            if (c.getFilesRead()             != null) allRead.addAll(c.getFilesRead());
            if (c.getFilesWritten()          != null) allWritten.addAll(c.getFilesWritten());
            if (c.getFilesUpdated()          != null) allUpdated.addAll(c.getFilesUpdated());
            if (c.getFilesDeleted()          != null) allDeleted.addAll(c.getFilesDeleted());
            if (c.getExternalProgramsCalled() != null) allCalls.addAll(c.getExternalProgramsCalled());
        }

        String subDomain  = chunks.get(0).getSubDomain();
        String procType   = chunks.get(0).getProcessingType();
        String chunkDomain = chunks.get(0).getDomain();

        graphBuilder.registerCobolProgram(
            meta.programId, chunkDomain, subDomain, procType,
            meta.author, meta.dateWritten,
            new ArrayList<>(meta.allCopybooks),
            meta.entryPoints,
            new ArrayList<>(allRead),
            new ArrayList<>(allWritten),
            new ArrayList<>(allUpdated),
            new ArrayList<>(allDeleted),
            new ArrayList<>(allCalls));
    }

    // -------------------------------------------------------
    // COBOL program chunking
    // -------------------------------------------------------

    private List<FileChunk> chunkCobolProgram(List<String> lines, String fileName,
                                               CobolAnalyzer.ProgramMetadata meta) {
        List<FileChunk> chunks = new ArrayList<>();

        String currentDivision = "PREAMBLE";
        String currentSection = "";
        List<String> currentChunkLines = new ArrayList<>();
        int chunkStartLine = 1;
        String currentSectionName = "PREAMBLE";
        boolean inProcedure = false;
        boolean entryBlockStarted = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim().toUpperCase();

            // Skip pure comment lines for boundary detection (but include them in content)
            boolean isComment = line.length() > 6 && line.charAt(6) == '*';

            // Check for DIVISION header
            Matcher divMatcher = DIVISION_PAT.matcher(line);
            if (!isComment && divMatcher.matches()) {
                saveChunk(chunks, currentChunkLines, currentDivision, currentSectionName,
                    chunkStartLine, i, fileName, meta);
                currentDivision = divMatcher.group(1).toUpperCase();
                currentSection = "";
                currentSectionName = currentDivision + "_DIVISION";
                chunkStartLine = i + 1;
                inProcedure = "PROCEDURE".equals(currentDivision);
                entryBlockStarted = false;
                currentChunkLines = new ArrayList<>();
                currentChunkLines.add(line);
                continue;
            }

            // Check for standard DATA/ENVIRONMENT section headers
            Matcher stdSecMatcher = STANDARD_SECTION_PAT.matcher(line);
            if (!isComment && !inProcedure && stdSecMatcher.matches()) {
                saveChunk(chunks, currentChunkLines, currentDivision, currentSectionName,
                    chunkStartLine, i, fileName, meta);
                currentSection = stdSecMatcher.group(1).toUpperCase();
                currentSectionName = currentDivision + "_" + currentSection + "_SECTION";
                chunkStartLine = i + 1;
                currentChunkLines = new ArrayList<>();
                currentChunkLines.add(line);
                continue;
            }

            // In PROCEDURE DIVISION — check for numbered sections
            if (inProcedure) {
                Matcher procSecMatcher = PROC_SECTION_PAT.matcher(line);
                if (!isComment && procSecMatcher.matches()) {
                    String secName = procSecMatcher.group(1).toUpperCase();
                    // Ignore standard COBOL division/section keywords appearing here
                    if (!isKeyword(secName)) {
                        saveChunk(chunks, currentChunkLines, currentDivision, currentSectionName,
                            chunkStartLine, i, fileName, meta);
                        currentSection = secName;
                        currentSectionName = secName;
                        chunkStartLine = i + 1;
                        currentChunkLines = new ArrayList<>();
                        currentChunkLines.add(line);
                        continue;
                    }
                }

                // ENTRY points — start a new chunk for each entry point group
                Matcher entryMatcher = ENTRY_PAT.matcher(line);
                if (!isComment && entryMatcher.find()) {
                    saveChunk(chunks, currentChunkLines, currentDivision, currentSectionName,
                        chunkStartLine, i, fileName, meta);
                    String entryName = entryMatcher.group(1).toUpperCase();
                    currentSection = "ENTRY_" + entryName;
                    currentSectionName = "ENTRY-" + entryName;
                    chunkStartLine = i + 1;
                    currentChunkLines = new ArrayList<>();
                    currentChunkLines.add(line);
                    continue;
                }

                // Numbered paragraph headers: 1000-NAME. or 0000-MAIN-PARA.
                Matcher paraMatcher = PARA_PAT.matcher(line);
                if (!isComment && paraMatcher.matches()) {
                    String paraName = paraMatcher.group(1).toUpperCase();
                    if (!isKeyword(paraName)) {
                        saveChunk(chunks, currentChunkLines, currentDivision, currentSectionName,
                            chunkStartLine, i, fileName, meta);
                        currentSection = paraName;
                        currentSectionName = paraName;
                        chunkStartLine = i + 1;
                        currentChunkLines = new ArrayList<>();
                        currentChunkLines.add(line);
                        continue;
                    }
                }
            }

            currentChunkLines.add(line);
        }

        // Save the last chunk
        saveChunk(chunks, currentChunkLines, currentDivision, currentSectionName,
            chunkStartLine, lines.size(), fileName, meta);

        // Set totalChunks and chunkIndex
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i + 1);
            chunks.get(i).setTotalChunks(chunks.size());
        }

        return chunks;
    }

    // -------------------------------------------------------
    // Copybook chunking (by 01-level records)
    // -------------------------------------------------------

    private List<FileChunk> chunkCopybook(List<String> lines, String fileName,
                                           CobolAnalyzer.ProgramMetadata meta) {
        List<FileChunk> chunks = new ArrayList<>();

        List<String> currentChunkLines = new ArrayList<>();
        String currentRecordName = "HEADER";
        int chunkStartLine = 1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = COPY_RECORD_PAT.matcher(line);
            if (m.find()) {
                if (!currentChunkLines.isEmpty()) {
                    FileChunk chunk = buildCopybookChunk(currentChunkLines, currentRecordName,
                        chunkStartLine, i, fileName, meta);
                    chunks.add(chunk);
                }
                currentRecordName = m.group(1).toUpperCase();
                chunkStartLine = i + 1;
                currentChunkLines = new ArrayList<>();
            }
            currentChunkLines.add(line);
        }

        if (!currentChunkLines.isEmpty()) {
            FileChunk chunk = buildCopybookChunk(currentChunkLines, currentRecordName,
                chunkStartLine, lines.size(), fileName, meta);
            chunks.add(chunk);
        }

        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i + 1);
            chunks.get(i).setTotalChunks(chunks.size());
        }

        return chunks;
    }

    private FileChunk buildCopybookChunk(List<String> chunkLines, String recordName,
                                          int lineStart, int lineEnd, String fileName,
                                          CobolAnalyzer.ProgramMetadata meta) {
        String content = String.join("\n", chunkLines);
        String subDomain = analyzer.inferSubDomain(meta.programId, content);
        String domain    = CobolAnalyzer.inferDomain(subDomain);

        // Count fields and conditions
        long fieldCount = chunkLines.stream()
            .filter(l -> l.trim().toUpperCase().startsWith("05")
                || l.trim().toUpperCase().startsWith("10"))
            .count();
        long conditionCount = chunkLines.stream()
            .filter(l -> l.trim().toUpperCase().startsWith("88"))
            .count();

        String purpose = "Copybook record layout '" + recordName + "'. "
            + "Contains " + fieldCount + " data fields and " + conditionCount + " condition names (88-levels). "
            + "Used to define the " + humanizeName(recordName) + " data structure "
            + "in programs that COPY " + meta.programId + ".";

        List<String> keyFields = analyzer.extractKeyDataFields(content);

        FileChunk chunk = new FileChunk();
        chunk.setChunkId(buildChunkId(fileName, "COPYBOOK", recordName, 0));
        chunk.setSourceFile(fileName);
        chunk.setFileType(FileType.COPYBOOK.label);
        chunk.setProgramId(meta.programId);
        chunk.setAuthor(meta.author.isBlank() ? null : meta.author);
        chunk.setDateWritten(meta.dateWritten.isBlank() ? null : meta.dateWritten);
        chunk.setProgramDescription(meta.programDescription.isBlank() ? null : meta.programDescription);
        chunk.setDomain(domain);
        chunk.setSubDomain(subDomain);
        chunk.setProcessingType("COPYBOOK_RECORD_LAYOUT");
        chunk.setDivision("DATA_LAYOUT");
        chunk.setSectionName(recordName);
        chunk.setLineStart(lineStart);
        chunk.setLineEnd(lineEnd);
        chunk.setSectionPurpose(purpose);
        chunk.setFilesRead(null);
        chunk.setFilesWritten(null);
        chunk.setFilesUpdated(null);
        chunk.setCopybooksReferenced(null);
        chunk.setEntryPoints(null);
        chunk.setParagraphsCalled(null);
        chunk.setKeyDataFields(keyFields.isEmpty() ? null : keyFields);
        chunk.setBusinessConditions(null);
        chunk.setHasFileIO(false);
        chunk.setHasErrorHandling(false);
        chunk.setTags(analyzer.generateTags(meta.programId, subDomain, recordName,
            "COPYBOOK", false, false, meta, domain));
        chunk.setContent(content);
        return chunk;
    }

    // -------------------------------------------------------
    // Chunk builder for COBOL programs
    // -------------------------------------------------------

    private void saveChunk(List<FileChunk> chunks, List<String> chunkLines,
                            String division, String sectionName,
                            int lineStart, int lineEnd,
                            String fileName, CobolAnalyzer.ProgramMetadata meta) {
        if (chunkLines == null || chunkLines.stream().allMatch(l -> l.isBlank())) return;

        String content = String.join("\n", chunkLines);
        String subDomain = analyzer.inferSubDomain(meta.programId, content);
        String domain    = CobolAnalyzer.inferDomain(subDomain);
        String procType  = analyzer.inferProcessingType(meta.programId, meta);

        boolean isProcedure = "PROCEDURE".equals(division);
        List<String> filesRead     = isProcedure ? analyzer.extractFilesRead(content, meta)    : List.of();
        List<String> filesWritten  = isProcedure ? analyzer.extractFilesWritten(content, meta)  : List.of();
        List<String> filesUpdated  = isProcedure ? analyzer.extractFilesUpdated(content, meta)  : List.of();
        List<String> filesDeleted  = isProcedure ? analyzer.extractFilesDeleted(content, meta)  : List.of();
        List<String> externalCalls = isProcedure ? analyzer.extractExternalCalls(content, meta)  : List.of();
        List<String> performs      = isProcedure ? analyzer.extractPerformCalls(content)        : List.of();
        List<String> conditions    = analyzer.extractBusinessConditions(content);
        List<String> keyFields     = analyzer.extractKeyDataFields(content);
        List<String> copybooksInChunk = analyzer.extractCopybooksInChunk(content);

        boolean hasFileIO = !filesRead.isEmpty() || !filesWritten.isEmpty()
            || !filesUpdated.isEmpty() || !filesDeleted.isEmpty();
        String contentUpper = content.toUpperCase();
        boolean hasErrorHandling = contentUpper.contains("INVALID KEY")
            || contentUpper.contains("ON EXCEPTION")
            || contentUpper.contains("ERR-RETURN-CODE")
            || contentUpper.contains("HANDLE-ERROR")
            || contentUpper.contains("FILE-STATUS")
            || contentUpper.contains("-RETURN-CODE");

        String purpose = analyzer.inferSectionPurpose(sectionName, content, meta, division);

        String displayDivision = division.equals("PREAMBLE") ? null : division + "_DIVISION";

        FileChunk chunk = new FileChunk();
        chunk.setChunkId(buildChunkId(fileName, division, sectionName, chunks.size()));
        chunk.setSourceFile(fileName);
        chunk.setFileType(FileType.COBOL_PROGRAM.label);
        chunk.setProgramId(meta.programId);
        chunk.setAuthor(meta.author.isBlank() ? null : meta.author);
        chunk.setDateWritten(meta.dateWritten.isBlank() ? null : meta.dateWritten);
        chunk.setProgramDescription(meta.programDescription.isBlank() ? null : meta.programDescription);
        chunk.setDomain(domain);
        chunk.setSubDomain(subDomain);
        chunk.setProcessingType(procType);
        chunk.setDivision(displayDivision);
        chunk.setSectionName(sectionName.equals("PREAMBLE") ? null : sectionName);
        chunk.setLineStart(lineStart);
        chunk.setLineEnd(lineEnd);
        chunk.setSectionPurpose(purpose);
        chunk.setFilesRead(filesRead.isEmpty() ? null : filesRead);
        chunk.setFilesWritten(filesWritten.isEmpty() ? null : filesWritten);
        chunk.setFilesUpdated(filesUpdated.isEmpty() ? null : filesUpdated);
        chunk.setFilesDeleted(filesDeleted.isEmpty() ? null : filesDeleted);
        chunk.setCopybooksReferenced(copybooksInChunk.isEmpty() ? null : copybooksInChunk);
        chunk.setEntryPoints(meta.entryPoints.isEmpty() ? null : meta.entryPoints);
        chunk.setExternalProgramsCalled(externalCalls.isEmpty() ? null : externalCalls);
        chunk.setParagraphsCalled(performs.isEmpty() ? null : performs);
        chunk.setKeyDataFields(keyFields.isEmpty() ? null : keyFields);
        chunk.setBusinessConditions(conditions.isEmpty() ? null : conditions);
        chunk.setHasFileIO(hasFileIO);
        chunk.setHasErrorHandling(hasErrorHandling);
        chunk.setTags(analyzer.generateTags(meta.programId, subDomain, sectionName,
            "COBOL_PROGRAM", hasFileIO, hasErrorHandling, meta, domain));
        chunk.setContent(content);

        chunks.add(chunk);
    }

    // -------------------------------------------------------
    // Utilities
    // -------------------------------------------------------

    private String buildChunkId(String fileName, String division, String section, int index) {
        String base = fileName.replaceAll("\\.[^.]+$", "").toUpperCase();
        String sec = section.replaceAll("[^A-Z0-9-]", "_").toUpperCase();
        return base + "_" + sec + "_" + String.format("%03d", index + 1);
    }

    private boolean isKeyword(String name) {
        Set<String> keywords = Set.of("FILE", "WORKING-STORAGE", "LINKAGE", "LOCAL-STORAGE",
            "INPUT-OUTPUT", "CONFIGURATION", "IDENTIFICATION", "ENVIRONMENT",
            "DATA", "PROCEDURE", "SCREEN");
        return keywords.contains(name.toUpperCase());
    }

    private String humanizeName(String name) {
        return name.replace("-", " ").toLowerCase();
    }
}