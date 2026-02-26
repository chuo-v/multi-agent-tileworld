# Tileworld Multi-Agent Implementation: Prototype 1

## 1. Overview
This directory contains a prototype implementation for the Tileworld multi-agent simulation. The architecture is built entirely around the concept of **Implicit Coordination**. Rather than using a centralized leader or explicit task-negotiation protocols, agents operate autonomously while maintaining a perfectly synchronized "Shared Map" via broadcast communication.

To ensure consistent survival and coordination logic across the team, the architecture relies heavily on Object-Oriented subclassing. A universal base class acts as the "Operating System," enforcing strict rules for survival, yielding, and memory synchronization, while specific agent implementations subclass this base to define unique worker strategies and optimizations.

## 2. Classes Added
The prototype introduces four core classes to the Tileworld framework to drive the implicit coordination strategy:

* [**`TWBaseAgent.java`**](Tileworld/src/tileworld/agent/TWBaseAgent.java)
  The abstract core of the agent team. It enforces a strict priority hierarchy for every tick and handles the standardized communication protocol and object utility scoring.
* [**`ConsensusMemory.java`**](Tileworld/src/tileworld/agent/ConsensusMemory.java)
  An advanced memory module that bridges an agent's private sensor data with the team's shared global state. It handles broadcast parsing, dynamic map zoning (Phase 1 vs. Phase 2 exploration), BFS-based exploration targeting, and "garbage collecting" expired objects.
* [**`PrototypeAgent.java`**](Tileworld/src/tileworld/agent/PrototypeAgent.java)
  A concrete implementation extending `TWBaseAgent`. It serves as the template for individual agent behaviors. It defines specific threshold constants (like `OPPORTUNISTIC_FUEL_THRESHOLD`) and handles lower-priority target hunting that falls outside the base agent's automatic reflex radius.
* [**`ConsensusPathGenerator.java`**](Tileworld/src/tileworld/planners/ConsensusPathGenerator.java)
  A customized A* pathfinder. Instead of using an agent's isolated local memory, this planner plots routes strictly using the `ConsensusMemory` map. This ensures all agents calculate distances and route around obstacles using the exact same global data snapshot.

## 3. Individual Agent Implementation

### Implementing the Agent

Creating a new agent strategy involves extending `TWBaseAgent` and implementing the [`executeWorkerLogic()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L970-L975) method. This method acts as a sandbox that is only executed if all higher-priority survival and reflex tasks are satisfied by the base class.

To prevent individual implementations from accidentally corrupting the shared memory or improperly claiming targets, the `consensusMemory` variable is [strictly encapsulated](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L56-L57). Implementations must use safe, delegated pipelines to interact with the environment state.

For example, when building out an agent like `PrototypeAgent.java`, you should utilize:
* [`getFuelStationFromConsensusMemory()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L644-L650) to evaluate opportunistic refueling if passing by the station.
* [`getLowerPriorityTilesFromConsensusMemory()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L312-L325) and [`getLowerPriorityHolesFromConsensusMemory()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L327-L340) to retrieve pre-filtered lists of valid targets that the agent is permitted to consider.
* [`isBestCandidate()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L723-L813) to mathematically verify that the agent has the highest utility claim to a target compared to all other peers before committing to a path.
* [`shouldYieldMove()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L815-L841) to proactively check if stepping into a specific coordinate would physically block or interfere with a higher-priority teammate (resolved via lexicographical name comparison, e.g., "Agent1" overrides "Agent2").

