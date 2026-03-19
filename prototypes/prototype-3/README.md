# Tileworld Multi-Agent Implementation: Prototype 3

## Overview

This directory contains Prototype 3. Building upon the Soft Zoning and Cluster Scoring introduced in Prototype 2, this iteration shifts the team's behavior to be State-Driven and Congestion-Aware. 

Note: An initial attempt was made to introduce Predictive Patrolling using spawn heatmaps. This approach failed during testing and was reverted, as detailed below.

## Improvements & Experiments

### A. State-Driven Heuristics (Flattened)
Previous: Target utility was primarily based on distance, soft-zoning penalties, and static cluster bonuses. An agent treated a cluster of 3 tiles the same regardless of whether it had space for 1 tile or 3 tiles.

Improvement: The A-star scoring logic in TWBaseAgent is now bound to the agent's physical inventory state.
1. Hard Constraints: Agents with 0 tiles assign Integer.MIN_VALUE to holes. Agents with 3 tiles assign Integer.MIN_VALUE to new tiles.
2. Capacity Scaling: The cluster bonus adds a static value based on the agent's available capacity. An empty agent prioritizes a cluster of tiles more than an agent with 1 tile. (Initial testing used a multiplier for capacity, which caused a global claiming inversion where distant empty agents claimed tasks over nearby full agents. This was flattened to a static addition to stabilize routing).

### B. Fuel Station Reservations (Congestion Control)
Previous: All agents used the same static threshold to determine when to refuel. On smaller maps or during mass spawns, multiple agents hit this threshold simultaneously, causing A-star pathfinding bottlenecks and traffic jams at the single fuel station.

Improvement: Agents broadcast their distance to the fuel station using a new FUEL_INTENT message when entering survival mode. ConsensusMemory maintains a fuelStationQueue. When calculating its fuel safety threshold, an agent reads this queue and applies a penalty for every peer currently ahead of it. This dynamically staggers refueling trips. Agents further away leave for the station earlier if it is crowded.

### C. Failed Experiment: Predictive Patrolling (Reverted)
Concept: Replaced the Phase 2 Repulsive Random exploration with a spawnHeatmap in ConsensusMemory. Agents were programmed to loiter in high-probability sectors rather than wandering randomly.

Result: Average reward dropped significantly. The predictive logic overfit to early randomness. Agents stubbornly loitered in old spawn zones and ignored actual new spawns, spending more time waiting than scoring.

Resolution: The heatmap logic was completely removed. Phase 2 exploration reverted to the Repulsive Random model from Prototype 2, where agents maximize distance between themselves and peers to ensure maximum sensor coverage.

## Core Files Modified
1. TWBaseAgent.java: Updated think loop, added state-driven addition math in calculateStandardizedScore, and implemented the FUEL_INTENT broadcast. Reverted exploration back to getRepulsiveTarget.
2. ConsensusMemory.java: Added the fuelStationQueue parser to manage station congestion. (Heatmap logic removed).

## Benchmarks (TileworldMain.java)

| Configuration File | Map Size | Object Spawns/Step | Object Lifetime | Run 1 Reward | Run 2 Reward | Average Reward |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Parameters.java | 50x50 | 0.2 (Low) | 100 (High) | 629.0 | 637.9 | 633.4 |
| Parameters2.java | 80x80 | 2.0 (High) | 30 (Low) | TBC | TBC | TBC |

## Conclusion

Despite the implementation of more sophisticated coordination logic, Prototype 3 resulted in a minor negative effect on the overall score compared to Prototype 2. The data suggests that these upgrades were not as useful as initially theorized. 

In a highly dynamic environment like Tileworld, where object lifetimes are short and spawn locations are stochastic, adding layers of state-dependent logic and "smart" reservations likely introduced enough decision-making overhead and pathfinding detours to offset the gains. Prototype 2’s simpler, more reactive model appears more robust for the current configurations.
