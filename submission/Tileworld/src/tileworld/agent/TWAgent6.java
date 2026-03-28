//package tileworld.agent;
//
//import java.awt.Color;
//import sim.display.GUIState;
//import sim.portrayal.Inspector;
//import sim.portrayal.LocationWrapper;
//import sim.portrayal.Portrayal;
//import tileworld.Parameters;
//import tileworld.environment.TWEnvironment;
//
///**
// * TWAgent6 (The Vanguard)
// * Specialized scout agent that appears RED in the GUI.
// */
//public class TWAgent6 extends TWBaseAgent {
//
//    public TWAgent6(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
//        super(name, xpos, ypos, env, fuelLevel);
//    }
//
//    /**
//     * This method is called by the GUI to determine how to draw TWAgent6.
//     * We pass Color.red and the sensor range to the portrayal.
//     */
//    public static Portrayal getPortrayal() {
//        // We use the constructor: TWAgentPortrayal(Paint paint, int sensorRange)
//        return new TWAgentPortrayal(Color.red, Parameters.defaultSensorRange) {
//
//            @Override
//            public Inspector getInspector(LocationWrapper wrapper, GUIState state) {
//                // Returns the standard inspector when you click the agent
//                return new AgentInspector(super.getInspector(wrapper, state), wrapper, state);
//            }
//        };
//    }
//
//    @Override
//    protected TWThought executeWorkerLogic() {
//        ConsensusMemory mem = (ConsensusMemory) this.getMemory();
//        
//        // Check if the agent is already on the target
//        if (explorationTarget != null && (this.getX() == explorationTarget.x && this.getY() == explorationTarget.y)) {
//            explorationTarget = null;
//        }
//
//        if (explorationTarget == null) {
//            explorationTarget = mem.getNearestUnexplored(this.getX(), this.getY(), false);
//        }
//
//        if (explorationTarget == null) {
//            return executeExploreMove(); // Fallback to repulsive random search
//        }
//
//        return executePathTo(explorationTarget.x, explorationTarget.y, TWAction.MOVE);
//    }
//
//}

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

    public TWAgent6(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel);
    }

    @Override
    protected TWThought executeWorkerLogic() {
        ConsensusMemory mem = (ConsensusMemory) this.getMemory();

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
        double distToMe = getStandardizedDistance(getX(), getY(), centerX, centerY);
        
        // 1. Get Peer Positions directly from ConsensusMemory!
        // This replaces the expensive Grid Scan we tried earlier.
        List<Int2D> peerPositions = mem.getTeamPositions(this.getName());
        
        double peerPenalty = 0;
        for (Int2D peerPos : peerPositions) {
            double distToPeer = getStandardizedDistance(peerPos.x, peerPos.y, centerX, centerY);

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
    protected double getStandardizedDistance(int x1, int y1, int x2, int y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }
}