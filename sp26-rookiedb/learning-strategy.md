# RookieDB Learning Strategy

A personal strategy doc, written after a long conversation with Claude diagnosing why reading the RookieDB codebase felt frustrating and unproductive. Plan: finish Project 3 first using ship-mode, then begin the toy-DB plan.

## The core diagnosis

**Reading code without a question is wandering, and wandering is what produces the frustration.** Two things made this worse for me specifically:

1. I was mixing two different goals — *shipping the task* and *learning the codebase* — in one activity. Neither got done well.
2. The CS61B model that worked for me (spec → implement → tests pass → "I built it") doesn't translate directly, because RookieDB is mostly already built. I was stuck in the role of reader, which doesn't carry the same payoff as builder.

The fix is to **separate the two modes** and find a way to put myself back in the builder's seat.

---

## Mode 1: Ship mode (for course projects)

Goal: pass the tests. Read narrowly, write fast, let test failures drive the reading.

### Rules
- Open only the files with `TODO(projN)` in them.
- For each TODO, read the **already-implemented sibling** first (SNLJ before BNLJ, SHJ before GHJ, InnerNode.fromBytes before LeafNode.fromBytes). The skeleton always gives a worked example one step simpler than the task.
- For helper classes (Buffer, BacktrackingIterator, Pair, etc.), read only the **one-line Javadoc and signatures**, not the implementation. Trust the contract.
- When tempted to wander into an unrelated file, **stop and write the question down** in a study list. Don't open the file. The list becomes fuel for study mode later.
- A failing test pointing at a file = a legitimate reason to open that file. Nothing else is.

### Spine vs plumbing
Each layer has 2-3 "spine" classes that carry the abstraction; the rest are plumbing. Spine you read carefully, plumbing you treat as a black box.

| Layer       | Spine (read)                                | Plumbing (skip)                          |
|-------------|---------------------------------------------|------------------------------------------|
| Storage     | DiskSpaceManager, BufferManager             | Page, BufferFrame, Buffer                |
| Table       | Table, PageDirectory                        | Record, Schema, RecordId                 |
| Index       | BPlusTree, InnerNode, LeafNode              | Metadata, Pair, iterators                |
| Query       | QueryPlan, QueryOperator, the join you need | The other 15 operators                   |
| Concurrency | LockManager, LockContext, LockUtil          | Lock, ResourceName                       |

Lecture gives one box per concept. Production code may give 5 classes per concept. Collapse them back down — don't promote plumbing to spine.

### Frustration as a signal
If reading feels draining, it's because I'm reading without a question. Stop reading and either (a) form a specific question, or (b) write a stub and let a failing test tell me what I need to know.

---

## Mode 2: Study mode (for real learning)

Goal: understanding. Runs on a **separate clock** from ship mode. Don't mix them.

### Topics ≠ questions
"Monday: BufferManager" is a topic, not a question. Topics have no stopping condition, which is why every prior attempt at scheduled study turned into wandering and note-taking-as-transcription.

A real study question is:
- **Specific** — names a method, scenario, or decision
- **Answerable** — I'll know when I've got it
- **Generative** — answering forces me to read ~3 connected files

Examples:
- ❌ "Study BufferManager."
- ✅ "When BufferManager evicts a dirty page, who writes it to disk, and how does it know the page is dirty?"
- ✅ "The lecture says WAL guarantees X. Find the exact line that enforces it. What breaks if I delete that line?"

### Where good questions come from
1. **Surprises from ship mode** — moments I wanted to wander but didn't. Write them down.
2. **Lecture claims I don't fully believe yet.** Find where they're enforced in code.
3. **Predictions that fail.** Predict what a function does from its name, then read it. Where I was wrong = my next question.
4. **"How would I have built this?"** Sketch my own design, compare to the real one. Differences = questions.

All four require me to *do something before reading*. The reading then has a target.

### Session shape (30–60 min)
1. **5 min:** Write the question at the top. One sentence. If I can't, I don't have a question yet.
2. **5 min:** Write my best guess *before reading any code*. Even if wrong — this is the hook memory attaches to.
3. **20–40 min:** Read only what's needed to confirm or refute. Stop when answered.
4. **5 min:** Write the answer + what surprised me + one follow-up question for next session.

### Notes that survive
Scribe-notes (what code does) rot. Notes that survive:
- Answers to specific questions, in my own words
- Surprises ("I assumed X, actually Y, because Z")
- Diagrams of invariants (what must always be true)
- "I would have done it differently" notes — design disagreements with reasoning

---

## The deeper issue: builder vs reader

CS61B worked because I was the implementer. Implementing has a clear success signal (tests pass) and produces real understanding as a byproduct. Reading has no equivalent — I close the file and don't know if I got it. That uncertainty is what makes reading feel passive and draining.

The fix isn't to learn to love reading. It's to **find ways to still be the builder.**

### Three ways to put myself back in the builder's seat

1. **Implement before reading.** Build a toy version from scratch, then read the real one. Every difference is a real question with built-in context.
2. **Predict, then check.** Read only the signature + Javadoc, write what I'd put in the body, then read the real version. Where I was wrong = a real question. Costs 30 seconds per method.
3. **Re-derive the design.** After shipping, close the code and try to re-explain to myself: "Why is X a base class? Why does Y return an Iterator instead of a List?" Where I can't re-derive = a real question.

