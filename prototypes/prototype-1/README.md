# Tileworld Multi-Agent Implementation: Prototype 1

## 1. Overview
This repository contains a prototype implementation for the Tileworld multi-agent simulation. The architecture is built entirely around the concept of **Implicit Coordination**. Rather than using a centralized leader or explicit task-negotiation protocols, agents operate autonomously while maintaining a perfectly synchronized "Shared Map" via broadcast communication.

To ensure consistent survival and coordination logic across the team, the architecture relies heavily on Object-Oriented subclassing. A universal base class acts as the "Operating System," enforcing strict rules for survival, yielding, and memory synchronization, while specific agent implementations subclass this base to define unique worker strategies and optimizations.

## 2. Classes Added
The prototype introduces four core classes to the Tileworld framework to drive the implicit coordination strategy:

* [**`TWBaseAgent.java`**](Tileworld/src/tileworld/agent/TWBaseAgent.java)
  The abstract core of the agent team. It enforces a strict priority hierarchy for every tick and handles the standardized communication protocol and object utility scoring.
* [**`ConsensusMemory.java`**](Tileworld/src/tileworld/agent/ConsensusMemory.java)
  An advanced memory module that bridges an agent's private sensor data with the team's shared global state. It is responsible for parsing broadcast messages, dynamic map zoning (Phase 1 vs. Phase 2 exploration), BFS-based exploration targeting, and "garbage collecting" expired objects.
* [**`PrototypeAgent.java`**](Tileworld/src/tileworld/agent/PrototypeAgent.java)
  A concrete implementation extending `TWBaseAgent`. It serves as the template for individual agent behaviors. It defines specific threshold constants (like `OPPORTUNISTIC_FUEL_THRESHOLD`) and provides the logic for evaluating and targeting lower-priority tiles and holes that fall outside the base agent's automatic reflex radius.
* [**`ConsensusPathGenerator.java`**](Tileworld/src/tileworld/planners/ConsensusPathGenerator.java)
  A customized A* pathfinder derived from `AstarPathGenerator`. Instead of using an agent's isolated local memory, this planner plots routes strictly using the `ConsensusMemory` map. This ensures that all agents calculate distances and route around obstacles using the exact same global data snapshot.

## 3. Individual Agent Implementation
Creating a new agent strategy involves extending `TWBaseAgent` and implementing the `executeWorkerLogic()` method. This method acts as a sandbox that is only executed if all higher-priority survival and reflex tasks are satisfied by the base class.

To prevent individual implementations from accidentally corrupting the shared memory or improperly claiming targets, the `consensusMemory` variable is strictly encapsulated. Implementations must use safe, delegated pipelines to interact with the environment state.

For example, when building out an agent like `PrototypeAgent.java`, you should utilize:
* `getKnownFuelStation()` to evaluate opportunistic refueling if passing by the station.
* `getLowerPriorityTilesFromConsensusMemory()` and `getLowerPriorityHolesFromConsensusMemory()` to retrieve pre-filtered lists of valid targets that the agent is permitted to consider.
* `isBestCandidate()` to mathematically verify that the agent has the highest utility claim to a target compared to all other peers before committing to a path.
* `shouldYieldMove()` to proactively check if stepping into a specific coordinate would physically block or interfere with a higher-priority teammate, allowing the agent to gracefully abort or reroute the move.

## 4. Strategy Details
The implicit coordination strategy relies on several sophisticated underlying mechanics to maximize efficiency and minimize wasted fuel.

### Decision-Making Hierarchy (The `think` Method)
Every agent's brain operates on a rigid, standardized priority queue defined in the `think()` method of `TWBaseAgent.java`. This ensures that critical survival and team-level tasks are never ignored. The hierarchy evaluates in this exact order:
1. **Immediate Free Lunch:** If the agent is physically standing on a valid tile or hole and has the capacity to interact with it, it instantly executes the pickup/putdown action to secure points without wasting a turn moving.
2. **Station Search:** If the team does not yet know where the fuel station is, the agent abandons all other tasks to execute an exploration sweep.
3. **Fuel Safety Check:** If the agent's fuel drops below its dynamic, distance-based safety threshold, it immediately routes to the fuel station.
4. **First Responder / Monitoring:** If the exact object lifetime of the environment is currently unknown, an agent with sufficient fuel will volunteer to monitor a newly spawned object (acting as a "First Responder") to lock in the true lifetime.
5. **Automatic High-Priority Logic (Reflex):** The agent scans for targets that score exceptionally high (above `AUTOMATIC_SCORE_THRESHOLD`). If found, it bypasses custom logic and reacts immediately.
6. **Worker Logic:** If no high-priority survival or reflex actions are triggered, the decision is delegated to the subclass (e.g., `PrototypeAgent`) to handle lower-priority target hunting or generalized exploration.

