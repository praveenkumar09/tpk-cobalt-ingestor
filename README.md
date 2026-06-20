# TPK COBOL Ingestor

A Java tool that ingests AS400/iSeries COBOL source code from any Git repository and prepares it for use in a Spring AI RAG (Retrieval-Augmented Generation) chatbot. It produces structured JSON chunks per program section, a relationship knowledge graph, and an interactive browser-based graph visualization — all with no external runtime dependencies.

---

## What It Does

1. **Fetches source code** from GitHub or Bitbucket (or a direct ZIP URL) via the `SOURCE_URL` environment variable.
2. **Chunks** each `.cbl`, `.cpy`, and `.jcl` file by COBOL division and section, producing one JSON file per source file with rich metadata.
3. **Infers domain** (BANKING / INSURANCE / GENERAL) and sub-domain (ACCOUNT\_MANAGEMENT, TRANSACTION\_PROCESSING, POLICY\_MANAGEMENT, etc.) dynamically from the code itself — nothing is hardcoded.
4. **Builds a knowledge graph** of program relationships: which programs copy which copybooks, read/write which files, call which programs, and which JCL jobs execute which programs.
5. **Exports an interactive HTML visualization** of the graph (vis.js, embedded locally — no internet needed in the browser).

---

## Prerequisites

| Tool | Version |
|---|---|
| Java | 23+ |
| Maven | 3.8+ |
| Internet access (first run only) | For downloading the source ZIP and vis.js |

---

## Project Structure

```
tpk-cobalt-ingestor/
├── src/main/java/org/example/
│   ├── Main.java                          Entry point
│   ├── chunker/
│   │   ├── CobolAnalyzer.java             Pattern matching, metadata extraction, domain inference
│   │   ├── CobolChunker.java              Divides COBOL programs into section chunks
│   │   └── JclChunker.java               Divides JCL jobs into step chunks
│   ├── fetcher/
│   │   └── SourceFetcher.java             Downloads ZIP from GitHub / Bitbucket / direct URL
│   ├── graph/
│   │   ├── KnowledgeGraphBuilder.java     Accumulates nodes and edges while chunking
│   │   ├── KnowledgeGraph.java            Graph POJO (nodes + edges + metadata)
│   │   ├── GraphNode.java / GraphEdge.java
│   │   ├── GraphWriter.java               Writes knowledge_graph.json
│   │   └── GraphHtmlExporter.java         Writes graph_visualization.html (vis.js embedded)
│   ├── model/
│   │   ├── FileChunk.java                 Chunk POJO with all metadata fields
│   │   └── FileType.java                  COBOL_PROGRAM / COPYBOOK enum
│   └── writer/
│       └── ChunkWriter.java               Writes one JSON file per source file
│
└── src/main/resources/
    ├── input/
    │   ├── copybooks/          Local COBOL copybooks (.cpy)
    │   ├── cobol/              Local COBOL programs (.cbl)
    │   ├── jcl/                Local JCL files (.jcl)
    │   └── github/             Auto-downloaded remote source (cached after first run)
    └── output/                 All generated files land here
```

---

## Running

```bash
mvn exec:java
```

On first run this downloads the configured source repository (~5–10 s). Subsequent runs use the local cache and complete in under 1 second.

### With a custom repository

```bash
SOURCE_URL=https://github.com/my-org/my-cobol-repo mvn exec:java
```

```bash
SOURCE_URL=https://bitbucket.org/my-workspace/my-repo \
SOURCE_BRANCH=develop \
SOURCE_SUBDIR=src \
mvn exec:java
```

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `SOURCE_URL` | carddemo (AWS sample) | Full URL to a GitHub or Bitbucket repo page, or a direct `.zip` URL |
| `SOURCE_BRANCH` | `main` | Branch to download |
| `SOURCE_SUBDIR` | `app` | Subdirectory inside the repo to process. Set to `""` to process the entire repo |
| `SOURCE_CACHE_DIR` | Derived from repo name | Local folder name under `input/github/` for the downloaded cache |