### Registering the Agent
To test a newly created agent class, you must manually register it in the simulation environment.
1. Open [`TWEnvironment.java`](Tileworld/src/tileworld/environment/TWEnvironment.java).
2. Locate the [`start()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/environment/TWEnvironment.java#L97-L132) method.
3. Replace the [`PrototypeAgent` instantiations](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/environment/TWEnvironment.java#L111-L123) with your new class (e.g., `createAgent(new MyCustomAgent("Agent1", pos.getX(), pos.getY(), this, Parameters.defaultFuelLevel));`).

## 4. Strategy Details
The implicit coordination strategy relies on several sophisticated underlying mechanics to maximize efficiency and minimize wasted fuel.

### Decision-Making Hierarchy ([`think()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L99-L168))
Every agent's brain operates on a rigid, standardized priority queue to ensure critical survival and team-level tasks are never ignored. The hierarchy evaluates in this exact order:
1. **Immediate Free Lunch:** If the agent is physically standing on a valid tile or hole and has capacity, it instantly executes the action ([`checkImmediateFreeLunch()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L170-L197)).
2. **Station Search:** If the team does not yet know where the fuel station is, the agent abandons all other tasks to execute a zoned exploration sweep.
3. **Fuel Safety Check:** If the agent's fuel drops below its dynamic, A*-distance-based safety threshold, it immediately routes to the fuel station ([`getFuelSafetyThreshold()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L652-L658)).
4. **First Responder / Monitoring:** If the exact object lifetime is currently unknown, an agent with sufficient fuel volunteers to monitor a newly spawned object to lock in the true lifetime ([`checkMonitoringTasks()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L344-L390)).
5. **Automatic High-Priority Logic (Reflex):** The agent scans for targets that score exceptionally high. If found, it bypasses custom logic and reacts immediately ([`executeAutomaticHighPriorityLogic()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L199-L241)).
6. **Worker Logic:** If no high-priority survival or reflex actions are triggered, the decision is delegated to the subclass (e.g., `PrototypeAgent`) to handle generalized exploration or lower-priority targets.

### Consensus Memory (Layered Architecture) & Zoning ([`performZoneAssignmentIfNecessary()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/ConsensusMemory.java#L147-L229))
`ConsensusMemory` manages data in two distinct layers. **Layer 1 (Private Shadow)** stores raw, unadulterated sensor timestamps used strictly for accurate passive crowdsourcing. **Layer 2 (Consensus Map)** stores the team's shared reality, which includes the statistical age penalties used for A* pathing.

