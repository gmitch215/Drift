# Claim-Check Labelling Protocol

This protocol was written before any thread was read. Labels are in `claim-labels.yml`.

## Unit

One closed issue thread from a repo in `corpus.yml`, with one candidate comment: the first comment
by a user whose `author_association` is `MEMBER`, `OWNER` or `COLLABORATOR`, who is not the issue
author, and whose text matches a correction pattern.

Correction patterns (case-insensitive), identified as `p1` to `p4` in this order:

- `not supported (on|in|under)`
- `(does not|doesn't|do not|don't) (support|allow)`
- `(is|are) not (possible|available) (on|in)`
- `you('re| are) (on|using|running) (version|v?[0-9])`

Threads come from `search/issues` with `is:issue is:closed` and one quoted phrase per query. For
each repo the candidates are ordered by SHA-256 of `repo#number` and the first 10 are read, so the
sample does not depend on search ranking.

## Verdicts

A verdict applies to the candidate comment and is decided by the first rule that holds:

1. `not-correction`: the issue author made no factual statement about their environment or about
   what the product does or supports (a feature request, a question, a crash report with no claim).
2. `not-correction`: the comment answers a feature request or says the behaviour is not supported
   without contradicting anything the author claimed.
3. `not-correction`: the comment says the author's version is old and the bug is fixed later, and
   the author did not claim to be on the latest version.
4. `correction`: the author stated a fact about their environment or the product (a version,
   platform, option or feature), and the comment contradicts it with a different stated fact.
5. `unclear`: the comment contradicts or implies a contradiction but gives no alternative fact, or
   the thread is too short to tell what the author claimed.

The reporter agreeing or disagreeing afterwards does not change the verdict.

## Stored Fields

`repo`, `issue`, `comment` (comment id), `pattern` (the first pattern that matched), `rule` (the
number above that decided it) and `verdict`. Issue or comment text is never stored.

## Measures

- Heuristic precision: `correction / (correction + not-correction)`, with the Wilson 95% interval;
  `unclear` is reported separately and excluded.
- Claim-check engine: `drift triage` claims are run over the redacted issue body of every
  `correction` thread. Report how many threads it flags, whether the flagged sentence is the one the
  maintainer contradicted, and its precision over all hits it makes on the labelled threads.
