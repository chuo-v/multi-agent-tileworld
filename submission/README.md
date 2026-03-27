# Tileworld Multi-Agent Implementation

## 1. Overview
This directory contains our final submission for the Tileworld multi-agent simulation. The architecture is built around the concept of **Implicit Coordination v2.0**, and is based off [prototype-2](../prototypes/prototype-2/) (an enhanced version of [prototype-1](../prototypes/prototype-1/)). Rather than using a centralized leader or explicit task-negotiation protocols, agents operate autonomously while maintaining a perfectly synchronized "Shared Map" via broadcast communication.

To ensure consistent survival and coordination logic across the team, the architecture relies heavily on Object-Oriented subclassing. A universal base class acts as the "Operating System," enforcing strict rules for survival, yielding, and memory synchronization, while specific agent implementations subclass this base to define unique worker strategies and optimizations.

## 2. Classes Added
The implementation introduces three core classes to the Tileworld framework to drive the implicit coordination strategy:

* [**`TWBaseAgent.java`**](Tileworld/src/tileworld/agent/TWBaseAgent.java)
  The abstract core of the agent team. It enforces a strict priority hierarchy for every tick and handles the standardized communication protocol and object utility scoring.
* [**`ConsensusMemory.java`**](Tileworld/src/tileworld/agent/ConsensusMemory.java)
  An advanced memory module that bridges an agent's private sensor data with the team's shared global state. It handles broadcast parsing, dynamic map zoning (Phase 1 vs. Phase 2 exploration), BFS-based exploration targeting, and "garbage collecting" expired objects.
* [**`ConsensusPathGenerator.java`**](Tileworld/src/tileworld/planners/ConsensusPathGenerator.java)
  A customized A* pathfinder. Instead of using an agent's isolated local memory, this planner plots routes strictly using the `ConsensusMemory` map. This ensures all agents calculate distances and route around obstacles using the exact same global data snapshot.

## 3. Individual Agent Implementation

### Implementation Overview

Creating a new agent strategy involves extending `TWBaseAgent` and implementing the [`executeWorkerLogic()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L1019-L1024) method. This method acts as a sandbox that is only executed if all higher-priority survival and reflex tasks are satisfied by the base class.

To prevent individual implementations from accidentally corrupting the shared memory or improperly claiming targets, the `consensusMemory` variable is [strictly encapsulated](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L57-L58). Implementations must use safe, delegated pipelines to interact with the environment state.

Individual agent implementations are added to the following files.
* [TWAgent1.java](Tileworld/src/tileworld/agent/TWAgent1.java)
* [TWAgent2.java](Tileworld/src/tileworld/agent/TWAgent2.java)
* [TWAgent3.java](Tileworld/src/tileworld/agent/TWAgent3.java)
* [TWAgent4.java](Tileworld/src/tileworld/agent/TWAgent4.java)
* [TWAgent5.java](Tileworld/src/tileworld/agent/TWAgent5.java)
* [TWAgent6.java](Tileworld/src/tileworld/agent/TWAgent6.java)

### Implementation Requirements & Tips

#### Requirements
* Before implicitly claiming a target, [`isBestCandidate()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L761-L862) should be used to mathematically verify that the agent has the highest utility claim to a target compared to all other peers before committing to a path.
* Before moving into a grid cell, [`shouldYieldMove()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L864-L890) should be used to proactively check if stepping into that specific coordinate would physically block or interfere with a higher-priority teammate (resolved via lexicographical name comparison, e.g., "Agent1" overrides "Agent2").

#### Tips
* [`getFuelStationFromConsensusMemory()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L682-L688) can be used to evaluate opportunistic refueling if passing by the station.
* [`getLowerPriorityTilesFromConsensusMemory()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L350-L363) and [`getLowerPriorityHolesFromConsensusMemory()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L365-L378) can be used to retrieve pre-filtered lists of valid targets that the agent is permitted to consider.

## 4. Strategy Details
The implicit coordination strategy relies on several sophisticated underlying mechanics to maximize efficiency and minimize wasted fuel.

### Decision-Making Hierarchy ([`think()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#LL100-L181))
Every agent's brain operates on a rigid, standardized priority queue to ensure critical survival and team-level tasks are never ignored. The hierarchy evaluates in this exact order:
1. **Immediate Free Lunch:** If the agent is physically standing on a valid tile or hole and has capacity, it instantly executes the action ([`checkImmediateFreeLunch()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L183-L210)).
2. **Station Search:** If the team does not yet know where the fuel station is, the agent abandons all other tasks to execute a zoned exploration sweep.
3. **Fuel Safety Check:** If the agent's fuel drops below its dynamic, A*-distance-based safety threshold, it immediately routes to the fuel station ([`getFuelSafetyThreshold()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L690-L696)).
4. **First Responder / Monitoring:** If the exact object lifetime is currently unknown, an agent with sufficient fuel volunteers to monitor a newly spawned object to lock in the true lifetime ([`checkMonitoringTasks()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L382-L428)).
5. **Automatic High-Priority Logic (Reflex):** The agent scans for targets that score exceptionally high. If found, it bypasses custom logic and reacts immediately ([`executeAutomaticHighPriorityLogic()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L212-L254)).
6. **Worker Logic:** If no high-priority survival or reflex actions are triggered, the decision is delegated to the subclass (e.g., `TWAgent1`) to handle generalized exploration or lower-priority targets.

