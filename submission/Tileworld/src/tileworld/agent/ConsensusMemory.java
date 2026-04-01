package tileworld.agent;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList; // Needed for BFS
import java.util.List;
import java.util.Map;
import java.util.Queue;      // Needed for BFS
import sim.engine.Schedule;
import sim.util.Bag;
import sim.util.Int2D;
import sim.util.IntBag;
import tileworld.Parameters;
import tileworld.environment.TWEntity;
import tileworld.environment.TWFuelStation;
import tileworld.environment.TWHole;
import tileworld.environment.TWObject;
import tileworld.environment.TWObstacle;
import tileworld.environment.TWTile;

/**
 * ConsensusMemory
 * * An advanced shared memory module bridging private sensor data (Layer 1)
 * with team-agreed global state data (Layer 2).
 */
public class ConsensusMemory extends TWAgentWorkingMemory {

    // --- FIELDS ---

    /** Reference to the agent owning this memory module. */
    private final TWAgent me;
    /** The simulation schedule for querying current steps and time. */
    private final Schedule schedule;

    /** Flag to ensure the station discovery log is only printed once. */
    private boolean fuelStationDiscoveredLog = false;

    // --- LAYER 1: PRIVATE SENSOR DATA (Shadowed) ---

    /** Private copy of immediate sensor data to track individual timestamps. */
    private TWAgentPercept[][] privateShadow;
    /** Grid tracking the simulation time a cell was last observed. */
    private double[][] lastObservationGrid;
    /** Grid tracking if the agent was physically present when an object spawned. */
    private boolean[][] witnessedCreationGrid;
    /** Grid tracking which coordinates the agent has personally explored. */
    private boolean[][] exploredGrid;
    /** Count of unique cells the agent has explored. */
    private int exploredCellCount = 0;

    // --- ZONING LOGIC VARIABLES ---

    /** The strict zone boundary assigned to this agent for executing tasks. */
    private Rectangle assignedZone = null;
    /** The dynamic sub-zone used specifically for spreading out during exploration. */
    private Rectangle explorationSubZone = null;
    /** Map linking agent names to their respective assigned zones. */
    private Map<String, Rectangle> allAgentZones = new HashMap<>();
    /** Map storing the latest broadcasted locations of all team members. */
    private Map<String, Int2D> teamPositions = new HashMap<>();
    /** Tracks whether the simulation has transitioned to the Phase 2 overlapping layout. */
    private boolean zoneAssignmentIsPhase2 = false;
    /** Configurable dimension describing how much North/South zones overlap in Phase 2. */
    private int phase2ZoneOverlap;

    // --- BUFFER ARRAYS FOR BROADCASTING ---

    /** List of nearby agents sensed in the current step. */
    private ArrayList<TWAgent> nearbyAgents;
    /** List of objects discovered this step that aren't yet known to the team. */
    private ArrayList<TWEntity> newDiscoveriesThisStep;
    /** List of objects whose actual spawn event was witnessed this step. */
    private ArrayList<TWEntity> createdObjectsThisStep;
    /** Buffer for object coordinates that should be broadcast as removed. */
    private ArrayList<String> removedObjectsBuffer;

    /** Stored integer value of a discovered object lifetime (if any this step). */
    private int discoveredLifetime = -1;
    /** Flag indicating if the discovered lifetime was confirmed from a witnessed spawn. */
    private boolean discoveredLifetimeIsDefinite = false;

    // --- LAYER 2: CONSENSUS DATA (Shared Knowledge) ---

    /** Map of all universally recognized active Tiles and their creation timestamps. */
    private HashMap<Int2D, Integer> consensusTiles;
    /** Map of all universally recognized active Holes and their creation timestamps. */
    private HashMap<Int2D, Integer> consensusHoles;
    /** Map of all universally recognized Obstacles. */
    private HashMap<Int2D, Integer> consensusObstacles;
    /** Map of active monitoring claims to prevent duplicate agent assignment. */
    private HashMap<Int2D, ArrayList<String>> monitorClaims;

    /** Universally agreed location of the fuel station. (-1,-1) if unknown. */
    private Int2D consensusFuelStation;
    /** Team's current working estimate of global object lifetime. */
    private int objectLifetime;
    /** Flag indicating the team has permanently confirmed the exact object lifetime. */
    private boolean definiteLifetimeKnown = false;

