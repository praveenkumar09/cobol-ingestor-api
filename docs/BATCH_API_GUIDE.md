# OpenAI Batch API — a plain-language guide to how this pipeline uses it

This doc explains what OpenAI's Batch API actually is, why this project uses
it, and exactly how it fits into the ingestion pipeline — written for
someone who has never touched the Batch API before. If you just want the
config knobs, jump to [Configuration reference](#configuration-reference).

---

## 1. The problem this solves

Every file this pipeline ingests needs at least one call to an OpenAI model:
one call to figure out how to split the file into meaningful chunks
("chunk analysis"), and usually a second call per chunk to write a nicer
business-level description of it ("enrichment").

The **normal** way to call OpenAI is synchronous: you send a request, you
wait, you get an answer, right there in your code. That's simple, but every
account has a **rate limit** — a cap on how many requests and how many
tokens you can send *per minute* for a given model. Push past it and OpenAI
replies with `HTTP 429` ("slow down"), and your code has to back off and
retry.

Early in this project, ingesting a real corpus meant hundreds of files all
wanting synchronous calls, and no matter how carefully the retry/backoff and
concurrency limits were tuned, the pipeline was still fundamentally
fighting over one shared per-minute budget. That budget is real and it
doesn't grow just because you write better retry code.

**The Batch API is a structurally different way to ask the same questions.**
Instead of "call me back right now," you say: "here are 500 questions,
answer them whenever you get to it, within the next 24 hours." OpenAI runs
those requests through a **separate queue** that doesn't touch your normal
per-minute rate limit at all, and — as a bonus — charges roughly **half
price** for it.

The trade-off is exactly what you'd expect: it's not instant. A batch
"usually" finishes faster than 24 hours, but there's no guaranteed fast
lane. You're trading latency for throughput and cost.

---

## 2. The mental model: a drop-box, not a phone call

Think of the synchronous API like phoning someone and waiting on the line
for an answer. The Batch API is like dropping a stack of questions in a
box, and coming back later to collect the stack of answers. A few
consequences fall directly out of that analogy:

- **You submit everything up front, as a file.** You can't have a
  back-and-forth conversation with a batch request — you ask your question
  once, in full, with everything it needs to be answered.
- **Answers can come back in any order.** If you drop off 10 questions and
  come back for 10 answers, the pile of answers isn't necessarily in the
  order you asked them. That's why every request needs a **custom ID**
  (see below) — a name tag that lets you match each answer back to its
  question.
- **You have to check back yourself.** There's no callback — you (or your
  code) has to periodically ask "is my batch done yet?" This is called
  **polling**.

---

## 3. The building blocks

### 3.1 A request file (JSONL)

You don't send 500 separate HTTP calls. You write **one file** with 500
lines, where each line is a self-contained request — same shape as a normal
chat-completion call, just written to a file instead of sent over the wire
immediately:

```json
{"custom_id": "0", "method": "POST", "url": "/v1/chat/completions", "body": {"model": "gpt-4o", "messages": [...], "max_tokens": 8000}}
{"custom_id": "1", "method": "POST", "url": "/v1/chat/completions", "body": {"model": "gpt-4o", "messages": [...], "max_tokens": 8000}}
```

This format is called **JSONL** ("JSON Lines") — literally one valid JSON
object per line, no commas or brackets joining them. In this codebase,
`BatchJsonl.java` builds and reads these files.

### 3.2 custom_id — the name tag

Every line gets a `custom_id` you make up yourself. It means nothing to
OpenAI except "put this same string on the matching answer." This project
uses a simple incrementing number (`"0"`, `"1"`, `"2"`, ...) assigned right
before a batch is built, and keeps a matching list on the side so it can
translate `"custom_id": "37"` back into "that was `COACTUPC.cbl`" once the
answer comes back.

### 3.3 The four-step lifecycle

1. **Upload** the JSONL file (`POST /v1/files`, `purpose=batch`) — you get
   back a `file_id`.
2. **Create the batch** (`POST /v1/batches`), pointing at that `file_id`.
   You get back a `batch_id` and the batch starts life in `validating`.
3. **Poll** (`GET /v1/batches/{id}`) every so often to check its `status`.
   A batch moves through: `validating` → `in_progress` → `finalizing` →
   one of `completed` / `failed` / `expired` / `cancelled`. Only those last
   four are **terminal** — polling stops once you hit one of them.
4. **Download the results** (`GET /v1/files/{output_file_id}/content`) —
   another JSONL file, one line per answer, each carrying back the
   `custom_id` you assigned so you can match it to your original question.