### Consensus Memory (Layered Architecture) & Zoning ([`performZoneAssignmentIfNecessary()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/ConsensusMemory.java#L147-L229))
`ConsensusMemory` manages data in two distinct layers. **Layer 1 (Private Shadow)** stores raw, unadulterated sensor timestamps used strictly for accurate passive crowdsourcing. **Layer 2 (Consensus Map)** stores the team's shared reality, which includes the statistical age penalties used for A* pathing.

To prevent agents from clumping together and redundantly exploring the same areas, `ConsensusMemory` utilizes a Hybrid Zoning Strategy:
* **Phase 1 (Strict Columns):** Used *before* the fuel station is found. The map is [divided into strict, non-overlapping vertical columns](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/ConsensusMemory.java#L214-L224). **Why?** To maximize exploration efficiency. Agents [use a BFS algorithm to sweep their specific column](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L449-L450), guaranteeing the team finds the fuel station as quickly as possible without stepping on each other's toes.
* **Phase 2 (Dynamic Overlapping Zones):** Activated *after* the fuel station is located. The map is [split into overlapping North and South regions](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/ConsensusMemory.java#L201-L211) with [agents divided equally between the regions](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/ConsensusMemory.java#L179-L197) (e.g., in a 6-agent team, 3 agents are assigned to the North and 3 to the South). **Why?** The primary goal shifts to **resource sharing**. By allowing multiple agents to freely wander the same large North or South zone, an agent can immediately capitalize on an object discovered—but skipped—by a nearby teammate. This level of cooperative harvesting is impossible if agents remain trapped in strict, non-overlapping columns.
  * *Exploration Fallback:* When there are no known targets to hunt in the zone, agents must [actively explore](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L462-L468). To maximize map coverage and prevent the 3 agents from accidentally clustering together, they dynamically slice their shared North/South zone into temporary sub-zones based on their peers' real-time X-coordinates ([`refreshExplorationSubZone()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/ConsensusMemory.java#L231-L289)). This allows agents to seamlessly shift left and right, efficiently sweeping the map without "lane-clumping."

### Utility Scoring System ([`calculateStandardizedScore()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L278-L334))
Targets are evaluated mathematically. The formula starts with a massive base score ([`BASE_SCORE_OFFSET`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L34-L37)) to ensure positive numbers, and subtracts penalties based on:
1. **A* Distance:** Multiplied by a distance weight.
2. **Inventory:** Weighted heavily to force empty agents to prefer tiles and full agents to prefer holes.
3. **Urgency (Slack):** The time remaining before the object naturally expires minus the distance required to reach it. If slack < 0, the target is instantly dropped to prevent chasing "mirages".

### Heartbeat `STATUS` Broadcasts ([`communicate()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L894-L965))
Every step, every agent broadcasts a single `STATUS` string containing its Name, X, Y, Inventory Size, and Fuel Level, appended with any newly discovered/removed objects. Because all agents process this in [`updateState()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/ConsensusMemory.java#L593-L668), the team maintains perfect real-time knowledge of who is closest to which target. This allows for conflict-free implicit target claiming without the massive overhead of explicit negotiation.

### Performance Optimizations (Memoization & Fast-Fail)
Because the implicit coordination strategy requires every agent to calculate the potential scores of all peers for a target ([`isBestCandidate()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L761-L862)), the system implements aggressive optimizations to prevent A* pathfinding from bottlenecking the simulation:
* **Manhattan Fast-Fail:** Before calculating a peer's true A* distance to a target, the agent [calculates the peer's optimal Manhattan distance](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L814-L822). If this absolute best-case mathematical score is still lower than the calling agent's score, the expensive A* calculation is [entirely skipped](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L824-L826).
* **Tick-Level Distance Caching:** `TWBaseAgent` maintains a [`distanceCache`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L73-L74) that memoizes all A* path lengths calculated during a single `think()` step. This guarantees the pathfinder only runs once per unique route per tick.

### The Self-Healing Map (Error Recovery)
Because the consensus map relies on statistical age penalties, it will occasionally be wrong (e.g., expecting an object to exist when it has already naturally expired). The architecture handles this gracefully:
* **Ghost Erasure:** If an agent attempts an action (`PICKUP` or `PUTDOWN`) and the object is physically missing, the agent catches the failure, [deletes the object locally, and queues a `REMOVE` broadcast](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L990-L1011) to instantly heal the shared map for the rest of the team.
* **Bump Recovery (`lastActionFailed`):** If an agent attempts to move into a cell that the `ConsensusPathGenerator` thought was empty but hits a newly spawned obstacle, it catches the `CellBlockedException`. It [flags](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L1015) `lastActionFailed = true`, which forces the agent to wipe its current targets on the next tick and recalculate its path using the freshly updated consensus map.