### Supported URL formats

```
https://github.com/owner/repo
https://github.com/owner/repo/tree/my-branch
https://bitbucket.org/workspace/repo
https://bitbucket.org/workspace/repo/src/my-branch
https://example.com/path/to/archive.zip
```

The ZIP root prefix (e.g., `repo-main/` for GitHub, `repo-abc123/` for Bitbucket) is detected dynamically, so the tool works with either hosting provider without manual configuration.

---

## Output Files

All output is written to `src/main/resources/output/`.

### Chunk JSON files (`<PROGRAM>_chunks.json`)

One file per source file, each containing an array of chunk objects:

```json
[
  {
    "chunkId": "CBACT01C_PROCEDURE_DIVISION_005",
    "chunkIndex": 5,
    "totalChunks": 8,
    "sourceFile": "CBACT01C.cbl",
    "fileType": "ILE COBOL Program",
    "programId": "CBACT01C",
    "author": "AWS",
    "domain": "BANKING",
    "subDomain": "ACCOUNT_MANAGEMENT",
    "processingType": "BATCH_PROCESSING",
    "division": "PROCEDURE_DIVISION",
    "sectionName": "0000-MAIN",
    "lineStart": 120,
    "lineEnd": 185,
    "sectionPurpose": "Main processing loop: reads account file, applies interest, writes output.",
    "filesRead": ["ACCOUNT-FILE", "XREF-FILE"],
    "filesWritten": ["OUTPUT-FILE"],
    "copybooksUsed": ["CVACT01Y", "CSUTLDWY"],
    "externalCalls": ["CSUTLDTC"],
    "hasFileIO": true,
    "hasErrorHandling": true,
    "tags": ["as400", "cobol", "banking", "batch", "account", "management"],
    "content": "       0000-MAIN.\n           ..."
  }
]
```

#### Chunk metadata fields

| Field | Description |
|---|---|
| `chunkId` | Unique identifier: `PROGRAMID_SECTION_NNN` |
| `domain` | `BANKING`, `INSURANCE`, or `GENERAL` — inferred from code |
| `subDomain` | Fine-grained domain: `ACCOUNT_MANAGEMENT`, `TRANSACTION_PROCESSING`, `POLICY_MANAGEMENT`, etc. |
| `processingType` | `BATCH_PROCESSING`, `CICS_ONLINE`, `SERVICE_PROGRAM_BO`, `BATCH_JCL`, etc. |
| `division` | COBOL division: `IDENTIFICATION_DIVISION`, `DATA_DIVISION`, `PROCEDURE_DIVISION` |
| `sectionName` | COBOL section or paragraph name |
| `sectionPurpose` | Natural-language description generated from the code |
| `filesRead/Written/Updated/Deleted` | Dataset names extracted from COBOL or CICS statements |
| `copybooksUsed` | COPY statements found in this section |
| `externalCalls` | `CALL` / `EXEC CICS LINK` / `EXEC CICS XCTL` targets |
| `hasFileIO` | `true` if section performs any file I/O |
| `hasErrorHandling` | `true` if section contains error-handling logic |
| `tags` | List of searchable keywords for vector store filtering |
| `content` | Raw COBOL/JCL source text for this section |

### `knowledge_graph.json`

A graph of all programs, copybooks, database files, entry points, and JCL jobs with their relationships.

**Current stats (carddemo + insurance sample):**

| Node type | Count |
|---|---|
| DATABASE\_FILE | 91 |
| COPYBOOK | 74 |
| COBOL\_PROGRAM | 66 |
| JCL\_JOB | 46 |
| ENTRY\_POINT | 6 |
| **Total** | **283** |

| Edge type | Count | Meaning |
|---|---|---|
| COPIES | 264 | Program copies a copybook |
| EXECUTES | 74 | JCL job executes a program |
| READS | 68 | Program reads a file |
| CALLS | 35 | Program calls another program |
| WRITES | 28 | Program writes a file |
| USES\_DATASET | 26 | JCL job references a dataset |
| UPDATES | 12 | Program rewrites a file |
| EXPOSES | 8 | Program exposes an entry point |
| DELETES\_FROM | 2 | Program deletes from a file |
| **Total** | **517** | |

