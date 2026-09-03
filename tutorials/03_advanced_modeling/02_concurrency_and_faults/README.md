# Concurrency and Faults

Parakeet has a strong notion of what a "deterministic" simulator must guarantee.

By "concurrency", we mean multiple tasks in a Parakeet simulator running at the same simulated time.
This is distinct from parallelism. Parakeet is single-threaded, and will physically run only one task at a time.
Handling concurrency carefully is an important part of Parakeet's deteriminism guarantees,
and understanding Parakeet's concurrency model is essential for fully understanding a Parakeet model.

## Motivation

Most discrete-event simulators are "deterministic", in the sense that feeding exactly the same inputs into the simulator twice will result in the same output.
We might label this property "debug determinism", as having this property makes it possible to re-run a misbehaving simulation for debugging.
Without this property, debugging is far more frustrating.

We take the position that debug determinism is necessary but not sufficient for a robust simulator.
The stronger property we'll call "operational determinism" or "ops determinism", defined as "equivalent inputs lead to equivalent outputs".
Asking only for equivalent, not equal, outputs makes this a much harder property to guarantee.
We're saying that differences in the input which the operator considers irrelevant should not meaningfully affect the output.
For example, changing the order that activities are listed in the plan shouldn't change the resource profiles from simulating that plan.

Moreover, we're saying that it's the simulator's responsibility to guarantee ops determinism.
In practice, the simulator can't fully guarantee this without drastically restricting the modeler, so the modeler must cooperate to some degree.
We contend that Parakeet provides clearer and less-restrictive guidelines for a modeler to achieve ops determinism than most competing simulators.

## Concurrency

Many discrete-event simulators largely ignore concurrency.
If two tasks T1 and T2 are scheduled at the same time, they are executed in an arbitrary order.
Whichever executes second observes the effects of the first.

Suppose these tasks are activities in the plan.
To achieve ops determinism, we must ensure that the order these activities are listed doesn't meaningfully affect the results.
To do this, we have two choices:
1. Ensure that the activities are run in a consistent order, or
2. Ensure that the activities' run order doesn't meaningfully affect the results.

Option 1 is not possible in general.
It requires imposing a total order on the activities of a plan, which is robust to all "insignificant" changes.
In particular, it should be robust to:
1. Changing the order that activities are listed in the plan,
2. Renaming the activities, activity types, or activity arguments,
3. Re-ordering the activity arguments in both the activity type and activity plan,
4. Changing the units of an activity argument (while making the inverse change to the value, e.g. expressing 1 hour as 60 minutes)

and potentially other "refactorings" which we shouldn't expect to impact the simulation results.

Since we can't rely on the order that activities are listed in the plan (point 1), we'd have to sort the activities.
Since we can't rely on the activity names or activity type names (point 2), we'd have to sort by their arguments.
But since we can't rely on the activity arguments' order (point 3), names (point 4), nor values (point 5), we can't do that either.

Thus, it's not possible to impose a total ordering on activities which is stable under all refactorings.
Put another way, any attempt at ops determinism through activity sorting will fail, showing behavioral changes under some refactorings.
We must go with option 2, and ensure that the activities' run order doesn't meaningfully affect the results.

Most simulators put the burden for this almost entirely on the modeler.
The modeler must ensure, somehow, that any combination of concurrent activities, run in any order, produces the same result.
This is challenging in practice.

