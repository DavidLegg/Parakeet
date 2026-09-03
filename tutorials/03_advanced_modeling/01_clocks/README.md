# Clocks and Timers

In this tutorial, we'll cover clocks and timers.
These are special resources which directly represent and track time and time-related quantities.

This tutorial also introduces the idea of continuous resources, assuming that you're familiar with discrete resources from [Intro to Modeling][].
We'll discuss continuous resources in more detail in later lessons.

## Clocks

All Parakeet simulations come with a built-in `simulation_clock` accessible through the `SimulationScope` context parameter.
This clock is synchronized to the simulation time, and cannot be modified by the model.
The current simulation time can be accessed by getting the value of this resource using `simulation_clock.getValue()`,
as we would access any other resource's value.
Since this operation is so common, there's also a convenience method `now()` defined on `SimulationScope` that returns the same value.

We can see that the type of `simulation_clock` is `ClockResource`, an alias for `Resource<Clock>`.
`Clock` is the "dynamics" type for the resource.
The "dynamics" of a resource are a wrapper around the value.
It may contain additional state data, and it defines how the value evolves continuously over time through a `step` method.
For the discrete resources we used in [Intro to Modeling][], the dynamics were type `Discrete`.
`Discrete` contains no additional state data, and trivially defines the evolution of the value to be constant over time.

By contrast, the `Clock` dynamics has two fields, `time` and `rate`, and it defines `step` to increase `time` by `rate * dt` for a given time step `dt`.
If we set `rate` to 1.0, the clock advances "normally". Set it to 0.0, and the clock is paused.
Other rates are possible, but unusual.

While we can't affect `simulation_clock` like this, we can build our own clocks and affect them.
To do so, we use the general-purpose `resource(...)` constructor, with a `Clock` dynamics, from an `InitScope` block like this:

```kotlin
val mutableClock: MutableClockResource = resource("mutableClock", Clock(start, 1.0))
// Unlike simulationClock, this one is mutable, so we can apply effects:
spawn("Apply effects to mutableClock", task {
    delay(1.hours)
    mutableClock.pause() // set the rate to 0.0
    delay(1.hours)
    mutableClock.resume() // set the rate to 1.0
    delay(1.hours)
    mutableClock.set(start) // set the time to start, but don't change the rate
})
```

We can also create a clock resource using the `clock()` constructor:

```kotlin
val clock1: MutableClockResource = clock("clock1")
```

Like most built-in resource types, the `ClockResourceOperations` class provides a suite of utilities for working with clocks,
including methods not covered here.

## Timers

Timers are like clocks, but they track relative time (type `Duration`) instead of absolute time (type `Instant`).
They also have a "rate" parameter that tells them how to evolve over time.

There are no built-in timers in Parakeet, but we can create our own.
We could once again use the general-purpose `resource(...)` constructor, but since timers are so common,
we also have the more-concise `timer()` constructor.

For example, to track the cumulative amount of time that a state is "ON":
```kotlin
val state: MutableDiscreteResource<State> = discreteResource("state", State.OFF)
val timeSpentOn: MutableTimerResource = timer("timeSpentOn", initialRate = 0.0)
// There are more advanced ways to do this using derived resources, but a daemon task like this works well:
spawn("Update timeSpentOn", whenever(state equals State.ON) {
    timeSpentOn.resume()
    await(state notEquals State.ON)
    timeSpentOn.pause()
})
```

Similar to clocks, timers also have a `TimerResourceOperations` class that provides additional utilities not covered here.

-----

_Aside_

If we only had discrete resources, we would need to store the last time the state was set to "ON",
and then manually integrate that time into our cumulative total whenever the state changes.
That code might look something like this:

```kotlin
val state: MutableDiscreteResource<State> = discreteResource("state", State.OFF)
val lastTimeOn: MutableDiscreteResource<Instant> = discreteResource("lastTimeOn", Instant.EPOCH)
val timeSpentOn: MutableDiscreteResource<Duration> = discreteResource("timeSpentOn", Duration.ZERO)
spawn("Update timeSpentOn", whenever(state equals State.ON) {
    lastTimeOn.set(now())
    await(state notEquals State.ON)
    timeSpentOn.increaseBy(now() - lastTimeOn.getValue())
})
```

While the state is "ON", the time stored by a purely-discrete model like this would be inaccurate, unless we wrote additional code to update it periodically.
Judicious use of continuous resources like this can greatly simplify a model.

-----

## Arithmetic on Clocks and Timers

Clocks and timers are naturally related by arithmetic laws.
For example, the difference between two clocks is a timer, the sum of a clock and timer is a clock, etc.

```kotlin
val clock1: MutableClockResource = clock("clock1").registered()
val clock2: MutableClockResource = clock("clock2", initialTime = start + 6.hours, initialRate = -1.0).registered()
val timer1: MutableTimerResource = timer("timer1", initialTime = 1.hours, initialRate = 0.0).registered()
val timer2: MutableTimerResource = timer("timer2").registered()

val clockDifference: TimerResource = (clock1 - clock2).named { "clockDifference" }.registered()
val offsetClock: ClockResource = (clock1 + timer1).named { "offsetClock" }.registered()
val timerSum: TimerResource = (timer1 + timer2).named { "timerSum" }.registered()
```

All currently-defined arithmetic operations between clocks and timers are defined in `ClockResourceOperations` and `TimerResourceOperations`.
Additional operations may be added in the future.

[Intro to Modeling]: ../../01_intro_to_modeling