    // --- CONSTRUCTORS ---

    /**
     * Initializes the ConsensusMemory module.
     * * @param moi      The owning agent.
     * @param schedule The simulation schedule.
     * @param x        The map X dimension.
     * @param y        The map Y dimension.
     */
    public ConsensusMemory(TWAgent moi, Schedule schedule, int x, int y) {
        super(moi, schedule, x, y);
        this.me = moi;
        this.schedule = schedule;

        this.privateShadow = new TWAgentPercept[x][y]; // Initialize Shadow Layer
        this.lastObservationGrid = new double[x][y];
        this.witnessedCreationGrid = new boolean[x][y];
        this.exploredGrid = new boolean[x][y];

        for(int i=0; i<x; i++) {
            for(int j=0; j<y; j++) {
                lastObservationGrid[i][j] = -1;
                exploredGrid[i][j] = false;
            }
        }

        this.nearbyAgents = new ArrayList<>();
        this.newDiscoveriesThisStep = new ArrayList<>();
        this.createdObjectsThisStep = new ArrayList<>();
        this.removedObjectsBuffer = new ArrayList<>();

        this.consensusTiles = new HashMap<>();
        this.consensusHoles = new HashMap<>();
        this.consensusObstacles = new HashMap<>();
        this.monitorClaims = new HashMap<>();

        this.phase2ZoneOverlap = (int)(me.getEnvironment().getyDimension() * 0.25);

        // Defaults
        this.consensusFuelStation = new Int2D(-1, -1);
        this.objectLifetime = 20; // Conservative estimate
    }

    // --- ZONING LOGIC ---

    /**
     * HYBRID ZONING STRATEGY
     * Triggers the appropriate spatial distribution algorithm (Phase 1 vs Phase 2)
     * if conditions have changed and recalculation is required.
     */
    public void performZoneAssignmentIfNecessary() {
        // Wait until Step 2 to do zone assignment.
        // Step 0: Everyone Broadcasts.
        // Step 1: Everyone Receives.
        // Step 2: Safe to calculate.
        if (schedule.getSteps() < 2) return;

        // Always sync own position to support zone assignment calculations.
        // Positions for other team members are synced from broadcasted messages
        // in `updateState`.
        teamPositions.put(me.getName(), new Int2D(me.getX(), me.getY()));

        boolean stationFound = (this.consensusFuelStation.x != -1);

        // If we already have an assigned zone AND we don't need to execute a
        // Phase Switch (i.e., we haven't found the station yet OR we are
        // already correctly in Phase 2), we do nothing.
        if (assignedZone != null && (!stationFound || zoneAssignmentIsPhase2)) {
            return;
        }

        allAgentZones.clear(); // Reset for recalculation

        int W = me.getEnvironment().getxDimension();
        int H = me.getEnvironment().getyDimension();
        int midH = H / 2;

        // 1. Sort All Agents by Y (North vs South)
        List<Map.Entry<String, Int2D>> sortedAgents = new ArrayList<>(teamPositions.entrySet());
        sortedAgents.sort((a, b) -> {
            int cmp = Integer.compare(a.getValue().y, b.getValue().y);
            if (cmp != 0) return cmp;
            return a.getKey().compareTo(b.getKey());
        });

        int teamSize = sortedAgents.size();
        int northCount = (int) Math.ceil(teamSize / 2.0);

        // 2. Split into North and South Groups
        List<Map.Entry<String, Int2D>> northGroup = new ArrayList<>();
        List<Map.Entry<String, Int2D>> southGroup = new ArrayList<>();

        for (int i = 0; i < teamSize; i++) {
            if (i < northCount) northGroup.add(sortedAgents.get(i));
            else southGroup.add(sortedAgents.get(i));
        }

        if (stationFound) {
            // --- PHASE 2: Overlapping North/South Split ---
            if (!zoneAssignmentIsPhase2) {
                System.out.println(">>> PHASE 2 SWITCH: " + me.getName() + " calculating global zones.");
                this.zoneAssignmentIsPhase2 = true;
            }

            int halfOverlap = this.phase2ZoneOverlap / 2;
            Rectangle northZone = new Rectangle(0, 0, W, midH + halfOverlap);
            Rectangle southZone = new Rectangle(0, midH - halfOverlap, W, (H - midH) + halfOverlap);

            for (Map.Entry<String, Int2D> entry : northGroup) allAgentZones.put(entry.getKey(), northZone);
            for (Map.Entry<String, Int2D> entry : southGroup) allAgentZones.put(entry.getKey(), southZone);

        } else {
            // --- PHASE 1: Strict Columns (NO Overlap) ---
            // We want strict partitioning for maximum exploration efficiency.
            assignColumns(northGroup, 0, midH, W, 0);
            assignColumns(southGroup, midH, H - midH, W, 0);

            this.zoneAssignmentIsPhase2 = false;

            // In Phase 1, Exploration Zone is identical to Assigned Zone
            this.explorationSubZone = allAgentZones.get(me.getName());

            System.out.println(">>> ZONING: " + me.getName() + " assigned Phase 1 Zone (Strict): " + this.explorationSubZone);
        }

        // 3. Update My Own Zone
        this.assignedZone = allAgentZones.get(me.getName());
    }