### Object Lifetime Estimation (Passive Crowdsourcing)
*(Note: While the true object lifetime is technically accessible globally via `Parameters.lifeTime`, this architecture intentionally prohibits agents from accessing it to prevent "cheating" and ensure the strategy remains robust even in randomized environments.)*

To prevent agents from chasing objects that will despawn before they arrive, the team dynamically crowdsources lifetime data:
* **The "First Responder" Protocol:** At the start of a simulation, an available agent [physically parks next to a newly spawned obstacle and monitors it until it naturally expires](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L394-L426). This captures the definitive lifespan, triggering [a `DEFINITE_LIFETIME` broadcast](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L939-L943).
* **Passive Crowdsourcing (Global Lifetime):** Agents [passively track objects in their private sensor memory](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/ConsensusMemory.java#L497-L588) (`privateShadow`). If an agent watches an object disappear, it [calculates the raw time it was visible to establish a safe minimum global lifetime estimate](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/ConsensusMemory.java#L525-L530).
* **Dynamic Statistical Discount (Individual Target Slack):** When an agent discovers an object but did *not* witness its creation, it [applies a dynamic age penalty](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L909-L925) *before* broadcasting the timestamp. This penalty scales automatically based on the map's total area. **Why?** On massive maps with lower sweep frequencies, it is highly probable an unwitnessed object is already partially through its lifespan. This discount artificially ages the object, tightening the slack calculation and preventing doomed cross-map journeys.

## 5. Tuning & Optimization (Hyperparameters)
If you are looking to improve the agent's performance, the architecture exposes several "dials and knobs" that control the agent's behavior. Tuning these constants is the fastest way to increase the team's reward:

* **Utility Scoring Weights ([`TWBaseAgent.java`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L45-L50)):**
  * `DISTANCE_WEIGHT` (Default: 10)
  * `INVENTORY_WEIGHT` (Default: 20)
  * `URGENCY_WEIGHT` (Default: 1)
  * *Tip: Adjusting these changes whether agents prefer close objects, safe objects (high slack), or prioritize clearing their inventory.*
* **Statistical Age Penalty ([`communicate()`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/TWBaseAgent.java#L909-L917)):**
  * `dynamicPenaltyRatio` divisor (Default: 25000.0) and ceiling (Default: 0.40).
  * *Tip: Decrease the divisor to make agents more skeptical (steep penalty curve) on large maps.*
* **Phase 2 Zone Overlap ([`ConsensusMemory.java`](https://github.com/chuo-v/multi-agent-tileworld/blob/704860a0e8c41f267f6f5cf0f995a16544ad7a61/submission/Tileworld/src/tileworld/agent/ConsensusMemory.java#L138)):**
  * `phase2ZoneOverlap` (Default: 25% of Y dimension).
  * *Tip: Increase this to give agents more freedom to move vertically without violating zone boundaries.*

## 6. Simulation Results

The table below outlines the average rewards achieved across 2 runs (of 10 full-length simulations) of [`TileworldMain.java`](Tileworld/src/tileworld/TileworldMain.java) for both predefined environment configurations using instances of [TWAgent1.java](Tileworld/src/tileworld/agent/TWAgent1.java), [TWAgent2.java](Tileworld/src/tileworld/agent/TWAgent2.java), [TWAgent3.java](Tileworld/src/tileworld/agent/TWAgent3.java), [TWAgent4.java](Tileworld/src/tileworld/agent/TWAgent4.java), [TWAgent5.java](Tileworld/src/tileworld/agent/TWAgent5.java), and [TWAgent6.java](Tileworld/src/tileworld/agent/TWAgent6.java).

| Configuration File | Map Size | Object Spawns/Step | Object Lifetime | Run 1 Reward | Run 2 Reward | Average Reward |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| [`Parameters.java`](Tileworld/src/tileworld/Parameters.java) | 50x50 | 0.2 (Low) | 100 (High) | 633.2 | 614.8 | 624.0 |
| [`Parameters2.java`](Tileworld/src/tileworld/Parameters2.java) | 80x80 | 2.0 (High) | 30 (Low) | 916.2 | 906.0 | 911.1 |

## 7. Notes & Setup

- **Running the GUI (Visual Debugging):** To watch the agents in real-time, execute the main method inside [`TWGUI.java`](Tileworld/src/tileworld/TWGUI.java).
- **Running Headless (Bulk Testing):** To run 10 iterations sequentially for fast result gathering, execute the main method inside [`TileworldMain.java`](Tileworld/src/tileworld/TileworldMain.java).
- **Configuration of External MASON Library:** The changes to configure the MASON library (`MASON_14.jar`) have not been committed with this project. Ensure the build path for this external JAR is correctly configured in your local environment to run the project.