---

## Domain classes vs infrastructure classes

A key realization: **gitlet was all domain classes; RookieDB is mostly infrastructure classes.** They're designed with different instincts.

- **Domain class** (Commit, Repository, Table, BPlusTree): represents a thing in the problem. API follows from the noun. Designable top-down.
- **Infrastructure class** (Buffer, Bits, Pair, BacktrackingIterator, IndexBacktrackingIterator): exists to serve other classes. Has no intrinsic shape — its API only makes sense once callers exist.

**Infrastructure cannot be spec'd in isolation.** That's why building `BacktrackingIterator` from scratch feels weird — it's a coding pattern, not a concept. Real engineers don't design these top-down; they extract them after writing ugly inline code in 3 callers and feeling the pain.

### The extract-when-it-hurts loop

1. Spec a **domain class** (e.g., toy Table).
2. Start implementing it. Inline everything. Write ugly code. Use raw `byte[]` and integer arithmetic.
3. Notice pain ("I'm writing `bytes[offset]<<8 | bytes[offset+1]` for the 5th time").
4. **Now** extract a helper. The API is obvious because I already wrote the callers.
5. Continue.

This is how Buffer, Bits, Pair, BacktrackingIterator were almost certainly born in the real codebase. They feel arbitrary in isolation because I'm seeing the result without the pain that motivated them. Extracting my own version is what makes the original click.

**Rule: domain classes pull infrastructure into existence. Never the reverse.**

---

## The toy-DB plan (after Project 3)

Build a small version of RookieDB myself. Not feature-complete — no recovery, no concurrency, no optimizer — but a real DB I built. Roughly one phase per weekend.

Each phase: pick a **domain class**, steal its public API from the real RookieDB code (don't read implementations, just signatures + Javadoc), strip it to a toy (cut locking, recovery, edge cases), write 3-5 test cases as the success signal, implement it. Let infrastructure helpers emerge from extraction.

| Phase | Domain target           | Likely extracted helpers     |
|-------|-------------------------|------------------------------|
| A     | Toy DiskSpaceManager    | PageId, maybe nothing        |
| B     | Toy BufferManager       | Frame, ClockHand             |
| C     | Toy Table               | Buffer, Bits, Record         |
| D     | Toy BPlusTree           | (mostly reuses C)            |
| E     | SNLJ → BNLJ → SMJ       | BacktrackingIterator         |

### Rules for the toy DB
- **Never spec infrastructure first.** Always start with a domain class. Let helpers fall out.
- **Steal API from real code, not implementations.** Open the real file, copy method signatures + Javadocs into my spec file, close the real file. The contract is the spec; the body is mine to write.
- **Write tests as the spec.** 3-5 test cases per domain class. They're the success signal — the CS61B-shaped "it works" moment.
- **No deadline.** This is a side project I want to do, not an obligation. The moment it becomes obligation, the joy dies.
- **Some weekends I won't have time. That's fine.** Better to do 3 phases excellently than 5 phases under duress.

### Why this won't be 5000 lines of pain
Gitlet was 1500 lines with no reference. The toy DB will be ~5000 lines but with the real RookieDB as a reference manual (consult only when stuck) and CS186 lectures explaining the algorithms. It may feel *easier* than gitlet per-line, because the algorithms are pre-explained — I'm translating concepts into code, which is the part I love.

---

## Concrete next steps

1. **Now → end of Project 3:** Ship mode only. Reading order for Project 3 Part 1:
   - Phase 0 (skim): JoinOperator.java, BacktrackingIterator.java interface
   - Phase 1 (BNLJ): read SNLJOperator → write BNLJOperator → test
   - Phase 2 (Sort): read Run.java signatures → write SortOperator (sortRun → mergeSortedRuns → mergePass → sort, one at a time) → test
   - Phase 3 (SMJ): write SortMergeOperator.fetchNextRecord → test
   - Phase 4 (GHJ): read SHJOperator → write GHJOperator → test
   - During Project 3: **keep a study list** of questions/curiosities I deferred. This becomes fuel for study mode later.

2. **After Project 3:** Start the toy DB. First task: toy Table (Phase C), because it's the most "domain" class and will naturally pull in 2-3 helpers. Use a `Map<Long, byte[]>` as fake disk — no BufferManager yet. Target: one weekend, ~200 lines.

3. **If the first toy task feels like gitlet** (energizing, "I built it" rush), continue with Phase B (BufferManager) the next weekend.

4. **If it doesn't feel like gitlet**, something else is going on — talk to Claude in the next session and diagnose.

---

## Things to remember

- **The conflict between "ship" and "learn" is real, but the fix is separate clocks, not choosing one.**
- **Frustration when reading = a missing question.** Stop and either form one or go write a stub.
- **Topics are not questions.** "Study BufferManager" fails. "Find the line that enforces WAL" works.
- **Infrastructure cannot be designed in isolation.** Always extract it from working domain code.
- **The "I want to build it myself" instinct from CS61B is correct.** It's how humans actually learn systems. Find ways to keep it alive even when the skeleton hands me 90% of the code.
- **Most CS students never realize they can make their own curriculum.** I already have. Follow that instinct.