    /**
     * DYNAMIC REFRESH
     * Recalculates the exploration sub-zone based on the team's CURRENT X-coordinates
     * to prevent rigid lane clumping.
     */
    public void refreshExplorationSubZone() {
        if (!zoneAssignmentIsPhase2 || assignedZone == null) return;

        int W = me.getEnvironment().getxDimension();

        // 1. Identify peers in my Row (North or South)
        List<Map.Entry<String, Int2D>> myRowPeers = new ArrayList<>();

        for (Map.Entry<String, Int2D> entry : teamPositions.entrySet()) {
            Rectangle pZone = allAgentZones.get(entry.getKey());
            // Check if peer shares my EXACT assigned zone
            if (pZone != null && pZone.equals(this.assignedZone)) {
                myRowPeers.add(entry);
            }
        }

        if (myRowPeers.isEmpty()) return;

        // 2. Sort by CURRENT X-Coordinate (Left to Right)
        myRowPeers.sort((a, b) -> {
            int cmp = Integer.compare(a.getValue().x, b.getValue().x);
            if (cmp != 0) return cmp;
            return a.getKey().compareTo(b.getKey());
        });

        // 3. Find my rank
        int myIndex = -1;
        for(int i=0; i<myRowPeers.size(); i++) {
            if (myRowPeers.get(i).getKey().equals(me.getName())) {
                myIndex = i;
                break;
            }
        }

        // 4. Calculate Slice (with Overlap)
        if (myIndex != -1) {
            int count = myRowPeers.size();
            int subZoneWidth = W / count;
            int halfOverlap = this.phase2ZoneOverlap / 2;

            int rawStart = myIndex * subZoneWidth;
            int rawEnd = (myIndex == count - 1) ? W : (myIndex + 1) * subZoneWidth;

            int effectiveStart = Math.max(0, rawStart - halfOverlap);
            if (myIndex == 0) effectiveStart = 0;

            int effectiveEnd = Math.min(W, rawEnd + halfOverlap);
            if (myIndex == count - 1) effectiveEnd = W;

            int width = effectiveEnd - effectiveStart;

            this.explorationSubZone = new Rectangle(effectiveStart, this.assignedZone.y, width, this.assignedZone.height);
        }
    }

    /**
     * Helper to subdivide a large horizontal band into vertical columns.
     * * @param group      The list of agents.
     * @param yStart     Y coordinate start for the block.
     * @param height     Height of the block.
     * @param totalWidth Total width of the block.
     * @param overlap    Amount of padding/overlap between columns.
     */
    private void assignColumns(List<Map.Entry<String, Int2D>> group, int yStart, int height, int totalWidth, int overlap) {
        group.sort((a, b) -> {
            int cmp = Integer.compare(a.getValue().x, b.getValue().x);
            if (cmp != 0) return cmp;
            return a.getKey().compareTo(b.getKey());
        });

        int count = group.size();
        if (count == 0) return;

        int subZoneWidth = totalWidth / count;
        int halfOverlap = overlap / 2;

        for (int i = 0; i < count; i++) {
            int rawStart = i * subZoneWidth;
            int rawEnd = (i == count - 1) ? totalWidth : (i + 1) * subZoneWidth;

            int effectiveStart = Math.max(0, rawStart - halfOverlap);
            if (i == 0) effectiveStart = 0;

            int effectiveEnd = Math.min(totalWidth, rawEnd + halfOverlap);
            if (i == count - 1) effectiveEnd = totalWidth;

            int width = effectiveEnd - effectiveStart;

            Rectangle z = new Rectangle(effectiveStart, yStart, width, height);
            allAgentZones.put(group.get(i).getKey(), z);
        }
    }