### `graph_visualization.html`

An interactive browser-based graph explorer. Open directly in any browser — no server needed, no internet needed (vis.js is embedded).

**Features:**
- Search by program or file name
- Filter by node type (COBOL Program, Copybook, DB File, JCL Job, Entry Point)
- Click any node for details (domain, sub-domain, processing type)
- Physics-based layout with zoom and pan
- Reset and Fit All controls

**Color legend:**

| Color | Node type |
|---|---|
| Blue | COBOL Program |
| Green | Copybook |
| Orange | Database File |
| Purple | Entry Point |
| Red | JCL Job |

---

## Domain and Sub-Domain Inference

Domain and sub-domain are derived dynamically by `CobolAnalyzer` — the program never needs to be told what kind of code it is processing.

### Banking sub-domains (detected by program name prefix: CB / CO / CV / CS / CC)

`ACCOUNT_MANAGEMENT` · `TRANSACTION_PROCESSING` · `CARD_MANAGEMENT` · `CUSTOMER_MANAGEMENT` · `STATEMENT_BILLING` · `USER_AUTHENTICATION` · `REPORTING` · `ADMINISTRATION` · `NAVIGATION` · `DATA_TRANSFER` · `BANKING_UTILITIES` · `BANKING_OPERATIONS`

### Insurance sub-domains (detected by program name keywords)

`POLICY_MANAGEMENT` · `CLAIMS_PROCESSING` · `PREMIUM_CALCULATION` · `BENEFICIARY_MANAGEMENT` · `POLICY_RENEWAL` · `INTEGRAL_LIFE_PRODUCT` · `UNDERWRITING` · `BILLING_PAYMENT` · `AGENT_BROKER` · `INSURANCE_OPERATIONS`

---

## CICS vs Batch Detection

The analyzer automatically distinguishes CICS online programs from batch programs:

- **CICS programs** (`EXEC CICS` detected) → use only `EXEC CICS READ/WRITE/REWRITE/DELETE/LINK/XCTL` patterns for file and call extraction. Batch `READ`/`WRITE` patterns are skipped to avoid false positives from COBOL comment lines.
- **Batch programs** → use standard COBOL `READ`/`WRITE`/`REWRITE`/`DELETE` patterns.
- **Processing type** is set to `CICS_ONLINE`, `BATCH_PROCESSING`, or `SERVICE_PROGRAM_BO` accordingly.

---

## How It Plugs Into Spring AI RAG

Each chunk JSON object is designed to be loaded directly into a vector store. The `content` field is what gets embedded; all other fields are metadata that can be used for pre-filtering in Spring AI's `SearchRequest`:

```java
SearchRequest.query("How does the account balance update work?")
    .withTopK(5)
    .withSimilarityThreshold(0.75)
    .withFilterExpression("domain == 'BANKING' && subDomain == 'ACCOUNT_MANAGEMENT'");
```

The knowledge graph (`knowledge_graph.json`) can be loaded into a graph database (Neo4j, Amazon Neptune) or used at query time to expand context — e.g., when asking about `CBACT01C`, also retrieve the chunks for every copybook it uses (`COPIES` edges) and every program it calls (`CALLS` edges).

---

## Local Input Files

You can add your own COBOL/JCL files to the local input directories. They are processed in addition to any remotely fetched source:

```
src/main/resources/input/
├── copybooks/    → .cpy files (data record layouts, shared definitions)
├── cobol/        → .cbl files (programs)
└── jcl/          → .jcl files (batch job control)
```

These are always processed first (before the remote source), so copybook metadata is available when programs are chunked.

---

## Dependencies

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.2</version>
</dependency>
```

Java's built-in `HttpClient` and `ZipInputStream` handle all downloading and extraction. No additional libraries are required.