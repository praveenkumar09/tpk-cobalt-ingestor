package org.example;

import org.example.chunker.CobolChunker;
import org.example.chunker.JclChunker;
import org.example.fetcher.SourceFetcher;
import org.example.graph.GraphHtmlExporter;
import org.example.graph.GraphWriter;
import org.example.graph.KnowledgeGraph;
import org.example.graph.KnowledgeGraphBuilder;
import org.example.llm.EmbeddingClient;
import org.example.llm.EmbeddingDocumentBuilder;
import org.example.llm.LlmEnricher;
import org.example.model.FileChunk;
import org.example.model.FileType;
import org.example.store.GraphStore;
import org.example.store.VectorStore;
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
        System.out.println("  LLM Enrichment : "
            + (enricher != null ? "ENABLED (model: " + enricher.getModel() + ")" : "DISABLED (set OPENAI_API_KEY)"));

        EmbeddingClient embedder = EmbeddingClient.create();
        System.out.println("  Embeddings     : "
            + (embedder != null ? "ENABLED (model: " + embedder.getModel() + ")" : "DISABLED (set OPENAI_API_KEY)"));

        // All chunks accumulated here for bulk embed + DB store at the end
        List<FileChunk> allChunks = new ArrayList<>();

        // ── Source fetch ──────────────────────────────────────────────
        System.out.println("\n--- Remote Source ---");
        SourceFetcher fetcher = new SourceFetcher();
        String cacheDirName = System.getenv("SOURCE_CACHE_DIR");
        if (cacheDirName == null || cacheDirName.isBlank()) cacheDirName = fetcher.getRepoName();
        Path cardDemoRoot = inputRoot.resolve("github").resolve(cacheDirName);
        try {
            fetcher.fetchIfAbsent(cardDemoRoot);
        } catch (Exception e) {
            System.out.println("  WARNING: Fetch failed: " + e.getMessage());
            System.out.println("  Continuing with locally cached files (if any)...");
        }

        // ── Local insurance files (flat directory scan) ───────────────
        processFlat(inputRoot.resolve("copybooks"), FileType.COPYBOOK,
            cobolChunker, null, "INSURANCE COPYBOOKS",
            graphBuilder, writer, outputDir, counters, enricher, allChunks);
        processFlat(inputRoot.resolve("cobol"), FileType.COBOL_PROGRAM,
            cobolChunker, null, "INSURANCE COBOL",
            graphBuilder, writer, outputDir, counters, enricher, allChunks);
        processFlat(inputRoot.resolve("jcl"), null,
            null, jclChunker, "INSURANCE JCL",
            graphBuilder, writer, outputDir, counters, enricher, allChunks);

        // ── Remote source: recursive traversal ───────────────────────
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
                        .sorted(Comparator.comparing((Path p) -> {
                            String ext = getExtension(p.getFileName().toString()).toLowerCase();
                            return switch (ext) { case "cpy" -> "1"; case "cbl" -> "2"; default -> "3"; };
                        }).thenComparing(p -> p.getFileName().toString()))
                        .toList();
                }
                System.out.println("  Found " + cardFiles.size() + " source files");

                for (Path file : cardFiles) {
                    String fileName = file.getFileName().toString();
                    String ext      = getExtension(fileName).toLowerCase();
                    System.out.print("  " + cardDemoRoot.relativize(file) + " ... ");

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
                        allChunks.addAll(chunks);
                        System.out.println(chunks.size() + " chunks");
                        counters[0]++;
                        counters[1] += chunks.size();
                    } else {
                        System.out.println("0 chunks");
                    }
                }
            } catch (IOException e) {
                System.out.println("ERROR walking source: " + e.getMessage());
            }
        }

        // ── Knowledge Graph — JSON + HTML ─────────────────────────────
        System.out.println("\n--- Knowledge Graph ---");
        KnowledgeGraph graph = null;
        try {
            graph = graphBuilder.build();
            new GraphWriter().write(graph, outputDir);
            new GraphHtmlExporter().export(graph, outputDir);
        } catch (Exception e) {
            System.out.println("ERROR writing graph: " + e.getMessage());
        }

        // ── Embed all chunks (batched) ────────────────────────────────
        Map<String, float[]> embeddingMap = embedAll(allChunks, embedder);

        // ── Store chunks → PostgreSQL / pgvector ──────────────────────
        storeToVectorDB(allChunks, embeddingMap);

        // ── Store knowledge graph → Neo4j ─────────────────────────────
        if (graph != null) storeToNeo4j(graph);

        // ── Summary ───────────────────────────────────────────────────
        System.out.println("\n============================================");
        System.out.println("  INGESTION COMPLETE");
        System.out.printf("  Files processed   : %d%n", counters[0]);
        System.out.printf("  Total chunks      : %d%n", counters[1]);
        System.out.printf("  Embeddings stored : %d%n", embeddingMap.size());
        System.out.println("  Output directory  : " + outputDir.toAbsolutePath());
        System.out.println("============================================");
    }

    // ─── Embed all embeddable chunks in BATCH_SIZE batches ─────────────────────

    private static Map<String, float[]> embedAll(List<FileChunk> allChunks,
                                                   EmbeddingClient embedder) {
        System.out.println("\n--- Generating Embeddings ---");
        Map<String, float[]> result = new HashMap<>();

        if (embedder == null) {
            System.out.println("  Skipped (OPENAI_API_KEY not set)");
            return result;
        }

        List<FileChunk> embeddable = allChunks.stream()
            .filter(c -> c.isShouldEmbed() && c.getEmbeddingText() != null)
            .toList();

        System.out.printf("  Embedding %d chunks (batch size %d)...%n",
            embeddable.size(), EmbeddingClient.BATCH_SIZE);

        for (int i = 0; i < embeddable.size(); i += EmbeddingClient.BATCH_SIZE) {
            List<FileChunk> batch = embeddable.subList(
                i, Math.min(i + EmbeddingClient.BATCH_SIZE, embeddable.size()));
            List<String> texts = batch.stream().map(FileChunk::getEmbeddingText).toList();

            try {
                List<float[]> vecs = embedder.embedBatch(texts);
                for (int j = 0; j < batch.size(); j++) {
                    result.put(batch.get(j).getChunkId(), vecs.get(j));
                }
                System.out.printf("  ... %d/%d embedded%n",
                    Math.min(i + EmbeddingClient.BATCH_SIZE, embeddable.size()), embeddable.size());

                if (i + EmbeddingClient.BATCH_SIZE < embeddable.size()) {
                    Thread.sleep(150); // stay within rate limits
                }
            } catch (Exception e) {
                System.out.println("  WARNING: batch " + (i / EmbeddingClient.BATCH_SIZE + 1)
                    + " failed: " + e.getMessage());
            }
        }

        System.out.println("  ✓ " + result.size() + " embeddings generated");
        return result;
    }

    // ─── Store to PostgreSQL / pgvector ────────────────────────────────────────

    private static void storeToVectorDB(List<FileChunk> allChunks,
                                         Map<String, float[]> embeddingMap) {
        System.out.println("\n--- Storing to PostgreSQL (pgvector) ---");
        try (VectorStore store = VectorStore.create()) {
            store.upsertChunks(allChunks, embeddingMap);
            System.out.println("  ✓ " + allChunks.size() + " chunks stored");
            System.out.println("    pgAdmin → http://localhost:5050");
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getMessage());
            System.out.println("  Is PostgreSQL running?  →  docker compose up -d postgres");
        }
    }

    // ─── Store knowledge graph to Neo4j ────────────────────────────────────────

    private static void storeToNeo4j(KnowledgeGraph graph) {
        System.out.println("\n--- Storing Knowledge Graph to Neo4j ---");
        try (GraphStore store = GraphStore.create()) {
            store.ingestGraph(graph);
            int nodes = graph.getNodes() != null ? graph.getNodes().size() : 0;
            int edges = graph.getEdges() != null ? graph.getEdges().size() : 0;
            System.out.println("  ✓ " + nodes + " nodes, " + edges + " edges stored");
            System.out.println("    Neo4j Browser → http://localhost:7474  (neo4j / admin)");
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getMessage());
            System.out.println("  Is Neo4j running?  →  docker compose up -d neo4j");
        }
    }

    // ─── processFlat ───────────────────────────────────────────────────────────

    private static void processFlat(Path dir, FileType cobolType,
                                     CobolChunker cobolChunker, JclChunker jclChunker,
                                     String label, KnowledgeGraphBuilder graphBuilder,
                                     ChunkWriter writer, Path outputDir, int[] counters,
                                     LlmEnricher enricher, List<FileChunk> allChunks) {
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
                allChunks.addAll(chunks);
                System.out.println(chunks.size() + " chunks");
                counters[0]++;
                counters[1] += chunks.size();
            } else {
                System.out.println("0 chunks");
            }
        }
    }

    // ─── Utilities ─────────────────────────────────────────────────────────────

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