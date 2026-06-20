package org.example;

import org.example.chunker.CobolChunker;
import org.example.chunker.JclChunker;
import org.example.fetcher.SourceFetcher;
import org.example.graph.GraphHtmlExporter;
import org.example.graph.GraphWriter;
import org.example.graph.KnowledgeGraph;
import org.example.graph.KnowledgeGraphBuilder;
import org.example.llm.EmbeddingDocumentBuilder;
import org.example.llm.LlmEnricher;
import org.example.model.FileChunk;
import org.example.model.FileType;
import org.example.writer.ChunkWriter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class Main {

    public static void main(String[] args) {
        System.out.println("============================================");
        System.out.println("  COBOL Source Ingestion Tool");
        System.out.println("  Multi-Domain | AS400 RAG + Graph");
        System.out.println("============================================");

        Path resourcesRoot = findResourcesDir();
        Path inputRoot     = resourcesRoot.resolve("input");
        Path outputDir     = resourcesRoot.resolve("output");

        try { Files.createDirectories(outputDir); }
        catch (IOException e) { System.err.println("ERROR creating output dir: " + e.getMessage()); return; }

        KnowledgeGraphBuilder graphBuilder = new KnowledgeGraphBuilder();
        CobolChunker cobolChunker = new CobolChunker();
        JclChunker   jclChunker   = new JclChunker();
        ChunkWriter  writer       = new ChunkWriter();
        int[] counters = {0, 0}; // [totalFiles, totalChunks]

        LlmEnricher enricher = LlmEnricher.create();
        if (enricher != null) {
            System.out.println("  LLM Enrichment : ENABLED (model: " + enricher.getModel() + ")");
        } else {
            System.out.println("  LLM Enrichment : DISABLED (set OPENAI_API_KEY to enable)");
        }

        // ── Source fetch (GitHub / Bitbucket / ZIP — set SOURCE_URL env var) ──
        System.out.println("\n--- Remote Source ---");
        SourceFetcher fetcher = new SourceFetcher();
        // Cache dir: SOURCE_CACHE_DIR env var, else derived from repo name
        String cacheDirName = System.getenv("SOURCE_CACHE_DIR");
        if (cacheDirName == null || cacheDirName.isBlank()) cacheDirName = fetcher.getRepoName();
        Path cardDemoRoot = inputRoot.resolve("github").resolve(cacheDirName);
        try {
            fetcher.fetchIfAbsent(cardDemoRoot);
        } catch (Exception e) {
            System.out.println("  WARNING: Fetch failed: " + e.getMessage());
            System.out.println("  Continuing with locally cached files (if any)...");
        }

        // ── Local insurance files (flat directory scan) ───────────
        processFlat(inputRoot.resolve("copybooks"), FileType.COPYBOOK,
            cobolChunker, null, "INSURANCE COPYBOOKS", graphBuilder, writer, outputDir, counters, enricher);
        processFlat(inputRoot.resolve("cobol"), FileType.COBOL_PROGRAM,
            cobolChunker, null, "INSURANCE COBOL", graphBuilder, writer, outputDir, counters, enricher);
        processFlat(inputRoot.resolve("jcl"), null,
            null, jclChunker, "INSURANCE JCL", graphBuilder, writer, outputDir, counters, enricher);

        // ── Remote source: full recursive traversal of ALL subdirectories ──
        if (Files.exists(cardDemoRoot)) {
            System.out.println("\n--- Processing: REMOTE SOURCE — " + cacheDirName + " (recursive) ---");
            try {
                List<Path> cardFiles;
                try (Stream<Path> walk = Files.walk(cardDemoRoot)) {
                    cardFiles = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String ext = getExtension(p.getFileName().toString()).toLowerCase();
                            return ext.equals("cbl") || ext.equals("cpy") || ext.equals("jcl");
                        })
                        // Process copybooks first, then programs, then JCL
                        .sorted(Comparator.comparing((Path p) -> {
                            String ext = getExtension(p.getFileName().toString()).toLowerCase();
                            return switch (ext) { case "cpy" -> "1"; case "cbl" -> "2"; default -> "3"; };
                        }).thenComparing(p -> p.getFileName().toString()))
                        .toList();
                }

                System.out.println("  Found " + cardFiles.size() + " source files across all subdirectories");

                for (Path file : cardFiles) {
                    String fileName = file.getFileName().toString();
                    String ext = getExtension(fileName).toLowerCase();
                    String relPath = cardDemoRoot.relativize(file).toString();
                    System.out.print("  " + relPath + " ... ");

                    List<FileChunk> chunks;
                    try {
                        chunks = switch (ext) {
                            case "cbl" -> cobolChunker.chunk(file, FileType.COBOL_PROGRAM, graphBuilder);
                            case "cpy" -> cobolChunker.chunk(file, FileType.COPYBOOK, graphBuilder);
                            case "jcl" -> jclChunker.chunk(file, graphBuilder);
                            default    -> { System.out.println("SKIPPED"); yield List.of(); }
                        };
                    } catch (Exception e) {
                        System.out.println("ERROR: " + e.getMessage());
                        continue;
                    }

                    if (!chunks.isEmpty()) {
                        if (enricher != null) enricher.enrichChunks(chunks);
                        EmbeddingDocumentBuilder.process(chunks);
                        try { writer.writeChunks(chunks, outputDir, fileName); }
                        catch (IOException e) { System.out.println("WRITE ERROR: " + e.getMessage()); continue; }
                        System.out.println(chunks.size() + " chunks");
                        counters[0]++;
                        counters[1] += chunks.size();
                    } else {
                        System.out.println("0 chunks");
                    }
                }
            } catch (IOException e) {
                System.out.println("ERROR walking carddemo: " + e.getMessage());
            }
        }

        // ── Knowledge Graph ─────────────────────────────────────────
        System.out.println("\n--- Knowledge Graph ---");
        try {
            KnowledgeGraph graph = graphBuilder.build();
            new GraphWriter().write(graph, outputDir);
            new GraphHtmlExporter().export(graph, outputDir);
        } catch (Exception e) {
            System.out.println("ERROR writing graph: " + e.getMessage());
            e.printStackTrace();
        }

        // ── Summary ──────────────────────────────────────────────────
        System.out.println("\n============================================");
        System.out.println("  INGESTION COMPLETE");
        System.out.printf("  Files processed : %d%n", counters[0]);
        System.out.printf("  Total chunks    : %d%n", counters[1]);
        System.out.println("  Output directory: " + outputDir.toAbsolutePath());
        System.out.println("============================================");
    }

    private static void processFlat(Path dir, FileType cobolType,
                                     CobolChunker cobolChunker, JclChunker jclChunker,
                                     String label, KnowledgeGraphBuilder graphBuilder,
                                     ChunkWriter writer, Path outputDir, int[] counters,
                                     LlmEnricher enricher) {
        if (!Files.exists(dir)) return;
        System.out.println("\n--- Processing: " + label + " ---");

        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            System.out.println("ERROR listing " + dir + ": " + e.getMessage());
            return;
        }

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String ext = getExtension(fileName).toLowerCase();
            System.out.print("  " + fileName + " ... ");

            List<FileChunk> chunks;
            try {
                chunks = switch (ext) {
                    case "cbl" -> cobolChunker != null
                        ? cobolChunker.chunk(file, FileType.COBOL_PROGRAM, graphBuilder)
                        : List.of();
                    case "cpy" -> cobolChunker != null
                        ? cobolChunker.chunk(file, cobolType, graphBuilder)
                        : List.of();
                    case "jcl" -> jclChunker != null
                        ? jclChunker.chunk(file, graphBuilder)
                        : List.of();
                    default -> List.of();
                };
            } catch (Exception e) {
                System.out.println("ERROR: " + e.getMessage());
                continue;
            }

            if (!chunks.isEmpty()) {
                if (enricher != null) enricher.enrichChunks(chunks);
                EmbeddingDocumentBuilder.process(chunks);
                try { writer.writeChunks(chunks, outputDir, fileName); }
                catch (IOException e) { System.out.println("WRITE ERROR: " + e.getMessage()); continue; }
                System.out.println(chunks.size() + " chunks");
                counters[0]++;
                counters[1] += chunks.size();
            } else {
                System.out.println("0 chunks");
            }
        }
    }

    private static Path findResourcesDir() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path candidate = cwd.resolve("src/main/resources");
        if (Files.isDirectory(candidate)) return candidate;
        candidate = cwd.resolve("../src/main/resources").normalize();
        if (Files.isDirectory(candidate)) return candidate;
        return cwd;
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }
}