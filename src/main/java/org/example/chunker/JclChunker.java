package org.example.chunker;

import org.example.graph.KnowledgeGraphBuilder;
import org.example.model.FileChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chunks JCL (Job Control Language) files.
 * Produces one chunk per JCL step plus a JOB-level header chunk.
 */
public class JclChunker {

    private static final Pattern JOB_PAT = Pattern.compile(
        "^//([A-Z][A-Z0-9]*(?:\\s+JOB\\s+))(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STEP_PAT = Pattern.compile(
        "^//([A-Z][A-Z0-9]*)\\s+EXEC\\s+PGM=([A-Z][A-Z0-9]*)(.*)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DD_PAT = Pattern.compile(
        "^//([A-Z][A-Z0-9]*)\\s+DD\\s+DSN=([A-Z0-9.&]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYSOUT_PAT = Pattern.compile(
        "^//([A-Z][A-Z0-9]*)\\s+DD\\s+SYSOUT=", Pattern.CASE_INSENSITIVE);
    private static final Pattern COND_PAT = Pattern.compile(
        "COND=\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARM_PAT = Pattern.compile(
        "PARM='?([^',\\s]+)", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> PROGRAM_DESCRIPTIONS = Map.ofEntries(
        Map.entry("PLCYINQ", "Policy Inquiry Program"),
        Map.entry("CLMPRC",  "Claims Processing Program"),
        Map.entry("PREMINQ", "Premium Calculation Program"),
        Map.entry("BNFUPD",  "Beneficiary Update Program"),
        Map.entry("POLRNWL", "Policy Renewal Batch Program"),
        Map.entry("INTLFBO", "Integral Life Business Object"),
        Map.entry("IEBGENER","IBM Utility - Sequential File Copy"),
        Map.entry("SORT",    "IBM Utility - Sort/Merge"),
        Map.entry("IEFBR14","IBM Utility - Do-Nothing Step")
    );

    public JclChunker() {}

    public List<FileChunk> chunk(Path filePath) throws IOException {
        return chunk(filePath, null);
    }

    public List<FileChunk> chunk(Path filePath, KnowledgeGraphBuilder graphBuilder) throws IOException {
        List<String> lines = Files.readAllLines(filePath);
        String fileName = filePath.getFileName().toString();
        String jobName = extractJobName(lines);

        List<FileChunk> chunks = new ArrayList<>();

        // --- JOB header chunk ---
        List<String> jobHeaderLines = new ArrayList<>();
        List<String> currentStepLines = new ArrayList<>();
        String currentStepName = null;
        String currentProgram = null;
        String currentParm = null;
        String currentCond = null;
        int stepStart = 1;
        int lineIdx = 0;

        for (String line : lines) {
            lineIdx++;
            Matcher stepMatcher = STEP_PAT.matcher(line);
            if (stepMatcher.find()) {
                // Save previous step or job header
                if (currentStepName != null) {
                    chunks.add(buildStepChunk(fileName, jobName, currentStepName,
                        currentProgram, currentParm, currentCond,
                        currentStepLines, stepStart, lineIdx - 1));
                } else {
                    // This is the first EXEC — save job header
                    jobHeaderLines.addAll(currentStepLines);
                }

                currentStepName = stepMatcher.group(1).toUpperCase();
                currentProgram = stepMatcher.group(2).toUpperCase();
                String restOfLine = stepMatcher.group(3);

                // Extract PARM
                Matcher pm = PARM_PAT.matcher(restOfLine);
                currentParm = pm.find() ? pm.group(1) : null;

                // Extract COND
                Matcher cm = COND_PAT.matcher(restOfLine);
                currentCond = cm.find() ? cm.group(1) : null;

                currentStepLines = new ArrayList<>();
                currentStepLines.add(line);
                stepStart = lineIdx;
            } else {
                // Check for COND on continuation line if not yet found
                if (currentStepName != null && currentCond == null) {
                    Matcher cm = COND_PAT.matcher(line);
                    if (cm.find()) currentCond = cm.group(1);
                }
                if (currentStepName == null) {
                    jobHeaderLines.add(line);
                } else {
                    currentStepLines.add(line);
                }
            }
        }

        // Save last step
        if (currentStepName != null) {
            chunks.add(buildStepChunk(fileName, jobName, currentStepName,
                currentProgram, currentParm, currentCond,
                currentStepLines, stepStart, lineIdx));
        }

        // Job header chunk at the front
        if (!jobHeaderLines.isEmpty()) {
            chunks.add(0, buildJobHeaderChunk(fileName, jobName, jobHeaderLines));
        }

        // Set indexes
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i + 1);
            chunks.get(i).setTotalChunks(chunks.size());
        }