### Consensus Memory & Zoning
`ConsensusMemory` utilizes a Hybrid Zoning Strategy to prevent agents from clumping together.
* **Phase 1:** Before the fuel station is found, agents are assigned strict, non-overlapping vertical columns to maximize map coverage using a BFS algorithm.
* **Phase 2:** Once the fuel station is located, the map is split into overlapping North/South zones, and agents dynamically slice their sub-zones based on their peers' real-time X-coordinates to maintain an even horizontal spread.

### Heartbeat `STATUS` Broadcasts
Every step, every agent executes `communicate()`, broadcasting a single `STATUS` string containing its Name, X, Y, Inventory Size, and Fuel Level. This is appended with any newly discovered objects or objects that need to be removed. Because all agents process this string in `updateState()`, the team maintains perfect real-time knowledge of who is closest to which target, allowing for conflict-free implicit target claiming without explicit negotiation.

### Utility Scoring System
Targets are evaluated using `calculateStandardizedScore()`. The formula starts with a massive base score (to ensure positive numbers) and subtracts penalties based on:
1. **A* Distance:** Multiplied by a distance weight.
2. **Inventory:** Weighted heavily to force empty agents to prefer tiles and full agents to prefer holes.
3. **Urgency (Slack):** The time remaining before the object naturally expires minus the distance required to reach it.

### Object Lifetime Estimation (First Responders & Passive Crowdsourcing)
*(Note: While the true object lifetime is technically accessible globally via `Parameters.lifeTime`, this architecture intentionally prohibits agents from accessing it. Forcing the team to dynamically discover the lifespan prevents "cheating" and ensures the strategy remains robust even in environments where lifespans are randomized or unknown at compile time.)*

To prevent agents from chasing objects that will despawn before they arrive (mirages), the team dynamically crowdsources lifetime data. The system strictly separates the calculation of the *global* lifetime from the estimated *remaining* lifetime of specific objects:

* **The "First Responder" Protocol (Global Lifetime):** At the start of a simulation, the exact lifespan of objects is unknown. To solve this, an available agent with sufficient fuel acts as a "First Responder." It physically parks next to a newly spawned obstacle and monitors it until it naturally expires. This captures the definitive lifespan, triggering a `DEFINITE_LIFETIME` broadcast that perfectly synchronizes the team's baseline knowledge.
* **Passive Crowdsourcing (Global Lifetime):** If the definitive lifetime is not yet known, agents passively track objects in their private, unadulterated sensor memory (`privateShadow`). If an agent watches an object disappear, it calculates the raw time it was visible to establish a safe minimum global lifetime estimate.
* **Dynamic Statistical Discount (Individual Target Slack):** When an agent discovers a specific object but did *not* witness its creation, it applies a **Dynamic Statistical Age Penalty** *before* broadcasting the object's discovery timestamp to the shared map. This penalty scales automatically based on the map's total area (up to a 40% penalty). This ensures that on massive maps with lower sweep frequencies, the team correctly assumes newly discovered objects are already partially through their lifespan, tightening the slack calculation and preventing doomed cross-map journeys. This penalty strictly affects target pathing and never corrupts the raw data used for global passive crowdsourcing.

## 5. Simulation Results

The table below outlines the average rewards achieved across 2 runs (of 10 full-length simulations) of [`TileworldMain.java`](Tileworld/src/tileworld/TileworldMain.java) for both predefined environment configurations using 6 (identical) `PrototypeAgent` instances. As the actual agents that would be implemented will have slight differences in behavior due to the varying customizations applied, the results below should just be considered as a rough indicator of the level of performance that can be expected.

| Configuration File | Map Size | Object Spawns/Step | Object Lifetime | Run 1 Reward | Run 2 Reward | Average Reward |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| [`Parameters.java`](Tileworld/src/tileworld/Parameters.java) | 50x50 | 0.2 (Low) | 100 (High) | 642.1 | 639.5 | 640.8 |
| [`Parameters2.java`](Tileworld/src/tileworld/Parameters2.java) | 80x80 | 2.0 (High) | 30 (Low) | 926.8 | 902.0 | 914.4 |

## 6. Notes

- **Configuration of External MASON Library:** The changes to configure the MASON library ([MASON_14.jar](../../MASON_14.jar)) have not been committed with this prototype implementation, and so the build path for this external JAR needs to be configured to run the project.