Parakeet tries to take up the majority of this load.
In Parakeet, concurrent tasks cannot observe each other's effects.
So long as the modeler writes task code without hidden state (mutable non-local variables which aren't cells managed by Parakeet),
which is a deterministic function of the cells it reads, then ops determinism of the simulator overall is guaranteed.

### Example 1 - Basic Concurrency

To see how this works, let's imagine T1 is a task like this:
```kotlin
context (_: TaskScope)
fun T1(x: MutableIntResource) {
    stdout.report("T1: ${x.getValue()}")
    x.increment(1)
    stdout.report("T1: ${x.getValue()}")
    delay(0.seconds)
    stdout.report("T1: ${x.getValue()}")
}
```
and T2, similarly, is a task like this:
```kotlin
context (_: TaskScope)
fun T2(x: MutableIntResource) {
    stdout.report("T2: ${x.getValue()}")
    x.increment(2)
    stdout.report("T2: ${x.getValue()}")
    delay(0.seconds)
    stdout.report("T2: ${x.getValue()}")
}
```

Running these two concurrently, and assuming `x` starts at 0, we'll get a set of reports something like this:
- T1: 0
- T1: 1
- T2: 0
- T2: 2
- T1: 3
- T2: 3

Conceptually, Parakeet runs these two tasks on separate "branches", until they "commit" their changes by calling `delay`.
(Actually, it's the `await` function that commits, but `delay` calls `await` under the hood.)
At that point, the branches are merged, and the next step of each task happens on separate branches again.
Visually, the history of `x` looks something like this:
```
T1:   0 ---- 1 ----        3 ----
     /             \      /      \
--- 0               3 ----        3 ---
     \             /      \      /
T2:   0 ---- 2 ----        3 ----
```

Parakeet knows how to combine these effects at the first commit point because the effects produced by each task are functions, not values.
Each effect is a procedure for how to mutate the value, not merely the value resulting from that mutation.

-----

_Aside_

The sharp-eyed may notice that an equivalent ordering of the tasks will produce a set of reports like this, by swapping the order T1 and T2 physically run:
- T2: 0
- T2: 2
- T1: 0
- T1: 1
- T2: 3
- T1: 3

This is why we can only claim that equivalent inputs produce "equivalent" outputs, and not "equal" outputs.
Since model code may write directly to the `stdout` and `stderr` channels, concurrent reports may appear in any order.
We guarantee those reports will have the same content, though.

-----

### Example 2 - Faults due to Imprecise Modeling

Let's see what would happen if we re-wrote these effects to read and write instead of using the `increment` effect, like this:
```kotlin
// T1
x.set(x.getValue() + 1)

// T2
x.set(x.getValue() + 2)
```

The first few reports will be correct, but then something goes quite wrong:
- T1: 0
- T1: 1
- T2: 0
- T2: 2

If we look at the `stderr` channel, we'll see messages saying that tasks T1 and T2 failed with an exception.

Recall that the effects in this scenario are "set 1" and "set 2".
When Parakeet tries to merge these effects, it detects that the order it applies these effects changes the outcome.
Instead of choosing arbitrarily, it maintains ops determinism by putting the resource in a "faulted" state.
When each task attempts to read this faulted resource, a `FaultedResourceException` is thrown, which crashes that task.

From this example, it may seem like Parakeet's approach is inferior.
If we had simply ordered these tasks arbitrarily, regardless of the order, we'll get a final answer of 3 and avoid the fault.
In some sense, maybe this better reflects the intent of the modeler.

### Example 3 - Faults due to Ambiguous Modeling

To see the pitfall in arbitrarily ordering tasks though, consider a third scenarios, where T1 increments and T2 resets the value to 0.
In this case, once again, Parakeet will fault, since the effects "increment by 1" and "set 0" do not commute.
By contrast, the arbitrarily ordered tasks would produce a final answer of either 0 or 1, depending on the order chosen.
As explained above, it's not possible for this choice to be consistent, so the model now has a silent non-determinism.

A small non-determinism like this may go undetected, or at least tolerated, in a small, simple model.
When the model grows in size and complexity, these small non-determinisms can compound into a hard-to-track-down bug.
David, the author of Parakeet, has personally spent days tracking down one such bug in a production system.

### Best Practices

First, use effects that reflect your intent.
For example, if you want to increment a value, use an increment effect, not a set effect.
This greatly reduces the likelihood of faults, since effects that "should" commute will do so.

Second, when effects don't commute, it means the model is ambiguous.
The model has a real problem in its logic which needs to be resolved.
Faulting is a signal to the modeler to proactively avoid a more subtle bug later.
Take this signal seriously, and try to address the issue in the model by more precisely aligning the model with your intent.

## Faults

Inevitably, models are imperfect.
Parakeet abides by the principle that "You don't have to be right, but you can't be wrong."
There are situations where the it's not feasible, or maybe not possible, to produce a right result.
Instead of producing a wrong result, we fail.

We can describe two major categories of failure for the simulator:
1. Cell code throws an exception
2. Task code throws an exception

We've seen the most common example, where effects don't commute.
This is an instance of cell code throwing an exception, and it causes the cell (resource) to enter a faulted state.

This contains the exception in the cell, allowing the rest of the simulator to continue, without putting an incorrect value in the cell.
When a task attempts to read a faulted cell, it throws a `FaultedResourceException`.

This, or any other uncaught exception thrown by task code, causes the task to crash.
A message is printed to the simulator's `stderr` channel, an activity end event is produced if the task was an activity,
and the task is removed from the simulator.
Once again, the exception is contained within that task, and other tasks and the simulator overall continue to run.

## Fault Recovery

Importantly, a task crashes when the exception goes _uncaught_.
Most exceptions, including `FaultedResourceException`, can be caught, if the task can provide a sensible way to recover.
A great example of this is the `register` method, which creates a daemon task to watch a resource and report when it changes.
If the resource faults, the daemon catches this exception, writes a warning to `stderr`, and continues.

If a cell is faulted, it's possible to recover if any task sets a value for that cell.
Note that the value must be `set`, rather than computed by any other effect.
Any other effect would rely on the cell's current value, but a faulted cell doesn't have a current value.