    // --- EXPLORATION LOGIC ---

    /**
     * Flags a localized square area around the agent as explored.
     * * @param cx    Center X coordinate.
     * @param cy    Center Y coordinate.
     * @param range Sensor range radius.
     */
    private void markAreaExplored(int cx, int cy, int range) {
        int xDim = me.getEnvironment().getxDimension();
        int yDim = me.getEnvironment().getyDimension();

        int startX = Math.max(0, cx - range);
        int endX = Math.min(xDim - 1, cx + range);
        int startY = Math.max(0, cy - range);
        int endY = Math.min(yDim - 1, cy + range);

        for (int i = startX; i <= endX; i++) {
            for (int j = startY; j <= endY; j++) {
                // Only increment if it wasn't already explored
                if (!exploredGrid[i][j]) {
                    exploredGrid[i][j] = true;
                    exploredCellCount++;
                }
            }
        }
    }

    /**
     * Discovers the nearest undiscovered cell using a BFS algorithm.
     * * @param startX         Start X.
     * @param startY         Start Y.
     * @param restrictToZone Whether the target must reside inside the assigned zone.
     * @return Coordinates of the target cell.
     */
    public Int2D getNearestUnexplored(int startX, int startY, boolean restrictToZone) {
        if (isMapFullyExplored()) return null;

        // Pass 1: Try to find an unexplored target in the PADDED zone (avoids walking to borders)
        Int2D target = searchUnexploredBFS(startX, startY, restrictToZone, true);
        if (target != null) return target;

        // Pass 2: Fallback to the strict zone (to catch corners/edges blocked by obstacles)
        return searchUnexploredBFS(startX, startY, restrictToZone, false);
    }

    /**
     * The internal BFS loop for resolving unexplored cells.
     * * @param startX         Start X.
     * @param startY         Start Y.
     * @param restrictToZone Apply zone bounding box.
     * @param usePadding     Apply padded bounding box to prevent border-hugging.
     * @return Coordinates of a valid unexplored cell.
     */
    private Int2D searchUnexploredBFS(int startX, int startY, boolean restrictToZone, boolean usePadding) {
        int xDim = me.getEnvironment().getxDimension();
        int yDim = me.getEnvironment().getyDimension();

        boolean[][] visitedBFS = new boolean[xDim][yDim];
        Queue<Int2D> queue = new LinkedList<>();

        Int2D startNode = new Int2D(startX, startY);
        queue.add(startNode);
        visitedBFS[startX][startY] = true;

        // Calculate the boundary constraint for this BFS pass
        Rectangle searchZone = assignedZone;
        if (restrictToZone && assignedZone != null && usePadding) {
            int pad = 3; // default sensor range
            int ix = assignedZone.x + pad;
            int iy = assignedZone.y + pad;
            int iw = assignedZone.width - (2 * pad);
            int ih = assignedZone.height - (2 * pad);

            // Only use padded zone if it's physically possible (width/height > 0)
            if (iw > 0 && ih > 0) {
                searchZone = new Rectangle(ix, iy, iw, ih);
            }
        }

        while (!queue.isEmpty()) {
            Int2D current = queue.poll();

            // Found a target!
            if (!exploredGrid[current.x][current.y]) {
                // Constraint Check: Is this target in our defined search zone?
                if (restrictToZone && searchZone != null) {
                    if (searchZone.contains(current.x, current.y)) {
                        return current;
                    }
                } else {
                    return current;
                }
            }

            int[] dx = {0, 0, 1, -1};
            int[] dy = {1, -1, 0, 0};

            for (int i = 0; i < 4; i++) {
                int nx = current.x + dx[i];
                int ny = current.y + dy[i];

                if (nx >= 0 && nx < xDim && ny >= 0 && ny < yDim && !visitedBFS[nx][ny]) {
                    // Optimization: Do not traverse through obstacles
                    if (!isConsensusBlocked(nx, ny)) {
                        visitedBFS[nx][ny] = true;
                        queue.add(new Int2D(nx, ny));
                    }
                }
            }
        }
        return null;
    }