        if (graphBuilder != null) {
            registerInGraph(graphBuilder, jobName, chunks);
        }
        return chunks;
    }

    private void registerInGraph(KnowledgeGraphBuilder graphBuilder,
                                  String jobName, List<FileChunk> chunks) {
        if (chunks.isEmpty()) return;

        Set<String> programs = new LinkedHashSet<>();
        Set<String> datasets = new LinkedHashSet<>();

        for (FileChunk c : chunks) {
            String sn = c.getSectionName();
            if (sn != null && sn.contains(" → ")) {
                programs.add(sn.split(" → ")[1].trim());
            }
            if (c.getFilesRead() != null) {
                for (String ds : c.getFilesRead()) datasets.add(ds.split("\\[")[0].trim());
            }
            if (c.getFilesWritten() != null) {
                for (String ds : c.getFilesWritten()) datasets.add(ds.split("\\[")[0].trim());
            }
        }

        String subDomain = chunks.get(0).getSubDomain();
        String domain = CobolAnalyzer.inferDomain(subDomain);
        graphBuilder.registerJclJob(jobName, domain, subDomain,
            new ArrayList<>(programs), new ArrayList<>(datasets));
    }

    private FileChunk buildJobHeaderChunk(String fileName, String jobName, List<String> lines) {
        String content = String.join("\n", lines);
        String subDomain = inferSubDomain(jobName);
        String domain = CobolAnalyzer.inferDomain(subDomain);

        FileChunk chunk = new FileChunk();
        chunk.setChunkId(jobName + "_JOB_HEADER_001");
        chunk.setSourceFile(fileName);
        chunk.setFileType("JCL Job");
        chunk.setProgramId(jobName);
        chunk.setDomain(domain);
        chunk.setSubDomain(subDomain);
        chunk.setProcessingType("BATCH_JCL");
        chunk.setDivision("JOB_CARD");
        chunk.setSectionName("JOB HEADER");
        chunk.setLineStart(1);
        chunk.setLineEnd(lines.size());
        chunk.setSectionPurpose("JCL JOB card for " + jobName + ". "
            + "Defines job accounting, execution class, message class, and notification settings "
            + "for the " + humanizeJobName(jobName) + " batch job stream.");
        chunk.setTags(buildJclTags(jobName, subDomain, domain));
        chunk.setContent(content);
        return chunk;
    }

    private FileChunk buildStepChunk(String fileName, String jobName, String stepName,
                                      String program, String parm, String cond,
                                      List<String> stepLines, int lineStart, int lineEnd) {
        String content = String.join("\n", stepLines);

        // Extract datasets
        List<String> datasetsInput = new ArrayList<>();
        List<String> datasetsOutput = new ArrayList<>();
        for (String line : stepLines) {
            Matcher ddm = DD_PAT.matcher(line);
            if (ddm.find()) {
                String ddName = ddm.group(1).toUpperCase();
                String dsn = ddm.group(2).toUpperCase();
                if (line.toUpperCase().contains("DISP=SHR")
                        || line.toUpperCase().contains("DISP=(SHR")) {
                    datasetsInput.add(dsn + " [" + ddName + "]");
                } else if (line.toUpperCase().contains("DISP=(NEW")
                        || line.toUpperCase().contains("DISP=(MOD")) {
                    datasetsOutput.add(dsn + " [" + ddName + "]");
                }
            }
        }

        // Extract SYSIN parameters
        List<String> sysinParams = extractSysinParams(stepLines);

        String programDesc = PROGRAM_DESCRIPTIONS.getOrDefault(program,
            "Program " + program);
        String subDomain = inferSubDomain(jobName);
        String domain = CobolAnalyzer.inferDomain(subDomain);

        String purpose = "JCL step '" + stepName + "' executes " + programDesc + " (" + program + ")";
        if (parm != null) purpose += " with PARM='" + parm + "'";
        if (cond != null) purpose += ". Runs if previous step RC passes COND=(" + cond + ")";
        if (!datasetsInput.isEmpty()) {
            purpose += ". Reads: " + String.join(", ",
                datasetsInput.stream().map(d -> d.split("\\[")[0].trim()).toList());
        }
        if (!datasetsOutput.isEmpty()) {
            purpose += ". Creates/writes: " + String.join(", ",
                datasetsOutput.stream().map(d -> d.split("\\[")[0].trim()).toList());
        }
        if (!sysinParams.isEmpty()) {
            purpose += ". SYSIN parameters: " + String.join(", ", sysinParams);
        }

        List<String> tags = buildJclTags(jobName, subDomain, domain);
        tags.add("step-" + stepName.toLowerCase());
        tags.add(program.toLowerCase());

        FileChunk chunk = new FileChunk();
        chunk.setChunkId(jobName + "_" + stepName + "_" + String.format("%03d",
            Integer.parseInt(stepName.replaceAll("[^0-9]", "0").substring(0, 1) + "01")));
        chunk.setSourceFile(fileName);
        chunk.setFileType("JCL Job Step");
        chunk.setProgramId(jobName);
        chunk.setDomain(domain);
        chunk.setSubDomain(subDomain);
        chunk.setProcessingType("BATCH_JCL_STEP");
        chunk.setDivision("JCL_STEP");
        chunk.setSectionName(stepName + " → " + program);
        chunk.setLineStart(lineStart);
        chunk.setLineEnd(lineEnd);
        chunk.setSectionPurpose(purpose);
        chunk.setFilesRead(datasetsInput.isEmpty() ? null : datasetsInput);
        chunk.setFilesWritten(datasetsOutput.isEmpty() ? null : datasetsOutput);
        chunk.setBusinessConditions(sysinParams.isEmpty() ? null : sysinParams);
        chunk.setHasFileIO(!datasetsInput.isEmpty() || !datasetsOutput.isEmpty());
        chunk.setTags(tags);
        chunk.setContent(content);
        return chunk;
    }

    private List<String> extractSysinParams(List<String> lines) {
        List<String> params = new ArrayList<>();
        boolean inSysin = false;
        for (String line : lines) {
            if (line.toUpperCase().contains("//SYSIN")) { inSysin = true; continue; }
            if (inSysin && line.startsWith("/*")) { inSysin = false; continue; }
            if (inSysin && !line.startsWith("//") && !line.isBlank()) {
                params.add(line.trim());
            }
        }
        return params;
    }

    private String extractJobName(List<String> lines) {
        for (String line : lines) {
            if (line.startsWith("//") && line.toUpperCase().contains("JOB")) {
                String name = line.substring(2).trim().split("\\s+")[0];
                return name.toUpperCase();
            }
        }
        return "UNKNOWN-JOB";
    }

    private String inferSubDomain(String jobName) {
        String jn = jobName.toUpperCase();
        // Insurance
        if (jn.contains("PLY") || jn.contains("PLCY") || jn.contains("POL")) return "POLICY_MANAGEMENT";
        if (jn.contains("CLM")) return "CLAIMS_PROCESSING";
        if (jn.contains("PREM")) return "PREMIUM_CALCULATION";
        if (jn.contains("BNF")) return "BENEFICIARY_MANAGEMENT";
        if (jn.contains("INTLF")) return "INTEGRAL_LIFE_PRODUCT";
        // Banking (carddemo patterns)
        if (jn.contains("ACCT") || jn.contains("ACCTFILE") || jn.contains("READACCT")) return "ACCOUNT_MANAGEMENT";
        if (jn.contains("TRAN") || jn.contains("POSTTRAN") || jn.contains("COMBTRAN")) return "TRANSACTION_PROCESSING";
        if (jn.contains("CARD") || jn.contains("CARDFILE") || jn.contains("READCARD")) return "CARD_MANAGEMENT";
        if (jn.contains("CUST") || jn.contains("CUSTFILE") || jn.contains("READCUST")) return "CUSTOMER_MANAGEMENT";
        if (jn.contains("STMT") || jn.contains("CREASTMT") || jn.contains("TRANBKP")) return "STATEMENT_BILLING";
        if (jn.contains("USRSEC") || jn.contains("DUSRSEC")) return "USER_AUTHENTICATION";
        if (jn.contains("INTCALC")) return "TRANSACTION_PROCESSING";
        return "BATCH_OPERATIONS";
    }

    private List<String> buildJclTags(String jobName, String subDomain, String domain) {
        List<String> tags = new ArrayList<>();
        tags.add("as400");
        tags.add("jcl");
        tags.add(domain.toLowerCase());
        tags.add("batch-job");
        if (subDomain != null) {
            for (String part : subDomain.split("_")) tags.add(part.toLowerCase());
        }
        return tags;
    }

    private String humanizeJobName(String jobName) {
        return jobName.replace("-", " ").replace("BATCH", "batch").toLowerCase();
    }
}