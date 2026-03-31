package tileworld.agent;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import sim.util.Int2D;
import tileworld.environment.TWDirection;
import tileworld.environment.TWEntity;
import tileworld.environment.TWEnvironment;
import tileworld.environment.TWFuelStation;
import tileworld.environment.TWHole;
import tileworld.Parameters;
import tileworld.environment.TWObject;
import tileworld.environment.TWObstacle;
import tileworld.environment.TWTile;
import tileworld.exceptions.CellBlockedException;
import tileworld.planners.ConsensusPathGenerator;
import tileworld.planners.TWPath;
import tileworld.planners.TWPathStep;

/**
 * TWBaseAgent
 * * The shared "Operating System" for all agents.
 * Enforces high-priority behaviors (Survival, Coordination, Reflexes) and
 * manages the A* pathfinding and memory synchronization.
 */
public abstract class TWBaseAgent extends TWAgent {

    // --- CONSTANTS ---

    /**
     * The base score offset used during calculations for targets to keep final scores positive.
     */
    protected static final int BASE_SCORE_OFFSET = 100000;

    /**
     * Threshold for Automatic High-Priority Actions. Targets scoring above this
     * are considered immediate reflex actions.
     */
    protected static final int AUTOMATIC_SCORE_THRESHOLD = BASE_SCORE_OFFSET - 200;

    /** Weight applied to distance when scoring targets. */
    protected static final int DISTANCE_WEIGHT = 10;
    /** Weight applied to the time remaining (urgency) when scoring targets. */
    protected static final int URGENCY_WEIGHT = 1;
    /** Weight applied to the agent's current inventory size when scoring targets. */
    protected static final int INVENTORY_WEIGHT = 20;

    // --- FIELDS ---

    /** The unique identifier for this agent. */
    private String name;

    /** The shared consensus memory that tracks global map state. */
    private ConsensusMemory consensusMemory;
    /** The A* path generator used for grid navigation. */
    private ConsensusPathGenerator pathGenerator;

    /** The current coordinate the agent is exploring towards. */
    protected Int2D explorationTarget = null;
    /** The specific entity the agent is currently assigned to monitor. */
    protected TWEntity monitoringTarget = null;

    /** Safety buffer added to the fuel threshold to account for dynamic obstacles or waiting time. */
    private final int fuelSafetyBuffer;

    /** Flag tracking if the last execution step threw a CellBlockedException. */
    private boolean lastActionFailed = false;

    /** Cache to memoize standardized A* distance calculations within a single step. */
    private Map<String, Double> distanceCache = new HashMap<>();

    // --- CONSTRUCTORS ---

    /**
     * Constructs the TWBaseAgent and initializes its custom ConsensusMemory.
     *
     * @param name      The unique identifier for this agent.
     * @param xpos      The starting X coordinate.
     * @param ypos      The starting Y coordinate.
     * @param env       The Tileworld environment.
     * @param fuelLevel The starting fuel level.
     */
    public TWBaseAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(xpos, ypos, env, fuelLevel);

        this.name = name;
        // CRITICAL: Overwrite the memory initialized in super() with our Custom Memory
        this.memory = new ConsensusMemory(this, env.schedule, env.getxDimension(), env.getyDimension());
        this.consensusMemory = (ConsensusMemory) this.memory;

        // --- STAGGERED FUELING PIT STOP IMPLEMENTATION ---
        int idOffset = 0;
        try {
            // Extract the number from the agent's name (e.g., "Agent1" -> 1)
            // Multiply by 15 to space out their return to the fuel station
            idOffset = Integer.parseInt(name.replaceAll("\\D+","")) * 15;
        } catch (Exception e) {
            idOffset = 0; // Fallback 
        }