    /**
     * Checks if the map is statistically 100% explored based on tracked counts.
     * * @return True if all map cells are explored.
     */
    private boolean isMapFullyExplored() {
        int totalCells = me.getEnvironment().getxDimension() * me.getEnvironment().getyDimension();
        return exploredCellCount >= totalCells;
    }

    /**
     * Returns true if the specific cell has been viewed by the agent.
     * * @param x Cell X.
     * @param y Cell Y.
     * @return Boolean exploration status.
     */
    public boolean isExplored(int x, int y) {
        if (x < 0 || x >= me.getEnvironment().getxDimension() || y < 0 || y >= me.getEnvironment().getyDimension()) return true;
        return exploredGrid[x][y];
    }

    // --- SENSOR & MEMORY UPDATES ---

    /**
     * Overrides the Base Memory update to populate the privateShadow and analyze
     * object lifecycles for the passive crowdsourcing system.
     */
    @Override
    public void updateMemory(Bag sensedObjects, IntBag objectXCoords, IntBag objectYCoords,
                             Bag sensedAgents, IntBag agentXCoords, IntBag agentYCoords) {

        super.updateMemory(sensedObjects, objectXCoords, objectYCoords, sensedAgents, agentXCoords, agentYCoords);

        newDiscoveriesThisStep.clear();
        createdObjectsThisStep.clear();
        nearbyAgents.clear();
        discoveredLifetime = -1;
        discoveredLifetimeIsDefinite = false;

        double currentTime = this.getSimulationTime();
        int range = Parameters.defaultSensorRange;
        int xDim = me.getEnvironment().getxDimension();
        int yDim = me.getEnvironment().getyDimension();

        // Update Shared Coverage Map
        markAreaExplored(me.getX(), me.getY(), range);

        // 1. Process Neighbors
        for(int i=0; i<sensedAgents.size(); i++) {
            Object obj = sensedAgents.get(i);
            if(obj instanceof TWAgent && obj != me) {
                nearbyAgents.add((TWAgent)obj);
            }
        }

        // 2. Detect Deaths & Clear Ghosts
        for (int x = me.getX() - range; x <= me.getX() + range; x++) {
            for (int y = me.getY() - range; y <= me.getY() + range; y++) {
                if (x < 0 || x >= xDim || y < 0 || y >= yDim) continue;

                // Determine if the cell is completely empty right now
                boolean cellIsEmpty = true;
                for (int i = 0; i < sensedObjects.size(); i++) {
                    TWEntity obj = (TWEntity) sensedObjects.get(i);
                    if (obj != null && obj.getX() == x && obj.getY() == y) {
                        cellIsEmpty = false;
                        break;
                    }
                }

                Int2D pos = new Int2D(x, y);

                // Proactively clean the Consensus map (outside private shadow)
                if (cellIsEmpty) {
                    if (consensusTiles.containsKey(pos)) {
                        consensusTiles.remove(pos);
                        queueRemoval("TILE", x, y);
                    }
                    if (consensusHoles.containsKey(pos)) {
                        consensusHoles.remove(pos);
                        queueRemoval("HOLE", x, y);
                    }
                    if (consensusObstacles.containsKey(pos)) {
                        consensusObstacles.remove(pos);
                        queueRemoval("OBSTACLE", x, y);
                    }
                }

                // Lifetime mathematics (requires private shadow)
                TWAgentPercept prevPercept = privateShadow[x][y];
                if (prevPercept != null) {
                    TWEntity oldObj = prevPercept.getO();

                    if (oldObj instanceof TWObject && cellIsEmpty) {
                        double lastSeen = lastObservationGrid[x][y];

                        if ((currentTime - lastSeen) <= 1.5) {
                            double startTime = prevPercept.getT();
                            int calculatedLife = (int) (currentTime - startTime);

                            // Only execute block if value is >= current knowledge
                            if (calculatedLife >= this.objectLifetime) {
                                // Only Obstacles provide definite data (Tiles/Holes can be interacted with)
                                if (witnessedCreationGrid[x][y] && (oldObj instanceof TWObstacle)) {
                                    System.out.println("Passive Crowdsourcing: " + me.getName() +
                                        " found DEFINITE lifetime: " + calculatedLife);

                                    this.updateLifetime(calculatedLife);
                                    this.discoveredLifetime = calculatedLife;
                                    this.definiteLifetimeKnown = true;
                                    this.discoveredLifetimeIsDefinite = true;

                                } else if (calculatedLife > this.objectLifetime) {
                                    this.updateLifetime(calculatedLife);
                                    this.discoveredLifetime = calculatedLife;
                                    System.out.println("Passive Crowdsourcing: " + me.getName() +
                                        " estimated lifetime >= " + calculatedLife);
                                }
                            }
                        }
                        privateShadow[x][y] = null;
                        witnessedCreationGrid[x][y] = false;
                    }
                }
            }
        }

        // 3. Detect Creations
        for (int i = 0; i < sensedObjects.size(); i++) {
            TWEntity o = (TWEntity) sensedObjects.get(i);
            if (!(o instanceof TWObject) && !(o instanceof TWFuelStation)) continue;

            int x = o.getX();
            int y = o.getY();
            Int2D pos = new Int2D(x, y);

            boolean isKnownInConsensus = false;
            if (o instanceof TWTile) isKnownInConsensus = consensusTiles.containsKey(pos);
            else if (o instanceof TWHole) isKnownInConsensus = consensusHoles.containsKey(pos);
            else if (o instanceof TWObstacle) isKnownInConsensus = consensusObstacles.containsKey(pos);
            else if (o instanceof TWFuelStation) isKnownInConsensus = (consensusFuelStation.x != -1);

            // If it's unknown to the team, we flag it for broadcast
            if (!isKnownInConsensus && !newDiscoveriesThisStep.contains(o)) {
                newDiscoveriesThisStep.add(o);
            }

            // Update Private Shadow with Timestamp
            // Logic: If it's the same object instance, keep original time. If new, update time.
            TWAgentPercept existing = privateShadow[x][y];
            if (existing != null && existing.getO() == o) {
                // Keep original timestamp (crucial for accurate lifetime calculation)
            } else {
                boolean witnessCreation = false;
                double lastLook = lastObservationGrid[x][y];

                // Only witnessed if:
                // 1. We looked recently (Last Look)
                // 2. AND we previously believed the cell was EMPTY (existing == null)
                if (lastLook != -1 && (currentTime - lastLook) <= 1.5 && existing == null) {
                    witnessCreation = true;
                }

                privateShadow[x][y] = new TWAgentPercept(o, currentTime);
                witnessedCreationGrid[x][y] = witnessCreation;

                if (witnessCreation && o instanceof TWObject) {
                    createdObjectsThisStep.add(o);
                }
            }
        }

        // 4. Update Timestamps
        for (int x = me.getX() - range; x <= me.getX() + range; x++) {
            for (int y = me.getY() - range; y <= me.getY() + range; y++) {
                if (x < 0 || x >= xDim || y < 0 || y >= yDim) continue;
                lastObservationGrid[x][y] = currentTime;
            }
        }
    }

