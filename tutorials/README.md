# Tutorials

Tutorials are broken up into several sections (the folders in this directory), and each section is broken up into several lessons.
While you can just read these tutorials online, I recommend cloning this repo and running the code for yourself.
All tutorials come with ready-to-execute code.

I suggest that everyone read [Intro to Modeling][], as this introduces key concepts for all Parakeet projects.
After that, I suggest [Simulation][], as this explores ways of running simulation that are more appropriate in a production environment.

From there, I suggest different sections depending on your role:
- For those writing or maintaining models, I suggest [Advanced Modeling][].
  This section greatly expands your tool set for modeling.
  It also discusses some best practices for building larger, more complex models.
  Unlike most other sections, the lessons in [Advanced Modeling][] are mostly stand-alone, rather than building on prior lessons.
  You should skip to lessons that suit your needs.
- For those writing schedulers or planners on top of the simulation, I suggest [Scheduling][].
  This section explores how to use Parakeet's scheduling support to build plans programmatically, informed by simulation.
  This includes an exploration of Parakeet's incremental simulation capabilities, and a discussion of the capabilities, limitations, and additional model requirements that come with it.


[Intro to Modeling]: ./01_intro_to_modeling
[Simulation]: ./02_simulation
[Advanced Modeling]: ./03_advanced_modeling
[Scheduling]: ./04_scheduling
