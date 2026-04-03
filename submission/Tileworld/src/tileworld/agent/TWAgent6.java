package tileworld.agent;
import java.util.List;
import java.awt.Color;
import java.util.LinkedList;
import sim.display.GUIState;
import sim.portrayal.Inspector;
import sim.portrayal.LocationWrapper;
import sim.portrayal.Portrayal;
import sim.util.Int2D;
import tileworld.Parameters;
import tileworld.environment.TWEnvironment;
import tileworld.agent.TWAgentPortrayal;
import tileworld.agent.AgentInspector;

public class TWAgent6 extends TWBaseAgent {

    // Decision persistence fields
    private LinkedList<Int2D> currentExplorationQueue = new LinkedList<>();
    private double currentQueueScore = -1.0;
    private final double SWITCHING_THRESHOLD = 1.2; // 20% loyalty bonus
    private final int WINDOW_SIZE = 5; // Search for 5x5 dark areas

    // Cached parameter for opportunistic sweeping
    private final int opportunisticSweepThreshold;

    public TWAgent6(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel);

        // Calculate and cache the scale-appropriate threshold for opportunistic sweeping
        this.opportunisticSweepThreshold = calculateSweepThreshold(env.getxDimension(), env.getyDimension());
    }

    @Override
    protected TWThought executeWorkerLogic() {
        ConsensusMemory mem = (ConsensusMemory) this.getMemory();

        // Opportunistically sweep nearby targets before committing to a dark area search
        TWThought vacuumThought = executeExpandedVacuumSynergy();
        if (vacuumThought != null) {
            return vacuumThought;
        }

        // 1 & 2. Scan the map for the best potential dark area
        ScoredArea bestNewArea = findBestAreaOnMap(mem);

        // 3. Comparison Logic: Should we switch our current plan?
        if (bestNewArea != null) {
            // Print the comparison for every scan
            System.out.println("Vanguard Audit -> Current Mission Score: " + String.format("%.2f", currentQueueScore) + 
                            " | New Area Score: " + String.format("%.2f", bestNewArea.score));

            if (currentExplorationQueue.isEmpty() || (bestNewArea.score > currentQueueScore * SWITCHING_THRESHOLD)) {
                
                if (currentExplorationQueue.isEmpty()) {
                    System.out.println("   [!] Initiating new exploration mission.");
                } else {
                    System.out.println("   [!] SWITCHING: New area is significantly better (>20% improvement).");
                }
                
                currentExplorationQueue = bestNewArea.coordinates;
                currentQueueScore = bestNewArea.score;
            }
        }

        // 4. Ghost Queue Cleanup: Remove tiles already cleared by peers
        while (!currentExplorationQueue.isEmpty() && mem.isExplored(currentExplorationQueue.peek().x, currentExplorationQueue.peek().y)) {
            currentExplorationQueue.poll();
        }

        // 5. Execution
        if (currentExplorationQueue.isEmpty()) {
            return executeExploreMove(); // Wander if nothing high-value is found
        }

        Int2D nextTarget = currentExplorationQueue.peek();
        return executePathTo(nextTarget.x, nextTarget.y, TWAction.MOVE);
    }

    /**
     * Scans the map in sliding windows to find the highest scoring dark patch.
     */
    private ScoredArea findBestAreaOnMap(ConsensusMemory mem) {
        int xDim = getEnvironment().getxDimension();
        int yDim = getEnvironment().getyDimension();
        ScoredArea best = null;

        // Slide window across the grid (stepping by 2 for performance)
        for (int x = 0; x <= xDim - WINDOW_SIZE; x += 2) {
            for (int y = 0; y <= yDim - WINDOW_SIZE; y += 2) {
                
                LinkedList<Int2D> darkTiles = new LinkedList<>();
                for (int i = x; i < x + WINDOW_SIZE; i++) {
                    for (int j = y; j < y + WINDOW_SIZE; j++) {
                        if (!mem.isExplored(i, j)) {
                            darkTiles.add(new Int2D(i, j));
                        }
                    }
                }

                if (darkTiles.isEmpty()) continue;

                // Apply Scoring Function
                double score = calculateScore(x + WINDOW_SIZE/2, y + WINDOW_SIZE/2, darkTiles.size());

                if (best == null || score > best.score) {
                    best = new ScoredArea(darkTiles, score);
                }
            }
        }
        return best;
    }

    private double calculateScore(int centerX, int centerY, int darkCount) {
        ConsensusMemory mem = (ConsensusMemory) this.getMemory();
        double distToMe = getEuclideanDistance(getX(), getY(), centerX, centerY);
        
        // 1. Get Peer Positions directly from ConsensusMemory!
        // This replaces the expensive Grid Scan we tried earlier.
        List<Int2D> peerPositions = mem.getTeamPositions(this.getName());
        
        double peerPenalty = 0;
        for (Int2D peerPos : peerPositions) {
            double distToPeer = getEuclideanDistance(peerPos.x, peerPos.y, centerX, centerY);

            // If the peer is closer to this dark patch than the Vanguard, penalize heavily.
            if (distToPeer < distToMe) {
                peerPenalty += (distToMe - distToPeer) * 8.0; 
            }
            
            // "Proximity Bubble" - don't crowd a worker already in the zone
            if (distToPeer < 10) {
                peerPenalty += (10 - distToPeer) * 4.0;
            }
        }

        // Formula: (Darkness Reward) - (Travel Cost) - (Peer Interference)
        return (darkCount * 15.0) - (distToMe * 2.5) - peerPenalty;
    }

    /**
     * Inner helper class to track potential mission data
     */
    private class ScoredArea {
        LinkedList<Int2D> coordinates;
        double score;

        ScoredArea(LinkedList<Int2D> coords, double s) {
            this.coordinates = coords;
            this.score = s;
        }
    }

    /**
     * Simple Euclidean distance formula: sqrt((x1-x2)^2 + (y1-y2)^2)
     */
    private double getEuclideanDistance(int x1, int y1, int x2, int y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    /**
     * Calculates the threshold for opportunistic sweeping based on map dimensions.
     * Uses an adaptive multiplier to ensure scale-appropriate behavior based on empirical data.
     */
    private int calculateSweepThreshold(int width, int height) {
        int mapSum = width + height;

        // Empirically derived multipliers based on map size.
        // Small maps afford high distraction; large maps require strict scouting focus.
        double opportunisticMultiplier;
        if (mapSum <= 60) {
            // e.g., matches 20x20, 30x30
            opportunisticMultiplier = 1.8;
        } else if (mapSum <= 120) {
            // e.g., matches 40x40, 50x50, 60x60
            opportunisticMultiplier = 1.6;
        } else {
            // e.g., matches 70x70, 80x80
            opportunisticMultiplier = 1.0;
        }

        int vacuumBoost = (int) ((mapSum / 2) * DISTANCE_WEIGHT * opportunisticMultiplier);

        return AUTOMATIC_SCORE_THRESHOLD - vacuumBoost;
    }

    /**
     * Evaluates targets using an expanded scoring threshold to replicate a massive
     * collection radius. This allows the agent to act as an opportunistic sweeper
     * on its way to dark patches.
     * * @return TWThought to execute a pickup/putdown, or null if no valid target is found.
     */
    private TWThought executeExpandedVacuumSynergy() {
        ConsensusMemory mem = (ConsensusMemory) this.getMemory();
        Int2D bestTarget = null;
        int bestScore = Integer.MIN_VALUE;
        TWAction bestAction = null;


        if (this.carriedTiles.size() < 3) {
            for (Int2D tilePos : mem.getConsensusTiles().keySet()) {
                int score = standardizedScoreForTile(tilePos.x, tilePos.y);
                if (score > opportunisticSweepThreshold && score > bestScore) {
                    if (isBestCandidate(tilePos.x, tilePos.y, score, this.getEnvironment().getMessages(), TWAction.PICKUP)) {
                        bestScore = score;
                        bestTarget = tilePos;
                        bestAction = TWAction.PICKUP;
                    }
                }
            }
        }

        if (this.carriedTiles.size() > 0) {
            for (Int2D holePos : mem.getConsensusHoles().keySet()) {
                int score = standardizedScoreForHole(holePos.x, holePos.y);
                if (score > opportunisticSweepThreshold && score > bestScore) {
                    if (isBestCandidate(holePos.x, holePos.y, score, this.getEnvironment().getMessages(), TWAction.PUTDOWN)) {
                        bestScore = score;
                        bestTarget = holePos;
                        bestAction = TWAction.PUTDOWN;
                    }
                }
            }
        }

        // If a target was found in the expanded vacuum radius, execute it immediately
        if (bestTarget != null) {
            this.explorationTarget = null;
            return executePathTo(bestTarget.x, bestTarget.y, bestAction);
        }

        return null;
    }
}