        // Add the offset so agents refuel in staggered waves
        this.fuelSafetyBuffer = (int)(0.4 * (env.getxDimension() + env.getyDimension())) + idOffset;
    }

    // --- CORE BEHAVIOR ---

    /**
     * THE BRAIN: Standardized Priority Logic
     * All agents follow this strict hierarchy to determine their next thought:
     * 1. Consensus Memory Sync (Broadcasted Messages)
     * 2. Locating Fuel Station
     * 3. Fuel Level Safety Check
     * 4. Object Lifetime Monitoring (First Responder)
     * 5. Automatically-Handled High-Priority Tasks (Reflex)
     * 6. Worker Logic (Agent-specific implementation for lower priority tasks)
     * * @return TWThought The decided action and direction for this step.
     */
    @Override
    protected final TWThought think() {
        distanceCache.clear();

        // Sync memory
        ArrayList<Message> messages = this.getEnvironment().getMessages();
        this.consensusMemory.updateState(messages, (int)this.getEnvironment().schedule.getSteps());

        if (lastActionFailed) {
            // Reset targets to not get stuck if the pathfinder keeps picking the same route.
            lastActionFailed = false;
            this.explorationTarget = null;
            this.monitoringTarget = null;
        }

        this.consensusMemory.performZoneAssignmentIfNecessary();

        Int2D station = this.consensusMemory.getConsensusFuelStation();

        // PRIORITY 1: IMMEDIATE ACTION ("Free Lunch" during exploration)
        // If we are physically standing on a valid target, take it before moving.
        TWThought immediateThought = checkImmediateFreeLunch();
        if (immediateThought != null) {
            return immediateThought;
        }

        // PRIORITY 2: STATION SEARCH
        if (station.x == -1) return executeExploreMove();

        // PRIORITY 3: OPPORTUNISTIC REFUELING
        // If we are close to the station and not full, just top up.
        int mapDimensionSum = this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension();
        int opportunisticFuelThreshold = mapDimensionSum / 2; // Approx 50 for 50x50 map
        int opportunisticDistMargin = mapDimensionSum / 20; // Approx 5 for 50x50 map
        if (station.x != -1 && this.fuelLevel < Parameters.defaultFuelLevel - opportunisticFuelThreshold) {
            double distToStation = getStandardizedDistance(this.getX(), this.getY(), station.x, station.y);
            if (distToStation <= opportunisticDistMargin) { // If within dynamic steps
                return executePathTo(station.x, station.y, TWAction.REFUEL);
            }
        }

        // PRIORITY 4: FUEL CHECK
        if (this.fuelLevel < getFuelSafetyThreshold()) {
            this.explorationTarget = null;

            // CONSERVATION MODE: Prevent suicide runs on impossible paths
            double pathCost = getStandardizedDistance(this.getX(), this.getY(), station.x, station.y);
            if (pathCost > this.fuelLevel) {
                return new TWThought(TWAction.MOVE, TWDirection.Z);
            }

            return executePathTo(station.x, station.y, TWAction.REFUEL);
        }

        // PRIORITY 5: FIRST RESPONDER / MONITORING
        if (!consensusMemory.isDefiniteLifetimeKnown()) {
            TWThought monitoringThought = checkMonitoringTasks();
            if (monitoringThought != null) return monitoringThought;
        } else {
            this.monitoringTarget = null;
        }

        // PRIORITY 6: AUTOMATIC HIGH-PRIORITY LOGIC (The "Reflex" Layer)
        TWThought automaticThought = executeAutomaticHighPriorityLogic();
        if (automaticThought != null) {
            return automaticThought;
        }

        // PRIORITY 7: WORKER LOGIC
        return executeWorkerLogic();
    }

    /**
     * Checks if the agent is currently standing directly on top of a valid target.
     * Allows the agent to scoop up points without detouring.
     * * @return A TWThought to pickup/putdown, or null if no valid target is underneath.
     */
    private TWThought checkImmediateFreeLunch() {
        int cx = this.getX();
        int cy = this.getY();

        // Am I standing on a Tile and have inventory space?
        if (this.carriedTiles.size() < 3 && consensusMemory.getConsensusTiles().containsKey(new Int2D(cx, cy))) {
            int score = standardizedScoreForTile(cx, cy);
            // Verify we are the rightful claimant (resolves ties if another agent is adjacent)
            if (score != Integer.MIN_VALUE && isBestCandidate(cx, cy, score, this.getEnvironment().getMessages(), TWAction.PICKUP)) {
                return new TWThought(TWAction.PICKUP, TWDirection.Z);
            }
        }

        // Am I standing on a Hole and have a tile to drop?
        if (this.carriedTiles.size() > 0 && consensusMemory.getConsensusHoles().containsKey(new Int2D(cx, cy))) {
            int score = standardizedScoreForHole(cx, cy);
            if (score != Integer.MIN_VALUE && isBestCandidate(cx, cy, score, this.getEnvironment().getMessages(), TWAction.PUTDOWN)) {
                return new TWThought(TWAction.PUTDOWN, TWDirection.Z);
            }
        }

        return null;
    }

    /**
     * Scans for high-score targets exceeding the AUTOMATIC_SCORE_THRESHOLD.
     * * @return TWThought to navigate towards the best reflex target, or null if none qualify.
     */
    private TWThought executeAutomaticHighPriorityLogic() {
        Int2D bestTarget = null;
        int bestScore = Integer.MIN_VALUE;
        TWAction bestAction = null;

        // Check Tiles (if we have space)
        if (this.carriedTiles.size() < 3) {
            for (Int2D tilePos : consensusMemory.getConsensusTiles().keySet()) {
                int score = standardizedScoreForTile(tilePos.x, tilePos.y);
                if (score > AUTOMATIC_SCORE_THRESHOLD && score > bestScore) {
                    if (isBestCandidate(tilePos.x, tilePos.y, score, this.getEnvironment().getMessages(), TWAction.PICKUP)) {
                        bestScore = score;
                        bestTarget = tilePos;
                        bestAction = TWAction.PICKUP;
                    }
                }
            }
        }

        // Check Holes (if we have tiles)
        if (this.carriedTiles.size() > 0) {
            for (Int2D holePos : consensusMemory.getConsensusHoles().keySet()) {
                int score = standardizedScoreForHole(holePos.x, holePos.y);
                if (score > AUTOMATIC_SCORE_THRESHOLD && score > bestScore) {
                    if (isBestCandidate(holePos.x, holePos.y, score, this.getEnvironment().getMessages(), TWAction.PUTDOWN)) {
                        bestScore = score;
                        bestTarget = holePos;
                        bestAction = TWAction.PUTDOWN;
                    }
                }
            }
        }

        if (bestTarget != null) {
            this.explorationTarget = null;
            return executePathTo(bestTarget.x, bestTarget.y, bestAction);
        }
        return null;
    }

    // --- SCORING METHODS ---

    /**
     * Calculates the standardized score for picking up a tile at the given coordinates.
     * * @param x X coordinate of the tile.
     * @param y Y coordinate of the tile.
     * @return Integer score, or Integer.MIN_VALUE if invalid.
     */
    public int standardizedScoreForTile(int x, int y) {
        return calculateStandardizedScore(x, y, TWAction.PICKUP);
    }

    /**
     * Calculates the standardized score for filling a hole at the given coordinates.
     * * @param x X coordinate of the hole.
     * @param y Y coordinate of the hole.
     * @return Integer score, or Integer.MIN_VALUE if invalid.
     */
    public int standardizedScoreForHole(int x, int y) {
        return calculateStandardizedScore(x, y, TWAction.PUTDOWN);
    }

    /**
     * Core scoring function determining the utility of performing an action at a target location.
     * Factor in zone restrictions, A* distance, lifetime slack, and inventory capacity.
     * * @param targetX X coordinate of target.
     * @param targetY Y coordinate of target.
     * @param action  The intended action (PICKUP or PUTDOWN).
     * @return Integer utility score.
     */
    protected int calculateStandardizedScore(int targetX, int targetY, TWAction action) {
        // --- SOFT ZONING ---
        int zonePenalty = 0;
        Rectangle myZone = consensusMemory.getAssignedZone();
        if (myZone != null && !myZone.contains(targetX, targetY)) {
            int mapDimensionSum = this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension();
            // Penalty equivalent to crossing half the map's total dimensions (width + height)
            zonePenalty = (mapDimensionSum / 2) * DISTANCE_WEIGHT; // Approx 500 for 50x50 map
        }

        double rawDist = getStandardizedDistance(this.getX(), this.getY(), targetX, targetY);
        if (rawDist == Double.MAX_VALUE) return Integer.MIN_VALUE;
        int dist = (int) rawDist;

        // Lifetime Check
        int currentStep = (int)this.getEnvironment().schedule.getSteps();
        Integer creationTime = (action == TWAction.PICKUP) ?
            consensusMemory.getTileTimestamp(targetX, targetY) :
            consensusMemory.getHoleTimestamp(targetX, targetY);

        int slack = Integer.MAX_VALUE;
        if (creationTime != null) {
            int life = consensusMemory.getKnownLifetime();
            int deadline = creationTime + life;
            slack = deadline - (currentStep + dist);
            if (slack < 0) return Integer.MIN_VALUE;
        }

        // --- CLUSTER BONUS ---
        int clusterBonus = 0;
        int clusterBonusMultiplier = ((this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension()) / 20) * DISTANCE_WEIGHT; // Approx 50 for 50x50 map
        if (action == TWAction.PICKUP) {
            clusterBonus = getClusterCount(targetX, targetY, consensusMemory.getConsensusTiles().keySet()) * clusterBonusMultiplier;
        } else if (action == TWAction.PUTDOWN) {
            clusterBonus = getClusterCount(targetX, targetY, consensusMemory.getConsensusHoles().keySet()) * clusterBonusMultiplier;
        }

        int score = BASE_SCORE_OFFSET - zonePenalty + clusterBonus;
        if (action == TWAction.PUTDOWN) {
             score += -(dist * DISTANCE_WEIGHT) + (this.carriedTiles.size() * INVENTORY_WEIGHT);
        } else {
             score += -(dist * DISTANCE_WEIGHT) - (this.carriedTiles.size() * INVENTORY_WEIGHT);
        }

        if (slack != Integer.MAX_VALUE) {
            score -= (slack * URGENCY_WEIGHT);
        }
        return score;
    }

    private int getClusterCount(int x, int y, java.util.Set<Int2D> others) {
        // Radius is ~10% of the average map dimension. Approx 5 for 50x50, 8 for 80x80.
        int radius = (this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension()) / 20;
        int count = 0;
        for (Int2D pos : others) {
            if (pos.x == x && pos.y == y) continue;
            if (Math.abs(pos.x - x) + Math.abs(pos.y - y) <= radius) count++;
        }
        // Cap bonus at the same dynamic radius to maintain balance
        return Math.min(count, radius);
    }

    // --- CONVENIENCE METHODS FOR SUBCLASSES ---

    /**
     * Returns a list of coordinates for valid tiles that score <= AUTOMATIC_SCORE_THRESHOLD.
     * * @return List of tile coordinates suitable for long-range planning.
     */
    public List<Int2D> getLowerPriorityTilesFromConsensusMemory() {
        ArrayList<Int2D> list = new ArrayList<>();
        for (Int2D pos : consensusMemory.getConsensusTiles().keySet()) {
            int score = standardizedScoreForTile(pos.x, pos.y);
            if (score != Integer.MIN_VALUE && score <= AUTOMATIC_SCORE_THRESHOLD) {
                list.add(pos);
            }
        }
        return list;
    }

    /**
     * Returns a list of coordinates for valid holes that score <= AUTOMATIC_SCORE_THRESHOLD.
     * * @return List of hole coordinates suitable for long-range planning.
     */
    public List<Int2D> getLowerPriorityHolesFromConsensusMemory() {
        ArrayList<Int2D> list = new ArrayList<>();
        for (Int2D pos : consensusMemory.getConsensusHoles().keySet()) {
            int score = standardizedScoreForHole(pos.x, pos.y);
            if (score != Integer.MIN_VALUE && score <= AUTOMATIC_SCORE_THRESHOLD) {
                list.add(pos);
            }
        }
        return list;
    }

    // --- EXPLORATION & MONITORING LOGIC ---

    /**
     * Manages monitoring assignment to ensure the team discovers object lifetimes quickly.
     * * @return TWThought to execute the monitoring move, or null if no monitoring needed.
     */
    protected TWThought checkMonitoringTasks() {
        if (this.monitoringTarget != null) {
            if (consensusMemory.hasGlobalHigherPriorityMonitor(this.getName())) {
                System.out.println(this.getName() + " yielding monitor task.");
                this.monitoringTarget = null;
            }
        }

        if (this.monitoringTarget == null) {
            if (!consensusMemory.isAnyAgentMonitoring(this.getName())) {
                double expectedTaskDuration = this.consensusMemory.getKnownLifetime();
                double requiredFuel = getFuelSafetyThreshold() + (expectedTaskDuration * 1.1);

                if (this.fuelLevel > requiredFuel) {
                    ArrayList<TWEntity> createdObjects = this.consensusMemory.getCreatedObjectsThisStep();
                    for (TWEntity e : createdObjects) {
                        if (e instanceof TWObstacle) {
                            int tx = e.getX();
                            int ty = e.getY();
                            Rectangle myZone = consensusMemory.getAssignedZone();
                            if (myZone != null && !myZone.contains(tx, ty)) continue;

                            if (!consensusMemory.isHigherPriorityAgentNear(tx, ty, 3, this.getName())) {
                                this.monitoringTarget = e;
                                System.out.println(this.getName() + " monitoring obstacle at " + tx + "," + ty);
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (this.monitoringTarget != null) {
            TWEntity visibleObj = this.consensusMemory.getObservedObject(monitoringTarget.getX(), monitoringTarget.getY());
            if (visibleObj != null && visibleObj == this.monitoringTarget) {
                return executeMonitorMove();
            } else {
                this.monitoringTarget = null;
            }
        }
        return null;
    }

    /**
     * Generates a move to explore unknown regions of the map based on the current phase.
     * * @return TWThought containing the move command.
     */
    protected TWThought executeExploreMove() {
        boolean stationKnown = (consensusMemory.getConsensusFuelStation().x != -1);
        boolean resetRequired = (explorationTarget == null) ||
                                (this.getX() == explorationTarget.x && this.getY() == explorationTarget.y);

        if (!stationKnown && !resetRequired) {
            if (consensusMemory.isExplored(explorationTarget.x, explorationTarget.y)) {
                resetRequired = true;
            }
        }

        if (resetRequired) {
            Rectangle zone = consensusMemory.getAssignedZone();

            if (!stationKnown) {
                // Phase 1: High Coverage (BFS)
                explorationTarget = consensusMemory.getNearestUnexplored(this.getX(), this.getY(), true);
            } else {
                explorationTarget = null;
            }

            if (explorationTarget == null) {
                if (!stationKnown) {
                    // PHASE 1: Uniform Random (No Repulsion) to cover edges
                    explorationTarget = generateZonedRandomLocation(zone);
                }
                else {
                    // PHASE 2: Repulsive Random (Avoid Clumping)
                    if (this.carriedTiles.size() > 0 && !consensusMemory.hasHolesInZone(zone)) {

                        // Refresh sub-zone assignments based on agents' current positions
                        consensusMemory.refreshExplorationSubZone();

                        Rectangle subZone = consensusMemory.getExplorationSubZone();
                        explorationTarget = generateZonedRandomLocation((subZone != null) ? subZone : zone);
                    } else {
                        explorationTarget = getRepulsiveTarget(zone);
                    }
                }
            }
        }
        return executePathTo(explorationTarget.x, explorationTarget.y, TWAction.MOVE);
    }

    /**
     * Generates a target that maximizes the distance between this agent and its teammates.
     * * @param zone The rectangle bounding the generation area.
     * @return Int2D representing the selected repulsive target.
     */
    private Int2D getRepulsiveTarget(Rectangle zone) {
        TWEnvironment env = this.getEnvironment();
        if (zone == null) return env.generateRandomLocation();

        List<Int2D> others = consensusMemory.getTeamPositions(this.getName());
        if (others.isEmpty()) {
            return generateZonedRandomLocation(zone);
        }

        Int2D bestTarget = null;
        double maxMinDistance = -1.0;

        // Sample ~20% of the zone's total area, enforcing a minimum of 50 samples.
        int numCandidates = Math.max(50, (int)((zone.width * zone.height) * 0.20));

        for (int i = 0; i < numCandidates; i++) {
            Int2D cand = generateZonedRandomLocation(zone);
            double minD = Double.MAX_VALUE;
            for (Int2D other : others) {
                double d = Math.abs(cand.x - other.x) + Math.abs(cand.y - other.y);
                if (d < minD) minD = d;
            }
            if (minD > maxMinDistance) {
                maxMinDistance = minD;
                bestTarget = cand;
            }
        }
        return (bestTarget != null) ? bestTarget : generateZonedRandomLocation(zone);
    }

    /**
     * Generates a random valid location confined within the given zone, padded to avoid walls.
     * * @param zone The rectangle to generate within.
     * @return A valid, unblocked Int2D target.
     */
    private Int2D generateZonedRandomLocation(Rectangle zone) {
        int gx, gy;
        TWEnvironment env = this.getEnvironment();

        // If no zone assigned yet, use global random
        if (zone == null) return env.generateFarRandomLocation(this.getX(), this.getY(), 20);

        // Pad the zone by sensor range to avoid inefficient overlap
        // Agents will stay "Range" distance away from borders but still see the edges.
        int padding = 3; // default sensor range
        int innerX = zone.x + padding;
        int innerY = zone.y + padding;
        int innerW = zone.width - (2 * padding);
        int innerH = zone.height - (2 * padding);

        // Safety check: Ensure the zone is actually big enough to support padding
        // (e.g. if map is very small, innerW might be negative)
        boolean usePadding = (innerW > 0 && innerH > 0);

        // Try to find a valid spot
        for (int i=0; i<50; i++) {
            if (usePadding) {
                // Generate inside the efficient inner box
                gx = ThreadLocalRandom.current().nextInt(innerW) + innerX;
                gy = ThreadLocalRandom.current().nextInt(innerH) + innerY;
            } else {
                // Fallback to full zone if it's too small for padding
                gx = ThreadLocalRandom.current().nextInt(zone.width) + zone.x;
                gy = ThreadLocalRandom.current().nextInt(zone.height) + zone.y;
            }

            if (env.isValidLocation(gx, gy) && !consensusMemory.isConsensusBlocked(gx, gy)) {
                return new Int2D(gx, gy);
            }
        }
        // Fallback if zone is completely packed
        return env.generateFarRandomLocation(this.getX(), this.getY(), 10);
    }

    // --- PATHING & MOVEMENT HELPERS ---

    /**
     * Calculates the path to a target and executes the next step. Handles yielding
     * to superior agents automatically.
     * * @param tx The target X coordinate.
     * @param ty The target Y coordinate.
     * @param actionOnArrival Action to perform once arrived.
     * @return TWThought representing the move.
     */
    protected TWThought executePathTo(int tx, int ty, TWAction actionOnArrival) {
        if (this.getX() == tx && this.getY() == ty) {
            return new TWThought(actionOnArrival, TWDirection.Z);
        }
        TWPath path = this.getPathGenerator().findPath(this.getX(), this.getY(), tx, ty);

        if (path == null || path.getpath().isEmpty()) {
            explorationTarget = null;
            // Only freeze if critically low. Otherwise, move to unblock others.
            if (actionOnArrival == TWAction.REFUEL && this.fuelLevel <= 10) {
                return new TWThought(TWAction.MOVE, TWDirection.Z);
            }
            return executeYieldMove();
        }

        TWPathStep firstStep = path.getpath().get(0);
        TWDirection dir = firstStep.getDirection();
        if (shouldYieldMove(firstStep.getX() + dir.dx, firstStep.getY() + dir.dy, this.getEnvironment().getMessages())) {
            // Freeze when fuel level is low
            if (actionOnArrival == TWAction.REFUEL && this.fuelLevel <= 10) {
                return new TWThought(TWAction.MOVE, TWDirection.Z);
            }
            return executeYieldMove();
        }
        return new TWThought(TWAction.MOVE, dir);
    }

    /**
     * Executes a move that maintains observation distance from the monitoring target.
     * * @return TWThought move command.
     */
    protected TWThought executeMonitorMove() {
        if (monitoringTarget == null) return executeRandomMove();
        int targetX = monitoringTarget.getX();
        int targetY = monitoringTarget.getY();
        if (Math.abs(this.getX() - targetX) + Math.abs(this.getY() - targetY) > 2)
            return executePathTo(targetX, targetY, TWAction.MOVE);
        return executeRandomMove();
    }

    /**
     * Generates a random move but respects the "Yield" protocol.
     * * @return TWThought containing the randomized move.
     */
    protected TWThought executeRandomMove() {
        TWDirection dir = getRandomDirection();
        int nextX = this.getX() + dir.dx;
        int nextY = this.getY() + dir.dy;
        if (shouldYieldMove(nextX, nextY, this.getEnvironment().getMessages())) {
            return executeYieldMove();
        }
        return new TWThought(TWAction.MOVE, dir);
    }

    /**
     * Executes a move intended to step out of the way of a superior agent.
     * * @return TWThought containing a move direction that increases separation.
     */
    protected TWThought executeYieldMove() {
        int superiorX = -1; int superiorY = -1;
        ArrayList<Message> messages = this.getEnvironment().getMessages();

        for (Message msg : messages) {
            String content = msg.getMessage();
            int firstSemi = content.indexOf(';');
            String statusPart = (firstSemi == -1) ? content : content.substring(0, firstSemi);
            String[] parts = statusPart.split(":");

            if (!parts[0].equals("STATUS")) continue;

            if (parts[1].compareTo(this.getName()) < 0) {
               int px = Integer.parseInt(parts[2]), py = Integer.parseInt(parts[3]);
               if (Math.abs(px - this.getX()) + Math.abs(py - this.getY()) <= 1) { superiorX=px; superiorY=py; break; }
            }
        }

        if (superiorX == -1) {
            return new TWThought(TWAction.MOVE, TWDirection.Z);
        }

        TWDirection bestDir = TWDirection.Z;
        double maxSeparation = -1.0;

        for (TWDirection dir : TWDirection.values()) {
            if (dir == TWDirection.Z) continue;
            int nextX = this.getX() + dir.dx;
            int nextY = this.getY() + dir.dy;
            if (this.getEnvironment().isCellBlocked(nextX, nextY)) continue;

            double distToSuperior = Math.abs(nextX - superiorX) + Math.abs(nextY - superiorY);
            if (distToSuperior > 1) {
                if (!shouldYieldMove(nextX, nextY, messages)) {
                    if (distToSuperior > maxSeparation) {
                        maxSeparation = distToSuperior;
                        bestDir = dir;
                    }
                }
            }
        }
        return new TWThought(TWAction.MOVE, bestDir);
    }

    /**
     * Returns a random cardinal direction (N, S, E, W), explicitly excluding Z.
     * * @return A random TWDirection.
     */
    protected TWDirection getRandomDirection() {
        // values() are[E, N, W, S, Z]. We only want indices 0-3.
        return TWDirection.values()[this.getEnvironment().random.nextInt(4)];
    }

    /**
     * Retrieves the configured A* path generator for the agent.
     * * This method implements a lazy initialization pattern, ensuring the
     * {@code ConsensusPathGenerator} is instantiated only once and reused
     * across all subsequent calls.
     * * @return The cached ConsensusPathGenerator instance for this agent.
     */
    protected ConsensusPathGenerator getPathGenerator() {
        if (this.pathGenerator == null) {
            int depth = Math.max(100, (this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension()) * 2);
            this.pathGenerator = new ConsensusPathGenerator(this.getEnvironment(), this, depth);
        }
        return this.pathGenerator;
    }

    // --- FUEL & METRICS HELPERS ---

    /**
     * Gets the fuel station location from the consensus memory.
     * * @return Int2D representation of the fuel station coordinates.
     */
    protected Int2D getFuelStationFromConsensusMemory() {
        return this.consensusMemory.getConsensusFuelStation();
    }

    /**
     * Calculates the agent's current fuel safety threshold based on its coordinates.
     * * @return Minimum required fuel level.
     */
    protected int getFuelSafetyThreshold() {
        return calculateFuelSafetyThreshold(this.getX(), this.getY());
    }

    /**
     * Calculates a peer's fuel safety threshold based on their coordinates.
     * * @param peerX X coordinate of peer.
     * @param peerY Y coordinate of peer.
     * @return Minimum required fuel level for peer.
     */
    protected int getPeerFuelSafetyThreshold(int peerX, int peerY) {
        return calculateFuelSafetyThreshold(peerX, peerY);
    }

    /**
     * Helper method to determine fuel thresholds via A* pathing distance.
     * * @param x The X coordinate to calculate from.
     * @param y The Y coordinate to calculate from.
     * @return The computed threshold.
     */
    private int calculateFuelSafetyThreshold(int x, int y) {
        Int2D station = consensusMemory.getConsensusFuelStation();
        int defaultFuelCapacity = 500;
        int computedThreshold = 0;

        if (station.x != -1) {
            double dist = getStandardizedDistance(x, y, station.x, station.y);
            if (dist != Double.MAX_VALUE) {
                computedThreshold = (int)dist + this.fuelSafetyBuffer;
            } else {
                computedThreshold = this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension();
            }
        } else {
            computedThreshold = this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension();
        }

        // Cap at (MaxFuel - 50) to ensure that a fully fueled agent
        // always satisfies the check and is allowed to leave the station.
        // Without this cap, a large buffer would trap the agent in a refuel loop.
        return Math.min(computedThreshold, defaultFuelCapacity - 50);
    }

    /**
     * Calculates distance using the Shared Consensus Map, memoizing results
     * to avoid redundant A* computations per step.
     * * @param startX Starting X coordinate.
     * @param startY Starting Y coordinate.
     * @param endX Target X coordinate.
     * @param endY Target Y coordinate.
     * @return The A* path length or Double.MAX_VALUE if unreachable.
     */
    protected double getStandardizedDistance(int startX, int startY, int endX, int endY) {
        if (startX == endX && startY == endY) return 0.0;

        String cacheKey = startX + "," + startY + "," + endX + "," + endY;

        if (distanceCache.containsKey(cacheKey)) {
            return distanceCache.get(cacheKey);
        }

        TWPath path = getPathGenerator().findPath(startX, startY, endX, endY);
        // Starting cell is included in the path, so 1 is subtracted from path size
        double dist = (path == null) ? Double.MAX_VALUE : (path.getpath().size() - 1);

        distanceCache.put(cacheKey, dist);
        return dist;
    }

    /**
     * Evaluates if this agent is the best candidate globally for a specific target
     * by comparing scores across all peers.
     * * @param targetX      X coordinate of target.
     * @param targetY      Y coordinate of target.
     * @param myScore      This agent's calculated score for the target.
     * @param peerMessages Communications from peers to parse state.
     * @param action       The intended action.
     * @return True if this agent has the highest valid utility claim to the target.
     */
    protected boolean isBestCandidate(int targetX, int targetY, int myScore, ArrayList<Message> peerMessages, TWAction action) {
        if (myScore == Integer.MIN_VALUE) return false;

        int currentStep = (int)this.getEnvironment().schedule.getSteps();
        Integer creationTime = (action == TWAction.PICKUP) ?
            consensusMemory.getTileTimestamp(targetX, targetY) :
            consensusMemory.getHoleTimestamp(targetX, targetY);

        // Pre-calculate cluster bonus for this target (same for everyone)
        int clusterBonus = 0;
        int clusterBonusMultiplier = ((this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension()) / 20) * DISTANCE_WEIGHT; // Approx 50 for 50x50 map
        if (action == TWAction.PICKUP) {
            clusterBonus = getClusterCount(targetX, targetY, consensusMemory.getConsensusTiles().keySet()) * clusterBonusMultiplier;
        } else if (action == TWAction.PUTDOWN) {
            clusterBonus = getClusterCount(targetX, targetY, consensusMemory.getConsensusHoles().keySet()) * clusterBonusMultiplier;
        }

        for (Message msg : peerMessages) {
            // Expected format: STATUS:name:x:y:inv:fuel;...
            String content = msg.getMessage();
            int firstSemi = content.indexOf(';');
            String statusPart = (firstSemi == -1) ? content : content.substring(0, firstSemi);
            String[] parts = statusPart.split(":");

            if (!parts[0].equals("STATUS")) continue;
            String peerName = parts[1];
            if (peerName.equals(this.getName())) continue; // Skip myself

            int peerX = Integer.parseInt(parts[2]);
            int peerY = Integer.parseInt(parts[3]);
            int peerInv = Integer.parseInt(parts[4]);
            double peerFuel = Double.parseDouble(parts[5]);

            if (peerFuel < getPeerFuelSafetyThreshold(peerX, peerY)) continue;

            if (action == TWAction.PICKUP && peerInv >= 3) continue;
            if (action == TWAction.PUTDOWN && peerInv == 0) continue;

            // --- SOFT ZONING CHECK ---
            int peerZonePenalty = 0;
            Rectangle peerZone = consensusMemory.getZone(peerName);
            if (peerZone != null && !peerZone.contains(targetX, targetY)) {
                int mapDimensionSum = this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension();
                peerZonePenalty = (mapDimensionSum / 2) * DISTANCE_WEIGHT; // Approx 500 for 50x50 map
            }

            // --- Check Mathematical Upper Bound (Manhattan Fast-Fail) ---
            int peerManhattan = Math.abs(peerX - targetX) + Math.abs(peerY - targetY);
            int maxPossiblePeerScore = BASE_SCORE_OFFSET - peerZonePenalty + clusterBonus;
            if (action == TWAction.PUTDOWN) {
                maxPossiblePeerScore += -(peerManhattan * DISTANCE_WEIGHT) + (peerInv * INVENTORY_WEIGHT);
            } else {
                maxPossiblePeerScore += -(peerManhattan * DISTANCE_WEIGHT) - (peerInv * INVENTORY_WEIGHT);
            }

            // Skip expensive A* calculation if absolute best-case scenario
            // is still worse than my actual score
            if (maxPossiblePeerScore < myScore) continue;

            double rawPeerDist = this.getStandardizedDistance(peerX, peerY, targetX, targetY);
            if (rawPeerDist == Double.MAX_VALUE) continue;
            int peerDist = (int) rawPeerDist;

            int peerSlack = Integer.MAX_VALUE;
            if (creationTime != null) {
                int life = consensusMemory.getKnownLifetime();
                int deadline = creationTime + life;
                peerSlack = deadline - (currentStep + peerDist);
                if (peerSlack < 0) continue;
            }

            int peerScore = BASE_SCORE_OFFSET - peerZonePenalty + clusterBonus;
            if (action == TWAction.PUTDOWN) {
                peerScore += -(peerDist * DISTANCE_WEIGHT) + (peerInv * INVENTORY_WEIGHT);
            } else {
                peerScore += -(peerDist * DISTANCE_WEIGHT) - (peerInv * INVENTORY_WEIGHT);
            }

            if (peerSlack != Integer.MAX_VALUE) {
                peerScore -= (peerSlack * URGENCY_WEIGHT);
            }

            if (peerScore > myScore) return false;
            else if (peerScore == myScore) {
                // Tie-breaker: Lower Distance is better
                double myDist = getStandardizedDistance(this.getX(), this.getY(), targetX, targetY);
                if (rawPeerDist < myDist) return false;
                else if (rawPeerDist == myDist) {
                    if (peerName.compareTo(this.getName()) < 0) return false;
                }
            }
        }
        return true;
    }

    /**
     * Determines if the agent should wait to avoid physically blocking a superior agent.
     * * @param nextX        The intended next X coordinate.
     * @param nextY        The intended next Y coordinate.
     * @param peerMessages Peer statuses.
     * @return True if the move should be aborted to yield.
     */
    protected boolean shouldYieldMove(int nextX, int nextY, ArrayList<Message> peerMessages) {
        for (Message msg : peerMessages) {
            String content = msg.getMessage();
            int firstSemi = content.indexOf(';');
            String statusPart = (firstSemi == -1) ? content : content.substring(0, firstSemi);
            String[] parts = statusPart.split(":");

            if (!parts[0].equals("STATUS")) continue;

            String peerName = parts[1];
            if (peerName.equals(this.getName())) continue;

            int peerX = Integer.parseInt(parts[2]);
            int peerY = Integer.parseInt(parts[3]);

            // Rule 1: Don't walk into a cell currently occupied by anyone
            if (peerX == nextX && peerY == nextY) return true;
            // Rule 2: "Bubble of Authority"
            if (peerName.compareTo(this.getName()) < 0) {
                if (Math.abs(peerX - nextX) + Math.abs(peerY - nextY) <= 1) return true;
            }
        }
        return false;
    }

    // --- EXECUTION PROTOCOLS ---

    /**
     * STANDARD COMMUNICATION PROTOCOL
     * Packages the agent's state and recent discoveries into a formatted string
     * broadcast for team consensus updates.
     */
    @Override
    public final void communicate() {
        // Generate the Heartbeat (STATUS)
        StringBuilder sb = new StringBuilder();
        sb.append(generateStatusString());

        ArrayList<TWEntity> newItems = consensusMemory.getNewDiscoveriesThisStep();
        ArrayList<TWEntity> witnessedItems = consensusMemory.getCreatedObjectsThisStep();
        int currentStep = (int) this.getEnvironment().schedule.getSteps();

        // Calculate a statistical age penalty for objects whose creation was witnessed by no agent
        int knownLife = consensusMemory.getKnownLifetime();
        int envArea = this.getEnvironment().getxDimension() * this.getEnvironment().getyDimension();
        // Scale the penalty based on area.
        // For 50x50 (Area 2500): Ratio is ~0.1 (10% penalty)
        // For 80x80 (Area 6400): Ratio is ~0.25 (25% penalty)
        // Math.min() strictly enforces a 40% ceiling.
        double dynamicPenaltyRatio = Math.min(0.40, ((double)envArea / 25000.0));
        int agePenalty = (knownLife > 0) ? (int)(knownLife * dynamicPenaltyRatio) : 0;

        for (TWEntity e : newItems) {
            // Determine the optimal timestamp to broadcast
            int broadcastTimestamp = currentStep;
            if (!witnessedItems.contains(e)) {
                // The creation of this object was unwitnessed. Apply the statistical discount
                // so the rest of the team knows it might expire sooner than expected.
                broadcastTimestamp = currentStep - agePenalty;
            }

            if (e instanceof TWTile) {
                sb.append(";TILE:").append(e.getX()).append(":").append(e.getY()).append(":").append(broadcastTimestamp);
            } else if (e instanceof TWHole) {
                sb.append(";HOLE:").append(e.getX()).append(":").append(e.getY()).append(":").append(broadcastTimestamp);
            } else if (e instanceof TWObstacle) {
                sb.append(";OBSTACLE:").append(e.getX()).append(":").append(e.getY()).append(":").append(broadcastTimestamp);
            } else if (e instanceof TWFuelStation) {
                sb.append(";FUEL_STATION:").append(e.getX()).append(":").append(e.getY());
            }
        }

        // Broadcast DEFINITE_LIFETIME if we have confirmed it this step
        int discoveredLife = consensusMemory.getDiscoveredLifetime();
        if (discoveredLife != -1) {
            if (consensusMemory.isDiscoveredLifetimeDefinite()) {
                sb.append(";DEFINITE_LIFETIME:").append(discoveredLife);
            } else {
                sb.append(";LIFETIME:").append(discoveredLife);
            }
        }

        if (this.monitoringTarget != null) {
            sb.append(";MONITORING:").append(monitoringTarget.getX()).append(":").append(monitoringTarget.getY());
        }

        // Broadcast Removals
        ArrayList<String> removals = consensusMemory.getRemovedObjectsBuffer();
        for (String rem : removals) {
            sb.append(";").append(rem);
        }

        // 'to' is set to null to signify a broadcast to all agents
        Message msg = new Message(this.getName(), null, sb.toString());
        this.getEnvironment().receiveMessage(msg);

        // Clear the buffer after sending, so we start fresh next step.
        removals.clear();
    }

    /**
     * Generates the standard heartbeat string for the agent.
     * * @return Formatted status string.
     */
    protected String generateStatusString() {
        return String.format("STATUS:%s:%d:%d:%d:%.1f", this.getName(),
            this.getX(), this.getY(), this.carriedTiles.size(), this.getFuelLevel());
    }

    /**
     * STANDARD ACTION EXECUTION
     * Routes the selected TWThought to the physical environment interactions and clears
     * resolved tasks from the consensus queue.
     * * @param thought The action decided in the think() loop.
     */
    @Override
    protected void act(TWThought thought) {
        lastActionFailed = false;
        try {
            switch (thought.getAction()) {
                case MOVE: this.move(thought.getDirection()); break;
                case PICKUP:
                    Object tile = this.getEnvironment().getObjectGrid().get(this.getX(), this.getY());
                    if (tile instanceof TWTile) {
                        this.pickUpTile((TWTile) tile);
                        this.consensusMemory.getConsensusTiles().remove(new Int2D(this.getX(), this.getY()));
                        this.consensusMemory.queueRemoval("TILE", this.getX(), this.getY());
                    } else {
                        // Ghost: Remove locally AND queue for broadcast
                        this.consensusMemory.getConsensusTiles().remove(new Int2D(this.getX(), this.getY()));
                        this.consensusMemory.queueRemoval("TILE", this.getX(), this.getY());
                    }
                    break;
                case PUTDOWN:
                    Object hole = this.getEnvironment().getObjectGrid().get(this.getX(), this.getY());
                    if (hole instanceof TWHole) {
                        this.putTileInHole((TWHole) hole);
                        this.consensusMemory.getConsensusHoles().remove(new Int2D(this.getX(), this.getY()));
                        this.consensusMemory.queueRemoval("HOLE", this.getX(), this.getY());
                    } else {
                        // Ghost: Remove locally AND queue for broadcast
                        this.consensusMemory.getConsensusHoles().remove(new Int2D(this.getX(), this.getY()));
                        this.consensusMemory.queueRemoval("HOLE", this.getX(), this.getY());
                    }
                    break;
                case REFUEL: this.refuel(); break;
            }
        } catch (CellBlockedException e) {
            lastActionFailed = true;
        } catch (Exception e) {}
    }

    /**
     * Abstract method that subclasses must implement to define their custom
     * lower-priority task logic.
     * * @return TWThought containing the worker logic's decision.
     */
    protected abstract TWThought executeWorkerLogic();

    /**
     * Returns the name of the agent.
     * * @return The agent name string.
     */
    @Override
    public String getName() { return name; }
}