To prevent agents from clumping together and redundantly exploring the same areas, `ConsensusMemory` utilizes a Hybrid Zoning Strategy:
* **Phase 1 (Strict Columns):** Used *before* the fuel station is found. The map is [divided into strict, non-overlapping vertical columns](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/ConsensusMemory.java#L214-L224). **Why?** To maximize exploration efficiency. Agents [use a BFS algorithm to sweep their specific column](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L411-L412), guaranteeing the team finds the fuel station as quickly as possible without stepping on each other's toes.
* **Phase 2 (Dynamic Overlapping Zones):** Activated *after* the fuel station is located. The map is [split into overlapping North and South regions](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/ConsensusMemory.java#L201-L211) with [agents divided equally between the regions](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/ConsensusMemory.java#L179-L197) (e.g., in a 6-agent team, 3 agents are assigned to the North and 3 to the South). **Why?** The primary goal shifts to **resource sharing**. By allowing multiple agents to freely wander the same large North or South zone, an agent can immediately capitalize on an object discovered—but skipped—by a nearby teammate. This level of cooperative harvesting is impossible if agents remain trapped in strict, non-overlapping columns.
  * *Exploration Fallback:* When there are no known targets to hunt in the zone, agents must [actively explore](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L424-L430). To maximize map coverage and prevent the 3 agents from accidentally clustering together, they dynamically slice their shared North/South zone into temporary sub-zones based on their peers' real-time X-coordinates ([`refreshExplorationSubZone()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/ConsensusMemory.java#L231-L289)). This allows agents to seamlessly shift left and right, efficiently sweeping the map without "lane-clumping."

### Exploration Strategy ([`executeExploreMove()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L392-L438))
To complement the zoning mechanics, agents adapt their movement patterns to match the current phase's objectives:
* **High-Coverage BFS (Phase 1):** When the station is unknown, agents [use a Breadth-First Search to locate the nearest unexplored cell within their strict column](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L411-L412). This systematically sweeps the map without leaving gaps.
* **Repulsive Exploration (Phase 2):** Once the station is known, agents switch to a [repulsive random exploration model](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L432). They select target coordinates within their dynamic sub-zone that deliberately maximize their distance from other team members, ensuring optimal sensor spread.
* **Custom Usage:** Beyond the mandatory initial station search, individual agent implementations (like `PrototypeAgent`) are free to call `executeExploreMove()` at any time. For example, if an agent's inventory is full but the team hasn't discovered any holes, the agent can call this method to actively scout its zone for new resources.

### Heartbeat `STATUS` Broadcasts ([`communicate()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L845-L916))
Every step, every agent broadcasts a single `STATUS` string containing its Name, X, Y, Inventory Size, and Fuel Level, appended with any newly discovered/removed objects. Because all agents process this in [`updateState()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/ConsensusMemory.java#L593-L668), the team maintains perfect real-time knowledge of who is closest to which target. This allows for conflict-free implicit target claiming without the massive overhead of explicit negotiation.

### Utility Scoring System ([`calculateStandardizedScore()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L265-L308))
Targets are evaluated mathematically. The formula starts with a massive base score ([`BASE_SCORE_OFFSET`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L33-L36)) to ensure positive numbers, and subtracts penalties based on:
1. **A* Distance:** Multiplied by a distance weight.
2. **Inventory:** Weighted heavily to force empty agents to prefer tiles and full agents to prefer holes.
3. **Urgency (Slack):** The time remaining before the object naturally expires minus the distance required to reach it. If slack < 0, the target is instantly dropped to prevent chasing "mirages".

### Performance Optimizations (Memoization & Fast-Fail)
Because the implicit coordination strategy requires every agent to calculate the potential scores of all peers for a target ([`isBestCandidate()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L723-L813)), the system implements aggressive optimizations to prevent A* pathfinding from bottlenecking the simulation:
* **Manhattan Fast-Fail:** Before calculating a peer's true A* distance to a target, the agent [calculates the peer's optimal Manhattan distance](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L765-L773). If this absolute best-case mathematical score is still lower than the calling agent's score, the expensive A* calculation is [entirely skipped](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L775-L777).
* **Tick-Level Distance Caching:** `TWBaseAgent` maintains a [`distanceCache`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L72-L73) that memoizes all A* path lengths calculated during a single `think()` step. This guarantees the pathfinder only runs once per unique route per tick.

### The Self-Healing Map (Error Recovery)
Because the consensus map relies on statistical age penalties, it will occasionally be wrong (e.g., expecting an object to exist when it has already naturally expired). The architecture handles this gracefully:
* **Ghost Erasure:** If an agent attempts an action (`PICKUP` or `PUTDOWN`) and the object is physically missing, the agent catches the failure, [deletes the object locally, and queues a `REMOVE` broadcast](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L941-L962) to instantly heal the shared map for the rest of the team.
* **Bump Recovery (`lastActionFailed`):** If an agent attempts to move into a cell that the `ConsensusPathGenerator` thought was empty but hits a newly spawned obstacle, it catches the `CellBlockedException`. It [flags](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L966) `lastActionFailed = true`, which forces the agent to wipe its current targets on the next tick and recalculate its path using the freshly updated consensus map.

### Object Lifetime Estimation (Passive Crowdsourcing)
*(Note: While the true object lifetime is technically accessible globally via `Parameters.lifeTime`, this architecture intentionally prohibits agents from accessing it to prevent "cheating" and ensure the strategy remains robust even in randomized environments.)*

To prevent agents from chasing objects that will despawn before they arrive, the team dynamically crowdsources lifetime data:
* **The "First Responder" Protocol:** At the start of a simulation, an available agent [physically parks next to a newly spawned obstacle and monitors it until it naturally expires](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L356-L388). This captures the definitive lifespan, triggering [a `DEFINITE_LIFETIME` broadcast](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L890-L894).
* **Passive Crowdsourcing (Global Lifetime):** Agents [passively track objects in their private sensor memory](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/ConsensusMemory.java#L497-L588) (`privateShadow`). If an agent watches an object disappear, it [calculates the raw time it was visible to establish a safe minimum global lifetime estimate](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/ConsensusMemory.java#L525-L530).
* **Dynamic Statistical Discount (Individual Target Slack):** When an agent discovers an object but did *not* witness its creation, it [applies a dynamic age penalty](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L860-L877) *before* broadcasting the timestamp. This penalty scales automatically based on the map's total area. **Why?** On massive maps with lower sweep frequencies, it is highly probable an unwitnessed object is already partially through its lifespan. This discount artificially ages the object, tightening the slack calculation and preventing doomed cross-map journeys.

## 5. Tuning & Optimization (Hyperparameters)
If you are looking to improve the agent's performance, the architecture exposes several "dials and knobs" that control the agent's behavior. Tuning these constants is the fastest way to increase the team's reward:

* **Utility Scoring Weights ([`TWBaseAgent.java`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L44-L49)):**
  * `DISTANCE_WEIGHT` (Default: 10)
  * `INVENTORY_WEIGHT` (Default: 20)
  * `URGENCY_WEIGHT` (Default: 1)
  * *Tip: Adjusting these changes whether agents prefer close objects, safe objects (high slack), or prioritize clearing their inventory.*
* **Statistical Age Penalty ([`communicate()`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L860-L868)):**
  * `dynamicPenaltyRatio` divisor (Default: 25000.0) and ceiling (Default: 0.40).
  * *Tip: Decrease the divisor to make agents more skeptical (steep penalty curve) on large maps.*
* **Phase 2 Zone Overlap ([`ConsensusMemory.java`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/ConsensusMemory.java#L138)):**
  * `phase2ZoneOverlap` (Default: 25% of Y dimension).
  * *Tip: Increase this to give agents more freedom to move vertically without violating zone boundaries.*
* **Opportunistic Refueling ([`PrototypeAgent.java`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/PrototypeAgent.java#L17-L22)):**
  * `OPPORTUNISTIC_FUEL_THRESHOLD` (Default: 250).
  * *Tip: Controls how readily an agent will top off its fuel when passing the station even if not in critical danger.*

## 6. Areas for Improvement (Future Work)
This prototype establishes a strong foundational architecture, but there are several key areas where the implementation can be optimized for higher rewards:
* **Fuel Station Crowding:** Currently, if all 6 agents hit their fuel safety threshold simultaneously, they will route to the station and potentially block each other. Advanced queuing or staggered fuel thresholds could prevent traffic jams.
* **Heuristic Tuning:** The [`calculateStandardizedScore`](https://github.com/chuo-v/multi-agent-tileworld/blob/3ade2a079f4613378955717786d0cd4b9141f21e/prototypes/prototype-1/Tileworld/src/tileworld/agent/TWBaseAgent.java#L265-L308) method uses a simple weighting formula. The weights for Distance vs. Inventory vs. Urgency are currently static and might benefit from dynamic scaling based on the map size or spawn rates.

## 7. Simulation Results

The table below outlines the average rewards achieved across 2 runs (of 10 full-length simulations) of [`TileworldMain.java`](Tileworld/src/tileworld/TileworldMain.java) for both predefined environment configurations using 6 (identical) `PrototypeAgent` instances. As the actual agents that would be implemented will have slight differences in behavior due to the varying customizations applied, the results below should just be considered as a rough indicator of the level of performance that can be expected.

| Configuration File | Map Size | Object Spawns/Step | Object Lifetime | Run 1 Reward | Run 2 Reward | Average Reward |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| [`Parameters.java`](Tileworld/src/tileworld/Parameters.java) | 50x50 | 0.2 (Low) | 100 (High) | 642.1 | 639.5 | 640.8 |
| [`Parameters2.java`](Tileworld/src/tileworld/Parameters2.java) | 80x80 | 2.0 (High) | 30 (Low) | 926.8 | 902.0 | 914.4 |

## 8. Notes & Setup

- **Running the GUI (Visual Debugging):** To watch the agents in real-time, execute the main method inside [`TWGUI.java`](Tileworld/src/tileworld/TWGUI.java).
- **Running Headless (Bulk Testing):** To run 10 iterations sequentially for fast result gathering, execute the main method inside [`TileworldMain.java`](Tileworld/src/tileworld/TileworldMain.java).
- **Configuration of External MASON Library:** The changes to configure the MASON library ([MASON_14.jar](../../MASON_14.jar)) have not been committed with this prototype implementation, and so the build path for this external JAR needs to be configured to run the project.