In this codebase, `OpenAiBatchClient.java` implements exactly these four
raw HTTP calls (no SDK — this project talks to OpenAI directly everywhere).

### 3.4 completion_window

The only value OpenAI accepts today is `"24h"` — a batch is guaranteed to
finish (or fail/expire) within 24 hours of being created. In practice small
batches in this pipeline have finished in a few minutes, but that's not a
guarantee — treat 24h as the real, honest upper bound.

---

## 4. How THIS pipeline actually uses it

Not every file in this pipeline goes through batch — only the ones where it
makes sense. Here's the exact breakdown, phase by phase (see
`Main.runBatchOrchestration` in code):

### Phase A0 — Discover and sort files into two piles

Every file gets measured by line count against a threshold
(`ingest.large-file-threshold-lines`, default 900 lines):

- **Small files** (≤900 lines) fit in a *single* LLM call — one prompt,
  one answer, the whole file. These are batch-eligible.
- **Large files** (>900 lines) need *multiple* calls, one per ~900-line
  "window," and — critically — each window's prompt needs to know what the
  *previous* window of the *same file* already figured out (this is called
  `CarriedContext` in the code), so a later window doesn't contradict an
  earlier one about e.g. what business domain the file belongs to. The
  Batch API has no way to say "wait for answer #1 before asking question
  #2" — every request in a batch is independent. So **large files are never
  batch-eligible, by design**, and always go through the normal
  step-by-step synchronous path.

### Phase A1 — Batch small files, synchronous-process large files, AT THE SAME TIME

This is the clever part: while the batch job for all the small files sits
in OpenAI's queue (which can take a while), the large files are being
processed synchronously on the main thread, in parallel, for free. By the
time the batch comes back, a good chunk of the large-file work is often
already done — no wasted waiting.

Once the batch completes, each small file's answer is matched back up (by
`custom_id`) and turned into the same chunk data structure the synchronous
path would have produced. **Any file whose batch answer is missing, failed,
or doesn't parse cleanly just gets re-run through the ordinary synchronous
call** — same code path a large file already uses, just for one file
instead of many. Nothing gets silently dropped.

### Phase B — One enrichment batch for EVERY chunk, from every file

Once every file (small and large alike) has its chunks, there's a second,
separate LLM pass: writing a nicer business description for each chunk
("enrichment"). Unlike chunk analysis, enrichment has **no dependency
between chunks at all** — chunk #5's description doesn't care about chunk
#4's — so this step batches *everything*, small-file chunks and
large-file chunks together, in one combined pass.

### Phase C — Unchanged

Embeddings, writing chunk files to disk, storing to Postgres/Neo4j — this
part doesn't care whether chunks came from batch or synchronous, so it's
identical either way.

---

## 5. The gotcha we actually hit: "enqueued tokens"

Batches aren't unlimited. OpenAI enforces a **separate cap** — on top of
the request-count and file-size limits you'd expect — on how many tokens
can be sitting in batches that are *currently queued or running* for one
model, per organization. This is **not documented anywhere until you hit
it** — it only shows up in a failed batch's error message.

We hit it directly: a batch of 164 chunk-analysis requests (gpt-4o, each
reserving up to 16,000 output tokens) failed **immediately** with:

> `token_limit_exceeded` — "Enqueued token limit reached for gpt-4o ...
> Limit: 90,000 enqueued tokens."

The key detail: OpenAI reserves a request's **full declared `max_tokens`**
against that budget the moment the batch is created — before it even knows
how much output the model will actually produce. So 164 requests × 16,000
possible-output-tokens-each = way over 90,000, and the *entire batch*
(all 164 requests) was rejected before a single one ran.

### How the fix works

Three changes, all in `BatchJsonl.java`, `LlmChunkAnalyzer.java`, and the
two `Batch*Service.java` orchestrators:

1. **Estimate tokens per request** before building a batch (rough
   chars-in-prompt ÷ 4, plus the request's `max_tokens`), and pack requests
   into groups that stay under a safe token budget
   (`openai.batch.max-enqueued-tokens`) — not just under the old
   request-count/file-size caps, which never came close to catching this.
2. **Submit groups one at a time, not all at once.** The enqueued-token
   cap applies across *every* batch currently in progress for a model —
   submitting five small groups back-to-back would just fail the same way
   five requests couldn't fit in one. So each group is fully submitted and
   waited on to completion before the next one is even created.
3. **A smaller `max_tokens` just for batch requests**
   (`openai.batch.chunk-max-tokens`, default 8000 — half the synchronous
   path's 16000). This is safe with **zero quality trade-off**: if a
   batch answer comes back truncated at the smaller ceiling, it fails the
   same truncation check the synchronous path already uses, and that one
   file automatically falls back to the full, untouched, 16,000-token
   synchronous path — same eventual quality, just paid for (in reserved
   tokens and an extra round trip) only by the handful of files that
   actually need it, not all 164 up front.

The real-world consequence of this cap: a corpus can genuinely need dozens
of sequential batch groups to get through, each with its own few minutes
of OpenAI-side overhead. Batch mode is still a real structural win (no more
rate-limit fights, ~50% cheaper), but at a lower OpenAI usage tier its
*throughput* benefit is smaller than "just dump everything in one giant
batch" would suggest — the enqueued-token cap is the actual bottleneck, not
this pipeline's code.

---

## 6. Crash survivability: the manifest

Every batch this pipeline creates gets logged — the moment it's created,
*before* any waiting begins — to:

```
src/main/resources/output/batch/manifest.json
```

One JSON line per batch: its ID, what it was for (`chunk-analysis` or
`enrichment`), how many requests, and when. This is deliberately the
*minimal* form of crash-survivability: if the whole process dies mid-run
(power loss, killed process, laptop sleeps), nothing tries to automatically
resume — but you have a durable record of exactly which batches were
actually submitted (and therefore billed), so you can check their status
directly against OpenAI's API or dashboard rather than wondering.

---

## 7. Configuration reference

All of these live in `application.properties`, with an env var override
following this project's usual `AppConfig` convention (env var → `.env`
file → properties file → hardcoded fallback).

| Property | Default | What it controls |
|---|---|---|
| `ingest.use-batch-api` | `true` | Master switch. `false` = fully synchronous, exactly like before any of this existed. |
| `openai.batch.poll-interval-seconds` | `30` | How often to check a batch's status while waiting. |
| `openai.batch.max-requests-per-file` | `50000` | OpenAI's hard cap on requests per batch file. Rarely the binding constraint — the token cap below usually bites first. |
| `openai.batch.max-file-bytes` | `150000000` | OpenAI's hard cap is 200MB; kept under that for safety margin. |
| `openai.batch.max-enqueued-tokens` | `80000` | The real bottleneck — see §5. Tuned just under this org's observed 90,000 limit for gpt-4o. **If you're on a different OpenAI account/tier, this number may be very different for you** — it's not something the API tells you in advance. |
| `openai.batch.completion-window` | `24h` | The only value OpenAI accepts today. |
| `openai.batch.max-wait-hours` | `26` | Safety timeout just above the 24h window — if a batch never finishes, that group falls back to synchronous processing instead of hanging forever. |
| `openai.chunk.max-tokens` | `16000` | Output budget for the **synchronous** chunk-analysis path (unchanged, still the proven, quality-safe value). |
| `openai.batch.chunk-max-tokens` | `8000` | Output budget for **batch-only** chunk-analysis requests — smaller on purpose, see §5. Never affects quality, only how many files fit per batch group. |
| `ingest.large-file-threshold-lines` | `900` | The small-vs-large split point. Anything at or under this is batch-eligible; anything over always goes synchronous. |

---

## 8. Quick FAQ

**Q: Does batch mode ever produce worse chunks than synchronous mode?**
No. Every batch-mode result goes through the exact same parsing/validation
as a synchronous result, and anything that doesn't pass falls back to the
real synchronous call. Batch mode can only be *slower on a per-file basis
when it fails*, never lower quality.

**Q: What happens if I set `ingest.use-batch-api=false`?**
The pipeline runs exactly as it did before any of this batch work existed
— every file, one synchronous call at a time (large files sequentially,
small files concurrently up to `ingest.parallelism`). Nothing about that
path was touched.

**Q: Why does the log say "164 batch-eligible files" when there are more
files than that in the run?**
See §4 (Phase A0) — files over the large-file threshold are always
synchronous by design (never even attempt batch), and a small number of
files can fail the pre-check that decides whether a file resolves to a
known chunkable type at all, which also routes them straight to
synchronous. So the total splits into three groups: batch-attempted,
always-synchronous-by-design (large files + unresolved types), and
synchronous-as-fallback (any batch-attempted file whose answer didn't come
back clean). Nothing in any of these three groups gets skipped — every
file ends up chunked one way or another.

**Q: I got `token_limit_exceeded` again even after this fix — now what?**
It means even the smaller, token-budgeted groups are still bumping into
your org's real cap. Lower `openai.batch.max-enqueued-tokens` further, or
check your usage tier at platform.openai.com/settings/organization/limits
— higher tiers get meaningfully larger batch quotas.
