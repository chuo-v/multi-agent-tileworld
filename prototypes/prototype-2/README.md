# Tileworld Multi-Agent Implementation: Prototype 2

## Overview

This directory contains Prototype 2, an enhanced version of Prototype 1. The architecture evolves the original concept into **"Implicit Coordination v2.0"**. While still rooted in a shared world view via broadcast communication, this version introduces several key improvements to agent coordination and efficiency. Only the improvements are documented below; all other mechanisms remain as described in Prototype 1.

## Improvements Over Prototype 1

### A. Soft Zoning (Dynamic Task Stealing)

**Previous:** In Prototype 1, agents strictly ignored any targets outside their assigned zone, applying a hard penalty (`Integer.MIN_VALUE`) that made cross-zone tasking impossible. This could lead to inefficiency, such as when a target was just outside a zone or the zone owner was far away.

**Improvement:** Prototype 2 replaces the hard constraint with a _soft penalty_. Agents can now opportunistically "steal" high-value tasks from neighboring zones if the benefit outweighs the penalty. This is achieved by applying a configurable zone-crossing penalty to the utility score, rather than outright forbidding the action. As a result, agents may claim tasks outside their zone if they are especially close or valuable, leading to more flexible and efficient team behavior without explicit negotiation.

### B. Cluster-Based Scoring

**Previous:** In Prototype 1, each target (tile or hole) was scored in isolation, regardless of its surroundings. An isolated target was valued the same as one in a dense cluster, missing opportunities for efficient batch collection.

**Improvement:** Prototype 2 introduces a _cluster bonus_ to the scoring system. Agents now increase the utility score of a target if it is surrounded by other nearby targets. This encourages agents to prioritize clusters, minimizing travel time between tasks and enabling more efficient region clearing. The bonus is dynamically calculated based on the density of valid targets in the vicinity of each candidate.

### C. Opportunistic Refueling

**Previous:** In Prototype 1, agents only refueled when their fuel level dropped below a critical safety threshold, often resulting in "panic runs" across the map to avoid running out of fuel.

**Improvement:** In Prototype 2, agents proactively refuel whenever they pass near the fuel station and are not already full, even if not in immediate danger. This "pit stop" strategy is implemented in the `TWBaseAgent` class, ensuring all agents benefit from improved fuel efficiency and reducing the risk of costly long-distance detours for emergency refueling. The logic checks for proximity to the station and available capacity, allowing agents to top up fuel as part of their normal route.

---

All other architectural details, agent registration, decision-making hierarchy, consensus memory, exploration strategy, communication, utility scoring, performance optimizations, and error recovery remain as described in Prototype 1.

## Simulation Results

The table below outlines the average rewards achieved across 2 runs (of 10 full-length simulations) of [`TileworldMain.java`](Tileworld/src/tileworld/TileworldMain.java) for both predefined environment configurations using 6 (identical) `PrototypeAgent` instances. As the actual agents that would be implemented will have slight differences in behavior due to the varying customizations applied, the results below should just be considered as a rough indicator of the level of performance that can be expected.

| Configuration File                                             | Map Size | Object Spawns/Step | Object Lifetime | Run 1 Reward | Run 2 Reward | Average Reward |
| :------------------------------------------------------------- | :------- | :----------------- | :-------------- | :----------- | :----------- | :------------- |
| [`Parameters.java`](Tileworld/src/tileworld/Parameters.java)   | 50x50    | 0.2 (Low)          | 100 (High)      | 652.7        | 657.5        | 655.1          |
| [`Parameters2.java`](Tileworld/src/tileworld/Parameters2.java) | 80x80    | 2.0 (High)         | 30 (Low)        | TBC          | TBC          | TBC            |

## Notes & Setup

- **Running the GUI (Visual Debugging):** To watch the agents in real-time, execute the main method inside [`TWGUI.java`](Tileworld/src/tileworld/TWGUI.java).
- **Running Headless (Bulk Testing):** To run 10 iterations sequentially for fast result gathering, execute the main method inside [`TileworldMain.java`](Tileworld/src/tileworld/TileworldMain.java).
- **Configuration of External MASON Library:** The changes to configure the MASON library ([MASON_14.jar](../../MASON_14.jar)) have not been committed with this prototype implementation, and so the build path for this external JAR needs to be configured to run the project.
