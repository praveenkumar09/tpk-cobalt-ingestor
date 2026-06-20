package org.example.chunker;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

// java.util.* covers Arrays, HashSet, Set, List, Map, etc.

/**
 * Analyzes COBOL source code to extract rich semantic metadata.
 * Parses program structure, data dependencies, and business logic
 * without requiring a full COBOL compiler.
 */
public class CobolAnalyzer {

    // --- Program-level extraction patterns ---
    private static final Pattern PROGRAM_ID_PAT = Pattern.compile(
        "PROGRAM-ID\\.\\s+([A-Z][A-Z0-9]+)\\.?", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHOR_PAT = Pattern.compile(
        "\\bAUTHOR\\.\\s+(.+?)\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_WRITTEN_PAT = Pattern.compile(
        "DATE-WRITTEN\\.\\s+(\\S+)\\.?", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_DESC_PAT = Pattern.compile(
        "^\\s+\\*\\s*(?:DESCRIPTION|DESC)\\s*:?\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_LINE_PAT = Pattern.compile(
        "^\\s+\\*\\s+(.+)$");

    // File control
    private static final Pattern SELECT_PAT = Pattern.compile(
        "SELECT\\s+([A-Z][A-Z0-9-]+)\\s+ASSIGN\\s+TO\\s+(?:DATABASE-|PRINTER-)?([A-Z][A-Z0-9]+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCESS_MODE_PAT = Pattern.compile(
        "ACCESS\\s+MODE\\s+IS\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORGANIZATION_PAT = Pattern.compile(
        "ORGANIZATION\\s+IS\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    // FD record mapping
    private static final Pattern FD_PAT = Pattern.compile(
        "^\\s+FD\\s+([A-Z][A-Z0-9-]+)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern RECORD_01_PAT = Pattern.compile(
        "^\\s+01\\s+([A-Z][A-Z0-9-]+)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // Copybooks
    private static final Pattern COPY_PAT = Pattern.compile(
        "^\\s+COPY\\s+([A-Z][A-Z0-9]+)\\.?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // Entry points (service programs / BOs)
    private static final Pattern ENTRY_PAT = Pattern.compile(
        "^\\s+ENTRY\\s+'([A-Z][A-Z0-9]+)'", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // --- Section-level extraction patterns ---
    // Negative lookbehind (?<!END-) prevents matching END-READ, END-WRITE, END-REWRITE, END-DELETE
    // via the word boundary that fires before their embedded verb (- is non-word, verb-start is word)
    private static final Pattern READ_PAT = Pattern.compile(
        "(?<!END-)\\bREAD\\s+([A-Z][A-Z0-9-]{2,})(?=\\s|$)",
        Pattern.CASE_INSENSITIVE);
    private static final Set<String> SKIP_AFTER_READ = Set.of(
        "NEXT", "RECORD", "INTO", "WITH", "KEY", "NO", "LOCK",
        // CICS verb keywords that appear on continuation lines after EXEC CICS READ
        "DATASET", "FILE", "RIDFLD", "LENGTH", "RESP", "RESPONSE", "COMMAREA");
    private static final Pattern WRITE_PAT = Pattern.compile(
        "(?<!END-)\\bWRITE\\s+([A-Z][A-Z0-9-]{2,})(?=\\s|FROM|$)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern REWRITE_PAT = Pattern.compile(
        "(?<!END-)\\bREWRITE\\s+([A-Z][A-Z0-9-]{2,})(?=\\s|FROM|$)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_PAT = Pattern.compile(
        "(?<!END-)\\bDELETE\\s+([A-Z][A-Z0-9-]+)", Pattern.CASE_INSENSITIVE);
    // Words that can follow DELETE inside string literals or error messages — not file names
    private static final Set<String> SKIP_AFTER_DELETE = Set.of(
        "FAILED", "SUCCESSFUL", "SUCCESS", "COMPLETE", "ERROR", "FOUND",
        "NOT", "ALL", "RECORD", "RECORDS", "OPERATION", "REQUEST");
    private static final Pattern OPEN_PAT = Pattern.compile(
        "\\bOPEN\\s+(INPUT|OUTPUT|I-O|EXTEND)\\s+([A-Z][A-Z0-9-]+)",
        Pattern.CASE_INSENSITIVE);
    // External program invocations: only quoted-literal form CALL 'PROGNAME' (reliable for AS400)
    // Unquoted CALL variable is dynamic — the name is only known at runtime
    private static final Pattern CALL_PAT = Pattern.compile(
        "\\bCALL\\s+'([A-Z][A-Z0-9]+)'",
        Pattern.CASE_INSENSITIVE);
    // SORT statements: SORT sort-work-file ON ASCENDING/DESCENDING KEY ...
    private static final Pattern SORT_PAT = Pattern.compile(
        "\\bSORT\\s+([A-Z][A-Z0-9-]+)\\s+ON\\s+(?:ASCENDING|DESCENDING)",
        Pattern.CASE_INSENSITIVE);
    // Catches numbered paragraphs (0000-MAIN, 1000-INIT) with or without -PARA suffix,
    // and legacy -PARA suffix paragraphs without a numeric prefix
    private static final Pattern PERFORM_PAT = Pattern.compile(
        "\\bPERFORM\\s+([0-9]{3,4}-[A-Z][A-Z0-9-]+|[A-Z][A-Z0-9-]+-PARA)(?=\\s|$)",
        Pattern.CASE_INSENSITIVE);
    // Anchored to line start; excludes OTHER and TRUE; allows numeric comparisons on the same line
    private static final Pattern EVALUATE_WHEN_PAT = Pattern.compile(
        "^\\s+WHEN\\s+((?!OTHER\\b|TRUE\\b)[A-Z0-9][A-Z0-9-]+(?:[\\s<>=]+[A-Z0-9.,]+)*)\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    // Negative lookbehind prevents matching the IF inside END-IF continuations
    private static final Pattern IF_PAT = Pattern.compile(
        "(?<!END-)\\bIF\\s+((?:NOT\\s+)?[A-Z][A-Z0-9-]+(?:\\s*(?:>|<|=|NOT|AND|OR|>=|<=)\\s*[A-Z0-9.,]+)?)",
        Pattern.CASE_INSENSITIVE);
    private static final Set<String> COBOL_KEYWORDS = Set.of(
        "MOVE", "PERFORM", "WRITE", "READ", "OPEN", "CLOSE", "STOP",
        "DISPLAY", "ACCEPT", "COMPUTE", "ADD", "SUBTRACT", "MULTIPLY",
        "DIVIDE", "EVALUATE", "INITIALIZE", "REWRITE", "DELETE", "START",
        "STRING", "UNSTRING", "INSPECT", "CALL", "CANCEL", "EXIT", "GO",
        "THEN", "ELSE", "END", "SECTION", "DIVISION", "PROCEDURE");
    // Generic field pattern: any 2-5 letter prefix + hyphen + identifier (at least 3 more chars)
    // Covers any naming convention (PMR-, CLM-, POL-, CUS-, ACT-, etc.)
    private static final Pattern BIZ_FIELD_PAT = Pattern.compile(
        "\\b([A-Z]{2,5}-[A-Z][A-Z0-9][A-Z0-9-]{1,})\\b",
        Pattern.CASE_INSENSITIVE);
    // Common working-storage, utility, and paragraph-verb prefixes that are NOT business data fields
    private static final Set<String> EXCLUDE_FIELD_PREFIXES = Set.of(
        "WS", "FS", "SW", "IN", "OF", "TO", "GO", "ON", "IS", "AT", "BY", "OR",
        "IF", "RC", "CC", "END", "NOT", "AND", "THE", "PIC", "SQL", "DB",
        // Common COBOL section/paragraph name verb prefixes
        "LOAD", "PROC", "INIT", "TERM", "MAIN", "LOOP", "DISP", "SORT",
        "SEND", "RECV", "OPEN", "CLOS", "CHCK", "REPT", "XFER");

    // --- CICS File Control and inter-program call patterns ---
    // These cover EXEC CICS READ/WRITE/REWRITE/DELETE FILE('NAME') and LINK/XCTL PROGRAM('NAME')
    private static final Pattern CICS_READ_PAT = Pattern.compile(
        "EXEC\\s+CICS\\s+(?:READ|STARTBR|READNEXT|READPREV)\\s+(?:FILE|DATASET)\\s*\\(\\s*'?([A-Z][A-Z0-9-]+)'?\\s*\\)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CICS_WRITE_PAT = Pattern.compile(
        "EXEC\\s+CICS\\s+WRITE\\s+(?:FILE|DATASET)\\s*\\(\\s*'?([A-Z][A-Z0-9-]+)'?\\s*\\)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CICS_REWRITE_PAT = Pattern.compile(
        "EXEC\\s+CICS\\s+REWRITE\\s+(?:FILE|DATASET)\\s*\\(\\s*'?([A-Z][A-Z0-9-]+)'?\\s*\\)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CICS_DELETE_PAT = Pattern.compile(
        "EXEC\\s+CICS\\s+DELETE\\s+(?:FILE|DATASET)\\s*\\(\\s*'?([A-Z][A-Z0-9-]+)'?\\s*\\)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CICS_LINK_PAT = Pattern.compile(
        "EXEC\\s+CICS\\s+(?:LINK|XCTL)\\s+PROGRAM\\s*\\(\\s*'?([A-Z][A-Z0-9-]+)'?\\s*\\)",
        Pattern.CASE_INSENSITIVE);

    // ========================================================
    // Public model for program-level metadata
    // ========================================================

    public static class ProgramMetadata {
        public String programId = "";
        public String author = "";
        public String dateWritten = "";
        public String programDescription = "";
        public List<String> allCopybooks = new ArrayList<>();
        public List<String> entryPoints = new ArrayList<>();
        public Map<String, String> fileAssignments = new LinkedHashMap<>(); // logical → physical
        public Map<String, String> fileOrganization = new LinkedHashMap<>(); // file → INDEXED/SEQUENTIAL
        public Map<String, String> fileAccessModes = new LinkedHashMap<>();  // file → INPUT/OUTPUT/I-O/EXTEND
        public Map<String, String> recordToFileMap = new LinkedHashMap<>();  // 01-record → FD-file
        public boolean isServiceProgram = false;
        public boolean isBatchProgram = false;
        public boolean isCicsProgram = false;
    }

    // ========================================================
    // Program-level metadata extraction
    // ========================================================

    public ProgramMetadata extractProgramMetadata(String fullContent) {
        ProgramMetadata meta = new ProgramMetadata();

        // PROGRAM-ID
        Matcher m = PROGRAM_ID_PAT.matcher(fullContent);
        if (m.find()) meta.programId = m.group(1).trim().toUpperCase();

        // AUTHOR
        m = AUTHOR_PAT.matcher(fullContent);
        if (m.find()) meta.author = m.group(1).trim();

        // DATE-WRITTEN
        m = DATE_WRITTEN_PAT.matcher(fullContent);
        if (m.find()) meta.dateWritten = m.group(1).trim().replace(".", "");

        // Description from comment block (lines starting with * near the top)
        meta.programDescription = extractCommentDescription(fullContent);

        // COPY statements
        m = COPY_PAT.matcher(fullContent);
        while (m.find()) {
            String cpyName = m.group(1).trim().toUpperCase();
            if (!meta.allCopybooks.contains(cpyName)) {
                meta.allCopybooks.add(cpyName);
            }
        }

        // ENTRY points (service programs / BOs)
        m = ENTRY_PAT.matcher(fullContent);
        while (m.find()) {
            meta.entryPoints.add(m.group(1).trim().toUpperCase());
        }
        meta.isServiceProgram = !meta.entryPoints.isEmpty();

        // FILE-CONTROL: SELECT ... ASSIGN TO
        extractFileAssignments(fullContent, meta);

        // FD → 01 record mapping
        buildRecordToFileMap(fullContent, meta);

        // OPEN statements to determine access mode
        extractOpenModes(fullContent, meta);

        // CICS and batch detection
        String upper = fullContent.toUpperCase();
        meta.isCicsProgram = upper.contains("EXEC CICS");
        meta.isBatchProgram = !meta.isCicsProgram
            && (upper.contains("UNTIL WS-END-OF-FILE")
                || upper.contains("AT END")
                || (upper.contains("READ") && upper.contains("NEXT RECORD")));

        return meta;
    }

    private String extractCommentDescription(String content) {
        String[] lines = content.split("\n");
        StringBuilder desc = new StringBuilder();
        boolean inCommentBlock = false;
        int commentLineCount = 0;

        for (String line : lines) {
            // Comment line (col 7 is *)
            Matcher cm = COMMENT_LINE_PAT.matcher(line);
            if (cm.matches()) {
                String commentText = cm.group(1).trim();
                if (commentText.startsWith("*") || commentText.startsWith("=")) {
                    inCommentBlock = true;
                    continue;
                }
                if (inCommentBlock && commentLineCount < 8) {
                    // Filter out purely decorative lines
                    if (!commentText.matches("[=*-]+")) {
                        // Extract meaningful comment content, skip labels like "PROGRAM-ID:"
                        String stripped = commentText.replaceFirst("^[A-Z][A-Z-]+\\s*:\\s*", "");
                        if (!stripped.isBlank() && stripped.length() > 5) {
                            if (desc.length() > 0) desc.append(" ");
                            desc.append(stripped.trim());
                            commentLineCount++;
                        }
                    }
                }
            } else if (line.trim().toUpperCase().startsWith("IDENTIFICATION DIVISION")
                    || line.trim().toUpperCase().startsWith("PROGRAM-ID")) {
                // Stop at code start
                if (desc.length() > 0) break;
            }
        }
        return desc.toString().replaceAll("\\s+", " ").trim();
    }

    private void extractFileAssignments(String content, ProgramMetadata meta) {
        // Grab the FILE-CONTROL block
        int fcStart = content.toUpperCase().indexOf("FILE-CONTROL");
        int fcEnd = content.toUpperCase().indexOf("DATA DIVISION");
        if (fcStart < 0) return;
        String fcBlock = fcEnd > fcStart ? content.substring(fcStart, fcEnd) : content.substring(fcStart);

        // Find all SELECT blocks
        String[] selectBlocks = fcBlock.toUpperCase().split("\\bSELECT\\b");
        for (String block : selectBlocks) {
            if (block.isBlank()) continue;
            // Extract logical file name (first token)
            String[] tokens = block.trim().split("\\s+");
            if (tokens.length < 1) continue;
            String logicalName = tokens[0].replaceAll("[^A-Z0-9-]", "");
            if (logicalName.isBlank()) continue;

            // ASSIGN TO
            Matcher am = SELECT_PAT.matcher("SELECT " + logicalName + " " + block);
            if (am.find()) {
                meta.fileAssignments.put(am.group(1).toUpperCase(), am.group(2).toUpperCase());
            }

            // ORGANIZATION IS
            Matcher om = ORGANIZATION_PAT.matcher(block);
            if (om.find()) {
                meta.fileOrganization.put(logicalName, om.group(1).toUpperCase());
            }
        }
    }

    private void buildRecordToFileMap(String content, ProgramMetadata meta) {
        // Find "FD filename" then the following "01 record-name"
        String upper = content.toUpperCase();
        int dataDiv = upper.indexOf("FILE SECTION");
        int wsDiv = upper.indexOf("WORKING-STORAGE SECTION");
        if (dataDiv < 0) return;
        String fileSection = wsDiv > dataDiv ? upper.substring(dataDiv, wsDiv) : upper.substring(dataDiv);

        String currentFd = null;
        for (String line : fileSection.split("\n")) {
            Matcher fdm = FD_PAT.matcher(line);
            if (fdm.find()) {
                currentFd = fdm.group(1).trim().toUpperCase();
            } else if (currentFd != null) {
                Matcher r01m = RECORD_01_PAT.matcher(line);
                if (r01m.find()) {
                    String recordName = r01m.group(1).trim().toUpperCase();
                    meta.recordToFileMap.put(recordName, currentFd);
                }
            }
        }
    }

    private void extractOpenModes(String content, ProgramMetadata meta) {
        Matcher m = OPEN_PAT.matcher(content);
        while (m.find()) {
            String mode = m.group(1).toUpperCase();
            String file = m.group(2).toUpperCase();
            meta.fileAccessModes.put(file, mode);
        }
    }

    // ========================================================
    // Section-level metadata extraction
    // ========================================================

    public List<String> extractFilesRead(String content, ProgramMetadata meta) {
        Set<String> result = new LinkedHashSet<>();
        if (!meta.isCicsProgram) {
            Matcher m = READ_PAT.matcher(content);
            while (m.find()) {
                String name = m.group(1).trim().toUpperCase();
                if (!SKIP_AFTER_READ.contains(name)) result.add(resolveToLogicalFile(name, meta));
            }
        }
        Matcher m = CICS_READ_PAT.matcher(content);
        while (m.find()) result.add(m.group(1).trim().toUpperCase());
        return new ArrayList<>(result);
    }

    public List<String> extractFilesWritten(String content, ProgramMetadata meta) {
        Set<String> result = new LinkedHashSet<>();
        if (!meta.isCicsProgram) {
            Matcher m = WRITE_PAT.matcher(content);
            while (m.find()) {
                String recordName = m.group(1).trim().toUpperCase();
                result.add(meta.recordToFileMap.getOrDefault(recordName, recordName));
            }
        }
        Matcher m = CICS_WRITE_PAT.matcher(content);
        while (m.find()) result.add(m.group(1).trim().toUpperCase());
        return new ArrayList<>(result);
    }

    public List<String> extractFilesUpdated(String content, ProgramMetadata meta) {
        Set<String> result = new LinkedHashSet<>();
        if (!meta.isCicsProgram) {
            Matcher m = REWRITE_PAT.matcher(content);
            while (m.find()) {
                String recordName = m.group(1).trim().toUpperCase();
                result.add(meta.recordToFileMap.getOrDefault(recordName, recordName));
            }
        }
        Matcher m = CICS_REWRITE_PAT.matcher(content);
        while (m.find()) result.add(m.group(1).trim().toUpperCase());
        return new ArrayList<>(result);
    }

    public List<String> extractFilesDeleted(String content, ProgramMetadata meta) {
        Set<String> result = new LinkedHashSet<>();
        if (!meta.isCicsProgram) {
            Matcher m = DELETE_PAT.matcher(content);
            while (m.find()) {
                String name = m.group(1).trim().toUpperCase();
                if (SKIP_AFTER_READ.contains(name) || SKIP_AFTER_DELETE.contains(name)) continue;
                if (!name.contains("-")) continue;
                String file = meta.fileAssignments.containsKey(name) ? name
                    : meta.recordToFileMap.getOrDefault(name, name);
                result.add(file);
            }
        }
        Matcher m = CICS_DELETE_PAT.matcher(content);
        while (m.find()) result.add(m.group(1).trim().toUpperCase());
        return new ArrayList<>(result);
    }

    public List<String> extractExternalCalls(String content, ProgramMetadata meta) {
        Set<String> result = new LinkedHashSet<>();
        if (!meta.isCicsProgram) {
            Matcher m = CALL_PAT.matcher(content);
            while (m.find()) result.add(m.group(1).trim().toUpperCase());
        }
        Matcher m = CICS_LINK_PAT.matcher(content);
        while (m.find()) result.add(m.group(1).trim().toUpperCase());
        return new ArrayList<>(result);
    }

    public List<String> extractExternalCalls(String content) {
        Set<String> result = new LinkedHashSet<>();
        Matcher m = CALL_PAT.matcher(content);
        while (m.find()) result.add(m.group(1).trim().toUpperCase());
        m = CICS_LINK_PAT.matcher(content);
        while (m.find()) result.add(m.group(1).trim().toUpperCase());
        return new ArrayList<>(result);
    }

    public List<String> extractSortFiles(String content) {
        Set<String> result = new LinkedHashSet<>();
        Matcher m = SORT_PAT.matcher(content);
        while (m.find()) result.add(m.group(1).trim().toUpperCase());
        return new ArrayList<>(result);
    }

    public List<String> extractPerformCalls(String content) {
        Set<String> result = new LinkedHashSet<>();
        Matcher m = PERFORM_PAT.matcher(content);
        while (m.find()) {
            result.add(m.group(1).trim().toUpperCase());
        }
        return new ArrayList<>(result);
    }

    public List<String> extractBusinessConditions(String content) {
        Set<String> result = new LinkedHashSet<>();

        // EVALUATE WHEN clauses
        Matcher m = EVALUATE_WHEN_PAT.matcher(content);
        while (m.find()) {
            String cond = m.group(1).trim().toUpperCase();
            if (!cond.equals("OTHER") && !cond.equals("TRUE") && !cond.isBlank()) {
                result.add("WHEN " + cond);
            }
        }

        // IF conditions (up to 8 meaningful ones)
        m = IF_PAT.matcher(content);
        int ifCount = 0;
        while (m.find() && ifCount < 8) {
            String cond = m.group(1).trim().toUpperCase();
            String firstWord = cond.split("\\s+")[0];
            if (cond.length() > 3
                    && !cond.startsWith("WS-EOF")
                    && !cond.startsWith("ERR-SYSTEM")
                    && !COBOL_KEYWORDS.contains(firstWord)) {
                result.add("IF " + cond);
                ifCount++;
            }
        }
        return new ArrayList<>(result);
    }

    public List<String> extractKeyDataFields(String content) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        Matcher m = BIZ_FIELD_PAT.matcher(content);
        while (m.find()) {
            String field = m.group(1).trim().toUpperCase();
            String prefix = field.split("-")[0];
            // Skip excluded prefixes (WS-, FS-, paragraph verb prefixes, etc.)
            if (EXCLUDE_FIELD_PREFIXES.contains(prefix)) continue;
            // Skip paragraph/section label names (these appear in section headers, not data)
            if (field.endsWith("-PARA") || field.endsWith("-SECTION")) continue;
            freq.merge(field, 1, Integer::sum);
        }
        return freq.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(12)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    public List<String> extractCopybooksInChunk(String content) {
        List<String> result = new ArrayList<>();
        Matcher m = COPY_PAT.matcher(content);
        while (m.find()) {
            result.add(m.group(1).trim().toUpperCase());
        }
        return result;
    }

    public List<String> extractOpenedFiles(String content) {
        List<String> result = new ArrayList<>();
        Matcher m = OPEN_PAT.matcher(content);
        while (m.find()) {
            result.add(m.group(2).trim().toUpperCase() + " [" + m.group(1).toUpperCase() + "]");
        }
        return result;
    }

    // ========================================================
    // Section purpose inference
    // ========================================================

    public String inferSectionPurpose(String sectionName, String content,
                                      ProgramMetadata meta, String division) {
        if ("IDENTIFICATION".equalsIgnoreCase(division)) {
            return "Program identification block: defines program name '" + meta.programId
                + "', author '" + meta.author + "', written " + meta.dateWritten + ".";
        }
        if ("ENVIRONMENT".equalsIgnoreCase(division)) {
            return buildEnvironmentPurpose(content, meta);
        }
        if ("DATA".equalsIgnoreCase(division)) {
            return buildDataDivisionPurpose(sectionName, content, meta);
        }

        // PROCEDURE DIVISION
        return buildProcedurePurpose(sectionName, content, meta);
    }

    private String buildEnvironmentPurpose(String content, ProgramMetadata meta) {
        StringBuilder sb = new StringBuilder("Environment configuration for IBM iSeries.");
        if (!meta.fileAssignments.isEmpty()) {
            sb.append(" Maps logical files to physical AS400 databases: ");
            List<String> mappings = new ArrayList<>();
            meta.fileAssignments.forEach((logical, physical) ->
                mappings.add(logical + " → DATABASE-" + physical));
            sb.append(String.join(", ", mappings)).append(".");
        }
        return sb.toString();
    }

    private String buildDataDivisionPurpose(String sectionName, String content, ProgramMetadata meta) {
        String upper = sectionName.toUpperCase();
        if (upper.contains("FILE")) {
            StringBuilder sb = new StringBuilder("File record definitions. ");
            if (!meta.allCopybooks.isEmpty()) {
                sb.append("Includes copybooks: ").append(String.join(", ", meta.allCopybooks)).append(". ");
            }
            sb.append("Defines record layouts for: ");
            List<String> records = new ArrayList<>(meta.recordToFileMap.keySet());
            sb.append(records.isEmpty() ? "see inline definitions" : String.join(", ", records)).append(".");
            return sb.toString();
        }
        if (upper.contains("WORKING-STORAGE")) {
            List<String> groups = extractWorkingStorageGroups(content);
            String groupList = groups.isEmpty() ? "counters, flags, and display fields"
                : String.join(", ", groups);
            return "Working storage variables: " + groupList + ". "
                + "Initializes file status codes, processing flags, and calculation fields.";
        }
        if (upper.contains("LINKAGE")) {
            return "Linkage section: defines parameters passed to this program by the calling application.";
        }
        return "Data definitions for " + sectionName + " section.";
    }

    private List<String> extractWorkingStorageGroups(String content) {
        List<String> groups = new ArrayList<>();
        Pattern grpPat = Pattern.compile("^\\s+01\\s+([A-Z][A-Z0-9-]+)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher m = grpPat.matcher(content);
        while (m.find()) {
            groups.add(m.group(1).toUpperCase());
        }
        return groups;
    }

    private String buildProcedurePurpose(String sectionName, String content, ProgramMetadata meta) {
        String nameLower = sectionName.replaceAll("^\\d{4}-", "").replace("-", " ").toLowerCase();
        // Use token set to avoid false-positive substring matches (e.g. "surrender" contains "end")
        Set<String> tokens = new HashSet<>(Arrays.asList(nameLower.split("\\s+")));

        List<String> performs = extractPerformCalls(content);
        List<String> filesRead = extractFilesRead(content, meta);
        List<String> filesWritten = extractFilesWritten(content, meta);
        List<String> filesUpdated = extractFilesUpdated(content, meta);
        List<String> filesDeleted = extractFilesDeleted(content, meta);
        List<String> sortFiles = extractSortFiles(content);
        List<String> externalCalls = extractExternalCalls(content, meta);

        String role = classifyRole(nameLower, content);
        StringBuilder sb = new StringBuilder(role).append(": ");

        if (tokens.contains("main")) {
            if (!performs.isEmpty()) {
                sb.append("orchestrates flow — ");
                sb.append(performs.stream()
                    .map(p -> p.replaceAll("-PARA$", "").replaceAll("^\\d{4}-", ""))
                    .collect(Collectors.joining(" → ")));
            } else {
                sb.append("program entry point and control flow coordinator");
            }
        } else if (tokens.contains("init")) {
            List<String> openedFiles = extractOpenedFiles(content);
            sb.append("initializes working storage");
            if (!openedFiles.isEmpty()) {
                sb.append(", opens files: ").append(String.join(", ", openedFiles));
            }
            if (!meta.allCopybooks.isEmpty()) {
                sb.append(". Uses copybooks: ").append(String.join(", ", meta.allCopybooks));
            }
        } else if (tokens.contains("end") || tokens.contains("term")) {
            List<String> openedFiles = new ArrayList<>(meta.fileAssignments.keySet());
            sb.append("closes");
            if (!openedFiles.isEmpty()) {
                sb.append(" ").append(String.join(", ", openedFiles));
            } else {
                sb.append(" all open files");
            }
            sb.append(", displays completion status");
        } else if (tokens.contains("error") || sectionName.toUpperCase().contains("HANDLE-ERR")) {
            sb.append("displays ERR-RETURN-CODE and ERR-MESSAGE-TEXT. ");
            sb.append("Performs 9000-END-PARA and STOP RUN on fatal error codes");
        } else if (!filesRead.isEmpty() || !filesWritten.isEmpty() || !filesUpdated.isEmpty()
                || !filesDeleted.isEmpty() || !sortFiles.isEmpty() || !externalCalls.isEmpty()) {
            buildFileOpDescription(sb, filesRead, filesWritten, filesUpdated,
                filesDeleted, sortFiles, externalCalls, performs);
        } else if (!performs.isEmpty()) {
            sb.append("delegates to: ")
              .append(performs.stream().limit(5).collect(Collectors.joining(", ")));
        } else {
            sb.append("performs ").append(nameLower).append(" processing");
        }

        // Append evaluate conditions if present
        appendEvaluateContext(sb, content, nameLower);

        return sb.toString();
    }

    private String classifyRole(String nameLower, String content) {
        // Use token set to avoid substring false-positives (e.g. "surrender" containing "end")
        Set<String> tokens = new HashSet<>(Arrays.asList(nameLower.split("\\s+")));
        String upper = content.toUpperCase();
        if (tokens.contains("main")) return "Main control";
        if (tokens.contains("init")) return "Initialization";
        if (tokens.contains("end") || tokens.contains("term")) return "Program termination";
        if (tokens.contains("error") || nameLower.contains("handle-err")) return "Error handling";
        if (tokens.contains("summary") || tokens.contains("report")) return "Summary/reporting";
        if (tokens.contains("audit")) return "Audit logging";
        if (tokens.contains("calc") || tokens.contains("calculate")) return "Calculation";
        if (tokens.contains("valid") || tokens.contains("validate")) return "Validation";
        if (tokens.contains("load") || tokens.contains("get")) return "Data retrieval";
        if (tokens.contains("read") && !tokens.contains("rewrite")) return "Data retrieval";
        if (tokens.contains("write") || tokens.contains("update") || tokens.contains("queue")) return "Data update";
        if (tokens.contains("display") || tokens.contains("print")) return "Display/output";
        if (tokens.contains("process") || tokens.contains("loop")) return "Processing";
        if (tokens.contains("apply")) return "Apply operation";
        if (tokens.contains("generate") || tokens.contains("create")) return "Generation";
        if (tokens.contains("lapse")) return "Lapse processing";
        if (tokens.contains("renew") || tokens.contains("renewal")) return "Renewal processing";
        if (tokens.contains("notice")) return "Notice generation";
        if (tokens.contains("output") || tokens.contains("result")) return "Output generation";
        if (tokens.contains("check")) return "Conditional check";
        if (tokens.contains("surrender")) return "Surrender value processing";
        if (tokens.contains("death") || tokens.contains("benefit")) return "Death benefit processing";
        if (tokens.contains("loan")) return "Policy loan processing";
        if (tokens.contains("mortality")) return "Mortality cost calculation";
        if (tokens.contains("interest") || tokens.contains("rate")) return "Interest rate lookup";
        if (upper.contains("EVALUATE TRUE") || upper.contains("EVALUATE")) return "Conditional branching";
        return "Processing";
    }

    private void buildFileOpDescription(StringBuilder sb,
                                         List<String> read, List<String> written,
                                         List<String> updated, List<String> deleted,
                                         List<String> sortFiles, List<String> externalCalls,
                                         List<String> performs) {
        if (!read.isEmpty()) {
            sb.append("reads ").append(String.join(", ", read));
        }
        if (!written.isEmpty()) {
            if (sb.length() > 20) sb.append("; writes ");
            else sb.append("writes ");
            sb.append(String.join(", ", written));
        }
        if (!updated.isEmpty()) {
            if (sb.length() > 20) sb.append("; rewrites ");
            else sb.append("rewrites ");
            sb.append(String.join(", ", updated));
        }
        if (!deleted.isEmpty()) {
            if (sb.length() > 20) sb.append("; deletes from ");
            else sb.append("deletes from ");
            sb.append(String.join(", ", deleted));
        }
        if (!sortFiles.isEmpty()) {
            sb.append(". Sorts: ").append(String.join(", ", sortFiles));
        }
        if (!externalCalls.isEmpty()) {
            sb.append(". Invokes: ").append(String.join(", ", externalCalls));
        }
        if (!performs.isEmpty()) {
            sb.append(". Calls: ").append(
                performs.stream().limit(4)
                    .map(p -> p.replaceAll("-PARA$", "").replaceAll("^\\d{4}-", ""))
                    .collect(Collectors.joining(", ")));
        }
    }

    private void appendEvaluateContext(StringBuilder sb, String content, String nameLower) {
        // Extract EVALUATE WHEN clauses to show business rules
        List<String> whens = new ArrayList<>();
        Matcher m = EVALUATE_WHEN_PAT.matcher(content);
        int count = 0;
        while (m.find() && count < 6) {
            String w = m.group(1).trim().toUpperCase();
            if (!w.equals("OTHER") && !w.equals("TRUE") && w.length() > 2) {
                whens.add(w);
                count++;
            }
        }
        if (!whens.isEmpty() && (nameLower.contains("calc") || nameLower.contains("valid")
                || nameLower.contains("build") || nameLower.contains("type")
                || nameLower.contains("status") || nameLower.contains("factor"))) {
            sb.append(". Evaluates: ").append(String.join(", ", whens));
        }
    }

    // ========================================================
    // Tag generation
    // ========================================================

    public List<String> generateTags(String programId, String subDomain, String sectionName,
                                      String fileType, boolean hasFileIO, boolean hasErrorHandling,
                                      ProgramMetadata meta) {
        return generateTags(programId, subDomain, sectionName, fileType,
            hasFileIO, hasErrorHandling, meta, "INSURANCE");
    }

    public List<String> generateTags(String programId, String subDomain, String sectionName,
                                      String fileType, boolean hasFileIO, boolean hasErrorHandling,
                                      ProgramMetadata meta, String domain) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("as400");
        tags.add("cobol");
        tags.add(domain.toLowerCase());
        tags.add(fileType.toLowerCase().replace("_", "-"));

        // Sub-domain tags
        if (subDomain != null) {
            for (String part : subDomain.split("_")) {
                tags.add(part.toLowerCase());
            }
        }

        // Program name keywords
        String pid = programId.toUpperCase();
        if (domain.equalsIgnoreCase("INSURANCE")) {
            if (pid.contains("PLY") || pid.contains("PLCY") || pid.contains("POL")) tags.add("policy");
            if (pid.contains("CLM")) tags.add("claims");
            if (pid.contains("PREM")) tags.add("premium");
            if (pid.contains("BNF")) tags.add("beneficiary");
            if (pid.contains("RNW") || pid.contains("RNWL")) tags.add("renewal");
            if (pid.contains("INTLF") || pid.contains("INTL")) { tags.add("integral-life"); tags.add("life-insurance"); }
        } else {
            if (pid.contains("ACCT") || pid.contains("COAD")) tags.add("account");
            if (pid.contains("CARD") || pid.contains("COCRD")) tags.add("credit-card");
            if (pid.contains("TRAN") || pid.contains("COTRN")) tags.add("transaction");
            if (pid.contains("SGN") || pid.contains("SIGN")) tags.add("authentication");
            if (pid.contains("BIL") || pid.contains("COBIL")) tags.add("billing");
        }

        // Section type
        if (sectionName != null) {
            String sn = sectionName.toLowerCase();
            if (sn.contains("calc")) tags.add("calculation");
            if (sn.contains("valid")) tags.add("validation");
            if (sn.contains("report") || sn.contains("display")) tags.add("reporting");
            if (sn.contains("batch") || sn.contains("loop")) tags.add("batch");
            if (sn.contains("error")) tags.add("error-handling");
            if (sn.contains("audit")) tags.add("audit");
            if (sn.contains("queue")) tags.add("payment-queue");
            if (sn.contains("lapse")) tags.add("lapse-processing");
            if (sn.contains("surrender")) tags.add("surrender-value");
            if (sn.contains("death") || sn.contains("benefit")) tags.add("death-benefit");
            if (sn.contains("loan")) tags.add("policy-loan");
        }

        if (hasFileIO) tags.add("file-io");
        if (hasErrorHandling) tags.add("error-handling");
        if (meta.isServiceProgram) tags.add("service-program");
        if (meta.isBatchProgram)   tags.add("batch-processing");
        if (meta.isCicsProgram)    { tags.add("cics"); tags.add("online"); }
        if (!meta.entryPoints.isEmpty()) tags.add("callable-interface");

        return new ArrayList<>(tags);
    }

    // ========================================================
    // Sub-domain inference
    // ========================================================

    public String inferSubDomain(String programId, String content) {
        String pid = programId.toUpperCase();
        // Banking credit card application checked FIRST: CB/CO/CV/CS/CC prefixes take precedence
        // (prevents e.g. COACTUPC matching ACTU → PREMIUM_CALCULATION via insurance path)
        if (pid.startsWith("CB") || pid.startsWith("CO")
                || pid.startsWith("CV") || pid.startsWith("CS") || pid.startsWith("CC")) {
            if (pid.contains("ACT"))   return "ACCOUNT_MANAGEMENT";
            if (pid.contains("TRN") || pid.contains("TRAN") || pid.contains("TRA")) return "TRANSACTION_PROCESSING";
            if (pid.contains("CRD") || pid.contains("CARD")) return "CARD_MANAGEMENT";
            if (pid.contains("CUS"))   return "CUSTOMER_MANAGEMENT";
            if (pid.contains("BIL") || pid.contains("STM")) return "STATEMENT_BILLING";
            if (pid.contains("SGN") || pid.contains("USR")) return "USER_AUTHENTICATION";
            if (pid.contains("RPT") || pid.contains("REP") || pid.contains("REPT")) return "REPORTING";
            if (pid.contains("ADM"))   return "ADMINISTRATION";
            if (pid.contains("MEN"))   return "NAVIGATION";
            if (pid.contains("DAT") || pid.contains("LKP") || pid.contains("UTL")) return "BANKING_UTILITIES";
            if (pid.contains("MSG") || pid.contains("SET") || pid.contains("STR")) return "BANKING_UTILITIES";
            if (pid.contains("XPRT") || pid.contains("XPORT") || pid.contains("IMPORT")) return "DATA_TRANSFER";
            return "BANKING_OPERATIONS";
        }
        // Insurance domain (checked after banking prefixes to avoid false positives)
        if (pid.contains("PLCY") || pid.startsWith("PLY") || pid.startsWith("POL")) return "POLICY_MANAGEMENT";
        if (pid.startsWith("CLM") || pid.contains("CLAIM")) return "CLAIMS_PROCESSING";
        if (pid.contains("PREM") || pid.contains("PRMC") || pid.contains("ACTU")) return "PREMIUM_CALCULATION";
        if (pid.startsWith("BNF") || pid.contains("BENF") || pid.contains("BENE")) return "BENEFICIARY_MANAGEMENT";
        if (pid.contains("RNW") || pid.contains("RNWL") || pid.contains("RENEW")) return "POLICY_RENEWAL";
        if (pid.contains("INTLF") || pid.contains("ILBO")) return "INTEGRAL_LIFE_PRODUCT";
        if (pid.contains("UNDW") || pid.contains("RISK")) return "UNDERWRITING";
        if (pid.contains("BILL") || pid.contains("PYMT") || pid.contains("PAY")) return "BILLING_PAYMENT";
        if (pid.contains("AGNT") || pid.contains("BRKR")) return "AGENT_BROKER";
        // Content-based inference for programs with non-standard or abbreviated names
        if (content != null) {
            String upper = content.toUpperCase();
            // Insurance domain (check first — insurance files typically don't contain banking terms)
            if (upper.contains("CLAIM") && upper.contains("POLICY")) return "CLAIMS_PROCESSING";
            if (upper.contains("PREMIUM") || upper.contains("ACTUARIAL")) return "PREMIUM_CALCULATION";
            if (upper.contains("BENEFICIARY")) return "BENEFICIARY_MANAGEMENT";
            if (upper.contains("RENEWAL") || upper.contains("LAPSE-DATE")) return "POLICY_RENEWAL";
            if (upper.contains("INTEGRAL LIFE") || upper.contains("SURRENDER VALUE")) return "INTEGRAL_LIFE_PRODUCT";
            if (upper.contains("CLAIM")) return "CLAIMS_PROCESSING";
            if (upper.contains("POLICY") || upper.contains("PREMIUM")) return "POLICY_MANAGEMENT";
            // Banking / credit card domain
            if (upper.contains("CREDIT CARD") || upper.contains("CREDIT-CARD")) return "CARD_MANAGEMENT";
            if (upper.contains("ACCOUNT") && upper.contains("BALANCE")) return "ACCOUNT_MANAGEMENT";
            if (upper.contains("TRANSACTION") && upper.contains("AMOUNT")) return "TRANSACTION_PROCESSING";
            if (upper.contains("STATEMENT") || upper.contains("BILLING-CYCLE")) return "STATEMENT_BILLING";
            if (upper.contains("SIGNON") || upper.contains("SIGN-ON") || upper.contains("LOGON")) return "USER_AUTHENTICATION";
            if (upper.contains("ACCOUNT-ID") || upper.contains("ACCT-ID")) return "ACCOUNT_MANAGEMENT";
            if (upper.contains("TRANSACTION")) return "TRANSACTION_PROCESSING";
            if (upper.contains("CARD-NUM") || upper.contains("CARDNUM")) return "CARD_MANAGEMENT";
        }
        return "INSURANCE_OPERATIONS";
    }

    // ========================================================
    // Domain inference — derived from the already-computed sub-domain
    // so no hardcoded "INSURANCE" or "BANKING" is needed in callers
    // ========================================================

    public static String inferDomain(String subDomain) {
        if (subDomain == null) return "GENERAL";
        return switch (subDomain) {
            case "POLICY_MANAGEMENT", "CLAIMS_PROCESSING", "PREMIUM_CALCULATION",
                 "BENEFICIARY_MANAGEMENT", "POLICY_RENEWAL", "INTEGRAL_LIFE_PRODUCT",
                 "UNDERWRITING", "BILLING_PAYMENT", "AGENT_BROKER", "INSURANCE_OPERATIONS"
                 -> "INSURANCE";
            case "ACCOUNT_MANAGEMENT", "CARD_MANAGEMENT", "TRANSACTION_PROCESSING",
                 "CUSTOMER_MANAGEMENT", "STATEMENT_BILLING", "USER_AUTHENTICATION",
                 "BANKING_OPERATIONS", "BANKING_UTILITIES", "NAVIGATION",
                 "ADMINISTRATION", "REPORTING", "DATA_TRANSFER"
                 -> "BANKING";
            default -> "GENERAL";
        };
    }

    public String inferProcessingType(String programId, ProgramMetadata meta) {
        if (meta.isServiceProgram) return "SERVICE_PROGRAM_BO";
        if (meta.isCicsProgram)    return "CICS_ONLINE";
        if (meta.isBatchProgram)   return "BATCH_PROCESSING";
        String pid = programId.toUpperCase();
        if (pid.contains("INQ") || pid.contains("QRY") || pid.endsWith("Q")) return "ONLINE_INQUIRY";
        if (pid.contains("UPD") || pid.contains("MNT") || pid.contains("CHG")) return "ONLINE_UPDATE";
        if (pid.contains("PRC") || pid.contains("PROC") || pid.contains("BATCH")) return "BATCH_PROCESSING";
        if (pid.contains("RNW") || pid.contains("RPT") || pid.contains("REPT")) return "BATCH_PROCESSING";
        if (pid.contains("ADD") || pid.contains("ENT") || pid.contains("ENR")) return "ONLINE_ENTRY";
        if (pid.contains("DEL") || pid.contains("CAN")) return "ONLINE_UPDATE";
        if (pid.contains("LIST") || pid.contains("PRNT")) return "REPORT_GENERATION";
        return "ONLINE_TRANSACTION";
    }

    // ========================================================
    // Helpers
    // ========================================================

    private String resolveToLogicalFile(String name, ProgramMetadata meta) {
        // Try to find the logical file name in assignments
        for (String logical : meta.fileAssignments.keySet()) {
            if (logical.equalsIgnoreCase(name) || logical.startsWith(name.split("-")[0])) {
                return logical;
            }
        }
        return name;
    }
}