    // --- CONSENSUS PROTOCOL ---

    /**
     * Translates broadcasted messages into global consensus updates.
     * * @param messages    List of raw strings received from the network.
     * @param currentStep Current simulation timestep for timeline bounding.
     */
    public void updateState(ArrayList<Message> messages, int currentStep) {
        monitorClaims.clear();

        for (Message msg : messages) {
            String content = msg.getMessage();
            String[] parts = content.split(";");
            String senderName = null;
            if (parts.length > 0 && parts[0].startsWith("STATUS")) {
                senderName = parts[0].split(":")[1];

                // Track team positions for Zoning
                try {
                    int px = Integer.parseInt(parts[0].split(":")[2]);
                    int py = Integer.parseInt(parts[0].split(":")[3]);
                    teamPositions.put(senderName, new Int2D(px, py));
                    markAreaExplored(px, py, Parameters.defaultSensorRange);
                } catch (Exception e) {}
            }

            // Format: STATUS:name:x:y...; TILE:x:y:time; ...
            for (String part : parts) {
                String[] data = part.trim().split(":");
                String type = data[0];
                try {
                    if (type.equals("TILE")) {
                        consensusTiles.put(new Int2D(Integer.parseInt(data[1]), Integer.parseInt(data[2])), Integer.parseInt(data[3]));
                    } else if (type.equals("HOLE")) {
                        consensusHoles.put(new Int2D(Integer.parseInt(data[1]), Integer.parseInt(data[2])), Integer.parseInt(data[3]));
                    } else if (type.equals("OBSTACLE")) {
                        consensusObstacles.put(new Int2D(Integer.parseInt(data[1]), Integer.parseInt(data[2])), Integer.parseInt(data[3]));
                    } else if (type.equals("FUEL_STATION")) {
                        int x = Integer.parseInt(data[1]);
                        int y = Integer.parseInt(data[2]);
                        if (x != -1 && y != -1 && this.consensusFuelStation.x == -1) {
                             if (!fuelStationDiscoveredLog) {
                                 // This agent just learned about it.
                                 // If it's the first time ANY agent prints this in the console:
                                System.out.println(">>> BENCHMARK: Fuel Station FOUND at Step " + currentStep + " by " + me.getName() + " (Location: " + x + "," + y + ")");
                                fuelStationDiscoveredLog = true;
                            }
                            this.consensusFuelStation = new Int2D(x, y);
                        }
                    } else if (type.equals("LIFETIME")) {
                        updateLifetime(Integer.parseInt(data[1]));
                    } else if (type.equals("DEFINITE_LIFETIME")) {
                        int val = Integer.parseInt(data[1]);
                        updateLifetime(val);
                        this.definiteLifetimeKnown = true;
                    } else if (type.equals("MONITORING") && senderName != null) {
                        int x = Integer.parseInt(data[1]);
                        int y = Integer.parseInt(data[2]);
                        Int2D pos = new Int2D(x, y);
                        monitorClaims.putIfAbsent(pos, new ArrayList<>());
                        monitorClaims.get(pos).add(senderName);
                    } else if (type.equals("REMOVE")) {
                        String objType = data[1];
                        int x = Integer.parseInt(data[2]);
                        int y = Integer.parseInt(data[3]);
                        Int2D pos = new Int2D(x, y);

                        if (objType.equals("TILE")) {
                            consensusTiles.remove(pos);
                        } else if (objType.equals("HOLE")) {
                            consensusHoles.remove(pos);
                        } else if (objType.equals("OBSTACLE")) {
                            consensusObstacles.remove(pos);
                        }
                    }
                } catch (Exception e) {}
            }
        }
        purgeExpiredConsensusObjects(currentStep);
    }

