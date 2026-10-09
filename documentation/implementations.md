# Implementations — DBOS

This explains the classes built on top of the core interfaces: the two `Trigger` implementations, the two `RetryPolicy` implementations, and `JobDefinition`. The actual code lives in the repo — this is about what each class does and why it's shaped the way it is.

---

## Trigger implementations

**`FixedRateTrigger`** fires repeatedly, forever, at a fixed interval. It stores a single `Duration` (the interval) set once at creation. Every time it's asked "when's the next fire time?", it just adds that interval to whatever the last fire time was. This is what makes it "fixed rate" — it keeps recalculating indefinitely, as long as something keeps asking.

Example: interval = 10s. Ask it with a last-fire-time of 12:00:00, it says 12:00:10. Ask it again with 12:00:10, it says 12:00:20. It never stops producing a next answer.

**`OneTimeTrigger`** fires exactly once. Instead of recalculating on each call, it computes its single fire time **once, at creation** — "now plus this delay" — and just keeps returning that same fixed answer forever after, completely ignoring the last-run-time it's given. This is the key structural difference from `FixedRateTrigger`: there's nothing to repeat, so there's nothing to recalculate.

Example: created at 21:00:00 with a 10s delay, its fire time is locked in as 21:00:10. Doesn't matter if you ask it immediately or five minutes later — it always says 21:00:10.

---

## RetryPolicy implementations

**`ExponentialBackoffPolicy`** makes each retry wait longer than the last — the wait doubles every attempt. It's configured with a max retry count and a base delay. Whether a retry is still allowed is a simple comparison against the max. How long to wait is the base delay multiplied by 2 raised to the attempt number, computed with a left-shift rather than `Math.pow` — left-shift stays in whole-number math the entire way, so there's no floating-point rounding risk and no need to cast anything.

Example: base delay 2s. Attempts 0, 1, 2 produce waits of 2s, 4s, 8s.

**`FixedDelayPolicy`** is the simpler sibling — same wait every single time, no growth. The "should I retry" check is identical to the exponential version. The "how long to wait" calculation just returns the configured delay outright, ignoring which attempt number it's on.

Example: delay 2s. Attempts 0, 1, 2 all produce a wait of 2s.

---

## JobDefinition

This is the object that ties a job together: which `Job` to run, on what `Trigger`, with what `RetryPolicy`, plus its own id, name, and current status. It's built exclusively through a nested `Builder`, never through a plain public constructor.

**Which fields are locked vs. changeable.** Everything describing *what the job is* — id, name, the job itself, its trigger, its retry policy — is set once and never touched again. The one exception is status, which is the whole point of having `JobStatus` as a mutable field: it's expected to move through PENDING → RUNNING → DONE/FAILED over the job's life.

**Status is never set directly.** There's deliberately no plain setter for status. Changing it goes through a guarded method that checks the transition is actually legal (using the rules built into the `JobStatus` enum itself) before allowing it, and throws if it isn't. This means nothing in the codebase — not today, not something added six weeks from now by someone who's forgotten the rules — can silently put a job into an illegal state like jumping from DONE back to RUNNING.

**Why a Builder instead of a plain constructor.** Two reasons. First, most of these fields are genuinely optional at different points of assembly — only the name is actually required — and a plain constructor would force every caller to supply everything, in a fixed order, even fields they don't have yet. Second, readability: a chained builder call names every argument at the point you set it, so nobody has to cross-reference a constructor signature to know what a bare value means, and it's structurally impossible to accidentally swap two same-typed arguments in the wrong order the way a plain constructor allows.

**id and status are deliberately not builder-settable.** You can't call something like `.id(...)` or `.status(...)` on the builder — on purpose. The id starts as a placeholder (0) and only becomes real once the job is actually saved by a `JobStore`, which is the thing responsible for generating real ids. The status is hardcoded to PENDING the moment a builder is created, with no way to override it, because every job genuinely does start at PENDING — there's no legitimate reason to let a caller build a "freshly created but already DONE" job.

**Open question, not yet resolved:** right now, building a `JobDefinition` only checks that a name was given — it doesn't check that an actual `Job` or `Trigger` was ever attached. So it's currently possible to build a job with nothing to run and nothing to schedule it, and have that succeed silently. Worth deciding whether to validate that in `build()` now, or let it surface later as a failure once the engine actually tries to use it.
