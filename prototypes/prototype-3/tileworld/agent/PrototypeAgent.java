package tileworld.agent;

import java.util.List;
import sim.util.Int2D;
import tileworld.environment.TWEnvironment;

/**
 * PrototypeAgent
 *
 * A specific implementation of TWBaseAgent that defines the worker logic
 * for handling tasks that fall outside the immediate automatic reflex radius.
 */
public class PrototypeAgent extends TWBaseAgent {

    // --- CONSTANTS ---

    // --- CONSTRUCTORS ---

    /**
     * Constructs a new PrototypeAgent.
     *
     * @param name      The unique identifier for this agent.
     * @param xpos      The starting X coordinate.
     * @param ypos      The starting Y coordinate.
     * @param env       The Tileworld environment.
     * @param fuelLevel The starting fuel level.
     */
    public PrototypeAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, xpos, ypos, env, fuelLevel);
    }

    // --- CORE BEHAVIOR ---

    /**
     * Implements the specific worker logic for this agent type.
     * This is only called if the following higher-priority tasks are satisfied:
     * 1. Consensus Memory Sync (Broadcasted Messages)
     * 2. Locating Fuel Station
     * 3. Fuel Level Safety Check
     * 4. Object Lifetime Monitoring (First Responder)
     * 5. Automatically-Handled High-Priority Tasks (Reflex)
     * * @return TWThought The decided action and direction for this step.
     */
    @Override
    protected TWThought executeWorkerLogic() {
        Int2D bestTarget = null;
        int bestScore = Integer.MIN_VALUE;
        TWAction bestAction = null;

        // 1. Evaluate Tile Candidates (Lower Score / Longer Distance)
        if (this.carriedTiles.size() < 3) { // only if we have space
            List<Int2D> tileCandidates = getLowerPriorityTilesFromConsensusMemory();
            for (Int2D target : tileCandidates) {
                int score = standardizedScoreForTile(target.x, target.y);
                if (score > bestScore) {
                    if (isBestCandidate(target.x, target.y, score, this.getEnvironment().getMessages(), TWAction.PICKUP)) {
                        bestScore = score;
                        bestTarget = target;
                        bestAction = TWAction.PICKUP;
                    }
                }
            }
        }

        // 2. Evaluate Hole Candidates (Lower Score / Longer Distance)
        if (this.carriedTiles.size() > 0) { // only if we have tiles
            List<Int2D> holeCandidates = getLowerPriorityHolesFromConsensusMemory();
            for (Int2D target : holeCandidates) {
                int score = standardizedScoreForHole(target.x, target.y);
                if (score > bestScore) {
                    if (isBestCandidate(target.x, target.y, score, this.getEnvironment().getMessages(), TWAction.PUTDOWN)) {
                        bestScore = score;
                        bestTarget = target;
                        bestAction = TWAction.PUTDOWN;
                    }
                }
            }
        }

        if (bestTarget != null) {
            this.explorationTarget = null; // important to reset exploration target if best target found
            return executePathTo(bestTarget.x, bestTarget.y, bestAction);
        } else {
            return executeExploreMove();
        }
    }
}