    /**
     * Safely purges objects that mathematically must be dead based on global lifetime knowledge.
     * * @param currentStep Current simulation timestep.
     */
    private void purgeExpiredConsensusObjects(int currentStep) {
        int threshold = currentStep - this.objectLifetime;
        consensusTiles.entrySet().removeIf(entry -> entry.getValue() <= threshold);
        consensusHoles.entrySet().removeIf(entry -> entry.getValue() <= threshold);
        consensusObstacles.entrySet().removeIf(entry -> entry.getValue() <= threshold);
    }

    // --- HELPER METHODS ---

    /**
     * Checks if a strictly higher priority agent is physically standing close to a coordinate.
     * * @param tx          Target X.
     * @param ty          Target Y.
     * @param sensorRange Sensor distance criteria.
     * @param myName      Agent identifier for authority sorting.
     * @return True if a superior agent is near.
     */
    public boolean isHigherPriorityAgentNear(int tx, int ty, int sensorRange, String myName) {
        for (TWAgent other : nearbyAgents) {
            double distToTarget = Math.abs(other.getX() - tx) + Math.abs(other.getY() - ty);
            if (distToTarget <= sensorRange) {
                if (other.getName().compareTo(myName) < 0) return true;
            }
        }
        // Note: We do NOT treat Holes as blocks in the pathfinder,
        // because agents need to step ONTO holes to interact with them.
        return false;
    }

    /**
     * Checks if a higher priority agent has formally claimed monitoring duties for a specific object.
     * * @param x      Object X.
     * @param y      Object Y.
     * @param myName Identity comparing agent.
     * @return True if claimed by a superior agent.
     */
    public boolean hasHigherPriorityMonitor(int x, int y, String myName) {
        Int2D pos = new Int2D(x, y);
        if (!monitorClaims.containsKey(pos)) return false;
        for (String otherAgent : monitorClaims.get(pos)) {
            if (otherAgent.compareTo(myName) < 0) return true;
        }
        return false;
    }

