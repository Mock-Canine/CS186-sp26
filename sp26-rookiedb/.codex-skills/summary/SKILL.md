---
name: summary
description: Use when the user is studying source code through questions and wants help discovering their misunderstandings, explaining confusing design choices, and distilling the discussion into concise future-review notes instead of a verbose transcript.
---

# Summary

Use this skill when the user is reading code, asking questions about design/fields/methods/control flow, comparing possible alternative implementations, or asking to summarize a code discussion for future review.

The goal is not to produce a transcript. The goal is to help the user build a durable mental model of the code.

## Operating Mode

Default to two phases:

1. **Discovery phase**: answer the user's current code question directly, while tracking likely confusion points.
2. **Distillation phase**: when the user asks for notes, summary, conclusion, or future-review material, produce a compact code-understanding note.

Do not force a formal note while the user is still exploring unless they ask for one.

## During Discovery

Read the relevant code before explaining. Prefer concrete references to fields, methods, call order, and state changes.

Actively look for the user's misunderstanding by noticing:

- repeated questions about the same field or method
- proposals to delete fields, simplify state, or use another API
- confusion between object identity and logical value
- confusion between iterator state and iterable source
- confusion between inner-level and outer-level state
- assumptions that a helper API preserves more state than it actually does
- uncertainty about whether code is necessary or accidental

When a misunderstanding appears, state it explicitly and neutrally:

```text
The key correction is: ...
```

Use small examples with state traces when helpful. Prefer examples that show why a tempting simpler implementation fails.

For code involving state machines, iterators, caches, locks, transactions, or recovery, explain:

- what state is being tracked
- who owns that state
- when the state changes
- what invariant would break if a field/method were removed

Avoid long method-by-method paraphrases unless the user specifically asks for them.

## Distillation Trigger

When the user asks to "summarize", "conclude", "save notes", "write markdown", "make future review notes", or similar, switch to distillation.

Before writing, infer the user's actual confusion points from the conversation. Do not merely compress all assistant messages.

## Distilled Note Format

Use this structure by default:

```md
# <Class/File/Concept> Notes

## Purpose

One short paragraph explaining what abstraction this code provides.

## Mental Model

The most important model for thinking about the code.

```text
small diagram or state relationship if useful
```

## Key State / Invariants

- `<field or concept>`: why it exists, not just what it stores.
- `<field or concept>`: what would break if it were removed.

## Important Flow

Short trace of the main behavior. Use an example if it clarifies the design.

## Confusions Resolved

- Initial assumption: ...
  Correction: ...
- Initial assumption: ...
  Correction: ...

## Review Checklist

- What to remember when reading/modifying this code.
- What simplification is tempting but wrong.
```

Adjust headings to the code. Keep the note compact. Aim for 30-80 lines unless the user asks for more.

## What To Include

Prioritize:

- purpose of the abstraction
- key invariants
- confusing fields and why they exist
- concrete examples that prove the design is necessary
- user's wrong assumptions and the corrected model
- relationships between classes/interfaces
- implications for future modifications

## What To Cut

Remove:

- full Q&A transcript
- repeated explanations
- generic code paraphrase
- obvious getter/setter behavior
- long code blocks copied from source
- every method listed mechanically when only two methods matter

The final note should preserve the useful insight, not the entire route taken to reach it.

## Example Distillation Style

For an iterator class, prefer:

```md
## Mental Model

This class tracks two levels of position:

```text
iterator object = exact position inside one chunk
index field     = which cached chunk that iterator belongs to
```

The iterator alone cannot tell which chunk comes next. The index alone cannot restore the item-level mark. Both are needed.
```

over:

```md
`hasNext()` calls `moveNextToNonEmpty()`.
`next()` calls `hasNext()`.
`reset()` resets the iterator.
```

## Interaction Pattern

If the user asks whether taking notes is worthwhile, recommend notes only for durable insights:

- abstraction purpose
- invariants
- non-obvious design choices
- examples that explain why simpler alternatives fail
- mistakes the user almost made

Warn against transcript-style notes and method-by-method summaries that only restate the source.

If the user wants repeated use, suggest this prompt:

```text
Use the summary skill.
Read the relevant code, answer my questions, track what I misunderstand, and when I ask for notes, distill the discussion into concise future-review notes with examples and resolved confusions.
```

## Quality Bar

A good distilled note lets the user return weeks later and quickly answer:

- What is this code for?
- What state is essential?
- Why is this design more complex than the naive version?
- What was I confused about?
- What should I avoid breaking when editing it?

If the note cannot answer those questions, revise it before finalizing.
