# Implementations — DBOS

This explains the classes built on top of the core interfaces: the two `Trigger` implementations, the two `RetryPolicy` implementations, and `JobDefinition`. The code lives in the repo; this is about what each class does and why it is shaped the way it is.

---

## Trigger implementations

**`FixedRateTrigger`** fires repeatedly at a fixed interval. It stores a single `Duration`, set once at creation, and every time it is asked "when is the next fire time?" it adds that interval to the time it is given. The interval must be positive, because a zero or negative interval would never move forward in time, so it is rejected at creation.

Example: interval 10s. Asked with 12:00:00 it says 12:00:10; asked with 12:00:10 it says 12:00:20.

**`OneTimeTrigger`** fires exactly once. It fixes its single fire time at creation and returns that same answer forever, ignoring the time it is given. There are two ways to build one: from an exact `Instant` (which is also how a stored database row is turned back into a trigger, and how tests stay repeatable), or from a delay measured from the moment the trigger is created. The engine ends the schedule when the trigger's answer is not later than the run it just finished, which is how a one-time job stops.

Example: created at 21:00:00 with a 10s delay, the fire time is 21:00:10 however many times it is asked.

---

## RetryPolicy implementations

Both policies count attempts from 1 and receive the number of the attempt that just failed. Both are configured with a maximum number of retries, and a retry is allowed while the failed attempt number is at most that maximum.

**`ExponentialBackoffPolicy`** makes each retry wait twice as long as the one before. The wait is the base delay times 2 raised to (attempt number minus 1), computed with a left shift rather than floating-point math, so there is no rounding risk. The exponent is capped so a very large attempt number cannot overflow.

Example: base delay 2s, maximum 3 retries. Attempts 1, 2 and 3 failing produce waits of 2s, 4s and 8s; a fourth failure is final.

**`FixedDelayPolicy`** waits the same time every attempt. With a maximum of 0 it means "never retry", which is what a job built without a policy receives.

Example: delay 2s, maximum 3 retries. Attempts 1, 2 and 3 failing each produce a wait of 2s.

---

## JobDefinition

`JobDefinition` is the permanent rule that ties a job together: its id, name, the `Job` to run, its `Trigger`, its `RetryPolicy` and whether it is still active. It holds **no status**; status lives on executions.

**It is fully immutable.** The only things that ever differ between saved versions of a job are its id and its active flag, so those change by making a copy (`withId` for the store assigning a real id, `withActive` for cancelling), never by mutating. Because a built `JobDefinition` is always complete and never changes, it can be shared freely between threads without locking, and the engine never has to check it for missing parts.

**It is built through a nested builder.** A job needs a name, a `Job` and a `Trigger`; the retry policy is optional. Two reasons for a builder instead of a long constructor: each value is named at the call site, so nobody has to look up a constructor signature or risk swapping two arguments of the same type; and `build()` is a single place where the result is validated.

**`build()` validates.** It rejects a missing or blank name, a missing job and a missing trigger with an `IllegalStateException`. The name is trimmed, so " report" and "report" cannot become two different jobs. A missing retry policy becomes a policy with zero retries.

**id and active are deliberately not builder-settable.** The id starts as a placeholder (0) and becomes real only when a `JobStore` saves the job, because the store is the thing responsible for generating ids. A new job always starts active.