    /**
     * Checks if any agent in the entire team has a monitoring claim over any object.
     * * @param myName String representing self, ignored in the check.
     * @return True if at least one peer is monitoring an object.
     */
    public boolean isAnyAgentMonitoring(String myName) {
        for(ArrayList<String> agents : monitorClaims.values()) {
            for(String agent : agents) {
                if(!agent.equals(myName)) return true;
            }
        }
        return false;
    }

    /**
     * Checks if there is a higher priority agent monitoring ANY object.
     * Resolves simultaneous starts on separate objects.
     * * @param myName The agent's name to resolve hierarchy against.
     * @return True if a superior agent is monitoring anything.
     */
    public boolean hasGlobalHigherPriorityMonitor(String myName) {
         for(ArrayList<String> agents : monitorClaims.values()) {
            for(String agent : agents) {
                if(agent.compareTo(myName) < 0) return true;
            }
        }
        return false;
    }

    /**
     * Queues an object to be broadcasted to the team for global removal from consensus lists.
     * * @param type Object type string (e.g., "TILE" or "HOLE").
     * @param x    Object X coordinate.
     * @param y    Object Y coordinate.
     */
    public void queueRemoval(String type, int x, int y) {
        // Format: "REMOVE:TYPE:X:Y"
        removedObjectsBuffer.add("REMOVE:" + type + ":" + x + ":" + y);
    }

    // --- GETTERS & SETTERS ---

    public Rectangle getZone(String agentName) { return allAgentZones.get(agentName); }
    public Rectangle getAssignedZone() { return assignedZone; }
    public Rectangle getExplorationSubZone() { return this.explorationSubZone; }

    /**
     * Returns a list of all team members' latest coordinates.
     * * @param excludeName Discard this name to prevent returning own location.
     * @return List of positional coordinates.
     */
    public List<Int2D> getTeamPositions(String excludeName) {
        List<Int2D> positions = new ArrayList<>();
        for (Map.Entry<String, Int2D> entry : teamPositions.entrySet()) {
            if (!entry.getKey().equals(excludeName)) {
                positions.add(entry.getValue());
            }
        }
        return positions;
    }

    public boolean hasHolesInZone(Rectangle zone) {
        if (zone == null) return false;
        if (consensusHoles.isEmpty()) return false;
        for (Int2D hole : consensusHoles.keySet()) {
            if (zone.contains(hole.x, hole.y)) return true;
        }
        return false;
    }

    public ArrayList<String> getRemovedObjectsBuffer() { return removedObjectsBuffer; }

    public Integer getTileTimestamp(int x, int y) { return consensusTiles.get(new Int2D(x, y)); }
    public Integer getHoleTimestamp(int x, int y) { return consensusHoles.get(new Int2D(x, y)); }

    public TWEntity getObservedObject(int x, int y) {
        if (x < 0 || x >= me.getEnvironment().getxDimension()) return null;
        if (y < 0 || y >= me.getEnvironment().getyDimension()) return null;
        TWAgentPercept p = privateShadow[x][y];
        return (p != null) ? p.getO() : null;
    }

    public ArrayList<TWEntity> getCreatedObjectsThisStep() { return createdObjectsThisStep; }
    public boolean isConsensusBlocked(int x, int y) { return consensusObstacles.containsKey(new Int2D(x, y)); }
    public ArrayList<TWEntity> getNewDiscoveriesThisStep() { return newDiscoveriesThisStep; }
    public int getDiscoveredLifetime() { return discoveredLifetime; }
    public boolean isDiscoveredLifetimeDefinite() { return discoveredLifetimeIsDefinite; }
    public boolean isDefiniteLifetimeKnown() { return definiteLifetimeKnown; }
    public int getKnownLifetime() { return objectLifetime; }
    public void updateLifetime(int val) { this.objectLifetime = Math.max(this.objectLifetime, val); }
    public Int2D getConsensusFuelStation() { return consensusFuelStation; }
    public HashMap<Int2D, Integer> getConsensusTiles() { return consensusTiles; }
    public HashMap<Int2D, Integer> getConsensusHoles() { return consensusHoles; }
    private double getSimulationTime() { return me.getEnvironment().schedule.getTime(); }
}